package cn.zhinan.uniapp.publicquery.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Immutable read-side publication. No transport DTOs, database types or file I/O. */
public record CatalogSnapshot(
    String releaseId,
    String contentVersion,
    String dataMode,
    String approvalStatus,
    Brand brand,
    Contact contact,
    List<Business> businesses,
    List<Package> packages,
    List<Work> works,
    Map<String, Asset> assets,
    List<Binding> bindings,
    List<String> featuredBusinesses,
    List<String> featuredPackages,
    List<String> featuredWorks,
    Map<String, String> overlayReleases) {
  /** V1-only snapshot: no S2 per-work overlay entries. */
  public CatalogSnapshot(
      String releaseId,
      String contentVersion,
      String dataMode,
      String approvalStatus,
      Brand brand,
      Contact contact,
      List<Business> businesses,
      List<Package> packages,
      List<Work> works,
      Map<String, Asset> assets,
      List<Binding> bindings,
      List<String> featuredBusinesses,
      List<String> featuredPackages) {
    this(
        releaseId,
        contentVersion,
        dataMode,
        approvalStatus,
        brand,
        contact,
        businesses,
        packages,
        works,
        assets,
        bindings,
        featuredBusinesses,
        featuredPackages,
        List.of(),
        Map.of());
  }

  /** Existing callers keep the V1/S2 default ordering until a V3 snapshot curates featured work. */
  public CatalogSnapshot(
      String releaseId,
      String contentVersion,
      String dataMode,
      String approvalStatus,
      Brand brand,
      Contact contact,
      List<Business> businesses,
      List<Package> packages,
      List<Work> works,
      Map<String, Asset> assets,
      List<Binding> bindings,
      List<String> featuredBusinesses,
      List<String> featuredPackages,
      Map<String, String> overlayReleases) {
    this(
        releaseId,
        contentVersion,
        dataMode,
        approvalStatus,
        brand,
        contact,
        businesses,
        packages,
        works,
        assets,
        bindings,
        featuredBusinesses,
        featuredPackages,
        List.of(),
        overlayReleases);
  }

  public CatalogSnapshot {
    featuredWorks = List.copyOf(featuredWorks);
    overlayReleases = Map.copyOf(overlayReleases);
    businesses = List.copyOf(businesses);
    packages = List.copyOf(packages);
    works = List.copyOf(works);
    assets = Map.copyOf(assets);
    bindings = List.copyOf(bindings);
    featuredBusinesses = List.copyOf(featuredBusinesses);
    featuredPackages = List.copyOf(featuredPackages);
  }

  public record Brand(
      String name,
      String englishName,
      String discoveryHeading,
      String shareTitle,
      String tagline,
      String description,
      String logoMediaId,
      List<TeamMember> team) {
    public Brand {
      team = List.copyOf(team);
    }

    public Brand(
        String name,
        String englishName,
        String discoveryHeading,
        String shareTitle,
        String tagline,
        String description,
        List<TeamMember> team) {
      this(name, englishName, discoveryHeading, shareTitle, tagline, description, null, team);
    }
  }

  public record TeamMember(
      String id,
      String name,
      String role,
      String bio,
      List<String> specialties,
      String portraitMediaId) {
    public TeamMember {
      specialties = List.copyOf(specialties);
    }
  }

  public record Contact(String phone, String address, String workingHours, List<Channel> channels) {
    public Contact {
      channels = List.copyOf(channels);
    }
  }

  public record Channel(
      String id,
      String platform,
      String label,
      String account,
      String qrCodeMediaId,
      String qrCodeStatus) {}

  public record Business(String id, String name, String summary, String coverMediaId) {}

  public record Package(
      String id,
      String businessId,
      String name,
      String summary,
      List<String> serviceItems,
      String suitableFor,
      Integer durationMinutes,
      String displayPrice,
      String priceType,
      List<String> tips,
      List<String> notices,
      boolean published,
      Instant publishedAt,
      String coverMediaId) {
    public Package {
      serviceItems = List.copyOf(serviceItems);
      tips = List.copyOf(tips);
      notices = List.copyOf(notices);
    }
  }

  public record Work(
      String id,
      String title,
      String summary,
      String description,
      List<String> businessIds,
      String packageLabel,
      String publicationStatus,
      Instant publishedAt,
      String coverMediaId,
      List<String> mediaIds,
      String candidateVersion,
      String bindingVersion,
      String subjectScopeRef) {
    public Work {
      businessIds = List.copyOf(businessIds);
      mediaIds = List.copyOf(mediaIds);
    }
  }

  public record Asset(String id, String altText, Map<String, Variant> variants) {
    public Asset {
      variants = Map.copyOf(variants);
    }
  }

  public record Variant(
      String objectKey,
      String sha256,
      long byteLength,
      int width,
      int height,
      String contentType) {}

  public record Binding(String ownerType, String ownerId, String mediaId, int position) {}
}
