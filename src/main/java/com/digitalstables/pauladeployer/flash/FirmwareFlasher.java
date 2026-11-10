package com.digitalstables.pauladeployer.flash;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.function.Consumer;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.fazecast.jSerialComm.SerialPort;

// Adapted from PaulaUploader's FirmwareFlasher (com.digitalstables.paula.uploader) - copied
// rather than shared as a Maven dependency, see PaulaSerialLink's comment for why. The one real
// change: flash() takes a Consumer<String> callback instead of printing straight to
// System.out.println, so this app can capture esptool's output line-by-line into the deployAttempt
// DB row (PersistenceManager.appendLog) for the live terminal view, instead of (or as well as)
// just logging it server-side. Also adds sendCommandToTarget - a generic multi-line command sender
// (same bounded-poll pattern PaulaSerialLink already uses for Wally) needed for the Inspect
// feature's GetProductDefinition call, which PaulaUploader's original never needed since it only
// ever sent single-line-reply commands (Ping, GetIpAddress) to the target.
public class FirmwareFlasher {

	private static final Logger logger = LogManager.getLogger("FirmwareFlasher");

	private static final String ESPTOOL_PATH = "/home/pi/.arduino15/packages/esp32/tools/esptool_py/3.0.0/esptool.py";
	private static final String BOOT_APP0_PATH = "/home/pi/.arduino15/packages/esp32/hardware/esp32/1.0.6/tools/partitions/boot_app0.bin";
	private static final String BOOTLOADER_PATH = "/home/pi/.arduino15/packages/esp32/hardware/esp32/1.0.6/tools/sdk/bin/bootloader_dio_80m.bin";

	// upload.sh prints this when a write fails and it is about to retry at a lower baud rate.
	private static final String RETRY_MARKER = "Flash failed at";

	private static final int DATA_RATE = 115200;
	private static final int READ_TIMEOUT_MILLISECONDS = 500;
	private static final int MAX_WAIT_MILLISECONDS = 30000;

	public SerialPort findTargetPort() {
		return findTargetPort(true);
	}

	// The device on the bench: any ttyUSB/ttyACM port that is not Paula's (2026-11-11). Paula's port
	// comes from PaulaRadio - held open, the fixed paula.port (Paula2's /dev/serial0, never a
	// ttyUSB/ttyACM anyway), or found once by its USB id + GetSwitchState and then held. Before this,
	// Paula was only excluded with two or more ports plugged in, so with Paula alone every "device"
	// command went to Paula. Now that case returns null ("nothing plugged in besides Paula").
	// excludeWallyPort=false keeps the old "any port" behaviour for a Pi with no Paula at all.
	// With two devices plugged in besides Paula the first one wins - lastTargetOthers says how many
	// more there were, and the reply's destination line shows which port was used.
	public SerialPort findTargetPort(boolean excludeWallyPort) {
		String paulaName = excludeWallyPort ? PaulaRadio.get().paulaPortName() : null;
		lastPaulaPort = paulaName;
		SerialPort chosen = null;
		int others = 0;
		for (SerialPort port : SerialPort.getCommPorts()) {
			String name = port.getSystemPortName();
			if (!(name.startsWith("ttyUSB") || name.startsWith("ttyACM"))) continue;
			if (name.equals(paulaName)) continue;
			if (chosen == null) chosen = port;
			else others++;
		}
		lastTargetOthers = others;
		lastTarget = chosen;
		if (others > 0) logger.warn(others + " more device(s) plugged in besides " + chosen.getSystemPortName() + " - using " + chosen.getSystemPortName());
		return chosen;
	}

	// Where the last findTargetPort pointed - for the destination line under a command's reply.
	private SerialPort lastTarget;
	private String lastPaulaPort;
	private int lastTargetOthers;

	// Device names learnt from replies that pass through sendCommandToTarget (GetProductDefinition,
	// GetSerialNumber - Inspect, deploys and the Send Command box all send them), keyed by the USB
	// adapter's serial number, so naming a device costs no extra command (each command opens the
	// port, which resets the ESP32).
	private static final java.util.Map<String, String> DEVICE_NAMES = new java.util.concurrent.ConcurrentHashMap<String, String>();
	private static final java.util.Map<String, String> DEVICE_SERIALS = new java.util.concurrent.ConcurrentHashMap<String, String>();

	private static void learnIdentity(SerialPort port, String reply) {
		if (port == null || reply == null) return;
		String key = PaulaSerialLink.usbKey(port);
		for (String line : reply.split("\n")) {
			line = line.trim();
			int at = line.indexOf("Ok-GetProductDefinition#");
			if (at >= 0) {
				// name#power#battery#pcbs#firmware#commission#ssid#wifipass#apssid#appass#host#mode#now#devicename#shortname#serial
				String[] f = line.substring(at).split("#", -1);
				String name = f.length > 1 ? f[1].trim() : "";
				String deviceName = f.length > 14 ? f[14].trim() : "";
				String label = deviceName.isEmpty() ? name : (name.isEmpty() || name.equals(deviceName) ? deviceName : deviceName + " (" + name + ")");
				if (!label.isEmpty()) DEVICE_NAMES.put(key, label);
				if (f.length > 16 && f[16].trim().matches("[0-9a-fA-F]{12}")) DEVICE_SERIALS.put(key, f[16].trim().toLowerCase());
			} else if (line.matches("[0-9a-fA-F]{12}Ok")) {
				DEVICE_SERIALS.put(key, line.substring(0, 12).toLowerCase());
			}
		}
	}

