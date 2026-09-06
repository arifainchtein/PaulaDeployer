package com.digitalstables.pauladeployer.forms;

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

// Same GetProductDefinition serial command and response parsing as the factory webapp's
// InspectProductProcessingHandler, sent over Paula's own connection to whatever target device is
// plugged in (FirmwareFlasher.findTargetPort, excluding Wally's own CP2104 port - same
// disambiguation the flash step itself uses) instead of the NUC's direct USB port.
public class InspectProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("InspectProcessingHandler");

	public InspectProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			logger.info("Inspect requested - sending GetProductDefinition to the target device");
			FirmwareFlasher flasher = new FirmwareFlasher();
			String result = flasher.sendCommandToTarget("GetProductDefinition", true);
			if(result == null){
				logger.warn("Inspect: no target device responded");
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "No target device responded - is it plugged in?");
			}
			logger.debug("Inspect raw response: " + result);

			// Ok-GetProductDefinition#name#powerSource#battery#pcbs#firmware#commissiondate#ssid#wifiPassword#softApSsid#softApPassword#hostName#stationMode#currenttime#deviceName#deviceShortName#serialNumber
			String[] parts = result.split("#");

			JSONObject data = new JSONObject();
			data.put("name", parts.length>1?parts[1]:"");
			data.put("powerSource", parts.length>2?parts[2]:"");
			data.put("battery", parts.length>3?parts[3]:"");
			data.put("pcbs", parts.length>4?parts[4]:"");
			data.put("firmware", parts.length>5?parts[5]:"");
			data.put("commissiondate", parts.length>6?parts[6]:"");
			data.put("ssid", parts.length>7?parts[7]:"");
			data.put("wifiPassword", parts.length>8?parts[8]:"");
			data.put("softApSsid", parts.length>9?parts[9]:"");
			data.put("softApPassword", parts.length>10?parts[10]:"");
			data.put("hostName", parts.length>11?parts[11]:"");
			data.put("stationMode", parts.length>12?parts[12]:"");
			data.put("currenttime", parts.length>13?parts[13]:"");
			data.put("deviceName", parts.length>14?parts[14]:"");
			data.put("deviceShortName", parts.length>15?parts[15]:"");
			data.put("serialNumber", parts.length>16?parts[16]:"");

			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
