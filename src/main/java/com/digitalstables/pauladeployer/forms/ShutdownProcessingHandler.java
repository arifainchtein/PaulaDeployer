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

// Clean shutdown from the field, for a low-battery Pi with no monitor/keyboard nearby - power
// just being cut mid-write risks corrupting the SD card's filesystem, this gives it a chance to
// unmount cleanly first. Requires passwordless sudo for this to work at all - there's no TTY for
// sudo to prompt on from a servlet, so without it the command just fails immediately. provision-pi.sh
// now guarantees that (added 2026-09-07, after this failed silently on a Paula that turned out not
// to have it - "pi has passwordless sudo" had only ever been confirmed on one Paula, not
// guaranteed by anything).
public class ShutdownProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("ShutdownProcessingHandler");

	public ShutdownProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			logger.warn("Shutdown requested via web UI - powering off this Pi now");
			ProcessBuilder pb = new ProcessBuilder("sudo", "shutdown", "-h", "now");
			pb.redirectErrorStream(true);
			Process p = pb.start();

			// A genuine shutdown takes several seconds (stopping services, syncing disks) - this
			// JVM will be killed partway through that, so we deliberately don't wait for it to
			// actually finish. But a FAILED sudo (e.g. no passwordless sudo configured, so it has
			// no TTY to prompt on) exits almost immediately instead - confirmed 2026-09-07, this
			// used to always report Success regardless, even when the command had already failed
			// and nothing was ever going to happen. This short wait distinguishes "still running,
			// genuinely shutting down" from "already failed" without risking a delay anywhere
			// near long enough for a real shutdown to kill this response first.
			boolean stillRunning = !p.waitFor(1500, TimeUnit.MILLISECONDS);
			JSONObject data = new JSONObject();
			if(stillRunning){
				data.put("message", "Shutdown initiated - this Pi will power off in a few seconds.");
				toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
			}else{
				StringBuilder output = new StringBuilder();
				try(BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))){
					String line;
					while((line = reader.readLine()) != null) output.append(line).append(" ");
				}
				String detail = "exit code " + p.exitValue() + (output.length() > 0 ? ": " + output.toString().trim() : "");
				logger.warn("Shutdown command exited immediately instead of running - " + detail);
				toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Shutdown command failed (" + detail + ") - is passwordless sudo set up for this user?");
			}
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
