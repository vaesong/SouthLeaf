package cn.zhinan.uniapp.publicquery.application;

import cn.zhinan.uniapp.publicquery.PublicQueryException;
import cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot;
import cn.zhinan.uniapp.publicquery.port.PublishedCatalogRepository;
import java.util.Set;

public class CatalogReadService {
  private final PublishedCatalogRepository repository;
  private final String mode;

  public CatalogReadService(PublishedCatalogRepository repository, String mode) {
    if (!Set.of("demo", "production").contains(mode))
      throw new IllegalArgumentException("Explicit public mode required");
    this.repository = repository;
    this.mode = mode;
  }

  public CatalogSnapshot load() {
    CatalogSnapshot snapshot = repository.readCurrent();
    if (!mode.equals(snapshot.dataMode())
        || ("production".equals(mode) && !"APPROVED".equals(snapshot.approvalStatus()))) {
      throw new PublicQueryException(
          PublicQueryException.ErrorCode.PUBLIC_SERVICE_UNAVAILABLE,
          "Publication mode/approval mismatch");
    }
    return snapshot;
  }
}
