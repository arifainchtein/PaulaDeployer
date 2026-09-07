package com.digitalstables.pauladeployer.servlet;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.digitalstables.pauladeployer.persistence.PersistenceManager;
import com.digitalstables.pauladeployer.utils.Constants;

public class WebAppContextListener implements ServletContextListener{

	private static final Logger logger = LogManager.getLogger("WebAppContextListener");

	// Self-registration for the factory webapp's "Send Deploy Package" Paula dropdown
	// (CheckInPaulaProcessingHandler) - lets a new/renamed/re-IP'd Paula show up there on its own
	// instead of an operator having to remember to INSERT/DELETE paulaUnit rows by hand every time
	// one is added or decommissioned. Fires once immediately on startup, then every 10 minutes for
	// as long as this webapp is running - only ever succeeds while wlan1 (the factory-network
	// client) actually has connectivity, same requirement the "Confirm" footer button already has.
	private static final int CHECKIN_INTERVAL_MINUTES = 10;
	private ScheduledExecutorService checkInScheduler;

	public void contextInitialized(ServletContextEvent sce){
		logger.info("PaulaDeployer starting up");
		ServletContext servletContext = sce.getServletContext();
		servletContext.setAttribute("DBManager", PersistenceManager.instance());
		logger.info("PersistenceManager attached to servlet context - startup complete");

		checkInScheduler = Executors.newSingleThreadScheduledExecutor();
		checkInScheduler.scheduleWithFixedDelay(this::checkInWithFactory, 0, CHECKIN_INTERVAL_MINUTES, TimeUnit.MINUTES);
	}

	public void contextDestroyed(ServletContextEvent sce){
		logger.info("PaulaDeployer shutting down");
		if(checkInScheduler != null) checkInScheduler.shutdownNow();
	}

	private void checkInWithFactory(){
		try{
			String hostname = InetAddress.getLocalHost().getHostName();
			String query = "formName=CheckInPaula&hostname=" + URLEncoder.encode(hostname, "UTF-8")
					+ "&name=" + URLEncoder.encode(hostname, "UTF-8");
			URL url = new URL(Constants.FACTORY_BASE_URL + "/FactoryServlet");
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setDoOutput(true);
			connection.setConnectTimeout(5000);
			connection.setReadTimeout(5000);
			connection.setRequestMethod("POST");
			connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			try(OutputStream os = connection.getOutputStream()){
				os.write(query.getBytes("UTF-8"));
			}
			int responseCode = connection.getResponseCode();
			connection.disconnect();
			logger.debug("Checked in with factory server as '" + hostname + "' - HTTP " + responseCode);
		}catch(Exception e){
			// Expected/harmless whenever wlan1 has no factory-network route yet (e.g. still on the
			// field hotspot only) - not logged louder than debug, this runs every 10 minutes for
			// the life of the webapp and a field deployment can go days without factory connectivity.
			logger.debug("Could not check in with factory server (probably no factory-network route right now): " + e.getMessage());
		}
	}

}
