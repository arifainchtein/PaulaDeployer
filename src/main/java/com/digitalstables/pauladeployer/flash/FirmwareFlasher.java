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

	private static final int DATA_RATE = 115200;
	private static final int READ_TIMEOUT_MILLISECONDS = 500;
	private static final int MAX_WAIT_MILLISECONDS = 30000;

	public SerialPort findTargetPort() {
		return findTargetPort(true);
	}

	public SerialPort findTargetPort(boolean excludeWallyPort) {
		java.util.List<String> candidateNames = new java.util.ArrayList<>();
		for (SerialPort port : SerialPort.getCommPorts()) {
			String name = port.getSystemPortName();
			if (name.startsWith("ttyUSB") || name.startsWith("ttyACM")) {
				candidateNames.add(name);
			}
		}
		// Only probe for Wally if there's more than one candidate plugged in. findWallyPort()
		// matches purely on USB VID/PID (0x10C4/0xEA60 - the whole CP210x chip family), and this
		// Paula's Daffodil target ALSO happens to use a CP2104 bridge, so with only one device
		// attached that probe was opening/writing to the TARGET itself "to check if it's Wally".
		// Confirmed 2026-09-05: that port-open pulses DTR/RTS and resets the target mid-Ping
		// (a full WiFi-scan/AP-mode boot sequence showed up in the Ping transcript), and the
		// probe's own unterminated "GetSwitchState" write got concatenated with the immediately
		// following "Ping" write into "GetSwitchStatePing" server-side - "Command Not Found" -
		// even though the flash itself had already succeeded and the device was fine.
		SerialPort wally = (excludeWallyPort && candidateNames.size() > 1) ? PaulaSerialLink.findWallyPort() : null;
		for (SerialPort port : SerialPort.getCommPorts()) {
			String name = port.getSystemPortName();
			if (wally != null && name.equals(wally.getSystemPortName())) {
				continue;
			}
			if (candidateNames.contains(name)) {
				return port;
			}
		}
		return null;
	}

	public boolean flash(String binPath, String partitionsPath, String workDir, boolean excludeWallyPort, Consumer<String> onLine) throws IOException, InterruptedException {
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
		command.append("python \"" + ESPTOOL_PATH + "\" ");
		command.append("--chip esp32 --port \"" + portName + "\" --baud 921600 --before default_reset ");
		command.append("--after hard_reset write_flash -z --flash_mode dio --flash_freq 80m ");
		command.append("--flash_size detect 0xe000 ");
		command.append("\"" + BOOT_APP0_PATH + "\" ");
		command.append("0x1000 \"" + BOOTLOADER_PATH + "\" ");
		command.append("0x10000 \"" + binPath + "\" ");
		command.append("0x8000 \"" + partitionsPath + "\"" + System.lineSeparator());
		command.append("touch firmwareUploadComplete");

		File uploadFile = new File(workDir, "upload.sh");
		FileUtils.writeStringToFile(uploadFile, command.toString());
		uploadFile.setExecutable(true);

		ProcessBuilder pb = new ProcessBuilder(uploadFile.getAbsolutePath());
		pb.directory(new File(workDir));
		pb.redirectErrorStream(true);
		Process p = pb.start();

		BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
		String line;
		while ((line = reader.readLine()) != null) {
			onLine.accept(line);
			if (line.startsWith("Serial port")) {
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
	public String sendCommandToTarget(String command, boolean excludeWallyPort) {
		logger.debug("sendCommandToTarget: " + command);
		SerialPort port = findTargetPort(excludeWallyPort);
		if (port == null){
			logger.warn("sendCommandToTarget() - no target serial port found");
			return null;
		}
		String result = sendCommand(port, command);
		logger.debug("sendCommandToTarget() result: " + result);
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
