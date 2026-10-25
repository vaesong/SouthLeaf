package cn.zhinan.uniapp.publicquery.application;

import cn.zhinan.uniapp.dto.PublicDtos;
import cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot;
import cn.zhinan.uniapp.works.application.PublicVisibilityPolicy;
import java.util.*;

/** Request-local projection; only qualification decisions within this one read are memoized. */
public final class CatalogProjection {
  private final CatalogSnapshot catalog;
  private final PublicVisibilityPolicy policy;
  private final Map<String, PublicVisibilityPolicy.Decision> decisions = new HashMap<>();

  public CatalogProjection(CatalogSnapshot catalog, PublicVisibilityPolicy policy) {
    this.catalog = catalog;
    this.policy = policy;
  }

  public CatalogSnapshot snapshot() {
    return catalog;
  }

  public PublicVisibilityPolicy.Decision decision(CatalogSnapshot.Work work) {
    return decisions.computeIfAbsent(work.id(), id -> policy.evaluate(catalog, work));
  }

  public List<CatalogSnapshot.Work> visibleWorks(String businessId, String sort) {
    var candidates =
        catalog.works().stream()
            .filter(w -> businessId == null || w.businessIds().contains(businessId))
            .toList();
    decisions.putAll(policy.evaluateAll(catalog, candidates));
    var stream = candidates.stream().filter(w -> decisions.get(w.id()).allowed());
    var order =
        Comparator.comparing(CatalogSnapshot.Work::publishedAt)
            .thenComparing(CatalogSnapshot.Work::id);
    return switch (sort) {
      case "newest" -> stream.sorted(order.reversed()).toList();
      case "oldest" -> stream.sorted(order).toList();
      default -> stream.toList();
    };
  }

  public Optional<CatalogSnapshot.Work> work(String id) {
    return catalog.works().stream()
        .filter(w -> w.id().equals(id))
        .findFirst()
        .filter(w -> decision(w).allowed());
  }

  private boolean bound(CatalogSnapshot.Binding binding) {
    return switch (binding.ownerType()) {
      case "WORK" ->
          work(binding.ownerId()).map(w -> w.mediaIds().contains(binding.mediaId())).orElse(false);
      case "BUSINESS" ->
          catalog.businesses().stream()
              .anyMatch(
                  b ->
                      b.id().equals(binding.ownerId())
                          && binding.mediaId().equals(b.coverMediaId()));
      case "PACKAGE" ->
          catalog.packages().stream()
              .anyMatch(
                  p ->
                      p.published()
                          && p.id().equals(binding.ownerId())
                          && binding.mediaId().equals(p.coverMediaId()));
      case "TEAM_MEMBER" ->
          catalog.brand().team().stream()
              .anyMatch(
                  t ->
                      t.id().equals(binding.ownerId())
                          && binding.mediaId().equals(t.portraitMediaId()));
      case "CONTACT_CHANNEL" ->
          catalog.contact().channels().stream()
              .anyMatch(
                  c ->
                      c.id().equals(binding.ownerId())
                          && binding.mediaId().equals(c.qrCodeMediaId())
                          && ("CONFIRMED".equals(c.qrCodeStatus())
                              || ("demo".equals(catalog.dataMode())
                                  && "PLACEHOLDER".equals(c.qrCodeStatus()))));
      default -> false;
    };
  }

  public Optional<PublicDtos.Media> media(String owner, String id, String variant) {
    var asset = catalog.assets().get(id);
    if (asset == null || !asset.variants().containsKey(variant)) return Optional.empty();
    return catalog.bindings().stream()
        .filter(b -> b.mediaId().equals(id) && (owner == null || owner.equals(b.ownerId())))
        .filter(this::bound)
        .findFirst()
        .map(
            binding -> {
              var v = asset.variants().get(variant);
              String prefix = "/api/v1/public/media/" + asset.id();
              return new PublicDtos.Media(
                  asset.id(),
                  prefix + "/thumbnail/content",
                  prefix + "/preview/content",
                  v.width(),
                  v.height(),
                  asset.altText(),
                  binding.position());
            });
  }

