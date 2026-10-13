package com.digitalstables.pauladeployer.chat;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.TimeZone;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.flash.FirmwareFlasher;
import com.digitalstables.pauladeployer.utils.Constants;
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
//
// Stream to file (2026-10-13): every stream is also saved to LOG_DIR as <DeviceName>_<yyyy-MM-dd_HHmm>.txt
// (device time zone), so out of WiFi range nothing is lost. Target FILE_ONLY skips the chat
// altogether; a chat target that can't be reached at the start drops to file only instead of
// failing. The name comes from the device's GetProductDefinition reply, sent before the start
// command ("Device" if it doesn't answer, e.g. asleep). The page lists the files
// (StreamToChatProcessingHandler action=logs) and StreamLogServlet serves them for download - from
// the phone they can then go to a chat through the photo upload page.
public class SerialChatStreamer {

	private static final Logger logger = LogManager.getLogger("SerialChatStreamer");

	private static final int DATA_RATE = 115200;
	private static final long BATCH_MS = 3000;
	private static final int BATCH_LINES = 25;
	private static final int MAX_MESSAGE_CHARS = 3500;
	private static final int RECENT_LINES = 200;
	private static final long NAME_WAIT_MS = 4000;      // for the GetProductDefinition reply
	private static final long COMMAND_GAP_MS = 1200;    // the device reads a command until 1 s of silence

