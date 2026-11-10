package com.digitalstables.pauladeployer.flash;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

// The Nearby Devices "Data" button (2026-11-10): decodes a device's last packet, as Paula received it
// (GetNearbyPacket), and checks its layout.
//
// Receivers recognise a packet by its size only, and DigitalStablesData kept its 244 bytes through
// several rearrangements. A device on old firmware therefore sends a packet every receiver accepts and
// misreads: 2026-11-10 the Fish Tank's "battery" was 144.47 V - its longitude - and its "measured
// height" a fixed setting, which looked like a failing ultrasonic sensor. So the packet is decoded with
// every layout of its size that ever existed (PacketLayouts, generated from the library's history) and
// each is scored for how believable its values are; the best one is shown. When that is not the current
// layout (or one compatible with it), the answer says so: the device's firmware is older than the
// receivers, and Annabelle, Chinampa and this list read its values wrong.
public final class PacketDecoder {

	private PacketDecoder(){}

	public static JSONObject decode(byte[] raw, String receivedAs){
		PacketLayouts.Layout best = null, current = null;
		int bestScore = Integer.MAX_VALUE;
		JSONArray candidates = new JSONArray();
		for(PacketLayouts.Layout l : PacketLayouts.ALL){
			if(l.size != raw.length) continue;
			if(l.isCurrent() && l.struct.equals(receivedAs)) current = l;
			int score = implausibility(l, raw);
			JSONObject c = new JSONObject();
			c.put("struct", l.struct);
			c.put("layout", l.label());
			c.put("implausible", score);
			candidates.put(c);
			// ties go to the newer layout, and to the struct Paula received it as
			if(best == null || score < bestScore || (score == bestScore && (l.struct.equals(receivedAs) || !best.struct.equals(receivedAs)))){
				best = l;
				bestScore = score;
			}
		}
		JSONObject out = new JSONObject();
		out.put("candidates", candidates);
		if(best == null){
			out.put("layoutOk", false);
			out.put("note", "No known layout is " + raw.length + " bytes long.");
			return out;
		}
		boolean ok = current != null && (best == current || compatible(best, current));
		out.put("struct", best.struct);
		out.put("layout", best.label());
		out.put("layoutOk", ok);
		if(!ok){
			String note = "These values only make sense in the " + best.struct + " layout of " + best.label()
					+ ": the device runs firmware older than its receivers.";
			if(current != null){
				note += " Annabelle, Chinampa and this list read it as the current layout, and get it wrong"
						+ " (implausible values: " + implausibility(current, raw) + " current, " + bestScore + " in this layout)."
						+ " Reflash the device to fix it - the sensor is probably fine.";
			}
			out.put("note", note);
			if(current != null) out.put("asCurrent", fields(current, raw));
		}
		out.put("fields", fields(best, raw));
		return out;
	}

	// Same name -> same offset and kind for every field both have (a field added in padding, like
	// chargerStatus 2026-10-31, changes nothing for the rest).
	private static boolean compatible(PacketLayouts.Layout a, PacketLayouts.Layout b){
		if(!a.struct.equals(b.struct)) return false;
		Map<String, PacketLayouts.Field> bf = new HashMap<String, PacketLayouts.Field>();
		for(PacketLayouts.Field f : b.fields) bf.put(f.name, f);
		for(PacketLayouts.Field f : a.fields){
			PacketLayouts.Field g = bf.get(f.name);
			if(g != null && (g.offset != f.offset || !g.kind.equals(f.kind) || g.count != f.count)) return false;
		}
		return true;
	}

	// How many values look wrong: floats that are NaN, huge, or tiny non-zero (an integer read as a
	// float - 2.52e-43 is the int 180), voltages over 60 V, a position off the globe, names with
	// unprintable characters, booleans other than 0/1. Lower is more believable.
	static int implausibility(PacketLayouts.Layout l, byte[] raw){
		int score = 0;
		Double lat = null, lon = null;
		for(PacketLayouts.Field f : l.fields){
			if("CHAR".equals(f.kind)){
				if(f.count > 1 && !printable(raw, f.offset, f.count)) score += 1;
				continue;
			}
			if(f.count > 1) continue;
			if("F32".equals(f.kind)){
				float v = Float.intBitsToFloat(i32(raw, f.offset));
				if(Float.isNaN(v) || Float.isInfinite(v)) score += 3;
				else if(Math.abs(v) > 1e7) score += 2;
				else if(v != 0 && Math.abs(v) < 1e-20) score += 2;
				else if(isVoltage(f.name) && Math.abs(v) > 60) score += 3;
				if("latitude".equals(f.name)) lat = (double) v;
				if("longitude".equals(f.name)) lon = (double) v;
			}else if("BOOL".equals(f.kind)){
				if((raw[f.offset] & 0xFF) > 1) score += 1;
			}
		}
		if(lat != null && lon != null && (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)) score += 5;
		return score;
	}

