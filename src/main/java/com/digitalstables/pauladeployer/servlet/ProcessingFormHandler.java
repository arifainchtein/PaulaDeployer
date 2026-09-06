package com.digitalstables.pauladeployer.servlet;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.json.JSONObject;

import com.digitalstables.pauladeployer.utils.Constants;

// Same shape as the factory webapp's ProcessingFormHandler - one subclass per formName, dispatched
// by reflection from PaulaDeployerServlet (see ProcessingFormHandlerFactory).
public abstract class ProcessingFormHandler{

	protected HttpServletRequest request;
	protected HttpServletResponse response;
	protected ServletContext servletContext;
	protected HttpSession session;

	public ProcessingFormHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext){
		this.request = req;
		this.response = res;
		this.servletContext = servletContext;
		this.session = req.getSession();
	}

	public abstract JSONObject process();

	protected JSONObject generateFormResponseObject(String status, String message, String data){
		JSONObject toReturn = new JSONObject();
		toReturn.put(Constants.PROCESSING_FORM_RESULT_TIME, new java.sql.Timestamp(System.currentTimeMillis()));
		toReturn.put(Constants.PROCESSING_FORM_RESULT_STATUS, status);
		toReturn.put(Constants.PROCESSING_FORM_RESULT_MESSAGE, message);
		toReturn.put(Constants.PROCESSING_FORM_RESULT_DATA, data);
		return toReturn;
	}

}
