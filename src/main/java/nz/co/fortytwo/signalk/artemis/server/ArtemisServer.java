package nz.co.fortytwo.signalk.artemis.server;
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static nz.co.fortytwo.signalk.util.SignalKConstants.SIGNALK_DISCOVERY;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.jmdns.JmmDNS;
import javax.jmdns.ServiceInfo;

import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.core.config.impl.SecurityConfiguration;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager;
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManagerImpl;
import org.apache.camel.main.Main;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.atmosphere.cpr.ApplicationConfig;
import nz.co.fortytwo.signalk.artemis.util.Config;
import org.atmosphere.nettosphere.Nettosphere;

import mjson.Json;
import nz.co.fortytwo.signalk.artemis.service.SignalkManagedStreamService;
import nz.co.fortytwo.signalk.artemis.util.Util;
import nz.co.fortytwo.signalk.model.impl.SignalKModelFactory;
import nz.co.fortytwo.signalk.util.ConfigConstants;
import nz.co.fortytwo.signalk.util.SignalKConstants;

/**
 * ActiveMQ Artemis embedded with JMS
 */
public final class ArtemisServer {

	private static Logger logger = LogManager.getLogger(ArtemisServer.class);
	private static EmbeddedActiveMQ embedded;
	private static Nettosphere server;
	private JmmDNS jmdns;
	private SaveListener saveListener;

	public ArtemisServer() throws Exception {
		Properties props = System.getProperties();
		props.setProperty("java.net.preferIPv4Stack", "true");
		props.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
		props.setProperty("log4j.configurationFile", "./conf/log4j2.json");
		System.setProperties(props);

		Config.getInstance();

		embedded = new EmbeddedActiveMQ();
		SecurityConfiguration conf = new SecurityConfiguration();
		conf.addUser("guest", "guest");
		conf.addRole("guest", "guest");
		conf.addUser("admin", "admin");
		conf.addRole("admin", "guest");
		conf.addRole("admin", "admin");

		ActiveMQSecurityManager securityManager = new ActiveMQSecurityManagerImpl(conf);

		embedded.setSecurityManager(securityManager);
		embedded.start();

		load();
		
		//now listen for changes and save them
		Config.startConfigListener();
		//startSaveListener();
		
		addShutdownHook(this);
		server = new Nettosphere.Builder().config(new org.atmosphere.nettosphere.Config.Builder().host("0.0.0.0")
				.port(8080).initParam(ApplicationConfig.PROPERTY_SESSION_SUPPORT, "true")
				.resource(SignalkManagedStreamService.class).resource("./signalk-static").build()).build();
		server.start();

		// create a new Camel Main so we can easily start Camel
		Main main = new Main();
		main.enableHangupSupport();
	}

	private void startSaveListener() throws Exception {
		saveListener = new SaveListener();
		saveListener.startSave();
	}

	private void load() throws Exception {

		Json signalk = Config.load();
		// now send in
		ClientSession session = Util.getVmSession(Config.getConfigProperty(Config.ADMIN_USER),
				Config.getConfigProperty(Config.ADMIN_PWD));
		try {
			// this loads the LVQ config queues.
			ClientProducer producer = session.createProducer();

			ClientMessage message = session.createMessage(true);

			message.getBodyBuffer().writeString(signalk.toString());
			producer.send("incoming.raw", message);

			message = session.createMessage(true);
			File jsonFile = new File(Util.SIGNALK_CFG_SAVE_FILE);
			Json json = Json.read(jsonFile.toURI().toURL());
			message.getBodyBuffer().writeString(json.toString());
			producer.send("incoming.raw", message);
			// now bootstrap the self vessel
			message = session.createMessage(true);
			message.getBodyBuffer().writeString("{\"vessels\": { \"self\" : {\"uuid\":\""
					+ Config.getConfigProperty(ConfigConstants.UUID) + "\"}}}");
			producer.send("incoming.raw", message);
		} finally {
			if (session != null)
				session.close();
		}

	}

	private static void addShutdownHook(final ArtemisServer server) {
		Runtime.getRuntime().addShutdownHook(new Thread("shutdown-hook") {
			@Override
			public void run() {
				server.stop();
			}
		});

	}

