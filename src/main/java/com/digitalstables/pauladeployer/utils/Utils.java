package com.digitalstables.pauladeployer.utils;

import java.io.PrintWriter;
import java.io.StringWriter;

public class Utils {

	public static String getStringException(Exception e){
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}

}
