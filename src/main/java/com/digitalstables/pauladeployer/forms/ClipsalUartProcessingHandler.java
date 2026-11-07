package com.digitalstables.pauladeployer.forms;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.flash.PaulaRadio;
import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// "Clipsal Uart" (2026-11-07): tests a ClipsallJunctionBoxWaterTrough board (HardwareDesign) - the
// junction box that joins a Daffodil's cable to a DYP-A02 UART ultrasonic (Grove plug, pin 18) and a
// waterproof DS18B20 (J1 screw terminal, pin 33). The board is wired to Paula's own terminals, as it
// would be to a Daffodil, and Paula.ino reads both sensors.
//   action=read -> ReadClipsal  -> {"distanceMM","ultrasonicOk","tempC","tempProbes","raw"}
//   action=stop -> StopClipsal  (pins 18/33 back to the flow sensors; sent when leaving the screen)
// Goes through PaulaRadio, not SendCommand's target port: it always talks to Paula even when a
// target device is plugged in too, and keeps the port open so Paula isn't reset by every read.
public class ClipsalUartProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("ClipsalUartProcessingHandler");
	// The first read opens the port: Paula resets and PaulaRadio waits 3 s for it to boot, then
	// Paula takes ~1 s to pick the command up and up to ~2 s to read both sensors.
	private static final int TIMEOUT_MILLISECONDS = 12000;

	public ClipsalUartProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		try{
			String action = request.getParameter("action");
			if("stop".equals(action)){
				String reply = PaulaRadio.get().command("StopClipsal", TIMEOUT_MILLISECONDS);
				if(reply == null || !reply.contains("Ok-StopClipsal")){
					return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", reply == null ? "Paula did not answer." : reply);
				}
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", "{}");
			}

			String reply = PaulaRadio.get().command("ReadClipsal", TIMEOUT_MILLISECONDS);
			if(reply == null){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Paula did not answer - is it plugged in?");
			}
			if(!reply.contains("Ok-ReadClipsal")){
				// e.g. "Failure-Command Not Found-ReadClipsal": Paula has firmware older than version 4
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", reply.contains("Command Not Found") ? "Paula's firmware doesn't know ReadClipsal - flash Paula.ino version 4 or later." : reply);
			}
			JSONObject data = new JSONObject();
			data.put("raw", reply);
			for(String line : reply.split("\n")){
				if(!line.startsWith("distanceMM=")) continue;
				for(String pair : line.trim().split(" ")){
					int eq = pair.indexOf('=');
					if(eq <= 0) continue;
					String key = pair.substring(0, eq), value = pair.substring(eq + 1);
					try{
						data.put(key, Double.parseDouble(value));
					}catch(NumberFormatException e){
						data.put(key, value);
					}
				}
			}
			logger.info("ReadClipsal: " + data.toString());
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
	}

}