	// {port, description, device, serialNumber, paulaPort, others} for the last command's target -
	// "device" is empty until the device has answered GetProductDefinition once (Inspect does that).
	public org.json.JSONObject lastTargetInfo() {
		org.json.JSONObject o = new org.json.JSONObject();
		if (lastTarget != null) {
			String key = PaulaSerialLink.usbKey(lastTarget);
			o.put("port", lastTarget.getSystemPortName());
			o.put("description", lastTarget.getPortDescription());
			o.put("device", DEVICE_NAMES.getOrDefault(key, ""));
			o.put("serialNumber", DEVICE_SERIALS.getOrDefault(key, ""));
		}
		o.put("paulaPort", lastPaulaPort == null ? "" : lastPaulaPort);
		o.put("others", lastTargetOthers);
		return o;
	}

	// Why there was no target, for the error shown instead of a reply.
	public String noTargetReason() {
		return lastPaulaPort != null
				? "Nothing plugged in besides Paula (on " + lastPaulaPort + ") - the command was not sent."
				: "No device plugged in - the command was not sent.";
	}

	public boolean flash(String binPath, String partitionsPath, String workDir, boolean excludeWallyPort, Consumer<String> onLine) throws IOException, InterruptedException {
		if (com.digitalstables.pauladeployer.chat.SerialChatStreamer.get().isRunning()) {
			onLine.accept("Stream 2 Chat is using the device's port - stop it first.");
			return false;
		}
		logger.info("flash() starting - binPath=" + binPath + " partitionsPath=" + partitionsPath + " workDir=" + workDir);
		SerialPort targetPort = findTargetPort(excludeWallyPort);
		if (targetPort == null) {
			logger.warn("flash() aborted - no target serial port found");
			onLine.accept("No target device found - is it plugged in?");
			return false;
		}
		String portName = "/dev/" + targetPort.getSystemPortName();
		logger.info("Target port resolved: " + portName);

		StringBuffer command = new StringBuffer();
		command.append("#!/bin/bash" + System.lineSeparator());
		// Confirmed gotcha (2026-09-09): without `set -e`, a failing esptool line (missing tool,
		// bad port, anything) just printed its error and fell through to the unconditional `touch`
		// below, which always succeeds - making flash()'s success check (exitCode==0 &&
		// completeFile.isFile()) meaningless. Every "successful" flash could have silently been a
		// no-op the whole time this bug existed; confirmed directly - a flash reported success with
		// esptool.py entirely missing from disk, and the post-flash Ping only succeeded because the
		// device was never actually touched and was still running its previous firmware.
		command.append("set -e" + System.lineSeparator());
		// Confirmed 2026-10-12: a Daffodil v57 deploy wrote the app fine, then died on the first
		// block of the www image with "Failed to write compressed data to flash after seq 0 (result
		// was C100)" - 0xC1 is the stub's bad-data-checksum status, i.e. bytes corrupted on the USB
		// serial link at 921600. Retry the whole write at slower rates instead of failing the
		// deploy. esptool inside the `if` doesn't trip `set -e`; the loop's `exit 1` still does
		// when every rate fails, so the touch below stays the only success path.
		command.append("for BAUD in 921600 460800 115200; do" + System.lineSeparator());
		command.append("  if python \"" + ESPTOOL_PATH + "\" ");
		command.append("--chip esp32 --port \"" + portName + "\" --baud $BAUD --before default_reset ");
		command.append("--after hard_reset write_flash -z --flash_mode dio --flash_freq 80m ");
		command.append("--flash_size detect 0xe000 ");
		command.append("\"" + BOOT_APP0_PATH + "\" ");
		command.append("0x1000 \"" + BOOTLOADER_PATH + "\" ");
		command.append("0x10000 \"" + binPath + "\" ");
		command.append("0x8000 \"" + partitionsPath + "\" ");
		command.append(WwwImage.writeFlashArgs(new File(partitionsPath)) + "; then" + System.lineSeparator());
		command.append("    touch firmwareUploadComplete" + System.lineSeparator());
		command.append("    exit 0" + System.lineSeparator());
		command.append("  fi" + System.lineSeparator());
		command.append("  echo \"" + RETRY_MARKER + " $BAUD baud - retrying slower\"" + System.lineSeparator());
		command.append("  sleep 2" + System.lineSeparator());
		command.append("done" + System.lineSeparator());
		command.append("exit 1");

		File uploadFile = new File(workDir, "upload.sh");
		FileUtils.writeStringToFile(uploadFile, command.toString());
		uploadFile.setExecutable(true);

		ProcessBuilder pb = new ProcessBuilder(uploadFile.getAbsolutePath());
		pb.directory(new File(workDir));
		pb.redirectErrorStream(true);
		Process p = pb.start();

		// esptool prints its "Connecting........_____" dots with no newline until the attempt ends,
		// so a line-at-a-time read shows nothing for the whole PROGRAM window. Read live, and - since
		// onLine appends a row to the deploy log the phone polls - report progress every few seconds
		// rather than once per dot.
		final long[] lastConnectingReport = {System.currentTimeMillis()};
		final long connectingStart = lastConnectingReport[0];
		LiveLineReader reader = new LiveLineReader(new InputStreamReader(p.getInputStream()), partial -> {
			long now = System.currentTimeMillis();
			if (partial.startsWith("Connecting") && now - lastConnectingReport[0] >= 5000) {
				lastConnectingReport[0] = now;
				onLine.accept("Still waiting for the board to enter programming mode (" + ((now - connectingStart) / 1000) + "s)...");
			}
		});
		String line;
		while ((line = reader.readLine()) != null) {
			onLine.accept(line);
			if (line.startsWith(RETRY_MARKER)) {
				onLine.accept("*** Get ready to press PROGRAM again ***");
			} else if (line.startsWith("Serial port")) {
				onLine.accept("*** PRESS PROGRAM NOW ***");
			} else if (line.startsWith("Hard resetting via RTS")) {
				onLine.accept("*** PRESS RESET NOW ***");
				Thread.sleep(5000);
			}
		}
		reader.close();

		int exitCode = p.waitFor();
		File completeFile = new File(workDir, "firmwareUploadComplete");
		boolean success = exitCode == 0 && completeFile.isFile();
		logger.info("flash() finished - exitCode=" + exitCode + " success=" + success);
		return success;
	}

