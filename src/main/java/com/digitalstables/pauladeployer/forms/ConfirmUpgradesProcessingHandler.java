package com.digitalstables.pauladeployer.forms;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.persistence.PersistenceManager;
import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// The "Confirm" footer button - only shown once wlan1 (the factory-network client interface) has
// a real IP, i.e. this Paula can actually reach the office NUC. Equivalent to an operator clicking
// "Confirm Upgrade Successful" on the factory webapp once per device, but done in bulk from here:
// every deployAttempt that succeeded and hasn't been reported yet (PersistenceManager.
// getUnreportedAttempts) gets the same ConfirmConfigurationUpgrade call the factory webapp's own
// button makes (see afterlogin.js's .confirm-configuration-upgrade handler / factory webapp's
// ConfirmConfigurationUpgradeProcessingHandler), then gets marked reported so it isn't sent twice.
// Plain HttpURLConnection, same pattern as PaulaUploader's own FactorySyncClient.
public class ConfirmUpgradesProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("ConfirmUpgradesProcessingHandler");

	public ConfirmUpgradesProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			PersistenceManager aDBManager = (PersistenceManager) servletContext.getAttribute("DBManager");
			JSONArray pending = aDBManager.getUnreportedAttempts();
			logger.info("ConfirmUpgrades - " + pending.length() + " unreported successful attempt(s) to confirm");

			int confirmedCount = 0;
			JSONArray errors = new JSONArray();
			for(int i = 0; i < pending.length(); i++){
				JSONObject attempt = pending.getJSONObject(i);
				int attemptId = attempt.getInt("id");
				int productId = attempt.getInt("productid");
				int productDefinitionId = attempt.getInt("productdefinitionid");
				String productName = attempt.optString("productname", "");
				try{
					confirmConfigurationUpgrade(productId, productDefinitionId);
					aDBManager.markReported(attemptId);
					confirmedCount++;
					logger.info("Confirmed upgrade for attemptId=" + attemptId + " product=" + productName);
				}catch(Exception e){
					logger.warn("Could not confirm attemptId=" + attemptId + " product=" + productName + ": " + e.getMessage());
					errors.put(productName + ": " + e.getMessage());
				}
			}

			JSONObject data = new JSONObject();
			data.put("confirmedCount", confirmedCount);
			data.put("errors", errors);
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

	private void confirmConfigurationUpgrade(int productId, int productDefinitionId) throws IOException{
		URL url = new URL(Constants.FACTORY_BASE_URL + "/FactoryServlet");
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setDoInput(true);
		connection.setDoOutput(true);
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(10000);
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

		String payload = "formName=ConfirmConfigurationUpgrade&id=" + productId + "&productDefinitionid=" + productDefinitionId;
		try(OutputStream os = connection.getOutputStream()){
			os.write(payload.getBytes("UTF-8"));
		}

		StringBuilder responseText = new StringBuilder();
		try(BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()))){
			String line;
			while((line = br.readLine()) != null) responseText.append(line);
		}finally{
			connection.disconnect();
		}

		JSONObject envelope = new JSONObject(responseText.toString());
		if(!"Success".equals(envelope.optString("Status"))){
			throw new IOException(envelope.optString("Data", "ConfirmConfigurationUpgrade failed"));
		}
	}

}
