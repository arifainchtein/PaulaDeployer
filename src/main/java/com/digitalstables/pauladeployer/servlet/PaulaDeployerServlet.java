package com.digitalstables.pauladeployer.servlet;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.exception.ServletProcessingException;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// Same formName-driven dispatch as the factory webapp's FactoryServlet, minus the multipart
// handling - nothing in this app ever uploads a file from the browser. One class per formName in
// the forms package, named "<formName>ProcessingHandler".
public class PaulaDeployerServlet extends HttpServlet{

	private static final long serialVersionUID = 1L;
	private static final Logger logger = LogManager.getLogger("PaulaDeployerServlet");

	public void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException{
		process(req, res);
	}

	public void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException{
		process(req, res);
	}

	private void process(HttpServletRequest req, HttpServletResponse res){
		String formName = req.getParameter("formName");
		String className = "com.digitalstables.pauladeployer.forms." + formName + "ProcessingHandler";
		logger.debug("Dispatching formName=" + formName);
		JSONObject toReturn;
		try{
			ProcessingFormHandler handler = ProcessingFormHandlerFactory.createProcessingFormHandler(className, req, res, getServletContext());
			toReturn = handler.process();
		}catch(ServletProcessingException e){
			logger.warn("Could not dispatch formName=" + formName + ": " + e.getMessage());
			toReturn = new JSONObject();
			toReturn.put(Constants.PROCESSING_FORM_RESULT_TIME, new java.sql.Timestamp(System.currentTimeMillis()));
			toReturn.put(Constants.PROCESSING_FORM_RESULT_STATUS, Constants.PROCESSING_FORM_RESULT_STATUS_ERROR);
			toReturn.put(Constants.PROCESSING_FORM_RESULT_MESSAGE, e.getMessage());
			toReturn.put(Constants.PROCESSING_FORM_RESULT_DATA, e.getInfo().toString());
		}

		try{
			PrintWriter out = res.getWriter();
			out.print(toReturn.toString());
			out.flush();
		}catch(IOException e){
			logger.warn(Utils.getStringException(e));
		}
	}

}