	public String pingTarget(boolean excludeWallyPort) {
		logger.debug("Pinging target device (excludeWallyPort=" + excludeWallyPort + ")");
		SerialPort port = findTargetPort(excludeWallyPort);
		if (port == null){
			logger.warn("pingTarget() - no target serial port found");
			return null;
		}
		String result = sendCommand(port, "Ping");
		logger.debug("pingTarget() result: " + result);
		return result;
	}

	// Same two-line data-then-acknowledgement convention PaulaSerialLink's sendCommand uses for
	// Wally - GetProductDefinition (the Inspect feature) replies with an "Ok-GetProductDefinition#..."
	// data line, not a bare single-line ack, so the single-line MAX_WAIT read pingTarget/
	// getTargetIpAddress use in the original isn't enough here.
	// Never log a customer key (2026-10-29): hides the argument of SetSecret# and any 32-character
	// base32 run (a key as GetSecret or some firmware's echo shows it).
	public static String mask(String s) {
		if (s == null) return null;
		return s.replaceAll("SetSecret#[^#]*", "SetSecret#<hidden>").replaceAll("[A-Z2-7]{32}", "<hidden>");
	}

	public String sendCommandToTarget(String command, boolean excludeWallyPort) {
		logger.debug("sendCommandToTarget: " + mask(command));
		// Stream 2 Chat holds the port open - opening it again here would fail or steal its output.
		if (com.digitalstables.pauladeployer.chat.SerialChatStreamer.get().isRunning()) {
			return "Failure - Stream 2 Chat is using the device's port. Stop it first.";
		}
		SerialPort port = findTargetPort(excludeWallyPort);
		if (port == null){
			logger.warn("sendCommandToTarget() - no target serial port found");
			return null;
		}
		String result = sendCommand(port, command);
		learnIdentity(port, result);
		logger.debug("sendCommandToTarget() result: " + mask(result));
		return result;
	}

	private String sendCommand(SerialPort port, String command) {
		port.setComPortParameters(DATA_RATE, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
		port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MILLISECONDS, 0);
		if (!port.openPort()) return null;

		BufferedReader input = new BufferedReader(new InputStreamReader(port.getInputStream()));
		BufferedWriter output = new BufferedWriter(new OutputStreamWriter(port.getOutputStream()));
		try {
			output.write(command, 0, command.length());
			Thread.sleep(100);
			output.flush();

			StringBuilder transcript = new StringBuilder();
			long deadline = System.currentTimeMillis() + MAX_WAIT_MILLISECONDS;
			while (System.currentTimeMillis() < deadline) {
				if (input.ready()) {
					String line = input.readLine();
					if (line != null) {
						if (transcript.length() > 0) transcript.append("\n");
						transcript.append(line);
						if (line.contains("Ok") || line.contains("Failure")) {
							return transcript.toString();
						}
					}
				}
				Thread.sleep(200);
			}
			return transcript.length() > 0 ? transcript.toString() : null;
		} catch (IOException | InterruptedException e) {
			logger.warn("Error talking to target device: " + e.getMessage());
			return null;
		} finally {
			try { input.close(); } catch (IOException e) { /* ignore */ }
			try { output.close(); } catch (IOException e) { /* ignore */ }
			port.closePort();
		}
	}
}
