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

			// Confirmed gotcha (2026-09-07): "shutdown -h now" doesn't block until the machine
			// actually halts - it just signals systemd and returns almost immediately, success or
			// failure alike (the several-second delay is the SYSTEM shutting down afterward, not
			// this command). So "did the process exit quickly" can't distinguish success from
			// failure - both exit fast. The exit CODE is what actually tells them apart. Waiting up
			// to 3s is nowhere near long enough for a real shutdown to kill this response first
			// (that takes several more seconds after the command itself has already returned).
			boolean exited = p.waitFor(3, TimeUnit.SECONDS);
			JSONObject data = new JSONObject();
			if(exited && p.exitValue() != 0){
				StringBuilder output = new StringBuilder();
				try(BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))){
					String line;
					while((line = reader.readLine()) != null) output.append(line).append(" ");
				}
				String detail = "exit code " + p.exitValue() + (output.length() > 0 ? ": " + output.toString().trim() : "");
				logger.warn("Shutdown command failed - " + detail);
				toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Shutdown command failed (" + detail + ") - is passwordless sudo set up for this user?");
			}else{
				// exit code 0, or still running 3s later (unusual but not a failure signal either way)
				data.put("message", "Shutdown initiated - this Pi will power off in a few seconds.");
				toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
			}
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
