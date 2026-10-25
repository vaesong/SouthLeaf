package cn.zhinan.uniapp.publicquery.application;

import static cn.zhinan.uniapp.publicquery.contract.PublicRequestRules.*;

import cn.zhinan.uniapp.dto.PublicDtos;
import cn.zhinan.uniapp.publicquery.contract.PublicPage;
import cn.zhinan.uniapp.publicquery.contract.PublicRequestRules;
import cn.zhinan.uniapp.works.application.PublicVisibilityPolicy;
import java.time.Clock;
import java.util.List;

public class PublicQueryService {
  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 50;
  private final CatalogReadService catalogs;
  private final PublicVisibilityPolicy visibility;
  private final Clock clock;

  public PublicQueryService(
      CatalogReadService catalogs, PublicVisibilityPolicy visibility, Clock clock) {
    this.catalogs = catalogs;
    this.visibility = visibility;
    this.clock = clock;
  }

  public record QueryResult<T>(T data, PublicDtos.Meta meta) {}

  private CatalogProjection projection() {
    return new CatalogProjection(catalogs.load(), visibility);
  }

  private <T> QueryResult<T> result(CatalogProjection p, T data, String next) {
    return new QueryResult<>(
        data,
        new PublicDtos.Meta(
            clock.instant(), p.snapshot().contentVersion(), next, p.snapshot().dataMode()));
  }

  public QueryResult<PublicDtos.Home> readHome() {
    var p = projection();
    var visibleWorks = p.visibleWorks(null, "recommended");
    var selectedWorks =
        p.snapshot().featuredWorks().isEmpty()
            ? visibleWorks
            : p.snapshot().featuredWorks().stream()
                .flatMap(id -> visibleWorks.stream().filter(work -> work.id().equals(id)).limit(1))
                .toList();
    var works = selectedWorks.stream().limit(DEFAULT_LIMIT).map(p::summary).toList();
    var heroes =
        works.stream()
            .limit(3)
            .map(
                w ->
                    new PublicDtos.HeroItem(
                        w.id(),
                        w.title(),
                        "demo".equals(p.snapshot().dataMode()) ? "DEMO / 展示样例" : "公开作品",
                        "浏览作品",
                        w.coverMedia(),
                        "work",
                        w.id()))
            .toList();
    var businesses =
        p.snapshot().featuredBusinesses().stream()
            .limit(3)
            .map(
                id ->
                    p.snapshot().businesses().stream()
                        .filter(v -> v.id().equals(id))
                        .findFirst()
                        .orElseThrow())
            .map(p::business)
            .toList();
    var packages =
        p.snapshot().featuredPackages().stream()
            .limit(3)
            .map(
                id ->
                    p.packages(null).stream()
                        .filter(v -> v.id().equals(id))
                        .findFirst()
                        .orElseThrow())
            .map(p::summary)
            .toList();
    return result(
        p, new PublicDtos.Home(p.brand(), heroes, works, businesses, packages, p.contact()), null);
  }

  public QueryResult<PublicDtos.Brand> readBrand() {
    var p = projection();
    return result(p, p.brand(), null);
  }

  public QueryResult<PublicPage<PublicDtos.WorkSummary>> readWorks(
      String businessId, String sort, Integer limit, String cursor) {
    optionalId(businessId);
    int size = limit(limit);
    String order = sort == null || sort.isBlank() ? "recommended" : sort;
    if (!List.of("recommended", "newest", "oldest").contains(order)) throw invalid();
    var p = projection();
    var page =
        VersionedCursor.page(
            p.visibleWorks(businessId, order).stream().map(p::summary).toList(),
            size,
            cursor,
            p.snapshot().contentVersion(),
            "works:" + businessId + ":" + order,
            PublicDtos.WorkSummary::id);
    return result(p, page, page.nextCursor());
  }

  public QueryResult<PublicDtos.WorkDetail> readWork(String workId) {
    requiredId(workId);
    var p = projection();
    return result(p, p.detail(p.work(workId).orElseThrow(PublicRequestRules::unavailable)), null);
  }

  public QueryResult<PublicPage<PublicDtos.BusinessSummary>> readBusinesses() {
    var p = projection();
    return result(
        p,
        new PublicPage<>(p.snapshot().businesses().stream().map(p::business).toList(), null),
        null);
  }

  public QueryResult<PublicDtos.BusinessDetail> readBusiness(String id) {
    requiredId(id);
    var p = projection();
    var business =
        p.snapshot().businesses().stream()
            .filter(v -> v.id().equals(id))
            .findFirst()
            .orElseThrow(PublicRequestRules::unavailable);
    var packages = p.packages(id).stream().map(p::summary).toList();
    return result(
        p,
        new PublicDtos.BusinessDetail(
            business.id(),
            business.name(),
            business.summary(),
            p.business(business).coverMedia(),
            !packages.isEmpty(),
            packages.size(),
            packages),
        null);
  }

  public QueryResult<PublicPage<PublicDtos.PackageSummary>> readPackages(
      String businessId, Integer limit, String cursor) {
    optionalId(businessId);
    int size = limit(limit);
    var p = projection();
    var page =
        VersionedCursor.page(
            p.packages(businessId).stream().map(p::summary).toList(),
            size,
            cursor,
            p.snapshot().contentVersion(),
            "packages:" + businessId,
            PublicDtos.PackageSummary::id);
    return result(p, page, page.nextCursor());
  }

  public QueryResult<PublicDtos.PackageDetail> readPackage(String id) {
    requiredId(id);
    var p = projection();
    return result(
        p,
        p.detail(
            p.packages(null).stream()
                .filter(v -> v.id().equals(id))
                .findFirst()
                .orElseThrow(PublicRequestRules::unavailable)),
        null);
  }

  public QueryResult<PublicDtos.Media> readMedia(String id, String variant) {
    requiredId(id);
    variant(variant);
    var p = projection();
    return result(p, p.media(null, id, variant).orElseThrow(PublicRequestRules::unavailable), null);
  }

  private static void optionalId(String id) {
    if (id != null) requiredId(id);
  }

  private static int limit(Integer value) {
    if (value == null) return DEFAULT_LIMIT;
    if (value < 1 || value > MAX_LIMIT) throw invalid();
    return value;
  }
}
