package com.digitalstables.pauladeployer.utils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;

public class Utils {

	public static String getStringException(Exception e){
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}

	// Runs a command and returns its trimmed output (stdout+stderr), or null if it failed to
	// start, timed out or exited non-zero.
	public static String runCommand(long timeoutSeconds, String... command){
		try{
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			if(!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)){
				p.destroyForcibly();
				return null;
			}
			String output = new String(p.getInputStream().readAllBytes()).trim();
			return p.exitValue() == 0 ? output : null;
		}catch(Exception e){
			return null;
		}
	}

	// timedatectl's NTPSynchronized property, or null if it can't be read.
	public static Boolean isNtpSynchronized(){
		String value = runCommand(5, "timedatectl", "show", "-p", "NTPSynchronized", "--value");
		return value == null ? null : Boolean.valueOf("yes".equals(value));
	}

}
