package com.digitalstables.pauladeployer.exception;

import org.json.JSONObject;

// Same shape as the factory webapp's exception - carries structured info about what went wrong
// creating a form handler, rather than a bare message.
public class ServletProcessingException extends Exception {

	private static final long serialVersionUID = 1L;
	private final JSONObject info;

	public ServletProcessingException(JSONObject info){
		super(info.optString("message", info.toString()));
		this.info = info;
	}

	public JSONObject getInfo(){
		return info;
	}

}
