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

// The "Remove" footer button, to the left of "Confirm" - once a device's upgrade has been
// confirmed with the factory server (ConfirmUpgradesProcessingHandler set its deployAttempt's
// reported=true), there's no reason to keep its manifest/zip sitting in ~/paulauploader/
// deployPackages/ forever - this deletes both files for every manifest whose latest attempt is
// Success AND already reported, which also makes its tile disappear from the main screen queue
// (GetDeployQueueProcessingHandler only lists manifests that still exist on disk). The deployAttempt
// DB row itself is left alone - this is a local-files cleanup, not a history wipe. Independent of
// wlan1/network reachability, unlike Confirm - purely local file deletion.
public class RemoveConfirmedProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("RemoveConfirmedProcessingHandler");

	public RemoveConfirmedProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			PersistenceManager aDBManager = (PersistenceManager) servletContext.getAttribute("DBManager");
			File deployDir = new File(Constants.PAULAUPLOADER_HOME, "deployPackages");
			File[] manifestFiles = deployDir.isDirectory()
					? deployDir.listFiles((dir, name) -> name.endsWith(".manifest.json"))
					: new File[0];

			int removedCount = 0;
			if(manifestFiles != null){
				for(File manifestFile : manifestFiles){
					String manifestName = manifestFile.getName();
					JSONObject latestAttempt = aDBManager.getLatestAttemptForManifest(manifestName);
					if(latestAttempt == null) continue;
					boolean success = "Success".equals(latestAttempt.optString("status"));
					boolean reported = latestAttempt.optBoolean("reported", false);
					if(!success || !reported) continue;

					File zipFile = new File(deployDir, manifestName.replace(".manifest.json", ".zip"));
					FileUtils.deleteQuietly(manifestFile);
					FileUtils.deleteQuietly(zipFile);
					removedCount++;
					logger.info("Removed confirmed deploy package: " + manifestName + " / " + zipFile.getName());
				}
			}

			JSONObject data = new JSONObject();
			data.put("removedCount", removedCount);
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
