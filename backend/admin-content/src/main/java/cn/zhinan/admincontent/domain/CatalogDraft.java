package cn.zhinan.admincontent.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict M3 authoring model. It contains no publication authority or persistence types. */
public record CatalogDraft(
    Brand brand,
    Contact contact,
    List<TeamMember> team,
    List<Channel> channels,
    List<Business> businesses,
    List<Package> packages,
    List<String> featuredBusinessIds,
    List<String> featuredPackageIds,
    List<String> featuredWorkIds,
    Map<String, String> mediaAltTexts) {
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
  private static final List<String> BUSINESS_IDS =
      List.of("child", "family", "wedding", "portrait", "maternity", "id-photo");
  private static final List<String> BUSINESS_NAMES =
      List.of("儿童", "亲子全家福", "婚纱", "写真", "孕照", "证件照");

  public CatalogDraft {
    if (brand == null || contact == null) throw invalid("CATALOG_ROOT_REQUIRED");
    team = copy(team, "TEAM_REQUIRED");
    channels = copy(channels, "CHANNELS_REQUIRED");
    businesses = copy(businesses, "BUSINESSES_REQUIRED");
    packages = copy(packages, "PACKAGES_REQUIRED");
    featuredBusinessIds = copy(featuredBusinessIds, "FEATURED_BUSINESSES_REQUIRED");
    featuredPackageIds = copy(featuredPackageIds, "FEATURED_PACKAGES_REQUIRED");
    featuredWorkIds = copy(featuredWorkIds, "FEATURED_WORKS_REQUIRED");
    if (mediaAltTexts == null) throw invalid("MEDIA_ALT_TEXTS_REQUIRED");
    mediaAltTexts =
        java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(mediaAltTexts));
  }

  public static CatalogDraft empty() {
    var businesses =
        java.util.stream.IntStream.range(0, BUSINESS_IDS.size())
            .mapToObj(
                i -> new Business(BUSINESS_IDS.get(i), BUSINESS_NAMES.get(i), "", null, i, false))
            .toList();
    return new CatalogDraft(
        new Brand("", "", "", "", "", "", null),
        new Contact("", "", ""),
        List.of(),
        List.of(),
        businesses,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of());
  }

  public List<MediaReference> mediaReferences() {
    return mediaReferences(false);
  }

  public List<MediaReference> publishedMediaReferences() {
    return mediaReferences(true);
  }

  public PublicationSnapshot publicationSnapshot() {
    return new PublicationSnapshot(
        brand,
        contact,
        team.stream().filter(TeamMember::published).toList(),
        channels.stream().filter(Channel::published).toList(),
        businesses.stream().filter(Business::published).toList(),
        packages.stream().filter(Package::published).toList(),
        featuredBusinessIds,
        featuredPackageIds,
        featuredWorkIds);
  }

  private List<MediaReference> mediaReferences(boolean publishedOnly) {
    var refs = new ArrayList<MediaReference>();
    addRef(refs, brand.logoMediaId(), "BRAND", "brand", 0);
    team.stream()
        .filter(v -> !publishedOnly || v.published())
        .forEach(v -> addRef(refs, v.portraitMediaId(), "TEAM_MEMBER", v.id(), v.position()));
    channels.stream()
        .filter(v -> !publishedOnly || v.published())
        .forEach(v -> addRef(refs, v.qrMediaId(), "CONTACT_CHANNEL", v.id(), v.position()));
    businesses.stream()
        .filter(v -> !publishedOnly || v.published())
        .forEach(v -> addRef(refs, v.coverMediaId(), "BUSINESS", v.id(), 0));
    packages.stream()
        .filter(v -> !publishedOnly || v.published())
        .forEach(v -> addRef(refs, v.coverMediaId(), "PACKAGE", v.id(), 0));
    return List.copyOf(refs);
  }

  public record PublicationSnapshot(
      Brand brand,
      Contact contact,
      List<TeamMember> team,
      List<Channel> channels,
      List<Business> businesses,
      List<Package> packages,
      List<String> featuredBusinessIds,
      List<String> featuredPackageIds,
      List<String> featuredWorkIds) {
    public PublicationSnapshot {
      team = List.copyOf(team);
      channels = List.copyOf(channels);
      businesses = List.copyOf(businesses);
      packages = List.copyOf(packages);
      featuredBusinessIds = List.copyOf(featuredBusinessIds);
      featuredPackageIds = List.copyOf(featuredPackageIds);
      featuredWorkIds = List.copyOf(featuredWorkIds);
    }
  }

  private void addRef(
      List<MediaReference> refs, String mediaId, String ownerType, String ownerId, int position) {
    if (mediaId != null)
      refs.add(
          new MediaReference(mediaId, mediaAltTexts.get(mediaId), ownerType, ownerId, position));
  }

  public record MediaReference(
      String mediaId, String altText, String ownerType, String ownerId, int position) {}

  public boolean hasMediaReferences() {
    return !mediaReferences().isEmpty();
  }

  /** Structural validation for an editable draft. Publish-specific completeness is separate. */
  public CatalogDraft validated() {
    brand.validate();
    contact.validate();
    if (team.size() > 50 || channels.size() > 30 || packages.size() > 200)
      throw invalid("CATALOG_COLLECTION_LIMIT");
    requireUnique(team.stream().map(TeamMember::id).toList(), "TEAM_ID_DUPLICATE");
    requireUnique(channels.stream().map(Channel::id).toList(), "CHANNEL_ID_DUPLICATE");
    requireUnique(packages.stream().map(Package::id).toList(), "PACKAGE_ID_DUPLICATE");
    requireUniquePositions(
        team.stream().map(TeamMember::position).toList(), "TEAM_POSITION_DUPLICATE");
    requireUniquePositions(
        channels.stream().map(Channel::position).toList(), "CHANNEL_POSITION_DUPLICATE");
    requireUniquePositions(
        packages.stream().map(Package::position).toList(), "PACKAGE_POSITION_DUPLICATE");
    if (businesses.size() != BUSINESS_IDS.size()) throw invalid("BUSINESS_SET_INCOMPLETE");
    var byBusiness = new java.util.HashMap<String, Business>();
    Set<String> businessIds = new HashSet<>();
    Set<Integer> businessPositions = new HashSet<>();
    for (var businessValue : businesses) {
      var business = businessValue.validate();
      if (!BUSINESS_IDS.contains(business.id())
          || !businessIds.add(business.id())
          || !businessPositions.add(business.position()))
        throw invalid("BUSINESS_IDENTITY_INVALID");
      byBusiness.put(business.id(), business);
    }
    if (!businessIds.equals(Set.copyOf(BUSINESS_IDS))) throw invalid("BUSINESS_SET_INCOMPLETE");
    for (var member : team) member.validate();
    for (var channel : channels) channel.validate();
    for (var item : packages) {
      item.validate();
      var business = byBusiness.get(item.businessId());
      if (business == null || (item.published() && !business.published()))
        throw invalid("PACKAGE_BUSINESS_UNAVAILABLE");
    }
    Set<String> referencedMedia =
        mediaReferences().stream()
            .map(MediaReference::mediaId)
            .collect(java.util.stream.Collectors.toSet());
    if (!referencedMedia.equals(mediaAltTexts.keySet())) throw invalid("MEDIA_ALT_SET_MISMATCH");
    mediaAltTexts.forEach(
        (id, alt) -> {
          requireId(id);
          if (alt == null || alt.isBlank() || alt.length() > 120)
            throw invalid("MEDIA_ALT_INVALID");
        });
    requireUnique(featuredBusinessIds, "FEATURED_BUSINESS_DUPLICATE");
    requireUnique(featuredPackageIds, "FEATURED_PACKAGE_DUPLICATE");
    requireUnique(featuredWorkIds, "FEATURED_WORK_DUPLICATE");
    featuredWorkIds.forEach(CatalogDraft::requireId);
    if (featuredBusinessIds.size() > 3
        || featuredPackageIds.size() > 3
        || featuredWorkIds.size() > 6) throw invalid("HOME_FEATURE_LIMIT");
    for (String id : featuredBusinessIds)
      if (businesses.stream().noneMatch(b -> b.id().equals(id) && b.published()))
        throw invalid("HOME_BUSINESS_UNAVAILABLE");
    for (String id : featuredPackageIds)
      if (packages.stream().noneMatch(p -> p.id().equals(id) && p.published()))
        throw invalid("HOME_PACKAGE_UNAVAILABLE");
    return this;
  }

  /** Validate the complete snapshot before it becomes public. */
  public CatalogDraft publishable() {
    validated();
    brand.require("BRAND_REQUIRED");
    if (brand.name().isBlank() || brand.englishName().isBlank() || brand.shareTitle().isBlank())
      throw invalid("BRAND_REQUIRED");
    boolean hasContact =
        !contact.phone().isBlank()
            || !contact.address().isBlank()
            || channels.stream().anyMatch(c -> c.published() && c.qrStatus().equals("CONFIRMED"));
    if (!hasContact) throw invalid("CONTACT_CHANNEL_REQUIRED");
    if (businesses.stream().noneMatch(Business::published)) throw invalid("BUSINESS_REQUIRED");
    return this;
  }

  private static <T> List<T> copy(List<T> values, String reason) {
    if (values == null) throw invalid(reason);
    return List.copyOf(values);
  }

  private static void requireUnique(List<String> values, String reason) {
    if (new HashSet<>(values).size() != values.size()) throw invalid(reason);
  }

  private static void requireUniquePositions(List<Integer> positions, String reason) {
    if (new HashSet<>(positions).size() != positions.size()) throw invalid(reason);
  }

  private static void requireId(String value) {
    if (value == null || !ID.matcher(value).matches()) throw invalid("ID_INVALID");
  }

  private static void text(String value, int max, String reason) {
    if (value == null || value.length() > max) throw invalid(reason);
  }

  private static ContentException invalid(String reason) {
    return new ContentException(ContentFailure.INVALID_REQUEST, reason);
  }

  public record Brand(
      String name,
      String englishName,
      String discoveryHeading,
      String shareTitle,
      String tagline,
      String description,
      String logoMediaId) {
    void validate() {
      text(name, 255, "BRAND_FIELD_INVALID");
      text(englishName, 255, "BRAND_FIELD_INVALID");
      text(discoveryHeading, 255, "BRAND_FIELD_INVALID");
      text(shareTitle, 255, "BRAND_FIELD_INVALID");
      text(tagline, 1024, "BRAND_FIELD_INVALID");
      text(description, 16000, "BRAND_FIELD_INVALID");
      if (logoMediaId != null) requireId(logoMediaId);
    }

    void require(String reason) {
      if (name.isBlank()) throw invalid(reason);
    }
  }

  public record Contact(String phone, String address, String workingHours) {
    void validate() {
      text(phone, 64, "CONTACT_FIELD_INVALID");
      text(address, 1024, "CONTACT_FIELD_INVALID");
      text(workingHours, 1024, "CONTACT_FIELD_INVALID");
    }
  }

  public record TeamMember(
      String id,
      String name,
      String role,
      String bio,
      List<String> specialties,
      String portraitMediaId,
      int position,
      boolean published) {
    public TeamMember {
      specialties = copy(specialties, "SPECIALTIES_REQUIRED");
    }

    TeamMember validate() {
      requireId(id);
      text(name, 255, "TEAM_FIELD_INVALID");
      text(role, 255, "TEAM_FIELD_INVALID");
      text(bio, 12000, "TEAM_FIELD_INVALID");
      if (portraitMediaId != null) requireId(portraitMediaId);
      if (position < 0 || specialties.size() > 20) throw invalid("TEAM_FIELD_INVALID");
      specialties.forEach(v -> text(v, 255, "SPECIALTY_INVALID"));
      return this;
    }
  }

  public record Channel(
      String id,
      String platform,
      String label,
      String account,
      String qrMediaId,
      String qrStatus,
      int position,
      boolean published) {
    Channel validate() {
      requireId(id);
      text(label, 255, "CHANNEL_FIELD_INVALID");
      text(account, 255, "CHANNEL_FIELD_INVALID");
      if (platform == null
          || qrStatus == null
          || !List.of("wechat", "douyin", "xiaohongshu").contains(platform)
          || !List.of("CONFIRMED", "PLACEHOLDER", "UNCONFIGURED").contains(qrStatus)
          || position < 0
          || (qrMediaId != null && !ID.matcher(qrMediaId).matches()))
        throw invalid("CHANNEL_FIELD_INVALID");
      if (published && (!"CONFIRMED".equals(qrStatus) || label.isBlank() || account.isBlank()))
        throw invalid("CHANNEL_UNCONFIRMED");
      return this;
    }
  }

  public record Business(
      String id,
      String name,
      String summary,
      String coverMediaId,
      int position,
      boolean published) {
    Business validate() {
      requireId(id);
      text(name, 255, "BUSINESS_FIELD_INVALID");
      text(summary, 12000, "BUSINESS_FIELD_INVALID");
      if (coverMediaId != null) requireId(coverMediaId);
      if (position < 0 || (published && name.isBlank())) throw invalid("BUSINESS_FIELD_INVALID");
      return this;
    }
  }

  public record Package(
      String id,
      String businessId,
      String name,
      String summary,
      String suitableFor,
      Integer durationMinutes,
      String displayPrice,
      String priceType,
      List<String> serviceItems,
      List<String> tips,
      List<String> notices,
      boolean published,
      String coverMediaId,
      int position) {
    public Package {
      serviceItems = copy(serviceItems, "SERVICE_ITEMS_REQUIRED");
      tips = copy(tips, "TIPS_REQUIRED");
      notices = copy(notices, "NOTICES_REQUIRED");
    }

    Package validate() {
      requireId(id);
      requireId(businessId);
      text(name, 255, "PACKAGE_FIELD_INVALID");
      text(summary, 12000, "PACKAGE_FIELD_INVALID");
      text(suitableFor, 4000, "PACKAGE_FIELD_INVALID");
      text(displayPrice, 255, "PRICE_INVALID");
      if (durationMinutes != null && durationMinutes <= 0) throw invalid("DURATION_INVALID");
      if (priceType == null
          || !List.of("FIXED", "FROM", "INQUIRY", "UNDISCLOSED").contains(priceType))
        throw invalid("PRICE_TYPE_INVALID");
      if (("FIXED".equals(priceType) || "FROM".equals(priceType)) && displayPrice.isBlank())
        throw invalid("PRICE_REQUIRED");
      if (("INQUIRY".equals(priceType) || "UNDISCLOSED".equals(priceType))
          && !displayPrice.isBlank()) throw invalid("PRICE_MUST_NOT_CONTAIN_AMOUNT");
      if (serviceItems.size() > 30 || tips.size() > 30 || notices.size() > 30 || position < 0)
        throw invalid("PACKAGE_COLLECTION_LIMIT");
      serviceItems.forEach(v -> text(v, 2000, "SERVICE_ITEM_INVALID"));
      tips.forEach(v -> text(v, 2000, "TIP_INVALID"));
      notices.forEach(v -> text(v, 2000, "NOTICE_INVALID"));
      if (coverMediaId != null) requireId(coverMediaId);
      if (published && (name.isBlank() || summary.isBlank() || suitableFor.isBlank()))
        throw invalid("PACKAGE_REQUIRED_FIELDS");
      return this;
    }
  }
}
