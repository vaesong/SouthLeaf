package cn.zhinan.uniapp.media.port;

import cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot.Variant;

public interface MediaObjectStore {
  byte[] readVerified(Variant variant);
}
