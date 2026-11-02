package com.digitalstables.pauladeployer.forms;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.FieldCaptures;
import com.digitalstables.pauladeployer.utils.Utils;

// "Signal Map" (2026-11-03): what Paula hears, on a map, with the phone's position as Paula's.
// The packets come from the Lora Sniffer (LoraSnifferProcessingHandler - the map polls it, sending
// the phone's position). Devices are drawn where their data packets say they are (lat/lon in
// Paula.ino's SNIFF lines); this keeps the positions saved from the map instead - Annabelle, whose
// weather forecast carries none, and any device whose broadcast position is wrong or missing. A
// saved position wins over the broadcast one until it is cleared. Keyed by the sniffer's label
// (device name, or "Weather forecast" for Annabelle).
//   action=list                                -> {"positions":{label:{lat,lon,saved}}}
//   action=save, label=<label>, lat=, lon=     -> the same, after saving
//   action=clear, label=<label>                -> the same, after removing it
public class SignalMapProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("SignalMapProcessingHandler");
	private static final File FILE = new File(Constants.PAULAUPLOADER_HOME, "signalmap-positions.json");
	private static final Object LOCK = new Object();

	public SignalMapProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		try{
			String action = request.getParameter("action");
			String label = request.getParameter("label");
			synchronized(LOCK){
				JSONObject positions = load();
				if("save".equals(action) || "clear".equals(action)){
					if(label == null || label.trim().isEmpty() || label.length() > 64){
						return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "No device given.");
					}
					if("save".equals(action)){
						double lat, lon;
						try{
							lat = Double.parseDouble(request.getParameter("lat"));
							lon = Double.parseDouble(request.getParameter("lon"));
						}catch(NumberFormatException | NullPointerException e){
							return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "No position given.");
						}
						if(!(Math.abs(lat) <= 90 && Math.abs(lon) <= 180)){
							return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "That position is not on Earth.");
						}
						JSONObject p = new JSONObject();
						p.put("lat", lat);
						p.put("lon", lon);
						p.put("saved", FieldCaptures.format("yyyy-MM-dd HH:mm"));
						positions.put(label, p);
						logger.info("Saved the position of " + label + ": " + lat + "," + lon);
					}else{
						positions.remove(label);
						logger.info("Cleared the saved position of " + label);
					}
					save(positions);
				}
				JSONObject data = new JSONObject();
				data.put("positions", positions);
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
			}
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
	}

	private static JSONObject load(){
		if(!FILE.isFile()) return new JSONObject();
		try{
			return new JSONObject(new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8));
		}catch(Exception e){
			logger.warn("Could not read " + FILE + " - starting with no saved positions: " + e.getMessage());
			return new JSONObject();
		}
	}

	// Written to a temporary file then moved over the old one, so a pulled plug never leaves half a file.
	private static void save(JSONObject positions) throws IOException{
		File tmp = new File(FILE.getPath() + ".tmp");
		Files.write(tmp.toPath(), positions.toString(2).getBytes(StandardCharsets.UTF_8));
		Files.move(tmp.toPath(), FILE.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
