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
//   action=read2us -> ReadClipsal2US -> {"distance1MM","ultrasonic1Ok","distance2MM","ultrasonic2Ok","raw"}
//                    ("Clipsal 2US", ClipsallJunctionBoxWaterTrough_2US: two DYP-A02s on 18 and 33)
//   action=readvm -> ReadVoltageMonitor -> {"inaFound","manufacturerId","deviceId","vbusV","vshuntMV",
//                    "inaDieTempC","ntcV","v50V","raw"} ("Clipsal Voltage Monitor", Clipsall_VoltageMonitor:
//                    INA228 at 0x4F on I2C, battery NTC on the pin-33 terminal as analog, AIN0). Current and NTC
//                    temperature are worked out on the page, from the shunt and NTC values typed there.
//   action=stop -> StopClipsal  (pins 18/33 back to the flow sensors; sent when leaving either screen)
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

			String paulaCommand = "read2us".equals(action) ? "ReadClipsal2US" : "readvm".equals(action) ? "ReadVoltageMonitor" : "ReadClipsal";
			String minimumVersion = "read2us".equals(action) ? "5" : "readvm".equals(action) ? "7" : "4";
			String reply = PaulaRadio.get().command(paulaCommand, TIMEOUT_MILLISECONDS);
			if(reply == null){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Paula did not answer - is it plugged in?");
			}
			if(!reply.contains("Ok-" + paulaCommand)){
				// e.g. "Failure-Command Not Found-ReadClipsal2US": Paula's firmware is too old (ReadClipsal
				// came in version 4, ReadClipsal2US in 5). An old v4 answers ReadClipsal2US as ReadClipsal.
				boolean old = reply.contains("Command Not Found") || reply.contains("Ok-ReadClipsal");
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", old ? "Paula's firmware doesn't know " + paulaCommand + " - flash Paula.ino version " + minimumVersion + " or later." : reply);
			}
			// Paula v6 answered ReadVoltageMonitor too, but read the NTC on AIN1 (pin 18); v7 reads AIN0
			// (pin 33, as the board's spec says) and says so with ntcAin=0.
			if("readvm".equals(action) && !reply.contains("ntcAin=0")){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Paula's firmware reads the NTC on the wrong channel - flash Paula.ino version 7 or later.");
			}
			JSONObject data = new JSONObject();
			data.put("raw", reply);
			for(String line : reply.split("\n")){
				// only the reading line - Paula also prints LoRa debug ("rssi= ... snr=...") in between
				if(!line.startsWith("distance") && !line.startsWith("inaFound")) continue;
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
			logger.info(paulaCommand + ": " + data.toString());
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
	}

}
