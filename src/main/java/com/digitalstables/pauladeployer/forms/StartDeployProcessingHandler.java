package com.digitalstables.pauladeployer.forms;

import java.io.File;
import java.io.FileOutputStream;
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
			if(!zipFile.isFile()){
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

			PersistenceManager aDBManager = (PersistenceManager) servletContext.getAttribute("DBManager");
			int attemptId = aDBManager.startAttempt(manifestFileName, productId, productName, serialNumber, repoName, version, productDefinitionId);
			if(attemptId < 0){
				logger.warn("startAttempt() returned no id for manifestFile=" + manifestFileName);
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Could not create a deploy attempt record.");
			}
			logger.info("Created deployAttempt id=" + attemptId + " for product=" + productName + " (" + repoName + " v" + version + ") - starting background flash thread");

			Thread worker = new Thread(() -> runDeploy(aDBManager, attemptId, zipFile, repoName, version));
			worker.setDaemon(true);
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

	private void runDeploy(PersistenceManager aDBManager, int attemptId, File zipFile, String repoName, int version){
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
			String pingResult = flasher.pingTarget(true);
			if(pingResult != null && pingResult.contains("Ok")){
				aDBManager.appendLog(attemptId, "Ping OK - device is alive and running the new firmware.");
				success = true;
				updateFirmwareLabelOnDevice(flasher, aDBManager, attemptId, repoName, version);
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

	// Daffodil.ino's "firmware" field (what Inspect shows) is just an operator-set NVS preference -
	// SetProductDefinition#<name>#<powerSource>#<battery>#<pcbs>#<firmware> / GetProductDefinition,
	// see Daffodil.ino's SetProductDefinition/GetProductDefinition command handlers. Flashing new
	// code never touches it, so without this it silently keeps showing whatever was typed in at
	// commissioning time forever, no matter how many real firmware updates land afterward - it's
	// not a "preferences aren't stored" firmware bug, just a field nothing was updating. Read the
	// other four fields back first and resend them unchanged, since SetProductDefinition takes all
	// five positionally and firmware is the only one this deploy actually knows a new value for.
	// Best-effort: failure here does not fail the deploy attempt, the flash itself already succeeded.
	private void updateFirmwareLabelOnDevice(FirmwareFlasher flasher, PersistenceManager aDBManager, int attemptId, String repoName, int version){
		try{
			String getResult = flasher.sendCommandToTarget("GetProductDefinition", true);
			if(getResult == null || !getResult.contains("Ok-GetProductDefinition")){
				logger.warn("attemptId=" + attemptId + " - could not read back product definition to update firmware label (got: " + getResult + ")");
				aDBManager.appendLog(attemptId, "Could not update the device's firmware label (GetProductDefinition failed) - not fatal.");
				return;
			}
			String[] parts = getResult.split("#");
			String name = parts.length > 1 ? parts[1] : "";
			String powerSource = parts.length > 2 ? parts[2] : "";
			String battery = parts.length > 3 ? parts[3] : "";
			String pcbs = parts.length > 4 ? parts[4] : "";
			String newFirmwareLabel = repoName + " v" + version;

			String setCommand = "SetProductDefinition#" + name + "#" + powerSource + "#" + battery + "#" + pcbs + "#" + newFirmwareLabel;
			String setResult = flasher.sendCommandToTarget(setCommand, true);
			if(setResult != null && setResult.contains("Ok-SetProductDefinition")){
				logger.info("attemptId=" + attemptId + " - updated device firmware label to '" + newFirmwareLabel + "'");
				aDBManager.appendLog(attemptId, "Updated the device's firmware label to \"" + newFirmwareLabel + "\".");
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
