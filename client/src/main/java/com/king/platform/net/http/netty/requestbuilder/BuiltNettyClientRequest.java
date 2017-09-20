// Copyright (C) king.com Ltd 2015
// https://github.com/king/king-http-client
// Author: Magnus Gustafsson
// License: Apache 2.0, https://raw.github.com/king/king-http-client/LICENSE-APACHE

package com.king.platform.net.http.netty.requestbuilder;


import com.king.platform.net.http.*;
import com.king.platform.net.http.HttpResponse;
import com.king.platform.net.http.netty.HttpClientCaller;
import com.king.platform.net.http.netty.ResponseFuture;
import com.king.platform.net.http.netty.ServerInfo;
import com.king.platform.net.http.netty.eventbus.ExternalEventTrigger;
import com.king.platform.net.http.netty.request.HttpBody;
import com.king.platform.net.http.netty.request.NettyHttpClientRequest;
import com.king.platform.net.http.util.Param;
import com.king.platform.net.http.util.UriUtil;
import io.netty.handler.codec.http.*;

import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class BuiltNettyClientRequest<T> implements BuiltClientRequest<T>, BuiltClientRequestWithBody<T> {

	private final HttpClientCaller httpClientCaller;
	private final HttpVersion httpVersion;
	private final HttpMethod httpMethod;
	private final String uri;

	private final String defaultUserAgent;


	private final int idleTimeoutMillis;
	private final int totalRequestTimeoutMillis;

	private final boolean followRedirects;
	private final boolean acceptCompressedResponse;
	private final boolean keepAlive;

	private final RequestBodyBuilder requestBodyBuilder;
	private final String contentType;
	private final Charset bodyCharset;

	private final List<Param> queryParameters;
	private final List<Param> headerParameters;
	private final Executor callbackExecutor;
	private final ResponseBodyConsumer<T> responseBodyConsumer;

	private HttpCallback<T> httpCallback;
	private NioCallback nioCallback;
	private ExternalEventTrigger externalEventTrigger;
	private UploadCallback uploadCallback;

	public BuiltNettyClientRequest(HttpClientCaller httpClientCaller, HttpVersion httpVersion, HttpMethod httpMethod, String uri, String defaultUserAgent, int idleTimeoutMillis, int totalRequestTimeoutMillis, boolean followRedirects, boolean acceptCompressedResponse, boolean keepAlive, RequestBodyBuilder requestBodyBuilder, String contentType, Charset bodyCharset, List<Param> queryParameters, List<Param> headerParameters, Executor callbackExecutor, ResponseBodyConsumer<T> responseBodyConsumer) {
		this.httpClientCaller = httpClientCaller;
		this.httpVersion = httpVersion;
		this.httpMethod = httpMethod;
		this.uri = uri;
		this.defaultUserAgent = defaultUserAgent;
		this.idleTimeoutMillis = idleTimeoutMillis;
		this.totalRequestTimeoutMillis = totalRequestTimeoutMillis;
		this.followRedirects = followRedirects;
		this.acceptCompressedResponse = acceptCompressedResponse;
		this.keepAlive = keepAlive;
		this.requestBodyBuilder = requestBodyBuilder;
		this.contentType = contentType;
		this.bodyCharset = bodyCharset;
		this.queryParameters = new ArrayList<>(queryParameters);
		this.headerParameters = new ArrayList<>(headerParameters);
		this.callbackExecutor = callbackExecutor;
		this.responseBodyConsumer = responseBodyConsumer;
	}


	@Override
	public BuiltClientRequest<T> withHttpCallback(HttpCallback<T> httpCallback) {
		this.httpCallback = httpCallback;
		return this;
	}

	@Override
	public BuiltClientRequest<T> withNioCallback(NioCallback nioCallback) {
		this.nioCallback = nioCallback;
		return this;
	}

	public BuiltClientRequest<T> withExternalEventTrigger(ExternalEventTrigger externalEventTrigger) {
		this.externalEventTrigger = externalEventTrigger;
		return this;
	}

	@Override
	public BuiltClientRequestWithBody<T> withUploadCallback(UploadCallback uploadCallback) {
		this.uploadCallback = uploadCallback;
		return this;
	}

	@Override
	public CompletableFuture<HttpResponse<T>> execute() {
		String completeUri = UriUtil.getUriWithParameters(uri, queryParameters);

		ServerInfo serverInfo = null;
		try {
			serverInfo = ServerInfo.buildFromUri(completeUri);
		} catch (URISyntaxException e) {
			return dispatchError(httpCallback, e);
		}

		String relativePath = UriUtil.getRelativeUri(completeUri);


		DefaultHttpRequest defaultHttpRequest = new DefaultHttpRequest(httpVersion, httpMethod, relativePath);

		HttpBody httpBody = null;

		if (requestBodyBuilder != null) {
			httpBody = requestBodyBuilder.createHttpBody(contentType, bodyCharset, serverInfo.isSecure());
		}

		NettyHttpClientRequest<T> nettyHttpClientRequest = new NettyHttpClientRequest<>(serverInfo, defaultHttpRequest, httpBody);

		HttpHeaders headers = nettyHttpClientRequest.getNettyHeaders();

		for (Param headerParameter : headerParameters) {
			headers.add(headerParameter.getName(), headerParameter.getValue());
		}


		if (acceptCompressedResponse && !headers.contains(HttpHeaderNames.ACCEPT_ENCODING)) {
			headers.set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP + "," + HttpHeaderValues.DEFLATE);
		}

		if (httpBody != null) {
			if (httpBody.getContentLength() < 0) {
				headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
			} else {
				headers.set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(httpBody.getContentLength()));
			}

			String contentType = httpBody.getContentType();
			if (contentType != null) {
				Charset characterEncoding = httpBody.getCharacterEncoding();
				if (characterEncoding != null && !contentType.contains("charset=")) {
					contentType = contentType + ";charset=" + characterEncoding.name();
				}
				headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
			}

		}


		if (!headers.contains(HttpHeaderNames.ACCEPT)) {
			headers.set(HttpHeaderNames.ACCEPT, "*/*");
		}

		if (!headers.contains(HttpHeaderNames.USER_AGENT)) {
			headers.set(HttpHeaderNames.USER_AGENT, defaultUserAgent);
		}

		if (serverInfo.getPort() == 80 || serverInfo.getPort() == 443) {	//Don't write the ports for default ports: Host = "Host" ":" host [ ":" port ] ;
			headers.set(HttpHeaderNames.HOST, serverInfo.getHost());
		} else {
			headers.set(HttpHeaderNames.HOST, serverInfo.getHost() + ":" + serverInfo.getPort());
		}


		nettyHttpClientRequest.setKeepAlive(keepAlive);

		return httpClientCaller.execute(httpMethod, nettyHttpClientRequest, httpCallback, nioCallback, responseBodyConsumer, idleTimeoutMillis, totalRequestTimeoutMillis,
			followRedirects, keepAlive, externalEventTrigger, callbackExecutor, uploadCallback);
	}

	private CompletableFuture<HttpResponse<T>> dispatchError(final HttpCallback<T> httpCallback, final URISyntaxException e) {
		if (httpCallback != null) {
            callbackExecutor.execute(() -> httpCallback.onError(e));
        }

		return ResponseFuture.error(e);
	}

	public int getIdleTimeoutMillis() {
		return idleTimeoutMillis;
	}

	public int getTotalRequestTimeoutMillis() {
		return totalRequestTimeoutMillis;
	}

	public boolean isFollowRedirects() {
		return followRedirects;
	}

	public boolean isAcceptCompressedResponse() {
		return acceptCompressedResponse;
	}

	public boolean isKeepAlive() {
		return keepAlive;
	}


}
