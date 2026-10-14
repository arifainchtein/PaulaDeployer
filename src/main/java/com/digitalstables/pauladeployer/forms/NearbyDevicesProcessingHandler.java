package com.digitalstables.pauladeployer.forms;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.digitalstables.pauladeployer.flash.PaulaRadio;
import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// "Nearby devices" (2026-10-14): the Daffodils Paula hears over LoRa, and their WiFi on/off - the
// same DeviceCommandRecord Annabelle's web page sends, so a Daffodil's own web page can be reached
// standing next to it. Paula.ino holds the command and resends it until the device's data shows
// the change (a sleeping device answers when it next wakes). Talks to Paula through PaulaRadio,
// which keeps the port open so Paula's lists survive between requests.
//   action=list                         -> Paula's GetNearbyDevices JSON
//   action=wifi, serial=<16 hex>, state=on|off
//   action=setClock                     -> sets Paula's RTC from the Pi's clock
// The list also carries clockOffsetSec (Paula's clock minus the Pi's) - a Daffodil rejects
// commands whose time is more than 15 min from its own clock (2026-10-14: every Paula command was
// rejected while Annabelle's worked).
public class NearbyDevicesProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("NearbyDevicesProcessingHandler");
	private static final int TIMEOUT_MILLISECONDS = 5000;

	public NearbyDevicesProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		try{
			String action = request.getParameter("action");
			if("wifi".equals(action)){
				String serial = request.getParameter("serial");
				String state = request.getParameter("state");
				if(serial == null || !serial.matches("[0-9A-Fa-f]{16}") || !("on".equals(state) || "off".equals(state))){
					return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Expected a 16-digit serial and on/off.");
				}
				String reply = PaulaRadio.get().command("DeviceWifi#" + serial.toUpperCase() + "#" + state, TIMEOUT_MILLISECONDS);
				if(reply == null || !reply.contains("Ok-DeviceWifi")){
					return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", reply == null ? "Paula did not answer - is it plugged in?" : reply);
				}
				logger.info("WiFi " + state + " requested for " + serial);
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", "Queued");
			}
			if("setClock".equals(action)){
				String error = PaulaRadio.get().setClockNow();
				if(error != null) return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", error);
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", "Clock set");
			}
			String reply = PaulaRadio.get().command("GetNearbyDevices", TIMEOUT_MILLISECONDS);
			if(reply == null){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Paula did not answer - is it plugged in?");
			}
			for(String line : reply.split("\n")){
				if(line.startsWith("{") && line.contains("\"devices\"")){
					JSONObject data = new JSONObject(line.trim());
					if(data.has("paulaTime")){
						// Code time = the devices' local wall-clock fields counted as UTC seconds (PCF8563TimeManager::dateAsSeconds)
						long piCodeTime = LocalDateTime.now(ZoneId.of(Constants.DEVICE_TIME_ZONE)).toEpochSecond(ZoneOffset.UTC);
						data.put("clockOffsetSec", data.getLong("paulaTime") - piCodeTime);
					}
					Boolean ntp = Utils.isNtpSynchronized();
					if(ntp != null) data.put("piNtpSynced", ntp.booleanValue());
					return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
				}
			}
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Unexpected answer from Paula: " + reply);
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
	}

}
