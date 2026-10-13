package com.digitalstables.pauladeployer.servlet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.digitalstables.pauladeployer.chat.SerialChatStreamer;

// Serves the Stream 2 Chat log files (2026-10-13): GET /streamlogs/<name>.txt downloads one, so a
// phone can save it and send it to a chat through the photo upload page. Only bare names inside
// SerialChatStreamer.LOG_DIR (SerialChatStreamer.logFile) - anything else is a 404.
public class StreamLogServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
		String path = req.getPathInfo();
		File f = SerialChatStreamer.logFile(path == null ? null : path.substring(1));
		if (f == null) {
			res.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		res.setContentType("text/plain; charset=utf-8");
		res.setHeader("Content-Disposition", "attachment; filename=\"" + f.getName() + "\"");
		res.setContentLengthLong(f.length());
		Files.copy(f.toPath(), res.getOutputStream());
	}
}
