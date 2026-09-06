package com.digitalstables.pauladeployer.forms;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// Feeds the page header - every wlan* interface's current IPv4 address (there are normally two:
// the hotspot the phone is actually talking over, and the factory-network client - see the field
// WiFi design in provision-pi.sh) plus this app's version string.
public class GetHeaderInfoProcessingHandler extends ProcessingFormHandler{

	private static final Logger logger = LogManager.getLogger("GetHeaderInfoProcessingHandler");

	public GetHeaderInfoProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException{
		super(req, res, servletContext);
	}

	public JSONObject process(){
		JSONObject toReturn;
		try{
			JSONArray interfaces = new JSONArray();
			Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
			while(nics.hasMoreElements()){
				NetworkInterface nic = nics.nextElement();
				String name = nic.getName();
				if(!name.toLowerCase(Locale.ROOT).contains("wlan")) continue;
				Enumeration<InetAddress> addresses = nic.getInetAddresses();
				while(addresses.hasMoreElements()){
					InetAddress addr = addresses.nextElement();
					if(addr.getHostAddress().contains(":")) continue; // skip IPv6
					JSONObject entry = new JSONObject();
					entry.put("interface", name);
					entry.put("ip", addr.getHostAddress());
					interfaces.put(entry);
				}
			}
			logger.debug("Found " + interfaces.length() + " wlan interface(s)");

			JSONObject data = new JSONObject();
			data.put("interfaces", interfaces);
			data.put("version", Constants.VERSION);
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
		}catch(Exception e){
			logger.warn(Utils.getStringException(e));
			toReturn = generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(), Utils.getStringException(e));
		}
		return toReturn;
	}

}
