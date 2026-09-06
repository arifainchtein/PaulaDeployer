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

// Same idea as the factory webapp's SendCommandProcessingHandler - send whatever command string
// the operator types straight to the target device and show the raw response, over Paula's own
// connection (FirmwareFlasher.sendCommandToTarget, excluding Wally's own CP2104) instead of the
// NUC's direct USB port. Used directly by the "Send Command" button (arbitrary command, typed by
// the operator) and by "Calibrate CSW" on the frontend (same endpoint, command hardcoded to
// CalibrateCSWReference there - no need for a separate handler, the wire protocol is identical).
public class SendCommandProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("SendCommandProcessingHandler");

	public SendCommandProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			String command = request.getParameter("command");
			if(command == null || command.trim().isEmpty()){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "No command given.");
			}
			logger.info("Sending command to target device: " + command);

			FirmwareFlasher flasher = new FirmwareFlasher();
			String result = flasher.sendCommandToTarget(command, true);
			if(result == null){
				logger.warn("No response from target device for command: " + command);
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "No target device responded - is it plugged in?");
			}
			logger.debug("Response: " + result);

			JSONObject data = new JSONObject();
			data.put("response", result);
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