	public void stop() {
		try {
			if(saveListener!=null)
				saveListener.stop();
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
		try {
			Config.stopConfigListener();
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
		try {
			stopMdns();
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
		try {
			server.stop();
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
		try {
			embedded.stop();
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
		
		
	}

	public static ActiveMQServer getActiveMQServer() {
		return embedded.getActiveMQServer();
	}

	/**
	 * Stop the DNS-SD server.
	 * 
	 * @throws IOException
	 */
	public void stopMdns() throws IOException {
		if (jmdns != null) {
			jmdns.unregisterAllServices();
			jmdns.close();
			jmdns = null;
		}
	}

	private void startMdns() {
		// DNS-SD
		// NetworkTopologyDiscovery netTop =
		// NetworkTopologyDiscovery.Factory.getInstance();
		Runnable r = new Runnable() {

			@Override
			public void run() {
				jmdns = JmmDNS.Factory.getInstance();

				jmdns.registerServiceType(SignalKConstants._SIGNALK_WS_TCP_LOCAL);
				jmdns.registerServiceType(SignalKConstants._SIGNALK_HTTP_TCP_LOCAL);
				ServiceInfo wsInfo = ServiceInfo.create(SignalKConstants._SIGNALK_WS_TCP_LOCAL, "signalk-ws",
						Config.getConfigPropertyInt(ConfigConstants.WEBSOCKET_PORT), 0, 0, getMdnsTxt());
				try {
					jmdns.registerService(wsInfo);
					ServiceInfo httpInfo = ServiceInfo.create(SignalKConstants._SIGNALK_HTTP_TCP_LOCAL, "signalk-http",
							Config.getConfigPropertyInt(ConfigConstants.REST_PORT), 0, 0, getMdnsTxt());
					jmdns.registerService(httpInfo);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		};
		Thread t = new Thread(r);
		t.setDaemon(true);
		t.start();

	}

	private Map<String, String> getMdnsTxt() {
		Map<String, String> txtSet = new HashMap<String, String>();
		txtSet.put("path", SIGNALK_DISCOVERY);
		txtSet.put("server", "signalk-server");
		txtSet.put("version", Config.getConfigProperty(ConfigConstants.VERSION));
		txtSet.put("vessel_name", Config.getConfigProperty(ConfigConstants.UUID));
		txtSet.put("vessel_mmsi", Config.getConfigProperty(ConfigConstants.UUID));
		txtSet.put("vessel_uuid", Config.getConfigProperty(ConfigConstants.UUID));
		return txtSet;
	}

	public static void main(String[] args) throws Exception {
		LoggerContext context = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
		File file = new File("./conf/log4j2.json");

		// this will force a reconfiguration
		context.setConfigLocation(file.toURI());
		new ArtemisServer();

	}

	class SaveListener implements Runnable {
		long lastSave = 0;
		ClientSession session = null;
		boolean running = true;
		Thread t = null;

		public void startSave() throws Exception {
			if (logger.isDebugEnabled())
				logger.debug("Checking for save listener..");
			if (t != null && t.isAlive())
				return;
			if (logger.isDebugEnabled())
				logger.debug("Starting save listener..");
			t = new Thread(this);
			t.setDaemon(true);
			t.start();
		}

		public void stop() {
			// TODO Auto-generated method stub
			running=false;
		}

		@Override
		public void run() {

			try {
				session = Util.getVmSession(Config.getConfigProperty(Config.ADMIN_USER),
						Config.getConfigProperty(Config.ADMIN_PWD));

				// now read partial
				ClientConsumer consumer = session.createConsumer("config", "_AMQ_LVQ_NAME like '%'", true);
				ClientMessage msgReceived = null;
				while ((msgReceived = consumer.receive(1000)) != null && running) {
					if (msgReceived.getBodyBuffer().readableBytes() == 0) {
						if (logger.isDebugEnabled())
							logger.debug("Save Value:" + null);
						continue;
					}
					Object recv = Util.readBodyBuffer(msgReceived);

					if (logger.isDebugEnabled())
						logger.debug("save message = " + msgReceived.getMessageID() + ":" + msgReceived.getAddress()+ ", " + recv);
				}
				consumer.close();

			} catch (Exception e) {
				logger.error(e.getMessage(),e);
			}finally {
				try {
					session.close();
				} catch (ActiveMQException e) {
					logger.error(e.getMessage(),e);
				}
			}

		}

	}
}
