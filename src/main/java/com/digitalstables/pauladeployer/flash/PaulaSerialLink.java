package com.digitalstables.pauladeployer.flash;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fazecast.jSerialComm.SerialPort;

// Copied from PaulaUploader's PaulaSerialLink (com.digitalstables.paula.uploader) rather than
// shared as a Maven dependency - PaulaUploader packages as a self-contained CLI jar
// (jar-with-dependencies), not a library artifact, so depending on it here would drag in a fat
// shaded jar and risk classpath conflicts. This is a separate deployable project (a WAR, not a
// CLI), so a plain copy keeps both builds simple and independent. See PaulaUploader's version for
// the original commentary this is based on.
public class PaulaSerialLink {

	private static final Logger logger = LogManager.getLogger("PaulaSerialLink");

	private static final int WALLY_VENDOR_ID = 0x10C4;
	private static final int WALLY_PRODUCT_ID = 0xEA60;

	private static final int DATA_RATE = 115200;
	private static final int READ_TIMEOUT_MILLISECONDS = 500;
	private static final int MAX_WAIT_MILLISECONDS = 30000;
	private static final int PROBE_TIMEOUT_MILLISECONDS = 3000;

	private SerialPort cachedWallyPort;

	public static SerialPort findWallyPort() {
		// Held open by PaulaRadio (Nearby devices): that is Paula - no probe, which would fail on
		// the busy port and let the flasher take Paula for the target.
		String held = PaulaRadio.get().heldPortName();
		if (held != null) {
			for (SerialPort port : SerialPort.getCommPorts()) {
				if (port.getSystemPortName().equals(held)) return port;
			}
		}
		// paula.port set to a fixed path (Paula2: /dev/serial0 on the Pi header) - that is Paula, no probe.
		if (PaulaPortConfig.isFixed()) {
			SerialPort fixed = PaulaPortConfig.fixedPort();
			if (fixed == null) logger.warn("paula.port=" + PaulaPortConfig.configured() + " in " + PaulaPortConfig.FILE + " cannot be opened");
			return fixed;
		}
		List<SerialPort> candidates = new ArrayList<SerialPort>();
		for (SerialPort port : SerialPort.getCommPorts()) {
			if (port.getVendorID() == WALLY_VENDOR_ID && port.getProductID() == WALLY_PRODUCT_ID) {
				candidates.add(port);
			}
		}
		for (SerialPort candidate : candidates) {
			// A board already probed and found not to be Paula isn't probed again (2026-11-11): every
			// probe opens its port, which resets that ESP32 - with Paula unplugged, each device command
			// would otherwise reset the device once for the probe and again for the command.
			String key = usbKey(candidate);
			if (NOT_PAULA.contains(key)) continue;
			String response = sendCommandOnPort(candidate, "GetSwitchState", PROBE_TIMEOUT_MILLISECONDS);
			// Paula.ino answers "Sleep=<n>" then "Ok-GetSwitchState" since its switch was removed - the
			// old Left=/Right= check never matched again, so findWallyPort() always returned null.
			if (response != null && response.contains("Ok-GetSwitchState")) {
				logger.debug("Resolved Wally on " + candidate.getSystemPortName());
				return candidate;
			}
			// Only a definite "not Paula" is remembered - a Failure reply means another firmware
			// answered. Silence may just be Paula still booting, so that board is probed again next time.
			if (response != null && response.contains("Failure")) {
				NOT_PAULA.add(key);
				logger.info(candidate.getSystemPortName() + " (" + key + ") is a device, not Paula - not probed again");
			}
		}
		logger.debug("No CP2104 candidate answered as Wally (" + candidates.size() + " candidate(s) probed)");
		return null;
	}

	// The USB adapter's own serial number (each CP2104 has one) - so the same board keeps its key
	// whichever ttyUSB name it gets, and a different board on the same name is a different key.
	private static final java.util.Set<String> NOT_PAULA = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

	static String usbKey(SerialPort port) {
		String serial = port.getSerialNumber();
		return (serial == null || serial.isEmpty() || "Unknown".equalsIgnoreCase(serial)) ? port.getSystemPortName() : serial;
	}

	public String setStatusText(String text) {
		return sendCommand("SetStatusText#" + text);
	}

	public Boolean isSwitchLeft() {
		String response = sendCommand("GetSwitchState");
		if (response == null) return null;
		for (String line : response.split("\n")) {
			if (line.contains("Left=")) {
				String leftValue = line.substring(line.indexOf("Left=") + 5, line.indexOf("Left=") + 6);
				return "1".equals(leftValue);
			}
		}
		return null;
	}

	public String sendCommand(String command) {
		logger.debug("sendCommand to Wally: " + command);
		SerialPort port = resolveWallyPort();
		if (port == null) {
			logger.warn("Wally not found on any USB port - is it plugged in?");
			return null;
		}
		String result = sendCommandOnPort(port, command, MAX_WAIT_MILLISECONDS);
		if (result == null) {
			logger.warn("No response from Wally on " + port.getSystemPortName() + " - dropping cached port");
			cachedWallyPort = null;
		}
		return result;
	}

	private SerialPort resolveWallyPort() {
		if (cachedWallyPort != null) {
			for (SerialPort port : SerialPort.getCommPorts()) {
				if (port.getSystemPortName().equals(cachedWallyPort.getSystemPortName())) {
					return cachedWallyPort;
				}
			}
			cachedWallyPort = null;
		}
		cachedWallyPort = findWallyPort();
		return cachedWallyPort;
	}

	private static String sendCommandOnPort(SerialPort port, String command, int timeoutMillis) {
		port.setComPortParameters(DATA_RATE, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
		port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MILLISECONDS, 0);

		if (!port.openPort()) {
			return null;
		}

		BufferedReader input = new BufferedReader(new InputStreamReader(port.getInputStream()));
		BufferedWriter output = new BufferedWriter(new OutputStreamWriter(port.getOutputStream()));
		try {
			output.write(command, 0, command.length());
			Thread.sleep(100);
			output.flush();

			StringBuilder transcript = new StringBuilder();
			long deadline = System.currentTimeMillis() + timeoutMillis;
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
				Thread.sleep(100);
			}
			return null;
		} catch (IOException | InterruptedException e) {
			return null;
		} finally {
			try { input.close(); } catch (IOException e) { /* ignore */ }
			try { output.close(); } catch (IOException e) { /* ignore */ }
			port.closePort();
		}
	}
}
