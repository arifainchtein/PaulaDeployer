package com.digitalstables.pauladeployer.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.TimeZone;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

// Field data captures (2026-10-24): what Paula recorded in the field, one plain-text file per
// session, sorted by kind -
//   /home/pi/paulauploader/captures/<kind>/<yyyy-MM-dd_HHmmss>[_<label>].txt
// e.g. captures/lorasniffer/2026-10-24_140322.txt, captures/firmwareupload/2026-10-24_141005_Cleotilde.txt.
// Browsable at http://<paula>/captures/ (CaptureServlet). A new kind needs only a constant here.
public class FieldCaptures {

	private static final Logger logger = LogManager.getLogger("FieldCaptures");

	public static final File ROOT = new File(Constants.PAULAUPLOADER_HOME, "captures");
	public static final String KIND_LORA_SNIFFER = "lorasniffer";
	public static final String KIND_FIRMWARE_UPLOAD = "firmwareupload";

	private static final String NAME_PATTERN = "[A-Za-z0-9_.-]+";

	// A new, empty capture file of that kind, named after now and label (null for none).
	public static File newFile(String kind, String label) throws IOException {
		File dir = new File(ROOT, kind);
		if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
		String base = format("yyyy-MM-dd_HHmmss");
		if (label != null && !label.trim().isEmpty()) base += "_" + label.trim().replaceAll("[^A-Za-z0-9_-]+", "_");
		File out = new File(dir, base + ".txt");
		for (int i = 2; out.exists(); i++) out = new File(dir, base + "_" + i + ".txt");
		if (!out.createNewFile()) throw new IOException("cannot create " + out);
		return out;
	}

	// The device's time zone, not the Pi's (see Constants.DEVICE_TIME_ZONE).
	public static String format(String pattern) {
		SimpleDateFormat f = new SimpleDateFormat(pattern);
		f.setTimeZone(TimeZone.getTimeZone(Constants.DEVICE_TIME_ZONE));
		return f.format(new Date());
	}

	// A firmware upload's terminal log, written when the attempt completes (PersistenceManager.completeAttempt).
	public static void saveFirmwareUpload(JSONObject attempt) {
		if (attempt == null) return;
		try {
			File out = newFile(KIND_FIRMWARE_UPLOAD, attempt.optString("productname", null));
			String header = "# Firmware upload " + attempt.optString("reponame") + " v" + attempt.optInt("version")
					+ " to " + attempt.optString("productname") + ", " + attempt.optString("status")
					+ ", saved " + format("yyyy-MM-dd HH:mm:ss z") + "\n";
			Files.write(out.toPath(), (header + attempt.optString("terminallog")).getBytes(StandardCharsets.UTF_8));
			logger.info("Saved " + out);
		} catch (IOException e) {
			logger.warn("Could not save the firmware upload capture: " + e.getMessage());
		}
	}

	// The kinds that have captures, each with its files newest first: {kind: [{name, size, modified}]}.
	public static JSONObject list() {
		JSONObject kinds = new JSONObject();
		File[] dirs = ROOT.listFiles(File::isDirectory);
		if (dirs == null) return kinds;
		for (File dir : dirs) {
			File[] files = dir.listFiles((d, name) -> name.endsWith(".txt"));
			if (files == null) continue;
			Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
			JSONArray list = new JSONArray();
			for (File f : files) {
				JSONObject o = new JSONObject();
				o.put("name", f.getName());
				o.put("size", f.length());
				o.put("modified", f.lastModified());
				list.put(o);
			}
			kinds.put(dir.getName(), list);
		}
		return kinds;
	}

	// A capture by kind and bare name, or null - never a path outside ROOT.
	public static File find(String kind, String name) {
		if (kind == null || name == null || !kind.matches(NAME_PATTERN) || !name.matches(NAME_PATTERN)
				|| kind.startsWith(".") || name.startsWith(".")) return null;
		File f = new File(new File(ROOT, kind), name);
		return f.isFile() ? f : null;
	}
}
