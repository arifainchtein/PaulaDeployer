package com.digitalstables.pauladeployer.forms;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.persistence.PersistenceManager;
import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// Polled by the terminal view (~every second) while a flash is running - returns the full
// terminal log so far and the current status (Running/Success/Failed). Re-sending the whole log
// each time rather than tracking a line cursor - it stays a few KB at most for one flash, so the
// simplicity is worth it over cursor bookkeeping on both ends.
public class GetDeployStatusProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("GetDeployStatusProcessingHandler");

	public GetDeployStatusProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			int attemptId = Integer.parseInt(request.getParameter("attemptId"));
			// trace, not debug - this is polled roughly once a second while a flash runs, debug
			// would flood the log for the duration of every deploy
			logger.trace("Polled for attemptId=" + attemptId);
			PersistenceManager aDBManager = (PersistenceManager) servletContext.getAttribute("DBManager");
			JSONObject attempt = aDBManager.getAttempt(attemptId);
			if(attempt == null){
				logger.warn("No such attempt: " + attemptId);
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "No such attempt: " + attemptId);
			}
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", attempt.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