	private static boolean isVoltage(String name){
		String n = name.toLowerCase(Locale.ROOT);
		return n.contains("voltage") || n.endsWith("volt");
	}

	private static JSONArray fields(PacketLayouts.Layout l, byte[] raw){
		JSONArray a = new JSONArray();
		for(PacketLayouts.Field f : l.fields){
			JSONArray row = new JSONArray();
			row.put(f.name);
			row.put(value(f, raw));
			a.put(row);
		}
		return a;
	}

	private static String value(PacketLayouts.Field f, byte[] raw){
		if("CHAR".equals(f.kind)){
			if(f.count == 1) return String.valueOf(raw[f.offset] & 0xFF);
			int n = 0;
			while(n < f.count && raw[f.offset + n] != 0) n++;
			StringBuilder s = new StringBuilder();
			for(byte b : new String(raw, f.offset, n, StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.ISO_8859_1)){
				int c = b & 0xFF;
				s.append(c >= 32 && c < 127 ? (char) c : '?');
			}
			return s.toString();
		}
		if(f.count > 1 && ("U8".equals(f.kind) || "I8".equals(f.kind))){
			StringBuilder s = new StringBuilder();
			for(int i = 0; i < f.count; i++) s.append(String.format("%02X", raw[f.offset + i] & 0xFF));
			return s.toString();
		}
		int width = width(f.kind);
		StringBuilder s = new StringBuilder();
		for(int i = 0; i < f.count; i++){
			if(i > 0) s.append(", ");
			s.append(scalar(f.kind, raw, f.offset + i * width));
		}
		return s.toString();
	}

	private static int width(String kind){
		if("F64".equals(kind)) return 8;
		if("F32".equals(kind) || "I32".equals(kind) || "U32".equals(kind)) return 4;
		if("I16".equals(kind) || "U16".equals(kind)) return 2;
		return 1;
	}

	private static String scalar(String kind, byte[] raw, int o){
		switch(kind){
			case "F32": {
				float v = Float.intBitsToFloat(i32(raw, o));
				return (v != 0 && Math.abs(v) < 1e-20) || Math.abs(v) >= 1e7 ? String.format(Locale.ROOT, "%.3g", v) : trim(v);
			}
			case "F64": return String.valueOf(Double.longBitsToDouble(((long) i32(raw, o + 4) << 32) | (i32(raw, o) & 0xFFFFFFFFL)));
			case "I32": return String.valueOf(i32(raw, o));
			case "U32": return String.valueOf(i32(raw, o) & 0xFFFFFFFFL);
			case "I16": return String.valueOf((short) ((raw[o] & 0xFF) | (raw[o + 1] << 8)));
			case "U16": return String.valueOf((raw[o] & 0xFF) | ((raw[o + 1] & 0xFF) << 8));
			case "I8": return String.valueOf(raw[o]);
			case "BOOL": return (raw[o] & 0xFF) == 0 ? "false" : (raw[o] & 0xFF) == 1 ? "true" : String.valueOf(raw[o] & 0xFF);
			default: return String.valueOf(raw[o] & 0xFF);
		}
	}

	private static String trim(float v){
		String s = String.format(Locale.ROOT, "%.4f", v);
		s = s.replaceAll("0+$", "");
		return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
	}

	private static boolean printable(byte[] raw, int o, int n){
		for(int i = 0; i < n; i++){
			int c = raw[o + i] & 0xFF;
			if(c == 0) return true;
			if(c < 32 || c > 126) return false;
		}
		return true;
	}

	private static int i32(byte[] raw, int o){
		return (raw[o] & 0xFF) | ((raw[o + 1] & 0xFF) << 8) | ((raw[o + 2] & 0xFF) << 16) | ((raw[o + 3] & 0xFF) << 24);
	}

	public static byte[] hex(String h){
		byte[] b = new byte[h.length() / 2];
		for(int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
		return b;
	}
}
