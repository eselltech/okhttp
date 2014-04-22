/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp;

import com.squareup.okhttp.internal.NamedRunnable;
import com.squareup.okhttp.internal.http.HttpEngine;
import com.squareup.okhttp.internal.http.OkHeaders;
import com.squareup.okhttp.internal.huc.HttpURLConnectionImpl;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import okio.BufferedSink;
import okio.BufferedSource;

import static com.squareup.okhttp.internal.Util.getEffectivePort;
import static com.squareup.okhttp.internal.http.StatusLine.HTTP_TEMP_REDIRECT;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;

final class Job extends NamedRunnable {
  private final Dispatcher dispatcher;
  private final OkHttpClient client;
  private final Response.Receiver responseReceiver;
  private int redirectionCount;

  volatile boolean canceled;

  /** The request; possibly a consequence of redirects or auth headers. */
  private Request request;
  HttpEngine engine;

  public Job(Dispatcher dispatcher, OkHttpClient client, Request request,
      Response.Receiver responseReceiver) {
    super("OkHttp %s", request.urlString());
    this.dispatcher = dispatcher;
    this.client = client;
    this.request = request;
    this.responseReceiver = responseReceiver;
  }

  String host() {
    return request.url().getHost();
  }

  Request request() {
    return request;
  }

  Object tag() {
    return request.tag();
  }

  @Override protected void execute() {
    try {
      Response response = getResponse();
      if (response != null && !canceled) {
        responseReceiver.onResponse(response);
      }
    } catch (IOException e) {
      responseReceiver.onFailure(new Failure.Builder()
          .request(request)
          .exception(e)
          .build());
    } finally {
      engine.close(); // Close the connection if it isn't already.
      dispatcher.finished(this);
    }
  }

  /**
   * Performs the request and returns the response. May return null if this job
   * was canceled.
   */
  Response getResponse() throws IOException {
    Response redirectedBy = null;

    // Copy body metadata to the appropriate request headers.
    Request.Body body = request.body();
    if (body != null) {
      MediaType contentType = body.contentType();
      if (contentType == null) throw new IllegalStateException("contentType == null");

      Request.Builder requestBuilder = request.newBuilder();
      requestBuilder.header("Content-Type", contentType.toString());

      long contentLength = body.contentLength();
      if (contentLength != -1) {
        requestBuilder.header("Content-Length", Long.toString(contentLength));
        requestBuilder.removeHeader("Transfer-Encoding");
      } else {
        requestBuilder.header("Transfer-Encoding", "chunked");
        requestBuilder.removeHeader("Content-Length");
      }

      request = requestBuilder.build();
    }

    // Create the initial HTTP engine. Retries and redirects need new engine for each attempt.
    engine = new HttpEngine(client, request, false, null, null, null);

    while (true) {
      if (canceled) return null;

      try {
        engine.sendRequest();

        if (body != null) {
          BufferedSink sink = engine.getBufferedRequestBody();
          body.writeTo(sink);
          sink.flush();
        }

        engine.readResponse();
      } catch (IOException e) {
        HttpEngine retryEngine = engine.recover(e);
        if (retryEngine != null) {
          engine = retryEngine;
          continue;
        }

        // Give up; recovery is not possible.
        throw e;
      }

      Response response = engine.getResponse();
      Request redirect = processResponse(engine, response);

      if (redirect == null) {
        engine.releaseConnection();
        return response.newBuilder()
            .body(new RealResponseBody(response, engine.getResponseBody()))
            .redirectedBy(redirectedBy)
            .build();
      }

      if (!sameConnection(request, redirect)) {
        engine.releaseConnection();
      }

      Connection connection = engine.close();
      redirectedBy = response.newBuilder().redirectedBy(redirectedBy).build(); // Chained.
      request = redirect;
      engine = new HttpEngine(client, request, false, connection, null, null);
    }
  }

  /**
   * Figures out the HTTP request to make in response to receiving {@code
   * response}. This will either add authentication headers or follow
   * redirects. If a follow-up is either unnecessary or not applicable, this
   * returns null.
   */
  private Request processResponse(HttpEngine engine, Response response) throws IOException {
    Request request = response.request();
    Proxy selectedProxy = engine.getRoute() != null
        ? engine.getRoute().getProxy()
        : client.getProxy();
    int responseCode = response.code();

    switch (responseCode) {
      case HTTP_PROXY_AUTH:
        if (selectedProxy.type() != Proxy.Type.HTTP) {
          throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
        }
        // fall-through
      case HTTP_UNAUTHORIZED:
        return OkHeaders.processAuthHeader(client.getAuthenticator(), response, selectedProxy);

      case HTTP_TEMP_REDIRECT:
        // "If the 307 status code is received in response to a request other than GET or HEAD,
        // the user agent MUST NOT automatically redirect the request"
        if (!request.method().equals("GET") && !request.method().equals("HEAD")) return null;
        // fall-through
      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_MOVED_TEMP:
      case HTTP_SEE_OTHER:
        String location = response.header("Location");
        if (location == null) return null;
        URL url = new URL(request.url(), location);

        // Don't follow redirects to unsupported protocols.
        if (!url.getProtocol().equals("https") && !url.getProtocol().equals("http")) return null;

        // If configured, don't follow redirects between SSL and non-SSL.
        boolean sameProtocol = url.getProtocol().equals(request.url().getProtocol());
        if (!sameProtocol && !client.getFollowSslRedirects()) return null;

        if (++redirectionCount > HttpURLConnectionImpl.MAX_REDIRECTS) {
          throw new ProtocolException("Too many redirects: " + redirectionCount);
        }

        return this.request.newBuilder().url(url).build();

      default:
        return null;
    }
  }

  static boolean sameConnection(Request a, Request b) {
    return a.url().getHost().equals(b.url().getHost())
        && getEffectivePort(a.url()) == getEffectivePort(b.url())
        && a.url().getProtocol().equals(b.url().getProtocol());
  }

  static class RealResponseBody extends Response.Body {
    private final Response response;
    private final BufferedSource source;

    RealResponseBody(Response response, BufferedSource source) {
      this.response = response;
      this.source = source;
    }

    @Override public MediaType contentType() {
      String contentType = response.header("Content-Type");
      return contentType != null ? MediaType.parse(contentType) : null;
    }

    @Override public long contentLength() {
      return OkHeaders.contentLength(response);
    }

    @Override public BufferedSource source() {
      return source;
    }
  }
}