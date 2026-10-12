package com.digitalstables.pauladeployer.chat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.flash.FirmwareFlasher;
import com.fazecast.jSerialComm.SerialPort;

// Stream 2 Chat (2026-10-12): holds the target device's serial port open, sends a start command
// (debug#1 by default) and forwards everything the device prints to a FactoryChat participant -
// so field debug output reaches a Claude session (or anyone in the chat) without copy/paste.
//
// One stream at a time for the whole webapp (singleton). While it runs it owns the port:
// FirmwareFlasher refuses Send Command / Inspect / flashing (isRunning()) instead of fighting it
// for the port.
//
// Lines are sent in batches - at most every BATCH_MS, or as soon as BATCH_LINES are waiting - so
// a chatty device doesn't flood the chat with one message per line. The last RECENT_LINES lines
// are kept for the webapp's live view.
public class SerialChatStreamer {

	private static final Logger logger = LogManager.getLogger("SerialChatStreamer");

	private static final int DATA_RATE = 115200;
	private static final long BATCH_MS = 3000;
	private static final int BATCH_LINES = 25;
	private static final int MAX_MESSAGE_CHARS = 3500;
	private static final int RECENT_LINES = 200;

	private static final SerialChatStreamer INSTANCE = new SerialChatStreamer();
	public static SerialChatStreamer get() { return INSTANCE; }

	private volatile boolean running = false;
	private volatile Thread worker;
	private String target = "";
	private String startCommand = "";
	private String portName = "";
	private long startedAt = 0;
	private long linesRead = 0;
	private long messagesSent = 0;
	private String lastError = "";
	private final Deque<String> recent = new ArrayDeque<>();

	private SerialChatStreamer() { }

	public boolean isRunning() { return running; }

	public synchronized JSONObject status() {
		JSONObject s = new JSONObject();
		s.put("running", running);
		s.put("target", target);
		s.put("startCommand", startCommand);
		s.put("port", portName);
		s.put("startedAt", startedAt);
		s.put("linesRead", linesRead);
		s.put("messagesSent", messagesSent);
		s.put("lastError", lastError);
		s.put("lines", new JSONArray(new ArrayList<>(recent)));
		return s;
	}

	// Returns "" when started, otherwise why not.
	public synchronized String start(String chatTarget, String command) {
		if (running) return "Already streaming to " + target + " - stop it first.";
		if (chatTarget == null || chatTarget.trim().isEmpty()) return "Choose a chat to stream to.";
		target = chatTarget.trim();
		startCommand = command == null ? "" : command.trim();
		linesRead = 0;
		messagesSent = 0;
		lastError = "";
		recent.clear();
		startedAt = System.currentTimeMillis();
		running = true;
		worker = new Thread(this::run, "stream2chat");
		worker.setDaemon(true);
		worker.start();
		return "";
	}

	public void stop() {
		running = false;
		Thread w = worker;
		if (w != null) {
			try { w.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		}
	}

	private void run() {
		FactoryChat chat = new FactoryChat();
		SerialPort port = null;
		List<String> pending = new ArrayList<>();
		long firstPendingAt = 0;
		StringBuilder partial = new StringBuilder();
		try {
			port = new FirmwareFlasher().findTargetPort(true);
			if (port == null) {
				fail("No target device found - is it plugged in (USB jumper on the USB position)?");
				return;
			}
			synchronized (this) { portName = port.getSystemPortName(); }
			port.setComPortParameters(DATA_RATE, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
			port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
			// Opening the port pulses DTR/RTS, which resets an ESP32 behind a CP2104 (seen
			// 2026-09-05). Asking for both low before the open keeps the device running where the
			// driver honours it.
			port.clearDTR();
			port.clearRTS();
			if (!port.openPort()) {
				fail("Could not open " + port.getSystemPortName() + " - is another tool using it?");
				return;
			}
			try {
				chat.publish(target, "[Stream 2 Chat from " + chat.getMachine() + " started on " + port.getSystemPortName()
						+ (startCommand.isEmpty() ? "" : ", sent " + startCommand) + "]");
				synchronized (this) { messagesSent++; }
			} catch (Exception e) {
				fail("Cannot reach FactoryChat (factory2) - " + e.getMessage());
				return;
			}
			if (!startCommand.isEmpty()) {
				byte[] cmd = startCommand.getBytes(StandardCharsets.UTF_8);
				port.writeBytes(cmd, cmd.length);
			}

			byte[] buf = new byte[1024];
			while (running) {
				int available = port.bytesAvailable();
				if (available < 0) {
					fail("The device was unplugged.");
					break;
				}
				if (available > 0) {
					int n = port.readBytes(buf, Math.min(available, buf.length));
					for (int i = 0; i < n; i++) {
						char c = (char) (buf[i] & 0xFF);
						if (c == '\n' || c == '\r') {
							if (partial.length() > 0) {
								String line = partial.toString();
								partial.setLength(0);
								addLine(line);
								if (pending.isEmpty()) firstPendingAt = System.currentTimeMillis();
								pending.add(line);
							}
						} else if (c >= 32 || c == '\t') {
							partial.append(c);
						}
					}
				} else {
					Thread.sleep(50);
				}
				boolean due = !pending.isEmpty()
						&& (pending.size() >= BATCH_LINES || System.currentTimeMillis() - firstPendingAt >= BATCH_MS);
				if (due) {
					flush(chat, pending);
				}
			}
			if (partial.length() > 0) pending.add(partial.toString());
			flush(chat, pending);
			try {
				long lines;
				synchronized (this) { lines = linesRead; }
				chat.publish(target, "[Stream 2 Chat stopped - " + lines + " lines]");
			} catch (Exception e) {
				logger.warn("Could not send the stop notice: " + e.getMessage());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			fail("Stream error: " + e.getMessage());
		} finally {
			if (port != null && port.isOpen()) port.closePort();
			chat.disconnect();
			running = false;
			worker = null;
		}
	}

	private synchronized void addLine(String line) {
		linesRead++;
		recent.addLast(line);
		while (recent.size() > RECENT_LINES) recent.removeFirst();
	}

	// Sends the waiting lines as one or more chat messages (each under MAX_MESSAGE_CHARS).
	private void flush(FactoryChat chat, List<String> pending) {
		if (pending.isEmpty()) return;
		StringBuilder msg = new StringBuilder();
		for (String line : pending) {
			if (msg.length() > 0 && msg.length() + line.length() + 1 > MAX_MESSAGE_CHARS) {
				send(chat, msg.toString());
				msg.setLength(0);
			}
			if (msg.length() > 0) msg.append('\n');
			msg.append(line);
		}
		if (msg.length() > 0) send(chat, msg.toString());
		pending.clear();
	}

	private void send(FactoryChat chat, String text) {
		try {
			chat.publish(target, text);
			synchronized (this) { messagesSent++; }
		} catch (Exception e) {
			synchronized (this) { lastError = "Chat send failed: " + e.getMessage(); }
			logger.warn("Chat send failed: " + e.getMessage());
		}
	}

	private void fail(String why) {
		logger.warn("Stream 2 Chat: " + why);
		synchronized (this) { lastError = why; }
		running = false;
	}
}
