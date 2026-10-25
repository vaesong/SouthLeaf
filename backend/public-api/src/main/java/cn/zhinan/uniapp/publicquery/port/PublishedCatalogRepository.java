package cn.zhinan.uniapp.publicquery.port;

import cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot;

public interface PublishedCatalogRepository {
  CatalogSnapshot readCurrent();
}
