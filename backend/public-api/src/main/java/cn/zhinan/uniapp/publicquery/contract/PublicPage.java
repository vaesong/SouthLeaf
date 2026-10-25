package cn.zhinan.uniapp.publicquery.contract;

import java.util.List;

public record PublicPage<T>(List<T> items, String nextCursor) {
  public PublicPage {
    if (items == null) {
      throw new IllegalArgumentException("items is required");
    }
    items = List.copyOf(items);
  }
}
