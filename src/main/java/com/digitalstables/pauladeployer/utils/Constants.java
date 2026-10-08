package com.digitalstables.pauladeployer.utils;

// Same {Status,Message,Time,Data} response envelope the factory webapp's FactoryConstants uses -
// afterlogin.js-style frontends (and this app's own js/app.js) all expect this exact shape.
public class Constants {

	public static final String PROCESSING_FORM_RESULT_STATUS = "Status";
	public static final String PROCESSING_FORM_RESULT_STATUS_ERROR = "Error";
	public static final String PROCESSING_FORM_RESULT_STATUS_SUCCESS = "Success";
	public static final String PROCESSING_FORM_RESULT_MESSAGE = "Message";
	public static final String PROCESSING_FORM_RESULT_TIME = "Time";
	public static final String PROCESSING_FORM_RESULT_DATA = "Data";

	// Shown in the page header - bump when deploying a meaningfully different build so it's
	// obvious at a glance which version is running on a given Paula.
	public static final String VERSION = "0.7.3 (2026-10-08)";

	// Confirmed gotcha 2026-09-05: this used to be System.getProperty("user.home") + "/paulauploader" -
	// worked fine while Tomcat ran as the "pi" user, but broke the moment Tomcat was started as
	// root (e.g. a StartWebserver.sh using sudo, to bind port 80 like the old Teleonome
	// convention) - user.home for root is /root, not /home/pi, so the deploy-package queue and
	// flash work directory silently pointed at a directory that doesn't exist and always looked
	// empty. Hardcoded instead, since this app only ever runs on this one Pi's "pi" user's files
	// regardless of which user account Tomcat's own process happens to run as.
	public static final String PAULAUPLOADER_HOME = "/home/pi/paulauploader";

	// Same NUC the field WiFi's "factory-network client" interface (wlan1) is meant to reach and
	// provision-pi.sh's own NUC_HOST already targets for the esptool/bootloader fetch - confirmed
	// reachable on plain port 80 (its own Tomcat, no :8080 needed - see FactorySyncClient's own
	// "http://factoryserver.local" default in PaulaUploader for the equivalent convention there).
	public static final String FACTORY_BASE_URL = "http://192.168.1.138";

	// The devices' RTCs hold local wall-clock time with daylight saving (same as Annabelle), so the
	// header clock and "Set Time" always use this zone - not the Pi's own OS timezone, which
	// provision-pi.sh never sets (Paula3 was on Europe/London, confirmed 2026-10-07).
	public static final String DEVICE_TIME_ZONE = "Australia/Melbourne";

	// Fallback for "Internet Time" when NTP doesn't sync in time - the Date header of a plain HTTP
	// request (1 s resolution, works where UDP 123 is blocked).
	public static final String HTTP_TIME_URL = "http://www.google.com";

}
