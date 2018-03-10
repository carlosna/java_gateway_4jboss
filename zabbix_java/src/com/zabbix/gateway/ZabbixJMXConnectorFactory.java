/*
** Zabbix
** Copyright (C) 2001-2016 Zabbix SIA
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
**/

package com.zabbix.gateway;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.Properties;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ZabbixJMXConnectorFactory
{
	private static final Logger logger = LoggerFactory.getLogger(ZabbixJMXConnectorFactory.class);

	private static final ExecutorService executor = Executors.newCachedThreadPool(new DaemonThreadFactory());
//	static int startPollers = ConfigurationManager.getIntegerParameterValue(ConfigurationManager.START_POLLERS);
//	private static final ExecutorService executor = Executors.newFixedThreadPool(startPollers);

	static {
		System.setProperty("com.sun.net.ssl.checkRevocation", "false");

		TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
			public java.security.cert.X509Certificate[] getAcceptedIssuers() {
				return null;
			}

			public void checkClientTrusted(X509Certificate[] certs, String authType) {
			}

			public void checkServerTrusted(X509Certificate[] certs, String authType) {
			}
		} };

		try {
			logger.info("Hacking SSL Validation");
			SSLContext sc = SSLContext.getInstance("SSL");
			sc.init(null, trustAllCerts, new java.security.SecureRandom());
			SSLContext.setDefault(sc);
			logger.info("SSL Validation hacked");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}

	private static class DaemonThreadFactory implements ThreadFactory
	{
		private ThreadFactory f = Executors.defaultThreadFactory();

		@Override
		public Thread newThread(Runnable r)
		{
			Thread t = f.newThread(r);

			t.setDaemon(true);

			return t;
		}
	}

//	static Map<JMXServiceURL, JMXConnector> connectionPool = Collections.synchronizedMap(new HashMap<JMXServiceURL, JMXConnector>());

	static JMXConnector connect(final JMXServiceURL url, final HashMap<String, Object> env) throws IOException
	{
		logger.debug("connecting to JMX agent at '{}'", url);

		final BlockingQueue<Object> queue = new ArrayBlockingQueue<Object>(1);

		Runnable task = new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
/*
				JMXConnector jmxc = connectionPool.get(url);

					if (jmxc != null) {

					try {
					logger.info("Cached JMX connection found for {}, calling cached.getMBeanServerConnection() if ",url);
					MBeanServerConnection c = jmxc.getMBeanServerConnection();
					logger.info("Got MBeanServerConnection {} , returning from cache for {}", c, url);
					queue.offer(c);
					return;
					} catch (Exception e) {
					logger.error("cached JMX connection seemed stale for, discarding conn for url=" + url, e);
					connectionPool.remove(url);
					  }
					}

					logger.info("No Cached JMX connection found for {}, creating new one", url);

			try {
*/
		    HashMap envSSL = new HashMap<>(env != null ? env : Collections.emptyMap());
//		    envSSL.put(JMXConnector.CREDENTIALS,env);
		    envSSL.put("com.sun.jndi.rmi.factory.socket", new SslRMIClientSocketFactory());
                    JMXConnector jmxc = JMXConnectorFactory.connect(url, envSSL);

					logger.trace("call to JMXConnectorFactory.connect('{}') successful", url);
/*
					} catch (Exception e) {
						logger.error("Got exception {} in first attemp, will try without SSL", e.getMessage());
						jmxc = JMXConnectorFactory.connect(url, env);
						logger.error("Non-SSL Connection created to {}", url);
					}

					connectionPool.put(url, jmxc);
					logger.trace("call to JMXConnectorFactory.getMBeanServerConnection('{}') successful", url);
					MBeanServerConnection c = jmxc.getMBeanServerConnection();
					queue.offer(c);
*/
					if (!queue.offer(jmxc))
						jmxc.close();

					MBeanServerConnection c = jmxc.getMBeanServerConnection();
					queue.offer(c);
				}
				catch (Throwable t)
				{
					logger.trace("call to JMXConnectorFactory.connect('{}') timed out '{}'", url, t.getMessage());

					queue.offer(t);
				}
			}
		};
		
		executor.submit(task);
		Object result;

		try
		{
			int timeout = ConfigurationManager.getIntegerParameterValue(ConfigurationManager.TIMEOUT);
//			executor.getThreadPoolExecutor().awaitTermination( 20, TimeUnit.SECONDS );
			result = queue.poll(timeout, TimeUnit.SECONDS);

			if (null == result)
			{
				if (!queue.offer(""))
					result = queue.take();
			}

			if (null == result)
				logger.trace("no connector after {} seconds", timeout);
			else
				logger.trace("connector acquired");
		}
		catch (InterruptedException e)
		{
			InterruptedIOException e2 = new InterruptedIOException(e.getMessage());

			e2.initCause(e);

			throw e2;
		}

		if (null == result)
			throw new SocketTimeoutException("connection timed out: " + url);

		if (result instanceof JMXConnector)
			return (JMXConnector)result;
		
	    	if (executor != null) {
	        logger.trace("waiting for running checks");
	        //executor.shutdown();
	        try {
	          executor.awaitTermination(15, TimeUnit.MILLISECONDS);
	        } catch (InterruptedException e) {
	          logger.warn("error waiting for executors to terminate", e);
	          }
	        }

		try
		{
			throw (Throwable)result;
		}
		catch (IOException e)
		{
			throw e;
		}
		catch (RuntimeException e)
		{
			throw e;
		}
		catch (Error e)
		{
			throw e;
		}
		catch (Throwable e)
		{
			throw new IOException(e.toString(), e);
		}
	}
}
