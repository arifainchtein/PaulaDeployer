package com.digitalstables.pauladeployer.forms;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// Wi-Fi panel in the (i) popup (2026-10-25): which network wlan1 (the client side - wlan0 is Paula's own
// hotspot) is on, its signal, whether the Factory WireGuard tunnel is up, and buttons to pin wlan1 to one
// saved network or go back to Automatic. Normally nothing needs pressing: wpa_supplicant picks the home
// Wi-Fi (priority 10) when it can see it and the iPhone hotspot (priority 1) otherwise, and
// factory-tunnel.timer (doc 07, OpenWrtOne) brings the tunnel up only away from home. The buttons are for
// the edge of the home Wi-Fi's range, where wlan1 hangs on to a weak home signal.
//
//   action=status (default)   current network, signal, saved networks, tunnel, mode
//   action=select&id=<n>      wpa_cli select_network <n> (disables the others until Automatic)
//   action=auto               wpa_cli enable_network all
//
// A select that hasn't connected within SELECT_FALLBACK_SECONDS goes back to Automatic by itself, so a
// wrong tap can't leave Paula on a network that isn't there. select_network isn't saved to
// wpa_supplicant.conf, so a reboot is also back to Automatic. The DHCP renew after a network change is
// done by factory-tunnel (it watches the SSID).
public class WifiNetworkProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("WifiNetworkProcessingHandler");

	private static final String WPA_CLI = "/usr/sbin/wpa_cli";
	private static final String INTERFACE = "wlan1";
	private static final String TUNNEL_INTERFACE = "factory";
	private static final long SELECT_FALLBACK_SECONDS = 30;

	private static final ScheduledExecutorService fallbackScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "wifi-select-fallback");
		t.setDaemon(true);
		return t;
	});
	// the network id pinned with select, or -1 in Automatic; shared by all requests
	private static volatile int selectedNetworkId = -1;

	public WifiNetworkProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			String action = request.getParameter("action");
			if(action == null) action = "status";
			String message = "";
			if("select".equals(action)){
				int id = Integer.parseInt(request.getParameter("id"));
				if(wpaCli("select_network", String.valueOf(id)) == null){
					return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "wpa_cli select_network " + id + " failed");
				}
				selectedNetworkId = id;
				logger.info("wlan1 pinned to network " + id + ", back to Automatic in " + SELECT_FALLBACK_SECONDS + " s if it doesn't connect");
				fallbackScheduler.schedule(() -> fallbackIfNotConnected(id), SELECT_FALLBACK_SECONDS, TimeUnit.SECONDS);
				message = "Switching - if it doesn't connect within " + SELECT_FALLBACK_SECONDS + " s, Paula goes back to Automatic.";
			}else if("auto".equals(action)){
				if(wpaCli("enable_network", "all") == null){
					return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "wpa_cli enable_network all failed");
				}
				selectedNetworkId = -1;
				logger.info("wlan1 back to Automatic (all saved networks enabled)");
				message = "Automatic: Paula uses the home Wi-Fi when it can see it, otherwise the hotspot.";
			}
			JSONObject data = status();
			data.put("message", message);
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

	private static void fallbackIfNotConnected(int id){
		if(selectedNetworkId != id) return;   // Automatic or another network was chosen meanwhile
		JSONObject s = statusLines(wpaCli("status"));
		String state = s.optString("wpa_state");
		if("COMPLETED".equals(state) && String.valueOf(id).equals(s.optString("id"))) return;
		logger.warn("wlan1 didn't connect to network " + id + " (state " + state + ") - back to Automatic");
		wpaCli("enable_network", "all");
		selectedNetworkId = -1;
	}

	private static JSONObject status(){
		JSONObject data = new JSONObject();
		JSONObject s = statusLines(wpaCli("status"));
		boolean connected = "COMPLETED".equals(s.optString("wpa_state"));
		data.put("state", s.optString("wpa_state", "unknown"));
		data.put("connected", connected);
		if(connected){
			data.put("ssid", decodeSsid(s.optString("ssid")));
			data.put("networkId", s.optString("id"));
			data.put("ip", s.optString("ip_address"));
			JSONObject signal = statusLines(wpaCli("signal_poll"));
			if(signal.has("RSSI")) data.put("rssi", Integer.parseInt(signal.getString("RSSI")));
		}

		// list_networks: "network id / ssid / bssid / flags" header, then tab-separated rows
		JSONArray networks = new JSONArray();
		String list = wpaCli("list_networks");
		if(list != null){
			for(String line : list.split("\n")){
				// runCommand trims the output, so the last row loses its trailing tab when it has no flags
				String[] cols = line.split("\t", -1);
				if(cols.length < 2 || !cols[0].trim().matches("\\d+")) continue;
				String flags = cols.length > 3 ? cols[3] : "";
				JSONObject n = new JSONObject();
				n.put("id", Integer.parseInt(cols[0].trim()));
				n.put("ssid", decodeSsid(cols[1]));
				n.put("current", flags.contains("CURRENT"));
				n.put("disabled", flags.contains("DISABLED"));
				networks.put(n);
			}
		}
		data.put("networks", networks);
		data.put("selectedNetworkId", selectedNetworkId);
		data.put("tunnelUp", new File("/sys/class/net/" + TUNNEL_INTERFACE).exists());
		return data;
	}

	// wpa_cli as root (Tomcat's user is in netdev too, but sudo matches the other handlers and doesn't
	// depend on the ctrl_interface group); null on failure or "FAIL"
	private static String wpaCli(String... args){
		String[] command = new String[args.length + 4];
		command[0] = "sudo";
		command[1] = WPA_CLI;
		command[2] = "-i";
		command[3] = INTERFACE;
		System.arraycopy(args, 0, command, 4, args.length);
		String out = Utils.runCommand(5, command);
		if(out == null || out.startsWith("FAIL")) return null;
		return out;
	}

	// key=value lines (status, signal_poll) as a JSON object
	private static JSONObject statusLines(String out){
		JSONObject o = new JSONObject();
		if(out == null) return o;
		for(String line : out.split("\n")){
			int eq = line.indexOf('=');
			if(eq > 0) o.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
		}
		return o;
	}

	// wpa_cli prints non-ASCII SSID bytes as \xHH ("Ari\xe2\x80\x99s iPhone" = Ari's with a curly apostrophe)
	static String decodeSsid(String escaped){
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for(int i = 0; i < escaped.length(); i++){
			char c = escaped.charAt(i);
			if(c == '\\' && i + 3 < escaped.length() && escaped.charAt(i + 1) == 'x'){
				try{
					bytes.write(Integer.parseInt(escaped.substring(i + 2, i + 4), 16));
					i += 3;
					continue;
				}catch(NumberFormatException e){
					// not an escape after all, keep the backslash
				}
			}else if(c == '\\' && i + 1 < escaped.length() && escaped.charAt(i + 1) == '\\'){
				bytes.write('\\');
				i++;
				continue;
			}
			bytes.write(c);
		}
		return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
	}

}
