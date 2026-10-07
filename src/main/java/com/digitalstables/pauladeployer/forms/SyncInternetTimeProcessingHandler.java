package com.digitalstables.pauladeployer.forms;

import java.net.HttpURLConnection;
import java.net.URL;

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

// "Internet Time" header button: sets the Pi's clock from the internet before it is used to set a
// device's RTC (the Pi has no RTC of its own, so after a boot without internet its clock can be
// anything). First restarts systemd-timesyncd and waits for an NTP sync; if that doesn't happen in
// NTP_WAIT_SECONDS (no internet yet, UDP 123 blocked), falls back to the Date header of a plain
// HTTP request and sets the clock with "date". Needs passwordless sudo, like Shutdown.
public class SyncInternetTimeProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("SyncInternetTimeProcessingHandler");
	private static final int NTP_WAIT_SECONDS = 10;

	public SyncInternetTimeProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			String method = null;

			if(Utils.runCommand(15, "sudo", "systemctl", "restart", "systemd-timesyncd") != null){
				for(int i = 0; i < NTP_WAIT_SECONDS; i++){
					Thread.sleep(1000);
					if(Boolean.TRUE.equals(Utils.isNtpSynchronized())){
						method = "NTP";
						break;
					}
				}
			}

			if(method == null){
				long httpMillis = readHttpDateMillis();
				if(httpMillis > 0 && Utils.runCommand(10, "sudo", "date", "-u", "-s", "@" + (httpMillis / 1000)) != null){
					method = "HTTP (" + Constants.HTTP_TIME_URL + ")";
				}
			}

			if(method == null){
				logger.warn("Internet time failed - neither NTP nor HTTP gave a time");
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Could not get the time from the internet - is this Pi online (wlan1)?");
			}

			long after = System.currentTimeMillis();
			logger.info("Pi clock set from the internet via " + method);
			JSONObject data = new JSONObject();
			data.put("method", method);
			data.put("piTimeMillis", after);
			data.put("timeZone", Constants.DEVICE_TIME_ZONE);
			Boolean ntpSynced = Utils.isNtpSynchronized();
			if(ntpSynced != null) data.put("ntpSynchronized", ntpSynced.booleanValue());
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

	// Date header of a HEAD request, in epoch millis, or -1.
	private long readHttpDateMillis(){
		try{
			HttpURLConnection conn = (HttpURLConnection) new URL(Constants.HTTP_TIME_URL).openConnection();
			conn.setRequestMethod("HEAD");
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(5000);
			conn.setInstanceFollowRedirects(false);
			conn.connect();
			long date = conn.getDate();
			conn.disconnect();
			return date > 0 ? date : -1;
		}catch(Exception e){
			logger.warn("HTTP time request failed: " + e.getMessage());
			return -1;
		}
	}

}
