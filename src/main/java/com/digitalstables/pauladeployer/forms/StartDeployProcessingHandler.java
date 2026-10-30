package com.digitalstables.pauladeployer.forms;

import java.io.File;
import java.io.FileOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.flash.FirmwareFlasher;
import com.digitalstables.pauladeployer.persistence.PersistenceManager;
import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// Kicks off a flash and returns immediately with an attemptId - GetDeployStatusProcessingHandler
// polls that id while the actual flash runs on a background thread (a phone request can't stay
// open for the whole minute-plus a flash takes). Only extracts the two firmware binaries
// (<repo>.ino.bin / <repo>.ino.partitions.bin, plus <repo>.www.bin when the device has a website) from the deploy package zip - not its bundled
// esptool.py/bootloader/boot_app0, since this Pi already has its own copies of those at the same
// path convention (fetched by provision-pi.sh, see FirmwareFlasher's ESPTOOL_PATH etc.), matching
// how PaulaUploader's own FirmwareFlasher already works.
public class StartDeployProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("StartDeployProcessingHandler");
	private static final int POST_FLASH_PING_ATTEMPTS = 4;
	private static final long POST_FLASH_PING_RETRY_MILLIS = 3000;

	public static final String NEW_DEPLOYMENT_TYPE = "NewDeployment";
	// 2026-10-29: the factory's "Allocate Customer via Paula" - no firmware, just the new customer's
	// key for a device that cannot come to the bench. See runSetCustomer.
	public static final String SET_CUSTOMER_TYPE = "SetCustomer";
	private static final Pattern TWELVE_HEX = Pattern.compile("\\b([0-9a-fA-F]{12})\\b");
	private static final String SERIAL_EXTRACTOR_BIN_NAME = "serialnumberextractor.ino.bin";
	private static final String SERIAL_EXTRACTOR_PARTITIONS_NAME = "serialnumberextractor.ino.partitions.bin";
	private static final Pattern SERIAL_NUMBER_PATTERN = Pattern.compile("([0-9a-fA-F]{12})Ok");
	// The factory bench uses 8s for each CSW calibration reset; longer here since the operator is
	// reading the prompt off a phone in the field.
	private static final long CSW_RESET_WAIT_MILLIS = 15000;
	// 2026-10-30: a Running attempt older than this is ended as Failed (GetDeployQueue), so a hung one
	// cannot block its package for ever. New Deployment has manual steps (RESET, CSW calibration).
	public static final long ATTEMPT_TIMEOUT_MILLIS = 30 * 60 * 1000L;
	// The background thread of each running attempt, for CancelDeploy
	public static final java.util.Map<Integer, Thread> RUNNING_ATTEMPTS = new java.util.concurrent.ConcurrentHashMap<>();

	public StartDeployProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			String manifestFileName = request.getParameter("manifestFile");
			logger.info("StartDeploy requested for manifestFile=" + manifestFileName);
			if(manifestFileName == null || manifestFileName.trim().isEmpty()){
				logger.warn("StartDeploy called with no manifestFile");
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "No manifestFile given.");
			}

			File deployDir = new File(Constants.PAULAUPLOADER_HOME, "deployPackages");
			File manifestFile = new File(deployDir, manifestFileName);
			if(!manifestFile.isFile()){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Manifest not found: " + manifestFileName);
			}
			JSONObject manifest = new JSONObject(org.apache.commons.io.FileUtils.readFileToString(manifestFile, "UTF-8"));

			String zipFileName = manifestFileName.replace(".manifest.json", ".zip");
			File zipFile = new File(deployDir, zipFileName);
			boolean setCustomer = SET_CUSTOMER_TYPE.equals(manifest.optString("deploymentType", ""));
			if(!setCustomer && !zipFile.isFile()){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Deploy package zip not found: " + zipFileName);
			}

			int productId = manifest.optInt("productId");
			String productName = manifest.optString("productName", "");
			String serialNumber = manifest.optString("serialNumber", "");
			String repoName = manifest.optString("repoName", "");
			int version = manifest.optInt("version");
			// 0 if this manifest predates the factory webapp's productDefinitionId field - see
			// PersistenceManager's startAttempt/getUnreportedAttempts comments for what that means
			// for the "Confirm" footer button later.
			int productDefinitionId = manifest.optInt("productDefinitionId");
			// Product definition name (e.g. "Daffodil V8") - written into the device's own product
			// definition after a good flash. "" for manifests from before the factory sent it, in
			// which case the device keeps its current name.
			String productDefinitionName = manifest.optString("productDefinitionName", "");

			PersistenceManager aDBManager = (PersistenceManager) servletContext.getAttribute("DBManager");
			int attemptId = aDBManager.startAttempt(manifestFileName, productId, productName, serialNumber, repoName, version, productDefinitionId);
			if(attemptId < 0){
				logger.warn("startAttempt() returned no id for manifestFile=" + manifestFileName);
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Could not create a deploy attempt record.");
			}
			logger.info("Created deployAttempt id=" + attemptId + " for product=" + productName + " (" + repoName + " v" + version + ") - starting background flash thread");

			// "NewDeployment" manifests (factory webapp's New Deployment button) are for a unit that
			// has never been through Configure Device - see runNewDeployment.
			JSONObject configuration = manifest.optJSONObject("configuration");
			boolean newDeployment = NEW_DEPLOYMENT_TYPE.equals(manifest.optString("deploymentType", "")) && configuration != null;
			Runnable job = setCustomer
					? () -> runSetCustomer(aDBManager, attemptId, manifestFile, manifest)
					: newDeployment
					? () -> runNewDeployment(aDBManager, attemptId, zipFile, repoName, version, configuration)
					: () -> runDeploy(aDBManager, attemptId, zipFile, repoName, version, productDefinitionName);
			Thread worker = new Thread(() -> {
				try {
					job.run();
				} finally {
					RUNNING_ATTEMPTS.remove(attemptId);
				}
			});
			worker.setDaemon(true);
			RUNNING_ATTEMPTS.put(attemptId, worker);
			worker.start();

			JSONObject data = new JSONObject();
			data.put("attemptId", attemptId);
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

	private void runDeploy(PersistenceManager aDBManager, int attemptId, File zipFile, String repoName, int version, String productDefinitionName){
		logger.info("Background flash thread started for attemptId=" + attemptId);
		boolean success = false;
		try{
			File workDir = new File(Constants.PAULAUPLOADER_HOME, "deployWork/" + attemptId);
			workDir.mkdirs();

			aDBManager.appendLog(attemptId, "Extracting firmware from " + zipFile.getName() + "...");
			String binFileName = repoName + ".ino.bin";
			String partitionsFileName = repoName + ".ino.partitions.bin";
			String wwwFileName = repoName + ".www.bin";
			extractEntries(zipFile, workDir, binFileName, partitionsFileName, wwwFileName);
			if(new File(workDir, wwwFileName).isFile()){
				aDBManager.appendLog(attemptId, "Package includes the device's website (www partition) - will flash it too.");
			}

			File binFile = new File(workDir, binFileName);
			File partitionsFile = new File(workDir, partitionsFileName);
			if(!binFile.isFile() || !partitionsFile.isFile()){
				logger.warn("attemptId=" + attemptId + " - firmware files missing after extraction from " + zipFile.getAbsolutePath());
				aDBManager.appendLog(attemptId, "Firmware files not found inside the deploy package - aborting.");
				aDBManager.completeAttempt(attemptId, false);
				return;
			}

			aDBManager.appendLog(attemptId, "Starting flash - watch for esptool's prompts below.");
			FirmwareFlasher flasher = new FirmwareFlasher();
			boolean flashed = flasher.flash(binFile.getAbsolutePath(), partitionsFile.getAbsolutePath(), workDir.getAbsolutePath(),
					true, line -> aDBManager.appendLog(attemptId, line));
			logger.info("attemptId=" + attemptId + " - flash() returned " + flashed);

			if(!flashed){
				aDBManager.appendLog(attemptId, "Flash failed.");
				aDBManager.completeAttempt(attemptId, false);
				return;
			}

			aDBManager.appendLog(attemptId, "Flash complete. Pinging the device...");
			// Confirmed gotcha 2026-10-07: the first Ping goes out while the freshly flashed board is
			// still booting, gets lost, and pingTarget() returns the boot output ("LittleFS Mount
			// Succces...") - every deploy since 2026-10-05 was flagged failed that way, and the
			// firmware label update below never ran. Retry once the boot output has gone by.
			String pingResult = null;
			for(int attempt = 1; attempt <= POST_FLASH_PING_ATTEMPTS; attempt++){
				pingResult = flasher.pingTarget(true);
				if(pingResult != null && pingResult.contains("Ok")) break;
				if(attempt < POST_FLASH_PING_ATTEMPTS){
					aDBManager.appendLog(attemptId, "No Ping reply yet (device still booting?) - retrying (" + (attempt + 1) + "/" + POST_FLASH_PING_ATTEMPTS + ")...");
					Thread.sleep(POST_FLASH_PING_RETRY_MILLIS);
				}
			}
			if(pingResult != null && pingResult.contains("Ok")){
				aDBManager.appendLog(attemptId, "Ping OK - device is alive and running the new firmware.");
				success = true;
				updateFirmwareLabelOnDevice(flasher, aDBManager, attemptId, repoName, version, productDefinitionName);
			}else{
				logger.warn("attemptId=" + attemptId + " - post-flash Ping did not return Ok (got: " + pingResult + ")");
				aDBManager.appendLog(attemptId, "Device did not respond to Ping after flashing - flagging as failed.");
				success = false;
			}
		}catch(Exception e){
			logger.warn("attemptId=" + attemptId + " - " + Utils.getStringException(e));
			aDBManager.appendLog(attemptId, "Error: " + e.getMessage());
			success = false;
		}finally{
			logger.info("attemptId=" + attemptId + " - finished, success=" + success);
			aDBManager.completeAttempt(attemptId, success);
		}
	}

	// The field version of the factory webapp's ConfigureDeviceProcessingHandler, for a manifest
	// from its "New Deployment" button: flash serialnumberextractor, read the serial number, inject
	// product definition/secret/time/device name/short name/timezone/wifi AP into NVS while the
	// extractor is running (it listens on Serial right after boot and writes the same NVS keys the
	// real firmware reads), then flash the product firmware and - for boards with a config switch -
	// run the CSW calibration. Same
	// command strings and order as ConfigureDeviceProcessingHandler - keep the two in step. The
	// serial number is stored on the attempt row; the Confirm footer button sends it back to the
	// factory (CompleteNewDeployment) once this Paula is on the factory network again.
	private void runNewDeployment(PersistenceManager aDBManager, int attemptId, File zipFile, String repoName, int version, JSONObject configuration){
		logger.info("Background new-deployment thread started for attemptId=" + attemptId);
		boolean success = false;
		try{
			File workDir = new File(Constants.PAULAUPLOADER_HOME, "deployWork/" + attemptId);
			workDir.mkdirs();

			aDBManager.appendLog(attemptId, "New deployment - extracting firmware from " + zipFile.getName() + "...");
			String binFileName = repoName + ".ino.bin";
			String partitionsFileName = repoName + ".ino.partitions.bin";
			String wwwFileName = repoName + ".www.bin";
			extractEntries(zipFile, workDir, binFileName, partitionsFileName, wwwFileName, SERIAL_EXTRACTOR_BIN_NAME, SERIAL_EXTRACTOR_PARTITIONS_NAME);

			File binFile = new File(workDir, binFileName);
			File partitionsFile = new File(workDir, partitionsFileName);
			File extractorBinFile = new File(workDir, SERIAL_EXTRACTOR_BIN_NAME);
			File extractorPartitionsFile = new File(workDir, SERIAL_EXTRACTOR_PARTITIONS_NAME);
			if(!binFile.isFile() || !partitionsFile.isFile() || !extractorBinFile.isFile() || !extractorPartitionsFile.isFile()){
				logger.warn("attemptId=" + attemptId + " - firmware/extractor files missing after extraction from " + zipFile.getAbsolutePath());
				aDBManager.appendLog(attemptId, "Firmware files not found inside the deploy package - aborting.");
				return;
			}

			FirmwareFlasher flasher = new FirmwareFlasher();

			//
			// Step 1 flash the serial number extractor
			//
			aDBManager.appendLog(attemptId, "Step 1/5: flashing the serial number extractor - watch for esptool's prompts below.");
			if(!flasher.flash(extractorBinFile.getAbsolutePath(), extractorPartitionsFile.getAbsolutePath(), workDir.getAbsolutePath(),
					true, line -> aDBManager.appendLog(attemptId, line))){
				aDBManager.appendLog(attemptId, "Flashing the serial number extractor failed.");
				return;
			}
			// flash() leaves this marker behind - clear it so the product flash's own check is real
			new File(workDir, "firmwareUploadComplete").delete();
			Thread.sleep(5000);

			//
			// Step 2 read the serial number
			//
			aDBManager.appendLog(attemptId, "Step 2/5: reading the serial number...");
			String serialResult = flasher.sendCommandToTarget("GetSerialNumber", true);
			Matcher matcher = SERIAL_NUMBER_PATTERN.matcher(serialResult == null ? "" : serialResult);
			if(!matcher.find()){
				logger.warn("attemptId=" + attemptId + " - GetSerialNumber reply not recognised: " + serialResult);
				aDBManager.appendLog(attemptId, "Could not read the serial number (got: " + serialResult + ") - aborting.");
				return;
			}
			String serialNumber = matcher.group(1);
			aDBManager.updateAttemptSerialNumber(attemptId, serialNumber);
			aDBManager.appendLog(attemptId, "Serial number: " + serialNumber);

			//
			// Step 3 inject the configuration into NVS / the RTC chip
			//
			aDBManager.appendLog(attemptId, "Step 3/5: writing the device configuration...");
			sendConfigCommand(flasher, aDBManager, attemptId, "SetProductDefinition#" + configuration.optString("productDefinitionName") + "#" + configuration.optString("powerSource")
					+ "#" + configuration.optString("battery") + "#" + configuration.optString("pcbSummary") + "#" + configuration.optString("firmwareSummary"));
			sendConfigCommand(flasher, aDBManager, attemptId, "SetSecret#" + configuration.optString("secret") + "#6#30");
			sendConfigCommand(flasher, aDBManager, attemptId, SetDeviceTimeProcessingHandler.setTimeCommand());
			sendConfigCommand(flasher, aDBManager, attemptId, "SetDeviceName#" + configuration.optString("deviceName"));
			sendConfigCommand(flasher, aDBManager, attemptId, "SetDeviceShortName#" + configuration.optString("deviceShortName"));
			if(!configuration.optString("timezone").isEmpty()){
				sendConfigCommand(flasher, aDBManager, attemptId, "SetTimezone#" + configuration.optString("timezone"));
			}
			sendConfigCommand(flasher, aDBManager, attemptId, "ConfigWifiAP#" + configuration.optString("apSsid") + "#" + configuration.optString("apPassword") + "#" + configuration.optString("deviceName"));

			//
			// Step 4 flash the product firmware - boots straight into the config above
			//
			if(new File(workDir, wwwFileName).isFile()){
				aDBManager.appendLog(attemptId, "Package includes the device's website (www partition) - will flash it too.");
			}
			aDBManager.appendLog(attemptId, "Step 4/5: flashing " + repoName + " v" + version + " - watch for esptool's prompts below.");
			if(!flasher.flash(binFile.getAbsolutePath(), partitionsFile.getAbsolutePath(), workDir.getAbsolutePath(),
					true, line -> aDBManager.appendLog(attemptId, line))){
				aDBManager.appendLog(attemptId, "Flashing the product firmware failed.");
				return;
			}

			aDBManager.appendLog(attemptId, "Flash complete. Pinging the device...");
			String pingResult = null;
			for(int attempt = 1; attempt <= POST_FLASH_PING_ATTEMPTS; attempt++){
				pingResult = flasher.pingTarget(true);
				if(pingResult != null && pingResult.contains("Ok")) break;
				if(attempt < POST_FLASH_PING_ATTEMPTS){
					aDBManager.appendLog(attemptId, "No Ping reply yet (device still booting?) - retrying (" + (attempt + 1) + "/" + POST_FLASH_PING_ATTEMPTS + ")...");
					Thread.sleep(POST_FLASH_PING_RETRY_MILLIS);
				}
			}
			if(pingResult == null || !pingResult.contains("Ok")){
				logger.warn("attemptId=" + attemptId + " - post-flash Ping did not return Ok (got: " + pingResult + ")");
				aDBManager.appendLog(attemptId, "Device did not respond to Ping after flashing - flagging as failed.");
				return;
			}
			aDBManager.appendLog(attemptId, "Ping OK - device is alive and running the new firmware.");
			success = true;

			//
			// Step 5 CSW calibration - only for boards with a config switch (Daffodil); the factory
			// sets configuration.cswCalibration from the New Deployment popup's checkbox. Same
			// two-reset sequence as the factory bench. Not fatal: the device is configured and
			// flashed either way, and it can be redone with Calibrate CSW from the toolbar.
			//
			if(configuration.optBoolean("cswCalibration", false)){
				aDBManager.appendLog(attemptId, "Step 5/5: CSW calibration.");
				aDBManager.appendLog(attemptId, "*** Set the config switch to 00000, then press RESET now ***");
				Thread.sleep(CSW_RESET_WAIT_MILLIS);
				String calibrateResult = flasher.sendCommandToTarget("CalibrateCSWReference", true);
				aDBManager.appendLog(attemptId, "CalibrateCSWReference: " + calibrateResult);
				aDBManager.appendLog(attemptId, "*** Calibration armed - press RESET again now (switches still at 00000) ***");
				Thread.sleep(CSW_RESET_WAIT_MILLIS);
				String cswVerifyResult = flasher.sendCommandToTarget("printCSWData", true);
				if(cswVerifyResult == null || cswVerifyResult.contains("cswReferenceRaw=0 ")){
					aDBManager.appendLog(attemptId, "WARNING: CSW calibration did not take - repeat it with Calibrate CSW.");
				}else{
					aDBManager.appendLog(attemptId, "CSW calibration captured.");
				}
				aDBManager.appendLog(attemptId, "*** Set the config switch to the unit's real function, then press RESET one last time ***");
			}else{
				aDBManager.appendLog(attemptId, "Step 5/5: no config switch on this board - skipping CSW calibration.");
			}
			aDBManager.appendLog(attemptId, "Device configured. Press Confirm when this Paula is back on the factory network to record serial number " + serialNumber + ".");
		}catch(Exception e){
			logger.warn("attemptId=" + attemptId + " - " + Utils.getStringException(e));
			aDBManager.appendLog(attemptId, "Error: " + e.getMessage());
			success = false;
		}finally{
			logger.info("attemptId=" + attemptId + " - new deployment finished, success=" + success);
			aDBManager.completeAttempt(attemptId, success);
		}
	}

	// "SetCustomer" (2026-10-29): give a device already in the field its new customer's key, the field
	// version of the factory's Allocate Customer. No flashing: the device keeps its firmware and
	// settings. Refuses unless the plugged-in device is the one in the manifest, and succeeds only on
	// Ok-SetSecret. Afterwards the key is removed from the manifest on disk; Confirm then tells the
	// factory (CompleteSetCustomer), which changes the customer and offers Send To DS again.
	// Neither the key nor the device's replies are logged (GetProductDefinition holds WiFi passwords).
	private void runSetCustomer(PersistenceManager aDBManager, int attemptId, File manifestFile, JSONObject manifest){
		boolean success = false;
		try{
			String expectedSerial = manifest.optString("serialNumber", "");
			String productName = manifest.optString("productName", "");
			String customerName = manifest.optString("customerName", "");
			String secret = manifest.optString("secret", "");
			if(secret.isEmpty()){
				aDBManager.appendLog(attemptId, "This job has no key in it (already used?) - nothing to do. Ask the factory to send it again.");
				return;
			}
			FirmwareFlasher flasher = new FirmwareFlasher();
			aDBManager.appendLog(attemptId, "Step 1/2: checking the plugged-in device is " + productName + " (" + expectedSerial + ")...");
			String serial = serialFromReply(flasher.sendCommandToTarget("GetSerialNumber", true));
			if(serial == null){
				// Not every firmware has GetSerialNumber; GetProductDefinition carries the serial in field 16,
				// as Inspect reads it (2026-10-30: since #labelBuild#runningBuild it is no longer the last
				// field, which is what made this fail on TopTank). Older replies, e.g. Langley, end with it.
				String productDefinition = flasher.sendCommandToTarget("GetProductDefinition", true);
				if(productDefinition != null && productDefinition.contains("Ok-GetProductDefinition")){
					String line = productDefinition.substring(productDefinition.indexOf("Ok-GetProductDefinition")).split("\\r?\\n")[0];
					String[] parts = line.split("#");
					String field16 = parts.length > 16 ? parts[16].trim() : "";
					String last = parts[parts.length - 1].trim();
					if(field16.matches("[0-9a-fA-F]{12}")) serial = field16;
					else if(last.matches("[0-9a-fA-F]{12}")) serial = last;
				}
			}
			if(serial == null){
				aDBManager.appendLog(attemptId, "Could not read the device's serial number - is it plugged in and running? Nothing changed.");
				return;
			}
			aDBManager.updateAttemptSerialNumber(attemptId, serial);
			if(!serial.equalsIgnoreCase(expectedSerial)){
				aDBManager.appendLog(attemptId, "The plugged-in device is " + serial + ", not " + productName + " (" + expectedSerial + ") - nothing changed.");
				return;
			}
			aDBManager.appendLog(attemptId, "Step 2/2: setting " + customerName + "'s key...");
			String reply = flasher.sendCommandToTarget("SetSecret#" + secret + "#6#30", true);
			if(reply == null || !reply.contains("Ok-SetSecret")){
				aDBManager.appendLog(attemptId, "The device did not confirm the key (no Ok-SetSecret) - try again. The factory is not told.");
				return;
			}
			success = true;
			// The key is in the device now; take it off Paula's disk
			manifest.remove("secret");
			manifest.put("keySetOn", System.currentTimeMillis());
			org.apache.commons.io.FileUtils.writeStringToFile(manifestFile, manifest.toString(4), "UTF-8");
			aDBManager.appendLog(attemptId, productName + " now has " + customerName + "'s key. Press Confirm when this Paula is back on the factory network, then Send To DS in the factory.");
		}catch(Exception e){
			logger.warn("attemptId=" + attemptId + " - set customer failed: " + e.getClass().getSimpleName());
			aDBManager.appendLog(attemptId, "Error: " + e.getClass().getSimpleName());
			success = false;
		}finally{
			logger.info("attemptId=" + attemptId + " - set customer finished, success=" + success);
			aDBManager.completeAttempt(attemptId, success);
		}
	}

	// A 12-hex serial number on its own in a GetSerialNumber reply, or null
	private static String serialFromReply(String reply){
		if(reply == null) return null;
		Matcher matcher = TWELVE_HEX.matcher(reply);
		return matcher.find() ? matcher.group(1) : null;
	}

	// Logs the command and the device's reply. Like ConfigureDeviceProcessingHandler, a bad reply
	// is logged but doesn't stop the run - the operator sees it in the terminal.
	private void sendConfigCommand(FirmwareFlasher flasher, PersistenceManager aDBManager, int attemptId, String command){
		String commandName = command.contains("#") ? command.substring(0, command.indexOf('#')) : command;
		String result = flasher.sendCommandToTarget(command, true);
		logger.info("attemptId=" + attemptId + " - " + commandName + " -> " + result);
		aDBManager.appendLog(attemptId, commandName + ": " + (result == null ? "no reply" : result.replace("\n", " ")));
	}

	// Daffodil.ino's "firmware" field (what Inspect shows) is just an operator-set NVS preference -
	// SetProductDefinition#<name>#<powerSource>#<battery>#<pcbs>#<firmware> / GetProductDefinition,
	// see Daffodil.ino's SetProductDefinition/GetProductDefinition command handlers. Flashing new
	// code never touches it, so without this it silently keeps showing whatever was typed in at
	// commissioning time forever, no matter how many real firmware updates land afterward - it's
	// not a "preferences aren't stored" firmware bug, just a field nothing was updating. Read the
	// other four fields back first and resend them unchanged, since SetProductDefinition takes all
	// five positionally and firmware is the only one this deploy actually knows a new value for.
	// Best-effort: failure here does not fail the deploy attempt, the flash itself already succeeded.
	private void updateFirmwareLabelOnDevice(FirmwareFlasher flasher, PersistenceManager aDBManager, int attemptId, String repoName, int version, String productDefinitionName){
		try{
			String getResult = flasher.sendCommandToTarget("GetProductDefinition", true);
			if(getResult == null || !getResult.contains("Ok-GetProductDefinition")){
				logger.warn("attemptId=" + attemptId + " - could not read back product definition to update firmware label (got: " + getResult + ")");
				aDBManager.appendLog(attemptId, "Could not update the device's firmware label (GetProductDefinition failed) - not fatal.");
				return;
			}
			String[] parts = getResult.split("#");
			String name = parts.length > 1 ? parts[1] : "";
			if(productDefinitionName != null && !productDefinitionName.isEmpty()) name = productDefinitionName;
			String powerSource = parts.length > 2 ? parts[2] : "";
			String battery = parts.length > 3 ? parts[3] : "";
			String pcbs = parts.length > 4 ? parts[4] : "";
			String newFirmwareLabel = repoName + " v" + version;

			String setCommand = "SetProductDefinition#" + name + "#" + powerSource + "#" + battery + "#" + pcbs + "#" + newFirmwareLabel;
			String setResult = flasher.sendCommandToTarget(setCommand, true);
			if(setResult != null && setResult.contains("Ok-SetProductDefinition")){
				logger.info("attemptId=" + attemptId + " - updated device firmware label to '" + newFirmwareLabel + "'");
				aDBManager.appendLog(attemptId, "Updated the device's product definition to \"" + name + "\", firmware \"" + newFirmwareLabel + "\".");
			}else{
				logger.warn("attemptId=" + attemptId + " - SetProductDefinition did not confirm (got: " + setResult + ")");
				aDBManager.appendLog(attemptId, "Could not confirm the device's firmware label was updated - not fatal.");
			}
		}catch(Exception e){
			logger.warn("attemptId=" + attemptId + " - error updating firmware label: " + Utils.getStringException(e));
		}
	}

	private void extractEntries(File zipFile, File destDir, String... entryNamesWanted) throws Exception{
		try(ZipInputStream zip = new ZipInputStream(java.nio.file.Files.newInputStream(zipFile.toPath()))){
			ZipEntry entry;
			while((entry = zip.getNextEntry()) != null){
				for(String wanted : entryNamesWanted){
					if(wanted.equals(entry.getName())){
						File outFile = new File(destDir, entry.getName());
						logger.debug("Extracting " + entry.getName() + " to " + outFile.getAbsolutePath());
						try(FileOutputStream out = new FileOutputStream(outFile)){
							IOUtils.copy(zip, out);
						}
					}
				}
			}
		}
	}

}
