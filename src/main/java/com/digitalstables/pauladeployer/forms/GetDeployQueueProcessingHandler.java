package com.digitalstables.pauladeployer.forms;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.persistence.PersistenceManager;
import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// Lists the tile grid for the main screen - one tile per manifest sitting in
// ~/paulauploader/deployPackages/ (pushed there by the factory webapp's "Send Deploy Package",
// see the design doc §13.1). Each tile's status comes from the most recent deployAttempt row for
// that manifest file (none yet = pending/yellow, Running = in progress, Success = green,
// Failed = red).
public class GetDeployQueueProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("GetDeployQueueProcessingHandler");

	public GetDeployQueueProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			PersistenceManager aDBManager = (PersistenceManager) servletContext.getAttribute("DBManager");
			File deployDir = new File(Constants.PAULAUPLOADER_HOME, "deployPackages");
			logger.debug("Scanning " + deployDir.getAbsolutePath() + " for manifests");

			JSONArray queue = new JSONArray();
			File[] manifestFiles = deployDir.isDirectory()
					? deployDir.listFiles((dir, name) -> name.endsWith(".manifest.json"))
					: new File[0];
			if(manifestFiles != null){
				Arrays.sort(manifestFiles, Comparator.comparing(File::getName));
				for(File manifestFile : manifestFiles){
					JSONObject manifest = new JSONObject(FileUtils.readFileToString(manifestFile, "UTF-8"));
					String manifestName = manifestFile.getName();
					String zipName = manifestName.replace(".manifest.json", ".zip");

					JSONObject tile = new JSONObject();
					tile.put("manifestFile", manifestName);
					tile.put("zipFile", zipName);
					tile.put("productId", manifest.optInt("productId"));
					tile.put("productName", manifest.optString("productName", "(unknown device)"));
					tile.put("serialNumber", manifest.optString("serialNumber", ""));
					tile.put("repoName", manifest.optString("repoName", ""));
					tile.put("version", manifest.optInt("version"));
					tile.put("productDefinitionLabel", manifest.optString("repoName", "") + " v" + manifest.optInt("version"));
					// "Allocate Customer via Paula" job (2026-10-29): no firmware, just the new customer's key
					if(StartDeployProcessingHandler.SET_CUSTOMER_TYPE.equals(manifest.optString("deploymentType", ""))){
						tile.put("productDefinitionLabel", "Set customer key -> " + manifest.optString("customerName", ""));
						tile.put("setCustomer", true);
					}
					// New Deployment = unit never configured yet (see StartDeployProcessingHandler.runNewDeployment)
					tile.put("newDeployment", StartDeployProcessingHandler.NEW_DEPLOYMENT_TYPE.equals(manifest.optString("deploymentType", "")));

					JSONObject latestAttempt = aDBManager.getLatestAttemptForManifest(manifestName);
					if(latestAttempt == null){
						tile.put("tileStatus", "Pending");
					}else{
						tile.put("tileStatus", latestAttempt.getString("status")); // Running | Success | Failed
						tile.put("lastAttemptId", latestAttempt.getInt("id"));
						tile.put("completedOn", latestAttempt.optLong("completedon", 0)); // epoch millis - frontend shows "Updated <date>" for Success
						tile.put("reported", latestAttempt.optBoolean("reported", false)); // frontend shows "Remove" once a Success tile is also reported
						// the serial number a New Deployment read off the device (the manifest's is blank)
						if(!latestAttempt.optString("serialnumber", "").isEmpty()) tile.put("serialNumber", latestAttempt.getString("serialnumber"));
					}
					queue.put(tile);
				}
			}

			logger.debug("Deploy queue has " + queue.length() + " tile(s)");
			JSONObject data = new JSONObject();
			data.put("queue", queue);
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
