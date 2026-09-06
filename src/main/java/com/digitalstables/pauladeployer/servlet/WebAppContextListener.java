package com.digitalstables.pauladeployer.servlet;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.digitalstables.pauladeployer.persistence.PersistenceManager;

public class WebAppContextListener implements ServletContextListener{

	private static final Logger logger = LogManager.getLogger("WebAppContextListener");

	public void contextInitialized(ServletContextEvent sce){
		logger.info("PaulaDeployer starting up");
		ServletContext servletContext = sce.getServletContext();
		servletContext.setAttribute("DBManager", PersistenceManager.instance());
		logger.info("PersistenceManager attached to servlet context - startup complete");
	}

	public void contextDestroyed(ServletContextEvent sce){
		logger.info("PaulaDeployer shutting down");
	}

}
