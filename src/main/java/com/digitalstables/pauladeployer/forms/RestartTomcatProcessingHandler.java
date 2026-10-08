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

// Restart Tomcat from the phone (2026-10-08) - needed after every WAR redeploy, because jSerialComm's
// native library can only be loaded once per JVM, so a hot-redeployed WAR loses the serial port
// (even Inspect stops connecting) until the process really restarts. Until now that meant ssh.
//
// This runs INSIDE the Tomcat being restarted, so a plain "systemctl restart" would kill this request
// (and the child process, which lives in Tomcat's cgroup) partway through. systemd-run schedules the
// restart as a transient timer owned by systemd itself, 2s from now - outside Tomcat's cgroup, so it
// survives the stop, and this response has time to reach the phone first. Needs passwordless sudo,
// like Shutdown (provision-pi.sh sets it up).
public class RestartTomcatProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("RestartTomcatProcessingHandler");
	private static final String SERVICE_NAME = "pauladeployer-tomcat";

	public RestartTomcatProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			logger.warn("Tomcat restart requested via web UI - restarting " + SERVICE_NAME + " in 2s");
			ProcessBuilder pb = new ProcessBuilder("sudo", "systemd-run", "--on-active=2",
					"--unit=pauladeployer-restart-" + System.currentTimeMillis(),
					"/bin/systemctl", "restart", SERVICE_NAME);
			pb.redirectErrorStream(true);
			Process p = pb.start();

			// systemd-run only registers the timer and returns at once, so its exit code is the real answer
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
				logger.warn("Tomcat restart command failed - " + detail);
				toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Restart command failed (" + detail + ") - is passwordless sudo set up for this user?");
			}else{
				JSONObject data = new JSONObject();
				data.put("message", "Tomcat restart scheduled - PaulaDeployer will be back in about 30 seconds.");
				toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
			}
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
