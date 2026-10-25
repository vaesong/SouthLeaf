package cn.zhinan.uniapp.publicquery.application;

import cn.zhinan.uniapp.publicquery.PublicQueryException;
import cn.zhinan.uniapp.publicquery.contract.PublicPage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * Opaque, non-authorizing cursor tied to one publication and query; never silently empty on
 * mismatch.
 */
public final class VersionedCursor {
  private VersionedCursor() {}

  public static <T> PublicPage<T> page(
      List<T> ordered,
      int limit,
      String cursor,
      String version,
      String query,
      Function<T, String> id) {
    int start = 0;
    if (cursor != null && !cursor.isBlank()) {
      try {
        if (cursor.length() > 1024 || !cursor.matches("[A-Za-z0-9_-]+"))
          throw new IllegalArgumentException();
        String[] parts =
            new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                .split("\n", -1);
        if (parts.length != 3 || !parts[1].equals(query)) throw new IllegalArgumentException();
        if (!parts[0].equals(version))
          throw new PublicQueryException(
              PublicQueryException.ErrorCode.PUBLIC_CURSOR_EXPIRED,
              "Cursor is stale; restart the query");
        int index = -1;
        for (int i = 0; i < ordered.size(); i++)
          if (id.apply(ordered.get(i)).equals(parts[2])) {
            index = i;
            break;
          }
        if (index < 0)
          throw new PublicQueryException(
              PublicQueryException.ErrorCode.PUBLIC_CURSOR_EXPIRED,
              "Cursor anchor is stale; restart the query");
        start = index + 1;
      } catch (IllegalArgumentException ex) {
        throw new PublicQueryException(
            PublicQueryException.ErrorCode.PUBLIC_QUERY_INVALID,
            "Cursor is invalid or stale; restart the query");
      }
    }
    List<T> items = List.copyOf(ordered.subList(start, Math.min(start + limit, ordered.size())));
    String next =
        start + items.size() < ordered.size()
            ? Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    (version + "\n" + query + "\n" + id.apply(items.getLast()))
                        .getBytes(StandardCharsets.UTF_8))
            : null;
    return new PublicPage<>(items, next);
  }
}
