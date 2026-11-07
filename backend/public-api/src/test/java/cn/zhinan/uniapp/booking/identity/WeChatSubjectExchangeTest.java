package cn.zhinan.uniapp.booking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhinan.uniapp.booking.PublicBookingException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WeChatSubjectExchangeTest {
  @Test
  void codeIsExchangedAgainstTheConfiguredEndpoint() throws Exception {
    var seen = new AtomicReference<String>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/sns/jscode2session",
        exchange -> {
          seen.set(exchange.getRequestURI().getRawQuery());
          byte[] body = "{\"openid\":\"openid-from-fake\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var exchange =
          new WeChatSubjectExchange(
              "wx0123456789abcdef",
              "app-secret-not-logged",
              URI.create(
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/sns/jscode2session"),
              HttpClient.newHttpClient());
      assertThat(exchange.subject("login-code-1")).isEqualTo("openid-from-fake");
      assertThat(seen.get()).contains("js_code=login-code-1").contains("app-secret-not-logged");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void platformErrorAndTransportFailureAreRefusals() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/sns/jscode2session",
        exchange -> {
          byte[] body =
              "{\"errcode\":40029,\"errmsg\":\"invalid code\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var exchange =
          new WeChatSubjectExchange(
              "wx0123456789abcdef",
              "app-secret-not-logged",
              URI.create(
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/sns/jscode2session"),
              HttpClient.newHttpClient());
      assertThatThrownBy(() -> exchange.subject("login-code-1"))
          .isInstanceOf(PublicBookingException.class);
      var missing =
          new WeChatSubjectExchange(
              "wx0123456789abcdef",
              "app-secret-not-logged",
              URI.create("http://127.0.0.1:1/sns/jscode2session"),
              HttpClient.newHttpClient());
      assertThatThrownBy(() -> missing.subject("login-code-1"))
          .isInstanceOf(PublicBookingException.class);
    } finally {
      server.stop(0);
    }
  }
}
