package com.digitalstables.pauladeployer.forms;

import java.util.List;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.chat.FactoryChat;
import com.digitalstables.pauladeployer.chat.SerialChatStreamer;
import com.digitalstables.pauladeployer.servlet.ProcessingFormHandler;
import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.Utils;

// "Stream 2 Chat" button (2026-10-12). action=
//   participants  - who is online in FactoryChat (for the dropdown), "Everyone" first
//   start         - target=<participant>, command=<sent to the device first, default debug#1>
//   stop          - stop streaming and release the serial port
//   status        - running, counters, last error and the most recent lines (polled by the page)
//   logs          - the saved stream files, newest first (2026-10-13; downloaded through StreamLogServlet)
//   deleteLog     - name=<file>
// See SerialChatStreamer.
public class StreamToChatProcessingHandler extends ProcessingFormHandler {

	private static final Logger logger = LogManager.getLogger("StreamToChatProcessingHandler");

	public StreamToChatProcessingHandler(HttpServletRequest req, HttpServletResponse res, ServletContext servletContext) throws ServletException {
		super(req, res, servletContext);
	}

	public JSONObject process() {
		try {
			String action = request.getParameter("action");
			SerialChatStreamer streamer = SerialChatStreamer.get();
			if ("participants".equals(action)) {
				List<String> names = FactoryChat.participants();
				JSONObject data = new JSONObject();
				data.put("participants", new JSONArray(names));
				data.put("machine", FactoryChat.localMachineName());
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", data.toString());
			}
			if ("start".equals(action)) {
				String command = request.getParameter("command");
				if (command == null) command = "debug#1";
				String error = streamer.start(request.getParameter("target"), command);
				if (!error.isEmpty()) {
					return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", error);
				}
				logger.info("Stream 2 Chat started -> " + request.getParameter("target"));
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", streamer.status().toString());
			}
			if ("logs".equals(action)) {
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", SerialChatStreamer.listLogs().toString());
			}
			if ("deleteLog".equals(action)) {
				java.io.File f = SerialChatStreamer.logFile(request.getParameter("name"));
				if (f == null || !f.delete()) {
					return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, "", "Could not delete " + request.getParameter("name"));
				}
				logger.info("Stream log deleted: " + f.getName());
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", SerialChatStreamer.listLogs().toString());
			}
			if ("stop".equals(action)) {
				streamer.stop();
				logger.info("Stream 2 Chat stopped");
				return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", streamer.status().toString());
			}
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_SUCCESS, "", streamer.status().toString());
		} catch (Exception e) {
			logger.warn(Utils.getStringException(e));
			return generateFormResponseObject(Constants.PROCESSING_FORM_RESULT_STATUS_ERROR, e.getMessage(),
					"Stream 2 Chat failed: " + e.getMessage());
		}
	}
}
