package com.digitalstables.pauladeployer.forms;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.flash.FirmwareFlasher;
import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// "Set Time" toolbar button: sets the plugged-in device's RTC from this Pi's clock, in the devices'
// own zone (Constants.DEVICE_TIME_ZONE, daylight saving included) - so a device leaves the bench
// with its clock right, which TOTP and Annabelle's time sync both depend on. Uses the firmware's
// existing serial command SetTime#date#month#yy#dayOfWeek#hour#minute#second, dayOfWeek counted
// from Sunday=1 as in the examples in Daffodil.ino. The command is built immediately before it is
// sent; the serial round trip adds well under a second.
public class SetDeviceTimeProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("SetDeviceTimeProcessingHandler");

	public SetDeviceTimeProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			ZonedDateTime now = ZonedDateTime.now(ZoneId.of(Constants.DEVICE_TIME_ZONE));
			int dayOfWeek = now.getDayOfWeek().getValue() % 7 + 1;  // java: Monday=1..Sunday=7 -> Sunday=1..Saturday=7
			String command = String.format("SetTime#%d#%d#%02d#%d#%d#%02d#%02d",
					now.getDayOfMonth(), now.getMonthValue(), now.getYear() % 100, dayOfWeek,
					now.getHour(), now.getMinute(), now.getSecond());
			logger.info("Setting device time: " + command);

			FirmwareFlasher flasher = new FirmwareFlasher();
			String result = flasher.sendCommandToTarget(command, true);
			if(result == null){
				logger.warn("No response from target device for " + command);
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "No target device responded - is it plugged in?");
			}

			JSONObject data = new JSONObject();
			data.put("command", command);
			data.put("response", result);
			data.put("ok", result.contains("Ok-SetTime"));
			Boolean ntpSynced = Utils.isNtpSynchronized();
			if(ntpSynced != null) data.put("ntpSynchronized", ntpSynced.booleanValue());
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
