package com.digitalstables.pauladeployer.flash;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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

	private static final int DATA_RATE = 115200;
	private static final int READ_TIMEOUT_MILLISECONDS = 500;
	private static final int MAX_WAIT_MILLISECONDS = 30000;
	private static final int PROBE_TIMEOUT_MILLISECONDS = 3000;

	// 2026-10-24: Paula used to be looked for only among CP2104 ports (VID/PID below), and was "not
	// found" once the Pi started giving the target ESP32 ttyUSB2. Now every port is probed, in this
	// order: the one Paula last answered on, ttyUSB0..ttyUSB5, then any other ttyUSB/ttyACM.
	private static final int MAX_NUMBERED_PORT = 5;
	private static final int PROBE_RETRY_MILLISECONDS = 2000;  // Paula may be booting when the port opens
	private static volatile String lastPaulaPortName;

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
		List<SerialPort> candidates = probeOrder(SerialPort.getCommPorts());
		StringBuilder tried = new StringBuilder();
		for (SerialPort candidate : candidates) {
			if (probeForPaula(candidate)) {
				logger.info("Found Paula on " + candidate.getSystemPortName());
				lastPaulaPortName = candidate.getSystemPortName();
				return candidate;
			}
			if (tried.length() > 0) tried.append(", ");
			tried.append(candidate.getSystemPortName());
		}
		logger.warn("Paula did not answer on any port (tried " + (tried.length() == 0 ? "none - no ttyUSB/ttyACM ports" : tried) + ")");
		return null;
	}

	// Last Paula port first, then ttyUSB0..ttyUSB<MAX_NUMBERED_PORT>, then the other ttyUSB/ttyACM
	// ports. Not filtered by VID/PID (CP2104, 10C4:EA60, as before): the target can be a CP2104 too,
	// and Paula's own chip may not be.
	private static List<SerialPort> probeOrder(SerialPort[] ports) {
		List<SerialPort> ordered = new ArrayList<SerialPort>();
		String last = lastPaulaPortName;
		if (last != null) addPort(ordered, ports, last);
		for (int i = 0; i <= MAX_NUMBERED_PORT; i++) addPort(ordered, ports, "ttyUSB" + i);
		for (SerialPort port : ports) {
			String name = port.getSystemPortName();
			if ((name.startsWith("ttyUSB") || name.startsWith("ttyACM")) && !ordered.contains(port)) ordered.add(port);
		}
		return ordered;
	}

	private static void addPort(List<SerialPort> ordered, SerialPort[] ports, String name) {
		for (SerialPort port : ports) {
			if (port.getSystemPortName().equals(name) && !ordered.contains(port)) ordered.add(port);
		}
	}

	// Paula.ino answers "Sleep=<n>" then "Ok-GetSwitchState" since its switch was removed - the old
	// Left=/Right= check never matched again. Asked a second time on the same open port when the
	// first gets no answer: opening it can reset Paula, and the first one then arrives while it
	// boots (reopening would just reset it again).
	private static boolean probeForPaula(SerialPort port) {
		port.setComPortParameters(DATA_RATE, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
		port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
		if (!port.openPort()) {
			logger.debug("Cannot open " + port.getSystemPortName() + " to look for Paula (in use?)");
			return false;
		}
		try {
			for (int attempt = 1; attempt <= 2; attempt++) {
				if (attempt == 2) Thread.sleep(PROBE_RETRY_MILLISECONDS);
				byte[] command = "GetSwitchState".getBytes(StandardCharsets.UTF_8);
				port.writeBytes(command, command.length);
				StringBuilder heard = new StringBuilder();
				long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MILLISECONDS;
				while (System.currentTimeMillis() < deadline) {
					int available = port.bytesAvailable();
					if (available > 0) {
						byte[] buffer = new byte[available];
						int read = port.readBytes(buffer, available);
						if (read > 0) heard.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
						if (heard.indexOf("Ok-GetSwitchState") >= 0) return true;
					}
					Thread.sleep(50);
				}
			}
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} finally {
			port.closePort();
		}
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
