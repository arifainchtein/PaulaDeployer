package com.digitalstables.pauladeployer.servlet;

import java.lang.reflect.Constructor;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.exception.ServletProcessingException;

public class ProcessingFormHandlerFactory{

	private static final Logger logger = LogManager.getLogger("ProcessingFormHandlerFactory");

	public static ProcessingFormHandler createProcessingFormHandler(String className, HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletProcessingException{
		try{
			Class<?> aClass = Class.forName(className);
			Constructor<?>[] constructors = aClass.getConstructors();
			for(Constructor<?> constructor : constructors){
				if(constructor.getParameterTypes().length == 3){
					return (ProcessingFormHandler) constructor.newInstance(req, res, servletContext);
				}
			}
		}catch(Exception e){
			logger.warn("Could not instantiate " + className + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
			JSONObject info = new JSONObject();
			info.put("Exception Thrown", e.getClass().getSimpleName());
			info.put("message", e.getMessage());
			info.put("In Class", className);
			throw new ServletProcessingException(info);
		}
		logger.warn("No matching handler found for " + className);
		JSONObject info = new JSONObject();
		info.put("message", "No matching handler found for formName");
		info.put("In Class", className);
		throw new ServletProcessingException(info);
	}

}
