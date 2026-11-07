package cn.zhinan.objectstore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** JDK client for a COS endpoint. Redirects are not followed. */
public final class JdkHttpTransport implements ObjectTransport {
  private final HttpClient client;

  public JdkHttpTransport() {
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public Response exchange(String method, String url, Map<String, String> headers, byte[] body) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
      if ("PUT".equals(method)) request.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
      else if ("GET".equals(method)) request.GET();
      else throw new ObjectStoreException("COS_METHOD_INVALID");
      headers.forEach(request::header);
      HttpResponse<byte[]> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      return new Response(response.statusCode(), response.body());
    } catch (ObjectStoreException refusal) {
      throw refusal;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ObjectStoreException("OBJECT_TRANSPORT");
    } catch (Exception failure) {
      throw new ObjectStoreException("OBJECT_TRANSPORT");
    }
  }
}
