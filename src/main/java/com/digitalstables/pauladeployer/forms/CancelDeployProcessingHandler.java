package com.digitalstables.pauladeployer.forms;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.persistence.PersistenceManager;
import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// 2026-10-30: the "Cancel" button on a Running tile. Ends the attempt as Failed (its package can then
// be retried or removed) and interrupts its background thread. An esptool write already under way
// finishes by itself, which is harmless: the ESP32 bootloader is in ROM, so a cut-off flash only means
// flashing again. A thread that finishes later cannot overwrite the Failed (completeAttempt only
// changes a Running attempt).
public class CancelDeployProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("CancelDeployProcessingHandler");

	public CancelDeployProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			int attemptId = Integer.parseInt(request.getParameter("attemptId"));
			PersistenceManager aDBManager = (PersistenceManager) servletContext.getAttribute("DBManager");
			boolean ended = aDBManager.failRunningAttempt(attemptId, "Cancelled on this Paula. Check the device (Inspect), then retry or remove.");
			Thread worker = StartDeployProcessingHandler.RUNNING_ATTEMPTS.get(attemptId);
			if(worker != null) worker.interrupt();
			logger.info("Cancel requested for attemptId=" + attemptId + " ended=" + ended + " threadFound=" + (worker != null));
			if(!ended){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "Not running", "This deployment is no longer running.");
			}
			JSONObject data = new JSONObject();
			data.put("attemptId", attemptId);
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(NumberFormatException e){
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "Invalid attemptId", "Invalid attemptId");
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}
}
