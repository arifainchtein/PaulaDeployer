package com.digitalstables.pauladeployer.forms;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// Reconnect Wi-Fi (2026-11-03): brings wlan1 - the USB dongle that joins the house/factory WiFi or the
// phone's hotspot - down and up again, the same sequence /etc/rc.local runs at boot (provision-pi.sh),
// so a dropped connection no longer needs a reboot. wlan0, Paula's own hotspot (what the phone is
// usually on), is not touched.
//
// It takes up to ~2 minutes (ifup waits for WPA and DHCP, retried), and an ifdown/ifup started from
// inside Tomcat would be killed with it if anything restarted Tomcat - so, like RestartTomcat, it runs
// as a transient systemd unit, outside Tomcat's cgroup, and this returns at once. The page then polls
// WifiNetwork status to say how it went. Log: journalctl -t paula-wifi. Needs passwordless sudo.
//
// rc.local's steps: rfkill unblock, ifdown (bounded), then up to 4 ifup attempts, each bounded by
// timeout - a stuck ifup holds ifupdown's lock forever otherwise (provision-pi.sh, 2026-09-07). Then
// wpa_cli enable_network all, i.e. back to Automatic if the (i) panel had pinned a network.
public class ReconnectWifiProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("ReconnectWifiProcessingHandler");
	private static final String INTERFACE = "wlan1";   // fixed by provision-pi.sh's udev rule

	private static final String SCRIPT =
			"logger -t paula-wifi 'reconnecting " + INTERFACE + " (PaulaDeployer)'; "
			+ "rfkill unblock all || true; "
			+ "timeout 20 ifdown " + INTERFACE + " || true; "
			+ "sleep 2; "
			+ "i=0; while [ $i -lt 4 ]; do "
			+ "  if timeout 30 ifup " + INTERFACE + "; then logger -t paula-wifi 'ifup " + INTERFACE + " ok'; break; fi; "
			+ "  i=$((i+1)); logger -t paula-wifi \"ifup " + INTERFACE + " failed, attempt $i\"; sleep 5; "
			+ "done; "
			+ "wpa_cli -i " + INTERFACE + " enable_network all >/dev/null 2>&1 || true; "
			+ "logger -t paula-wifi \"done: $(wpa_cli -i " + INTERFACE + " status 2>/dev/null | grep -E '^(ssid|wpa_state|ip_address)=' | tr '\\n' ' ')\"";

	public ReconnectWifiProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		try{
			logger.warn("Wi-Fi reconnect of " + INTERFACE + " requested via web UI");
			ProcessBuilder pb = new ProcessBuilder("sudo", "systemd-run", "--collect",
					"--unit=pauladeployer-wifi-" + System.currentTimeMillis(),
					"/bin/sh", "-c", SCRIPT);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			// systemd-run starts the unit and returns at once, so its exit code is the real answer
			boolean exited = p.waitFor(5, TimeUnit.SECONDS);
			StringBuilder output = new StringBuilder();
			if(exited){
				try(BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))){
					String line;
					while((line = reader.readLine()) != null) output.append(line).append(" ");
				}
			}
			if(!exited || p.exitValue() != 0){
				String detail = exited ? "exit code " + p.exitValue() + (output.length() > 0 ? ": " + output.toString().trim() : "") : "systemd-run did not return";
				logger.warn("Wi-Fi reconnect command failed - " + detail);
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Reconnect command failed (" + detail + ") - is passwordless sudo set up for this user?");
			}
			JSONObject data = new JSONObject();
			data.put("message", "Reconnecting " + INTERFACE + " - this takes up to 2 minutes.");
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
	}

}
