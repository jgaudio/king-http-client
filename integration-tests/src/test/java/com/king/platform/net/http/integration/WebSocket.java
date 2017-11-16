// Copyright (C) king.com Ltd 2015
// https://github.com/king/king-http-client
// Author: Magnus Gustafsson
// License: Apache 2.0, https://raw.github.com/king/king-http-client/LICENSE-APACHE

package com.king.platform.net.http.integration;


import com.king.platform.net.http.ConfKeys;
import com.king.platform.net.http.HttpClient;
import com.king.platform.net.http.WebSocketConnection;
import com.king.platform.net.http.WebSocketListener;
import com.king.platform.net.http.netty.NettyHttpClientBuilder;
import com.king.platform.net.http.netty.backpressure.EvictingBackPressure;
import com.king.platform.net.http.netty.eventbus.DefaultEventBus;
import com.king.platform.net.http.netty.pool.NoChannelPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.servlet.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static junit.framework.TestCase.assertEquals;

public class WebSocket {
	IntegrationServer integrationServer;
	private HttpClient httpClient;
	private int port;

	private String okBody = "EVERYTHING IS OKAY!";
	private RecordingEventBus recordingEventBus;

	@Before
	public void setUp() throws Exception {
		integrationServer = new JettyIntegrationServer(500);
		integrationServer.start();
		port = integrationServer.getPort();

		recordingEventBus = new RecordingEventBus(new DefaultEventBus());

		httpClient = new NettyHttpClientBuilder()
			.setNioThreads(2)
			.setHttpCallbackExecutorThreads(2)
			.setChannelPool(new NoChannelPool()).setExecutionBackPressure(new EvictingBackPressure(10))
			.setOption(ConfKeys.IDLE_TIMEOUT_MILLIS, 0)
			.setOption(ConfKeys.TOTAL_REQUEST_TIMEOUT_MILLIS, 0)
			.setRootEventBus(recordingEventBus)
			.createHttpClient();


		httpClient.start();

	}

	public static class EchoWebSocketEndpoint implements org.eclipse.jetty.websocket.api.WebSocketListener {

		private Session session;

		@Override
		public void onWebSocketBinary(byte[] payload, int offset, int len) {

		}

		@Override
		public void onWebSocketClose(int statusCode, String reason) {
		}

		@Override
		public void onWebSocketConnect(Session session) {
			this.session = session;
		}

		@Override
		public void onWebSocketError(Throwable cause) {
		}

		@Override
		public void onWebSocketText(String message) {
			try {
				session.getRemote().sendString(message.toUpperCase());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	@Test
	public void webSocket() throws Exception {

		integrationServer.addServlet(new WebSocketServlet() {
			@Override
			public void configure(WebSocketServletFactory factory) {

				factory.register(EchoWebSocketEndpoint.class);
			}
		}, "/websocket/test");


		CountDownLatch countDownLatch = new CountDownLatch(1);
		AtomicReference<String> receivedText = new AtomicReference<>();

		httpClient.createWebSocket("ws://localhost:" + port + "/websocket/test")
			.build()
			.execute(new WebSocketListener() {
				WebSocketConnection client;

				@Override
				public void onConnect(WebSocketConnection connection) {
					this.client = connection;
					connection.sendTextFrame("hello world");
				}

				@Override
				public void onDisconnect(int code, String reason) {
					countDownLatch.countDown();
				}

				@Override
				public void onError(Throwable t) {
					System.out.println("Client error " + t);
				}

				@Override
				public void onBinaryFrame(byte[] payload, boolean finalFragment, int rsv) {

				}

				@Override
				public void onTextFrame(String payload, boolean finalFragment, int rsv) {
					receivedText.set(payload);
					client.sendCloseFrame();
				}
			});


		countDownLatch.await();

		assertEquals("HELLO WORLD", receivedText.get());

	}

	@Test
	public void webSocketRequestEvents() throws Exception {
		integrationServer.addServlet(new WebSocketServlet() {
			@Override
			public void configure(WebSocketServletFactory factory) {

				factory.register(EchoWebSocketEndpoint.class);
			}
		}, "/websocket/test");


		CountDownLatch countDownLatch = new CountDownLatch(1);
		AtomicReference<String> receivedText = new AtomicReference<>();

		httpClient.createWebSocket("ws://localhost:" + port + "/websocket/test")
			.build()
			.execute(new WebSocketListener() {
				WebSocketConnection client;

				@Override
				public void onConnect(WebSocketConnection connection) {
					this.client = connection;
					connection.sendTextFrame("hello world");
				}

				@Override
				public void onDisconnect(int code, String reason) {
					countDownLatch.countDown();
				}

				@Override
				public void onError(Throwable t) {
					System.out.println("Client error " + t);
				}

				@Override
				public void onBinaryFrame(byte[] payload, boolean finalFragment, int rsv) {

				}

				@Override
				public void onTextFrame(String payload, boolean finalFragment, int rsv) {
					receivedText.set(payload);
					client.sendCloseFrame();
				}
			});


		countDownLatch.await();
		Thread.sleep(100);

		recordingEventBus.printDeepInteractionStack();
	}

	@After
	public void tearDown() throws Exception {
		integrationServer.shutdown();
		httpClient.shutdown();

	}


}
