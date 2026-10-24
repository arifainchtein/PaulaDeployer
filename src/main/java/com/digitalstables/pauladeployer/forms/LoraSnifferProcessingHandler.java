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

// "Lora Sniffer" (2026-10-24): every LoRa packet Paula hears, in a terminal - what the original
// Paula showed on its OLED when taken outside. Paula.ino prints a SNIFF line per packet while
// LoraSniffer#1 is on; PaulaRadio keeps them (its port stays open, as for Nearby devices).
//   action=start / stop, session=true|false  (session: opening / leaving the screen - see PaulaRadio.setSniffing)
//   action=poll, after=<sequence>   -> {"sequence","sniffing","connected","capture","packets":[..]}
// Each session is saved as a field capture, captures/lorasniffer/<date_time>.txt (FieldCaptures).
public class LoraSnifferProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("LoraSnifferProcessingHandler");

	public LoraSnifferProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		try{
			String action = request.getParameter("action");
			if("start".equals(action) || "stop".equals(action)){
				// session=true: the screen was opened / left (a new capture file / its end), not Resume / Pause
				String error = PaulaRadio.get().setSniffing("start".equals(action), "true".equals(request.getParameter("session")));
				if(error != null) return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", error);
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", PaulaRadio.get().sniffedAfter(Long.MAX_VALUE).toString());
			}
			long after = 0;
			String afterParam = request.getParameter("after");
			if(afterParam != null && afterParam.matches("\\d+")) after = Long.parseLong(afterParam);
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", PaulaRadio.get().sniffedAfter(after).toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
	}

}
