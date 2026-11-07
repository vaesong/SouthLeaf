package cn.zhinan.objectstore;

import java.util.Map;

/** One HTTP exchange with an object endpoint. Implementations do not interpret COS errors. */
public interface ObjectTransport {
  record Response(int status, byte[] body) {
    public Response {
      body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }
  }

  Response exchange(String method, String url, Map<String, String> headers, byte[] body);
}
