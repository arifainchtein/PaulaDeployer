package com.digitalstables.pauladeployer.flash;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.digitalstables.pauladeployer.utils.Constants;
import com.fazecast.jSerialComm.SerialPort;

// Where Paula's own ESP32 is (2026-11-11). /home/pi/paulauploader/paula.properties:
//
//   paula.port=auto            today's Paula: a USB cable to the Pi. Found by PaulaSerialLink.findWallyPort
//                              (CP210x USB id + "GetSwitchState" answered), then held open by PaulaRadio.
//   paula.port=/dev/serial0    Paula2: the ESP32 on the Pi's header UART. No probing - that port IS Paula,
//                              and every ttyUSB/ttyACM is a device on the bench.
//
// No file, or no key = auto. Read on every call, so editing the file needs no Tomcat restart.
// (Paula2's Pi also needs the UART enabled and its login console off: raspi-config > Interface >
// Serial Port - login shell No, hardware Yes - or enable_uart=1 and no console=serial0 in cmdline.txt.)
public final class PaulaPortConfig {

	private static final Logger logger = LogManager.getLogger("PaulaPortConfig");
	public static final String FILE = Constants.PAULAUPLOADER_HOME + "/paula.properties";
	public static final String KEY = "paula.port";
	public static final String AUTO = "auto";

	private PaulaPortConfig() {
	}

	// "auto" or a device path such as /dev/serial0.
	public static String configured() {
		File file = new File(FILE);
		if (!file.isFile()) return AUTO;
		Properties properties = new Properties();
		try (InputStream in = new FileInputStream(file)) {
			properties.load(in);
		} catch (IOException e) {
			logger.warn("Cannot read " + FILE + " - using auto: " + e.getMessage());
			return AUTO;
		}
		String value = properties.getProperty(KEY, AUTO).trim();
		return value.isEmpty() ? AUTO : value;
	}

	public static boolean isFixed() {
		return !AUTO.equalsIgnoreCase(configured());
	}

	// The configured port when it is fixed, else null. jSerialComm resolves the /dev/serial0 symlink
	// to the real device (ttyAMA0 / ttyS0), so compare ports by getSystemPortName() of this object.
	public static SerialPort fixedPort() {
		String path = configured();
		if (AUTO.equalsIgnoreCase(path)) return null;
		try {
			return SerialPort.getCommPort(path);
		} catch (Exception e) {
			logger.warn("paula.port=" + path + " is not a serial port: " + e.getMessage());
			return null;
		}
	}
}