	public static final String FILE_ONLY = "File only";
	public static final File LOG_DIR = new File(Constants.PAULAUPLOADER_HOME, "streamlogs");

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
	private String fileName = "";
	private boolean chatOn = false;
	private final Deque<String> recent = new ArrayDeque<>();
	private BufferedWriter file;   // worker thread only

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
		s.put("file", fileName);
		s.put("chat", chatOn);
		s.put("lines", new JSONArray(new ArrayList<>(recent)));
		return s;
	}

	// Returns "" when started, otherwise why not.
	public synchronized String start(String chatTarget, String command) {
		if (running) return "Already streaming to " + target + " - stop it first.";
		if (chatTarget == null || chatTarget.trim().isEmpty()) return "Choose a chat, or File only.";
		target = chatTarget.trim();
		startCommand = command == null ? "" : command.trim();
		linesRead = 0;
		messagesSent = 0;
		lastError = "";
		fileName = "";
		chatOn = !FILE_ONLY.equals(target);
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

			// Ask the device its name for the file name. Whatever it prints meanwhile is kept.
			List<String> early = new ArrayList<>();
			String deviceName = identify(port, early, partial);
			openFile(deviceName);
			for (String line : early) {
				writeFileLine(line);
				if (pending.isEmpty()) firstPendingAt = System.currentTimeMillis();
				pending.add(line);
			}

			if (chatOn) {
				try {
					chat.publish(target, "[Stream 2 Chat from " + chat.getMachine() + " started on " + port.getSystemPortName()
							+ (startCommand.isEmpty() ? "" : ", sent " + startCommand) + ", also saved as " + fileName + "]");
					synchronized (this) { messagesSent++; }
				} catch (Exception e) {
					// Out of WiFi range: keep going to the file only, rather than losing the session.
					synchronized (this) {
						chatOn = false;
						lastError = "Cannot reach FactoryChat - saving to the file only.";
					}
					logger.warn("Stream 2 Chat: chat unreachable, file only - " + e.getMessage());
				}
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
								writeFileLine(line);
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
			if (partial.length() > 0) {
				writeFileLine(partial.toString());
				pending.add(partial.toString());
			}
			flush(chat, pending);
			if (chatOn) {
				try {
					long lines;
					synchronized (this) { lines = linesRead; }
					chat.publish(target, "[Stream 2 Chat stopped - " + lines + " lines, saved as " + fileName + "]");
				} catch (Exception e) {
					logger.warn("Could not send the stop notice: " + e.getMessage());
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			fail("Stream error: " + e.getMessage());
		} finally {
			if (port != null && port.isOpen()) port.closePort();
			closeFile();
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

	// Sends GetProductDefinition and waits up to NAME_WAIT_MS for "Ok-GetProductDefinition#name#...#deviceName#..."
	// (field 14 = Device Name, else field 1). Other lines it reads go to early. Returns "Device" if none.
	private String identify(SerialPort port, List<String> early, StringBuilder partial) throws InterruptedException {
		byte[] cmd = "GetProductDefinition".getBytes(StandardCharsets.UTF_8);
		port.writeBytes(cmd, cmd.length);
		String name = "";
		long deadline = System.currentTimeMillis() + NAME_WAIT_MS;
		byte[] buf = new byte[1024];
		while (running && name.isEmpty() && System.currentTimeMillis() < deadline) {
			int available = port.bytesAvailable();
			if (available <= 0) { Thread.sleep(50); continue; }
			int n = port.readBytes(buf, Math.min(available, buf.length));
			for (int i = 0; i < n; i++) {
				char c = (char) (buf[i] & 0xFF);
				if (c == '\n' || c == '\r') {
					if (partial.length() == 0) continue;
					String line = partial.toString();
					partial.setLength(0);
					addLine(line);
					early.add(line);
					int at = line.indexOf("Ok-GetProductDefinition#");
					if (at >= 0) {
						String[] parts = line.substring(at).split("#");
						if (parts.length > 14 && !parts[14].trim().isEmpty()) name = parts[14].trim();
						else if (parts.length > 1) name = parts[1].trim();
					}
				} else if (c >= 32 || c == '\t') {
					partial.append(c);
				}
			}
		}
		// The device takes a command as everything until ~1 s of silence - keep the start command separate.
		Thread.sleep(COMMAND_GAP_MS);
		name = name.replaceAll("[^A-Za-z0-9_-]", "");
		return name.isEmpty() ? "Device" : name;
	}

	private void openFile(String deviceName) {
		try {
			if (!LOG_DIR.isDirectory() && !LOG_DIR.mkdirs()) throw new IOException("cannot create " + LOG_DIR);
			SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd_HHmm");
			f.setTimeZone(TimeZone.getTimeZone(Constants.DEVICE_TIME_ZONE));
			String base = deviceName + "_" + f.format(new Date());
			File out = new File(LOG_DIR, base + ".txt");
			for (int i = 2; out.exists(); i++) out = new File(LOG_DIR, base + "_" + i + ".txt");
			file = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8));
			synchronized (this) { fileName = out.getName(); }
			SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
			stamp.setTimeZone(TimeZone.getTimeZone(Constants.DEVICE_TIME_ZONE));   // the Pi's own zone is not set (Europe/London on Paula3)
			writeFileLine("# Stream from " + deviceName + " on " + portName + ", started " + stamp.format(new Date())
					+ (startCommand.isEmpty() ? "" : ", start command " + startCommand));
		} catch (IOException e) {
			file = null;
			synchronized (this) { lastError = "Could not create the log file: " + e.getMessage(); }
			logger.warn("Stream 2 Chat: " + e.getMessage());
		}
	}

	// Flushed per line, so a pulled plug or a dead Paula battery still leaves everything up to then.
	private void writeFileLine(String line) {
		if (file == null) return;
		try {
			file.write(line);
			file.newLine();
			file.flush();
		} catch (IOException e) {
			synchronized (this) { lastError = "Log file write failed: " + e.getMessage(); }
			closeFile();
		}
	}

	private void closeFile() {
		if (file == null) return;
		try { file.close(); } catch (IOException e) { /* ignore */ }
		file = null;
	}

	// The saved logs, newest first: [{name, size, modified}].
	public static JSONArray listLogs() {
		JSONArray list = new JSONArray();
		File[] files = LOG_DIR.listFiles((dir, name) -> name.endsWith(".txt"));
		if (files == null) return list;
		Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
		for (File f : files) {
			JSONObject o = new JSONObject();
			o.put("name", f.getName());
			o.put("size", f.length());
			o.put("modified", f.lastModified());
			list.put(o);
		}
		return list;
	}

	// A log by its bare name, or null - never a path outside LOG_DIR.
	public static File logFile(String name) {
		if (name == null || !name.matches("[A-Za-z0-9_.-]+\\.txt") || name.startsWith(".")) return null;
		File f = new File(LOG_DIR, name);
		return f.isFile() ? f : null;
	}

	// Sends the waiting lines as one or more chat messages (each under MAX_MESSAGE_CHARS).
	// File only (or chat unreachable): the lines are already in the file, just drop them.
	private void flush(FactoryChat chat, List<String> pending) {
		if (pending.isEmpty()) return;
		if (!chatOn) { pending.clear(); return; }
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
