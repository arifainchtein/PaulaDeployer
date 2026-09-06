package com.digitalstables.pauladeployer.forms;

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

// Clean shutdown from the field, for a low-battery Pi with no monitor/keyboard nearby - power
// just being cut mid-write risks corrupting the SD card's filesystem, this gives it a chance to
// unmount cleanly first. "pi" already has passwordless sudo for everything on this Pi (confirmed
// 2026-09-05, `sudo -l`), so no extra sudoers setup is needed. Deliberately does NOT waitFor() the
// spawned process - shutdown -h now takes several seconds to actually halt (stopping services,
// syncing disks), and this JVM will be killed partway through that; the HTTP response needs to
// make it back to the browser before that happens, not after.
public class ShutdownProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("ShutdownProcessingHandler");

	public ShutdownProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			logger.warn("Shutdown requested via web UI - powering off this Pi now");
			new ProcessBuilder("sudo", "shutdown", "-h", "now").start();
			JSONObject data = new JSONObject();
			data.put("message", "Shutdown initiated - this Pi will power off in a few seconds.");
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
