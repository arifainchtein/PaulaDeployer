package com.digitalstables.pauladeployer.flash;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fazecast.jSerialComm.SerialPort;

// One serial connection to Paula's own Wally that stays open (2026-10-14), for the Nearby devices
// window (GetNearbyDevices / DeviceWifi in Paula.ino). Opening a CP2104 port pulses DTR/RTS and
// resets the ESP32 behind it (see FirmwareFlasher.findTargetPort), and Paula keeps the devices it
// heard and the WiFi commands it is still delivering in RAM - so a fresh open per request, as
// PaulaSerialLink.sendCommand does, would wipe them every time. Opened on first use (one reset,
// then a wait for Paula to boot) and kept until a command fails. PaulaSerialLink.findWallyPort()
// answers with this port while it is held, so the flasher never mistakes Paula for the target.
public class PaulaRadio {

	private static final Logger logger = LogManager.getLogger("PaulaRadio");
	private static final PaulaRadio INSTANCE = new PaulaRadio();

	private static final int DATA_RATE = 115200;
	private static final int BOOT_WAIT_MILLISECONDS = 3000;  // Paula restarts when the port opens
	private static final int READ_TIMEOUT_MILLISECONDS = 200;

	private SerialPort port;
	private BufferedReader input;
	private OutputStream output;

	public static PaulaRadio get() {
		return INSTANCE;
	}

	// System name of the held port, or null.
	public synchronized String heldPortName() {
		return port != null && port.isOpen() ? port.getSystemPortName() : null;
	}

	// Sends a command and returns everything Paula printed up to its "Ok-"/"Failure" line, or
	// null when Paula can't be found or doesn't answer within timeoutMillis.
	public synchronized String command(String command, int timeoutMillis) {
		if (!ensureOpen()) return null;
		try {
			while (input.ready()) input.readLine();  // debug output / boot messages since last time
			output.write(command.getBytes(StandardCharsets.UTF_8));
			output.flush();
			StringBuilder transcript = new StringBuilder();
			long deadline = System.currentTimeMillis() + timeoutMillis;
			while (System.currentTimeMillis() < deadline) {
				if (input.ready()) {
					String line = input.readLine();
					if (line == null) continue;
					if (transcript.length() > 0) transcript.append("\n");
					transcript.append(line);
					if (line.startsWith("Ok-") || line.startsWith("Failure")) return transcript.toString();
				} else {
					Thread.sleep(50);
				}
			}
			logger.warn("No answer from Paula to " + command + " - closing the port");
		} catch (IOException | InterruptedException e) {
			logger.warn("Paula link failed: " + e.getMessage());
		}
		close();
		return null;
	}

	private boolean ensureOpen() {
		if (port != null && port.isOpen()) return true;
		SerialPort found = PaulaSerialLink.findWallyPort();
		if (found == null) {
			logger.warn("Paula not found on any USB port");
			return false;
		}
		found.setComPortParameters(DATA_RATE, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
		found.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MILLISECONDS, 0);
		if (!found.openPort()) {
			logger.warn("Cannot open Paula's port " + found.getSystemPortName());
			return false;
		}
		port = found;
		input = new BufferedReader(new InputStreamReader(port.getInputStream(), StandardCharsets.UTF_8));
		output = port.getOutputStream();
		logger.info("Holding Paula's port " + port.getSystemPortName() + " open");
		try {
			Thread.sleep(BOOT_WAIT_MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return true;
	}

	private void close() {
		if (port != null) port.closePort();
		port = null;
		input = null;
		output = null;
	}
}
