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
import com.king.platform.net.http.netty.eventbus.Event;
import com.king.platform.net.http.netty.pool.NoChannelPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.fail;

public class WebSocket {
	IntegrationServer integrationServer;
	private HttpClient httpClient;
	private int port;

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

		integrationServer.addServlet(new WebSocketServlet() {
			@Override
			public void configure(WebSocketServletFactory factory) {

				factory.register(EchoWebSocketEndpoint.class);
			}
		}, "/websocket/test");


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

	@Test(timeout = 5000)
	public void webSocket() throws Exception {


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
				public void onCloseFrame(int code, String reason) {

				}

				@Override
				public void onError(Throwable t) {
					System.out.println("Client error " + t);
				}

				@Override
				public void onDisconnect() {
					countDownLatch.countDown();
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

	@Test(timeout = 5000)
	public void webSocketRequestEvents() throws Exception {

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
				public void onCloseFrame(int code, String reason) {
				}

				@Override
				public void onError(Throwable t) {
					System.out.println("Client error " + t);
				}

				@Override
				public void onDisconnect() {
					countDownLatch.countDown();

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
		recordingEventBus.hasTriggered(Event.WS_UPGRADE_PIPELINE);
		recordingEventBus.hasTriggered(Event.COMPLETED);
		recordingEventBus.printDeepInteractionStack();
	}

	@Test(timeout = 5000)
	public void buildAnWebSocketAndLaterConnectIt() throws Exception {


		CountDownLatch countDownLatch = new CountDownLatch(1);
		AtomicReference<String> receivedText = new AtomicReference<>();

		WebSocketConnection connection = httpClient.createWebSocket("ws://localhost:" + port + "/websocket/test")
			.build()
			.build();

		connection.addListener(new WebSocketListener() {
			@Override
			public void onConnect(WebSocketConnection connection) {
				connection.sendTextFrame("hello world");
			}

			@Override
			public void onCloseFrame(int code, String reason) {
			}

			@Override
			public void onError(Throwable t) {
				countDownLatch.countDown();
			}

			@Override
			public void onDisconnect() {
				countDownLatch.countDown();
			}

			@Override
			public void onBinaryFrame(byte[] payload, boolean finalFragment, int rsv) {

			}

			@Override
			public void onTextFrame(String payload, boolean finalFragment, int rsv) {
				receivedText.set(payload);
				connection.sendCloseFrame();
			}
		});
		connection.connect();

		countDownLatch.await();

		assertEquals("HELLO WORLD", receivedText.get());

	}

	@Test
	public void twoConnectDirectlyAfterEachOtherShouldFail() throws Exception {

		WebSocketConnection connection = httpClient.createWebSocket("ws://localhost:" + port + "/websocket/test")
			.build()
			.build();

		connection.connect();
		try {
			connection.connect();
			fail("Should have failed!");
		} catch (Exception ignored) {
		}
	}

	@Test
	public void connectWhenConnectedShouldFail() throws Exception {
		WebSocketConnection connection = httpClient.createWebSocket("ws://localhost:" + port + "/websocket/test")
			.build()
			.build();

		CompletableFuture<WebSocketConnection> future = connection.connect();

		future.join();

		try {
			connection.connect();
			fail("Should have failed!");
		} catch (Exception ignored) {
		}

	}

	@Test
	public void awaitCloseShouldWaitUntilClosed() throws Exception {
		WebSocketConnection connection = httpClient.createWebSocket("ws://localhost:" + port + "/websocket/test")
			.build()
			.build();

		connection.addListener(new WebSocketListener() {
			@Override
			public void onConnect(WebSocketConnection connection) {
				connection.sendCloseFrame();
			}

			@Override
			public void onError(Throwable t) {

			}

			@Override
			public void onDisconnect() {

			}

			@Override
			public void onCloseFrame(int code, String reason) {

			}

			@Override
			public void onBinaryFrame(byte[] payload, boolean finalFragment, int rsv) {

			}

			@Override
			public void onTextFrame(String payload, boolean finalFragment, int rsv) {

			}
		});

		CompletableFuture<WebSocketConnection> future = connection.connect();

		future.join();

		connection.awaitClose();


	}



	@After
	public void tearDown() throws Exception {
		integrationServer.shutdown();
		httpClient.shutdown();

	}


}
