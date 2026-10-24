package com.digitalstables.pauladeployer.servlet;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.utils.Constants;
import com.digitalstables.pauladeployer.utils.FieldCaptures;

// The field captures (FieldCaptures, 2026-10-24):
//   GET /captures/               -> a plain page listing every capture, by kind, newest first
//   GET /captures/<kind>/<name>  -> that file as text (?download for a download)
public class CaptureServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
		String path = req.getPathInfo();
		if (path == null) {
			res.sendRedirect(req.getRequestURI() + "/");
			return;
		}
		if (path.equals("/")) {
			listPage(res);
			return;
		}
		String[] parts = path.substring(1).split("/");
		File f = parts.length == 2 ? FieldCaptures.find(parts[0], parts[1]) : null;
		if (f == null) {
			res.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		res.setContentType("text/plain; charset=utf-8");
		if (req.getParameter("download") != null) res.setHeader("Content-Disposition", "attachment; filename=\"" + f.getName() + "\"");
		res.setContentLengthLong(f.length());
		Files.copy(f.toPath(), res.getOutputStream());
	}

	private void listPage(HttpServletResponse res) throws IOException {
		res.setContentType("text/html; charset=utf-8");
		SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm");
		when.setTimeZone(TimeZone.getTimeZone(Constants.DEVICE_TIME_ZONE));
		PrintWriter out = res.getWriter();
		out.println("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
				+ "<title>Field Captures</title><style>body{font-family:sans-serif;margin:16px}h2{margin-top:24px;font-size:18px}"
				+ "li{margin:6px 0}span{color:#6c757d;font-size:13px}</style></head><body><h1>Field Captures</h1><p><a href=\"/\">Back to Paula Deployer</a></p>");
		JSONObject kinds = FieldCaptures.list();
		if (kinds.length() == 0) out.println("<p>No captures yet.</p>");
		for (String kind : new java.util.TreeSet<String>(kinds.keySet())) {
			out.println("<h2>" + kind + "</h2><ul>");
			JSONArray files = kinds.getJSONArray(kind);
			for (int i = 0; i < files.length(); i++) {
				JSONObject f = files.getJSONObject(i);
				String href = kind + "/" + f.getString("name");
				out.println("<li><a href=\"" + href + "\">" + f.getString("name") + "</a> <span>"
						+ when.format(new Date(f.getLong("modified"))) + ", " + (f.getLong("size") + 1023) / 1024 + " KB"
						+ " &middot; <a href=\"" + href + "?download\">download</a></span></li>");
			}
			out.println("</ul>");
		}
		out.println("</body></html>");
	}
}
