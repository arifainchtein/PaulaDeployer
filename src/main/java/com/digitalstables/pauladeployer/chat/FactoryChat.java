package com.digitalstables.pauladeployer.chat;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

// Minimal FactoryChat participant for Stream 2 Chat - same protocol as the Orchestrator's
// FactoryChatProtocol (FactorySystem/Orchestrator, chat package), copied rather than shared like
// the rest of this system: JSON {machine, text, timestamp} on factorychat/messages (Everyone) or
// factorychat/dm/<name> (direct), and the retained roster on factorychat/presence. Publishes
// under this Pi's short hostname, so messages show up as e.g. "paula: ...".
public class FactoryChat {

	private static final Logger logger = LogManager.getLogger("FactoryChat");

	public static final String BROKER_URL = "tcp://192.168.1.138:1883";   // FactoryHeart on factory2 (fixed, never derived)
	public static final String TOPIC_MESSAGES = "factorychat/messages";
	public static final String TOPIC_PRESENCE = "factorychat/presence";
	public static final String TOPIC_DM_PREFIX = "factorychat/dm/";
	public static final String EVERYONE = "Everyone";

	private MqttClient client;
	private final String machine = localMachineName();

	public String getMachine() { return machine; }

	public synchronized void connect() throws Exception {
		if (client != null && client.isConnected()) return;
		client = new MqttClient(BROKER_URL, "paula-stream-" + machine + "-" + System.currentTimeMillis(), new MemoryPersistence());
		MqttConnectOptions options = new MqttConnectOptions();
		options.setCleanSession(true);
		options.setAutomaticReconnect(true);
		options.setConnectionTimeout(5);
		client.connect(options);
	}

	public synchronized void publish(String target, String text) throws Exception {
		connect();
		JSONObject json = new JSONObject();
		json.put("machine", machine);
		json.put("text", text);
		json.put("timestamp", System.currentTimeMillis());
		MqttMessage message = new MqttMessage(json.toString().getBytes(StandardCharsets.UTF_8));
		message.setQos(1);
		String topic = EVERYONE.equals(target) ? TOPIC_MESSAGES : TOPIC_DM_PREFIX + target;
		client.publish(topic, message);
	}

	public synchronized void disconnect() {
		try {
			if (client != null && client.isConnected()) client.disconnect();
			if (client != null) client.close();
		} catch (Exception e) {
			logger.debug("disconnect: " + e.getMessage());
		}
		client = null;
	}

	// Who is online now: the broker's retained roster, read with a short-lived connection.
	// "Everyone" first, this Pi itself left out.
	public static List<String> participants() throws Exception {
		List<String> names = new ArrayList<>();
		names.add(EVERYONE);
		MqttClient c = new MqttClient(BROKER_URL, "paula-roster-" + System.currentTimeMillis(), new MemoryPersistence());
		CountDownLatch got = new CountDownLatch(1);
		final String self = localMachineName();
		c.setCallback(new MqttCallback() {
			@Override public void connectionLost(Throwable cause) { }
			@Override public void deliveryComplete(IMqttDeliveryToken token) { }
			@Override public void messageArrived(String topic, MqttMessage message) {
				try {
					JSONArray array = new JSONArray(new String(message.getPayload(), StandardCharsets.UTF_8));
					for (int i = 0; i < array.length(); i++) {
						String name = array.getString(i);
						// The broker lists connection IDs, so this class's own short-lived
						// connections (paula-roster-*, paula-stream-*) show up too - leave them out.
						if (!name.equals(self) && !name.startsWith("paula-roster-") && !name.startsWith("paula-stream-")
								&& !names.contains(name)) names.add(name);
					}
				} catch (Exception e) {
					logger.warn("Bad presence payload: " + e.getMessage());
				}
				got.countDown();
			}
		});
		MqttConnectOptions options = new MqttConnectOptions();
		options.setCleanSession(true);
		options.setConnectionTimeout(5);
		try {
			c.connect(options);
			c.subscribe(TOPIC_PRESENCE, 1);
			got.await(3, TimeUnit.SECONDS);
		} finally {
			try { if (c.isConnected()) c.disconnect(); c.close(); } catch (Exception e) { /* ignore */ }
		}
		return names;
	}

	public static String localMachineName() {
		try {
			String hostname = InetAddress.getLocalHost().getHostName();
			int dot = hostname.indexOf('.');
			return dot > 0 ? hostname.substring(0, dot) : hostname;
		} catch (Exception e) {
			return "paula";
		}
	}
}