  public String accessStamp(String mediaId, String variant) {
    if (media(null, mediaId, variant).isEmpty()) return null;
    StringBuilder stamp =
        new StringBuilder(catalog.contentVersion())
            .append(':')
            .append(catalog.assets().get(mediaId).variants().get(variant).sha256());
    catalog.bindings().stream()
        .filter(b -> b.mediaId().equals(mediaId) && "WORK".equals(b.ownerType()))
        .forEach(
            b ->
                work(b.ownerId()).ifPresent(w -> stamp.append(':').append(decision(w).revision())));
    return stamp.toString();
  }

  public PublicDtos.Contact contact() {
    var c = catalog.contact();
    return new PublicDtos.Contact(
        c.phone(),
        c.address(),
        c.workingHours(),
        c.channels().stream()
            .map(
                v ->
                    new PublicDtos.ContactChannel(
                        v.id(),
                        v.platform(),
                        v.label(),
                        v.account(),
                        media(v.id(), v.qrCodeMediaId(), "preview").orElse(null),
                        v.qrCodeStatus()))
            .toList());
  }

  public PublicDtos.Brand brand() {
    var b = catalog.brand();
    return new PublicDtos.Brand(
        b.name(),
        b.englishName(),
        b.discoveryHeading(),
        b.shareTitle(),
        b.tagline(),
        b.description(),
        null,
        contact(),
        b.team().stream()
            .map(
                t ->
                    new PublicDtos.TeamMember(
                        t.id(),
                        t.name(),
                        t.role(),
                        t.bio(),
                        t.specialties(),
                        media(t.id(), t.portraitMediaId(), "preview").orElse(null)))
            .toList());
  }

  public String businessName(String id) {
    return catalog.businesses().stream()
        .filter(b -> b.id().equals(id))
        .findFirst()
        .orElseThrow()
        .name();
  }

  public List<CatalogSnapshot.Package> packages(String businessId) {
    return catalog.packages().stream()
        .filter(CatalogSnapshot.Package::published)
        .filter(p -> businessId == null || p.businessId().equals(businessId))
        .toList();
  }

  public PublicDtos.BusinessSummary business(CatalogSnapshot.Business b) {
    int count = packages(b.id()).size();
    return new PublicDtos.BusinessSummary(
        b.id(),
        b.name(),
        b.summary(),
        media(b.id(), b.coverMediaId(), "preview").orElse(null),
        count > 0,
        count);
  }

  public PublicDtos.PackageSummary summary(CatalogSnapshot.Package p) {
    return new PublicDtos.PackageSummary(
        p.id(),
        p.businessId(),
        businessName(p.businessId()),
        p.name(),
        p.summary(),
        p.displayPrice(),
        p.priceType(),
        media(p.id(), p.coverMediaId(), "preview").orElse(null));
  }

  public PublicDtos.PackageDetail detail(CatalogSnapshot.Package p) {
    return new PublicDtos.PackageDetail(
        p.id(),
        p.businessId(),
        businessName(p.businessId()),
        p.name(),
        p.summary(),
        p.serviceItems(),
        p.suitableFor(),
        p.durationMinutes(),
        p.displayPrice(),
        p.priceType(),
        media(p.id(), p.coverMediaId(), "preview").orElse(null),
        p.tips(),
        p.notices(),
        contact(),
        p.publishedAt());
  }

  public PublicDtos.WorkSummary summary(CatalogSnapshot.Work w) {
    return new PublicDtos.WorkSummary(
        w.id(),
        w.title(),
        w.summary(),
        media(w.id(), w.coverMediaId(), "preview").orElseThrow(),
        w.businessIds().stream().map(this::businessName).toList(),
        w.publishedAt());
  }

  public PublicDtos.WorkDetail detail(CatalogSnapshot.Work w) {
    return new PublicDtos.WorkDetail(
        w.id(),
        w.title(),
        w.summary(),
        w.description(),
        w.mediaIds().stream().map(id -> media(w.id(), id, "preview").orElseThrow()).toList(),
        w.businessIds().stream().map(this::businessName).toList(),
        w.packageLabel(),
        contact(),
        w.publishedAt());
  }
}
