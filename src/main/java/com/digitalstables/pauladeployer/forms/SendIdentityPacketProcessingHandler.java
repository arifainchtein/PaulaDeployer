package com.digitalstables.pauladeployer.forms;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.flash.FirmwareFlasher;
import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// "Send Identity Packet" toolbar button (2026-10-08): makes the plugged-in device send its
// DeviceIdentityRecord over LoRa again, so Annabelle/the Teleonome pick up its product definition
// and running build without waiting for the daily resend.
//
// Daffodil v54+ has a SendDeviceIdentity command that transmits it immediately - tried first.
// Older firmware has no dedicated command - but SetProductDefinition marks an identity
// packet as due (Esp32SecretManager::saveProductDefinition sets NVS identPend). So read the
// product definition back and resend it unchanged, the same way StartDeployProcessingHandler's
// updateFirmwareLabelOnDevice does minus the label change. The device then sends the identity
// right after its next VitalSigns (every 10 minutes while awake, every pulse when it sleeps).
// Side effect: labelBuild is re-stamped with the running build - the label is confirmed as
// describing the code that is running now, which is what pressing this on the bench means anyway.
public class SendIdentityPacketProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("SendIdentityPacketProcessingHandler");

	public SendIdentityPacketProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			FirmwareFlasher flasher = new FirmwareFlasher();
			// Daffodil v54+: SendDeviceIdentity transmits immediately. Older firmware answers
			// "Failure-Command Not Found-..." - fall back to queueing it below.
			String sendResult = flasher.sendCommandToTarget("SendDeviceIdentity", true);
			if(sendResult == null){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "No target device responded - is it plugged in?");
			}
			if(!sendResult.contains("Command Not Found")){
				JSONObject data = new JSONObject();
				data.put("immediate", true);
				data.put("ok", sendResult.contains("Ok-SendDeviceIdentity"));
				data.put("response", sendResult);
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
			}

			String getResult = flasher.sendCommandToTarget("GetProductDefinition", true);
			if(getResult == null){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "No target device responded - is it plugged in?");
			}
			if(!getResult.contains("Ok-GetProductDefinition")){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Could not read the product definition: " + getResult);
			}
			// Ok-GetProductDefinition#name#powerSource#battery#pcbs#firmware#...
			String[] parts = getResult.trim().split("#", -1);
			String name = parts.length > 1 ? parts[1] : "";
			String powerSource = parts.length > 2 ? parts[2] : "";
			String battery = parts.length > 3 ? parts[3] : "";
			String pcbs = parts.length > 4 ? parts[4] : "";
			String firmware = parts.length > 5 ? parts[5] : "";

			String setCommand = "SetProductDefinition#" + name + "#" + powerSource + "#" + battery + "#" + pcbs + "#" + firmware;
			logger.info("Re-sending product definition to trigger an identity packet: " + setCommand);
			String setResult = flasher.sendCommandToTarget(setCommand, true);

			JSONObject data = new JSONObject();
			data.put("immediate", false);
			data.put("ok", setResult != null && setResult.contains("Ok-SetProductDefinition"));
			data.put("name", name);
			data.put("firmware", firmware);
			data.put("response", setResult == null ? "" : setResult);
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
