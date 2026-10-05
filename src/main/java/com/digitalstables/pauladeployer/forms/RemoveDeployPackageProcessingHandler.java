package com.digitalstables.pauladeployer.forms;

import java.io.File;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.persistence.PersistenceManager;
import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// The small "x" on a Failed tile - removes that one deploy package (manifest + zip) from
// ~/paulauploader/deployPackages/ so a failed deployment can be cleared and re-sent from the
// factory webapp. The footer "Remove" button (RemoveConfirmedProcessingHandler) only ever
// clears Success+reported packages, so a failed one had no way off the screen. Only allowed
// while the latest attempt is Failed - never touches a Running/Pending/Success package. Like
// RemoveConfirmed, the deployAttempt DB rows are left alone (local-files cleanup only).
public class RemoveDeployPackageProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("RemoveDeployPackageProcessingHandler");

	public RemoveDeployPackageProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			String manifestName = request.getParameter("manifestFile");
			File deployDir = new File(Constants.PAULAUPLOADER_HOME, "deployPackages");
			// manifestFile comes from the browser - only accept a bare file name that lives
			// directly inside deployPackages/, never a path.
			if(manifestName == null || !manifestName.endsWith(".manifest.json")
					|| manifestName.contains("/") || manifestName.contains("\\") || manifestName.contains("..")){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "Invalid manifest file", "Invalid manifest file");
			}
			File manifestFile = new File(deployDir, manifestName);
			if(!manifestFile.isFile()){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "Deploy package not found", "Deploy package not found: " + manifestName);
			}

			PersistenceManager aDBManager = (PersistenceManager) servletContext.getAttribute("DBManager");
			JSONObject latestAttempt = aDBManager.getLatestAttemptForManifest(manifestName);
			if(latestAttempt == null || !"Failed".equals(latestAttempt.optString("status"))){
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "Only failed deployments can be removed", "Only failed deployments can be removed");
			}

			File zipFile = new File(deployDir, manifestName.replace(".manifest.json", ".zip"));
			FileUtils.deleteQuietly(manifestFile);
			FileUtils.deleteQuietly(zipFile);
			logger.info("Removed failed deploy package: " + manifestName + " / " + zipFile.getName());

			JSONObject data = new JSONObject();
			data.put("removed", manifestName);
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
