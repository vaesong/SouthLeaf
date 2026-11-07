package cn.zhinan.uniapp.booking.identity;

import cn.zhinan.uniapp.booking.PublicBookingException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** {@code code2session} exchange against the official endpoint; every failure is a refusal. */
public final class WeChatSubjectExchange implements SubjectExchange {
  private static final String ENDPOINT = "https://api.weixin.qq.com/sns/jscode2session";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient client;
  private final String appId;
  private final String appSecret;
  private final URI endpoint;

  public WeChatSubjectExchange(String appId, String appSecret) {
    this(
        appId,
        appSecret,
        URI.create(ENDPOINT),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  /**
   * The endpoint is injected so a test can stand in for the platform without embedding a secret.
   */
  WeChatSubjectExchange(String appId, String appSecret, URI endpoint, HttpClient client) {
    this.client = client;
    this.appId = appId;
    this.appSecret = appSecret;
    this.endpoint = endpoint;
  }

  @Override
  public String provider() {
    return "WECHAT";
  }

  @Override
  public String subject(String loginCode) {
    String query =
        "appid="
            + encode(appId)
            + "&secret="
            + encode(appSecret)
            + "&js_code="
            + encode(loginCode)
            + "&grant_type=authorization_code";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
            .timeout(Duration.ofSeconds(6))
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) throw rejected();
      JsonNode body = JSON.readTree(response.body());
      String openId = body.path("openid").asText("");
      if (openId.isBlank() || openId.length() > 128 || body.hasNonNull("errcode")) throw rejected();
      return openId;
    } catch (PublicBookingException refusal) {
      throw refusal;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw rejected();
    } catch (Exception failure) {
      throw rejected();
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static PublicBookingException rejected() {
    return new PublicBookingException(
        PublicBookingException.ErrorCode.IDENTITY_REJECTED, "WeChat code rejected");
  }
}
