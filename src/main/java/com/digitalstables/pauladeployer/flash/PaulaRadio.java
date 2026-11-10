package com.digitalstables.pauladeployer.flash;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.forms.SetDeviceTimeProcessingHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.FieldCaptures;
import com.digitalstables.pauladeployer.utils.Utils;
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
	private OutputStream output;
	private final StringBuilder partialLine = new StringBuilder();

	// LoRa Sniffer (2026-10-24): Paula's "SNIFF {...}" lines (LoraSniffer#1, see printSniff in
	// Paula.ino), each with the Pi's time and a sequence number so the page asks only for new ones.
	// Collected whenever the port is read - drain() and command() keep them instead of discarding.
	private static final String SNIFF_PREFIX = "SNIFF ";
	private static final int SNIFF_KEEP = 500;
	private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
	private final LinkedList<JSONObject> sniffed = new LinkedList<JSONObject>();
	private long sniffSequence = 0;
	private boolean sniffing = false;
	// The session's capture file (FieldCaptures, kind lorasniffer) - from opening the Lora Sniffer
	// screen to leaving it, Pause/Resume included - and its summary, written at the end:
	// label -> {count, last time}. namesBySerial: VitalSigns packets carry only the serial, so they
	// count under the name that device's data packets gave.
	private BufferedWriter capture;
	private String captureName;
	private final Map<String, Object[]> summary = new TreeMap<String, Object[]>();
	private final Map<String, String> namesBySerial = new HashMap<String, String>();
	// Signal Map (2026-11-03): the phone's GPS position, sent with each poll. Paula itself has no GPS
	// (that is Paula2), so each packet is stamped with where the phone was - "plat"/"plon"/"pacc" -
	// if it was told within PHONE_POSITION_MAX_AGE, and the capture file gets it too, so a walk can
	// be read back as "heard X at this spot".
	private static final long PHONE_POSITION_MAX_AGE_MILLISECONDS = 30000;
	private double phoneLat, phoneLon, phoneAccuracy;
	private long phonePositionAt = 0;

	public static PaulaRadio get() {
		return INSTANCE;
	}

	// System name of the held port, or null.
	public synchronized String heldPortName() {
		return port != null && port.isOpen() ? port.getSystemPortName() : null;
	}

	// Paula's port name, opening (and so finding) it if it isn't held yet - null when Paula isn't
	// connected. FirmwareFlasher.findTargetPort uses it so a device command never goes to Paula
	// (2026-11-11). With paula.port=auto and nothing held this probes the CP210x ports once; after
	// that Paula stays held and nothing is probed again.
	public synchronized String paulaPortName() {
		return ensureOpen() ? port.getSystemPortName() : null;
	}

	// Sends a command and returns everything Paula printed up to its "Ok-"/"Failure" line, or
	// null when Paula can't be found or doesn't answer within timeoutMillis.
	public synchronized String command(String command, int timeoutMillis) {
		return command(command, timeoutMillis, true);
	}

	// Send Command's "Paula" destination (2026-11-11): a typed command may be one Paula answers
	// without an Ok-/Failure line (SetTime, GetCommandCode), so silence returns what arrived and
	// keeps the port - closing it would reset Paula and lose the Nearby Devices she has heard.
	public synchronized String typedCommand(String command, int timeoutMillis) {
		return command(command, timeoutMillis, false);
	}

	private String command(String command, int timeoutMillis, boolean closeOnSilence) {
		if (!ensureOpen()) return null;
		try {
			drain();  // debug output / boot messages since last time
			output.write(command.getBytes(StandardCharsets.UTF_8));
			output.flush();
			StringBuilder transcript = new StringBuilder();
			long deadline = System.currentTimeMillis() + timeoutMillis;
			while (System.currentTimeMillis() < deadline) {
				String line = nextLine();
				if (line == null) {
					Thread.sleep(50);
					continue;
				}
				if (keepSniffed(line)) continue;
				if (transcript.length() > 0) transcript.append("\n");
				transcript.append(line);
				if (line.startsWith("Ok-") || line.startsWith("Failure")) return transcript.toString();
			}
			if (!closeOnSilence) return transcript.toString();
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
		output = port.getOutputStream();
		logger.info("Holding Paula's port " + port.getSystemPortName() + " open");
		try {
			Thread.sleep(BOOT_WAIT_MILLISECONDS);
			setPaulaClock();
			if (sniffing) writeSnifferCommand(true);  // opening the port reset Paula, which turned it off
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			logger.warn("Could not set Paula's clock: " + e.getMessage());
		}
		return true;
	}

	// Paula's commands carry a code for Paula's own time, and a Daffodil rejects them when that time
	// is more than 15 min from its clock (2026-10-14: Paula's were all rejected while Annabelle's
	// worked). So Paula's RTC is set from the Pi's each time the port is opened - only when the Pi's
	// clock is NTP-synced, since an offline Pi's clock can be hours off. Paula's SetTime sends no
	// "Ok-" line, so this doesn't wait for one; Paula reads a command after ~1 s of silence, so the
	// pause keeps the next command from running into this one.
	private void setPaulaClock() throws IOException, InterruptedException {
		if (!Boolean.TRUE.equals(Utils.isNtpSynchronized())) {
			logger.warn("Pi clock not NTP-synced - Paula's clock left as it is");
			return;
		}
		writeSetTime();
	}

	// The Nearby Devices "Set Paula's clock" button: sets it from the Pi's clock whether or not the
	// Pi is NTP-synced (the page shows that, so the user decides). Returns null when it worked.
	public synchronized String setClockNow() {
		if (!ensureOpen()) return "Paula not found - is it plugged in?";
		try {
			writeSetTime();
			return null;
		} catch (IOException | InterruptedException e) {
			close();
			return "Could not set Paula's clock: " + e.getMessage();
		}
	}

	private void writeSetTime() throws IOException, InterruptedException {
		String command = SetDeviceTimeProcessingHandler.setTimeCommand();
		output.write(command.getBytes(StandardCharsets.UTF_8));
		output.flush();
		Thread.sleep(1500);
		drain();  // Paula echoes the date without a line ending
		logger.info("Set Paula's clock: " + command);
	}

	// The Lora Sniffer screen's Start / Stop. session: on start, begin a new capture file (the screen
	// was just opened, not Resume); on stop, end it (the screen was left, not Pause). Returns null
	// when it worked.
	public synchronized String setSniffing(boolean on, boolean session) {
		if (session && on) startCapture();
		if (session && !on) endCapture();
		boolean wasOpen = heldPortName() != null;
		sniffing = on;
		if (!ensureOpen()) return on ? "Paula not found - is it plugged in?" : null;
		try {
			if (wasOpen) writeSnifferCommand(on);  // a fresh open reset Paula, and ensureOpen() already sent it
			return null;
		} catch (IOException | InterruptedException e) {
			close();
			return "Could not reach Paula: " + e.getMessage();
		}
	}

	// LoraSniffer#1/#0 - like SetTime, written without waiting for its "Ok-" (Paula reads a command
	// after ~1 s of silence), then whatever came in meanwhile is collected.
	private void writeSnifferCommand(boolean on) throws IOException, InterruptedException {
		output.write(("LoraSniffer#" + (on ? 1 : 0)).getBytes(StandardCharsets.UTF_8));
		output.flush();
		Thread.sleep(1500);
		drain();
		logger.info("Lora Sniffer " + (on ? "on" : "off"));
	}

	// The packets heard after sequence number `after`: {"sequence":<last>,"sniffing":..,"packets":[..]}.
	// A packet the page never asked for again drops off after SNIFF_KEEP.
	public synchronized JSONObject sniffedAfter(long after) {
		JSONObject result = new JSONObject();
		if (sniffing && ensureOpen()) {
			String line;
			while ((line = nextLine()) != null) keepSniffed(line);
		}
		JSONArray packets = new JSONArray();
		for (JSONObject packet : sniffed) {
			if (packet.getLong("sequence") > after) packets.put(packet);
		}
		result.put("sequence", sniffSequence);
		result.put("sniffing", sniffing);
		result.put("connected", port != null && port.isOpen());
		if (captureName != null) result.put("capture", captureName);
		result.put("packets", packets);
		return result;
	}

	// Signal survey ping (2026-11-03): Paula pings every device at `power` dBm (Paula.ino sendPing);
	// their answers arrive as SNIFF lines of type LinkAck over the next ~5.5 s and are kept like any
	// packet. Returns {"probe":<senderTime>,"seq":<n>,"power":<dBm>}, or null when Paula didn't answer.
	// The ping goes into the capture file, with where the phone was.
	public synchronized JSONObject ping(int power) {
		String reply = command("Ping#" + power, 4000);
		if (reply == null) return null;
		for (String line : reply.split("\n")) {
			if (!line.startsWith("Ok-Ping#")) continue;
			String[] f = line.split("#");
			if (f.length < 4) return null;
			JSONObject result = new JSONObject();
			result.put("probe", Long.parseLong(f[1].trim()));
			result.put("seq", Integer.parseInt(f[2].trim()));
			result.put("power", Integer.parseInt(f[3].trim()));
			String at = phonePositionAt > 0 && System.currentTimeMillis() - phonePositionAt < PHONE_POSITION_MAX_AGE_MILLISECONDS
					? String.format(" paula %.6f,%.6f ~%dm", phoneLat, phoneLon, Math.round(phoneAccuracy)) : "";
			writeCapture(LocalTime.now(ZoneId.of(Constants.DEVICE_TIME_ZONE)).format(HMS) + " PING #" + f[2].trim()
					+ " at " + f[3].trim() + " dBm" + ("0".equals(f[1].trim()) ? " NOT SENT" : "") + at);
			return result;
		}
		return null;
	}

	// Where the phone is (degrees, accuracy in metres) - see phoneLat.
	public synchronized void setPhonePosition(double lat, double lon, double accuracy) {
		phoneLat = lat;
		phoneLon = lon;
		phoneAccuracy = accuracy;
		phonePositionAt = System.currentTimeMillis();
	}

	// True when line was a sniffer line (kept).
	private boolean keepSniffed(String line) {
		if (!line.startsWith(SNIFF_PREFIX)) return false;
		JSONObject packet;
		try {
			packet = new JSONObject(line.substring(SNIFF_PREFIX.length()));
		} catch (Exception e) {
			packet = new JSONObject();
			packet.put("raw", line);  // cut short or garbled on the wire
		}
		packet.put("sequence", ++sniffSequence);
		String time = LocalTime.now(ZoneId.of(Constants.DEVICE_TIME_ZONE)).format(HMS);
		packet.put("time", time);
		String label = labelOf(packet);
		packet.put("label", label);
		if (phonePositionAt > 0 && System.currentTimeMillis() - phonePositionAt < PHONE_POSITION_MAX_AGE_MILLISECONDS) {
			packet.put("plat", phoneLat);
			packet.put("plon", phoneLon);
			packet.put("pacc", Math.round(phoneAccuracy));
		}
		sniffed.add(packet);
		while (sniffed.size() > SNIFF_KEEP) sniffed.removeFirst();
		Object[] entry = summary.get(label);
		if (entry == null) summary.put(label, entry = new Object[] { 0, null });
		entry[0] = (Integer) entry[0] + 1;
		entry[1] = time;
		writeCapture(captureLine(packet));
		return true;
	}

	// What the screen and the summary call the packet: the device's name, or the kind of packet.
	private String labelOf(JSONObject p) {
		if (p.has("raw")) return "Garbled";
		String serial = p.optString("serial", null);
		String name = p.optString("name", "");
		if (!name.isEmpty()) {
			if (serial != null) namesBySerial.put(serial, name);
			return name;
		}
		if (serial != null && namesBySerial.containsKey(serial)) return namesBySerial.get(serial);
		String type = p.optString("type");
		if ("WeatherForecastUpdate".equals(type)) return "Weather forecast";
		if (type.startsWith("VitalSignsRecord")) return "Vital signs " + (serial == null ? "" : serial);
		return type;
	}

	// The same line the screen shows: 14:03:22 Cleotilde R -87 s -10.8, then where the device says it
	// is and where the phone was, when known: "at -37.133056,144.474722 paula -37.134001,144.475120 ~5m"
	private static String captureLine(JSONObject p) {
		if (p.has("raw")) return p.getString("time") + " " + p.getString("raw");
		String line = p.getString("time") + " " + p.getString("label") + " R " + p.optInt("rssi") + " s "
				+ String.format("%.1f", p.optDouble("snr"));
		if (p.has("ok") && !p.getBoolean("ok")) line += "WeatherForecastUpdate".equals(p.optString("type")) ? " BAD CODE" : " BAD CHECKSUM";
		if ("LinkAck".equals(p.optString("type"))) {
			line += " answers PING #" + p.optInt("seq") + ": heard Paula R " + p.optInt("prssi") + " s " + String.format("%.1f", p.optDouble("psnr"));
			if (p.has("grssi")) line += ", hears Annabelle R " + p.optInt("grssi") + " s " + String.format("%.1f", p.optDouble("gsnr")) + " (" + p.optInt("gage") + " s ago)";
		}
		if (p.has("lat")) line += String.format(" at %.6f,%.6f", p.getDouble("lat"), p.getDouble("lon"));
		if (p.has("plat")) line += String.format(" paula %.6f,%.6f ~%dm", p.getDouble("plat"), p.getDouble("plon"), p.getLong("pacc"));
		if (p.has("hex")) line += "\n    " + p.optInt("size") + " B: " + p.getString("hex");
		return line;
	}

	private void startCapture() {
		endCapture();
		try {
			File f = FieldCaptures.newFile(FieldCaptures.KIND_LORA_SNIFFER, null);
			capture = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
			captureName = FieldCaptures.KIND_LORA_SNIFFER + "/" + f.getName();
			writeCapture("# Lora Sniffer, started " + FieldCaptures.format("yyyy-MM-dd HH:mm:ss z") + " (R = RSSI, s = SNR)");
		} catch (IOException e) {
			logger.warn("Could not create the Lora Sniffer capture: " + e.getMessage());
			capture = null;
			captureName = null;
		}
	}

	// The summary goes at the end of the file, then it's closed.
	private void endCapture() {
		if (capture != null) {
			writeCapture("# Ended " + FieldCaptures.format("yyyy-MM-dd HH:mm:ss z"));
			writeCapture("# Summary:");
			for (Map.Entry<String, Object[]> e : summary.entrySet()) {
				writeCapture("# " + e.getKey() + " " + e.getValue()[0] + " received, last " + e.getValue()[1]);
			}
			try { capture.close(); } catch (IOException e) { /* ignore */ }
		}
		capture = null;
		captureName = null;
		summary.clear();
		namesBySerial.clear();
	}

	// Flushed per line, so a pulled plug still leaves everything up to then.
	private void writeCapture(String line) {
		if (capture == null) return;
		try {
			capture.write(line);
			capture.newLine();
			capture.flush();
		} catch (IOException e) {
			logger.warn("Lora Sniffer capture write failed: " + e.getMessage());
			try { capture.close(); } catch (IOException e2) { /* ignore */ }
			capture = null;
		}
	}

	// Reads bytes straight from the port (2026-10-14): a BufferedReader's readLine() waited for the
	// end of a line that Paula's SetTime echo never sends, hit the port's read timeout and threw -
	// "the request timed out", and the port closed (resetting Paula). Never blocks.
	private String nextLine() {
		while (port.bytesAvailable() > 0) {
			byte[] one = new byte[1];
			if (port.readBytes(one, 1) != 1) break;
			char c = (char) (one[0] & 0xff);
			if (c == '\n') {
				String line = partialLine.toString();
				partialLine.setLength(0);
				return line;
			}
			if (c != '\r') partialLine.append(c);
		}
		return null;
	}

	// Discards everything waiting, half lines included - except sniffer lines.
	private void drain() {
		String line;
		while ((line = nextLine()) != null) keepSniffed(line);
		partialLine.setLength(0);
	}

	private void close() {
		if (port != null) port.closePort();
		port = null;
		output = null;
		partialLine.setLength(0);
	}
}
