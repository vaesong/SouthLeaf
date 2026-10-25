package cn.zhinan.uniapp.media.application;

import cn.zhinan.uniapp.media.port.MediaObjectStore;
import cn.zhinan.uniapp.publicquery.application.CatalogProjection;
import cn.zhinan.uniapp.publicquery.application.CatalogReadService;
import cn.zhinan.uniapp.publicquery.contract.PublicRequestRules;
import cn.zhinan.uniapp.works.application.PublicVisibilityPolicy;

public class PublicMediaService {
  private final CatalogReadService catalogs;
  private final PublicVisibilityPolicy visibility;
  private final MediaObjectStore store;

  public PublicMediaService(
      CatalogReadService catalogs, PublicVisibilityPolicy visibility, MediaObjectStore store) {
    this.catalogs = catalogs;
    this.visibility = visibility;
    this.store = store;
  }

  public record Content(byte[] bytes, String contentType) {
    public Content {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  public Content read(String id, String variant) {
    PublicRequestRules.requiredId(id);
    PublicRequestRules.variant(variant);
    var before = new CatalogProjection(catalogs.load(), visibility);
    String stamp = before.accessStamp(id, variant);
    if (stamp == null) throw PublicRequestRules.unavailable();
    var descriptor = before.snapshot().assets().get(id).variants().get(variant);
    byte[] bytes = store.readVerified(descriptor);
    // A fresh database snapshot and current authorization fact are read after buffering.
    var after = new CatalogProjection(catalogs.load(), visibility);
    if (!stamp.equals(after.accessStamp(id, variant))) throw PublicRequestRules.unavailable();
    return new Content(bytes, descriptor.contentType());
  }
}
