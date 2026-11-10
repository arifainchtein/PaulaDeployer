package com.digitalstables.pauladeployer.forms;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import com.digitalstables.pauladeployer.flash.FirmwareFlasher;
import com.digitalstables.pauladeployer.flash.PaulaRadio;
import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;
import com.fazecast.jSerialComm.SerialPort;

// Same GetProductDefinition serial command and response parsing as the factory webapp's
// InspectProductProcessingHandler, sent over Paula's own connection to whatever target device is
// plugged in (FirmwareFlasher.findTargetPort, excluding Wally's own CP2104 port - same
// disambiguation the flash step itself uses) instead of the NUC's direct USB port.
//
// 2026-11-03: also inspects Paula itself and lists the USB serial ports, so Inspect doubles as "is the
// USB working?". With only Paula plugged in it used to pick Paula's own port as the "target" (no probe
// with a single port), fail to open it (PaulaRadio holds it for Nearby Devices) - or reset Paula by
// opening it - and say "No target device responded". Now:
//   1. Paula is asked through PaulaRadio's held connection (no reset): GetProductDefinition + GetFirmware
//   2. any OTHER ttyUSB/ttyACM port is the target device: GetProductDefinition as before
//   3. "ports": every serial port with its USB id, role (paula / device) and whether it answered
// The target's fields stay at the top level of the reply, as before; Paula's are under "paula".
public class InspectProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("InspectProcessingHandler");
	private static final int PAULA_TIMEOUT_MILLISECONDS = 4000;

	public InspectProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		try{
			JSONObject data = new JSONObject();

			// 1. Paula, through its held port (opening it here would reset it)
			JSONObject paula = new JSONObject();
			String paulaReply = PaulaRadio.get().command("GetProductDefinition", PAULA_TIMEOUT_MILLISECONDS);
			String paulaPort = PaulaRadio.get().heldPortName();
			if(paulaReply == null){
				paula.put("error", "Paula's own board did not answer - is its USB cable plugged into the Pi?");
			}else{
				String line = lineStartingWith(paulaReply, "Ok-GetProductDefinition");
				if(line != null) paula = parseProductDefinition(line);
				else paula.put("error", "Paula's firmware is older than Version 2 (no GetProductDefinition) - flash the current Paula.ino");
				String firmware = lineStartingWith(PaulaRadio.get().command("GetFirmware", PAULA_TIMEOUT_MILLISECONDS), "Version ");
				if(firmware != null) paula.put("runningFirmware", firmware.trim());
			}
			if(paulaPort != null) paula.put("port", paulaPort);
			data.put("paula", paula);

			// 2. the target device - any port but Paula's
			int otherPorts = 0;
			for(SerialPort port : SerialPort.getCommPorts()){
				String name = port.getSystemPortName();
				if((name.startsWith("ttyUSB") || name.startsWith("ttyACM")) && !name.equals(paulaPort)) otherPorts++;
			}
			String targetPort = null;
			boolean targetAnswered = false;
			if(otherPorts == 0){
				data.put("targetError", "Nothing plugged in besides Paula.");
			}else{
				logger.info("Inspect requested - sending GetProductDefinition to the target device");
				FirmwareFlasher flasher = new FirmwareFlasher();
				SerialPort target = flasher.findTargetPort(true);
				if(target != null) targetPort = target.getSystemPortName();
				String result = flasher.sendCommandToTarget("GetProductDefinition", true);
				String line = lineStartingWith(result, "Ok-GetProductDefinition");
				if(line != null){
					targetAnswered = true;
					JSONObject parsed = parseProductDefinition(line);
					for(String key : parsed.keySet()) data.put(key, parsed.get(key));
				}else{
					logger.warn("Inspect: no target device responded (" + result + ")");
					data.put("targetError", result == null ? "The device on " + (targetPort == null ? "USB" : targetPort) + " did not answer - is it running, jumper on USB?"
							: "The device answered, but not to GetProductDefinition: " + result.trim());
				}
			}
			data.put("targetFound", targetAnswered);

			// 3. every serial port the Pi sees
			JSONArray ports = new JSONArray();
			for(SerialPort port : SerialPort.getCommPorts()){
				String name = port.getSystemPortName();
				if(!(name.startsWith("ttyUSB") || name.startsWith("ttyACM"))) continue;
				JSONObject p = new JSONObject();
				p.put("name", name);
				p.put("description", port.getPortDescription());
				p.put("usbId", String.format("%04X:%04X", port.getVendorID(), port.getProductID()));
				boolean isPaula = name.equals(paulaPort);
				p.put("role", isPaula ? "paula" : "device");
				p.put("answered", isPaula ? paulaReply != null : (name.equals(targetPort) && targetAnswered));
				ports.put(p);
			}
			// Paula2 (paula.port=/dev/serial0): Paula is on the header UART, not a ttyUSB/ttyACM - list it too
			if(paulaPort != null && !paulaPort.startsWith("ttyUSB") && !paulaPort.startsWith("ttyACM")){
				JSONObject p = new JSONObject();
				p.put("name", paulaPort);
				p.put("description", "Pi header UART (paula.port=" + com.digitalstables.pauladeployer.flash.PaulaPortConfig.configured() + ")");
				p.put("usbId", "-");
				p.put("role", "paula");
				p.put("answered", paulaReply != null);
				ports.put(p);
			}
			data.put("ports", ports);

			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
	}

	// The first line of a reply that starts with prefix, or null.
	private static String lineStartingWith(String reply, String prefix){
		if(reply == null) return null;
		for(String line : reply.split("\\r?\\n")){
			if(line.trim().startsWith(prefix)) return line.trim();
		}
		return null;
	}

	// Ok-GetProductDefinition#name#powerSource#battery#pcbs#firmware#commissiondate#ssid#wifiPassword#softApSsid#softApPassword#hostName#stationMode#currenttime#deviceName#deviceShortName#serialNumber#labelBuild#runningBuild
	private static JSONObject parseProductDefinition(String result){
		String[] parts = result.split("#");
		JSONObject data = new JSONObject();
		data.put("name", parts.length>1?parts[1]:"");
		data.put("powerSource", parts.length>2?parts[2]:"");
		data.put("battery", parts.length>3?parts[3]:"");
		data.put("pcbs", parts.length>4?parts[4]:"");
		data.put("firmware", parts.length>5?parts[5]:"");
		data.put("commissiondate", parts.length>6?parts[6]:"");
		data.put("ssid", parts.length>7?parts[7]:"");
		data.put("wifiPassword", parts.length>8?parts[8]:"");
		data.put("softApSsid", parts.length>9?parts[9]:"");
		data.put("softApPassword", parts.length>10?parts[10]:"");
		data.put("hostName", parts.length>11?parts[11]:"");
		data.put("stationMode", parts.length>12?parts[12]:"");
		data.put("currenttime", parts.length>13?parts[13]:"");
		data.put("deviceName", parts.length>14?parts[14]:"");
		data.put("deviceShortName", parts.length>15?parts[15]:"");
		data.put("serialNumber", parts.length>16?parts[16].trim():"");
		// Added 2026-10-07 (Daffodil): #labelBuild#runningBuild - the build stamp (YYMMDDhh) running
		// when the firmware label was saved, and the one running now. Different = the label is stale
		// (flashed without SetProductDefinition). Missing on older firmware.
		data.put("labelBuild", parts.length>17?parts[17].trim():"");
		data.put("runningBuild", parts.length>18?parts[18].trim():"");
		return data;
	}

}
