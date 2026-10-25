package cn.zhinan.uniapp.publicquery.adapter.jdbc;

import cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot;
import cn.zhinan.uniapp.publicquery.domain.CatalogSnapshot.*;
import cn.zhinan.uniapp.publicquery.port.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One immutable published aggregate is cached; the active database pointer is always checked. */
@Repository
public class JdbcPublishedCatalogRepository implements PublishedCatalogRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate reads;
  private final AtomicReference<CatalogSnapshot> cached = new AtomicReference<>();
  private final AtomicReference<Merged> merged = new AtomicReference<>();

  /** Cache key for V1 + S2 overlay: the exact V1 snapshot instance and the projection epoch. */
  private record Merged(
      CatalogSnapshot v1, long workEpoch, long catalogEpoch, CatalogSnapshot snapshot) {}

  public JdbcPublishedCatalogRepository(
      JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.reads = new TransactionTemplate(transactions);
    reads.setReadOnly(true);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  @Override
  public CatalogSnapshot readCurrent() {
    try {
      return reads.execute(
          status -> {
            var release =
                jdbc.queryForMap(
                    "SELECT r.* FROM active_publication a JOIN catalog_release r ON r.id=a.release_id WHERE a.singleton_id=1");
            var existing = cached.get();
            if (existing != null
                && existing.releaseId().equals(str(release, "id"))
                && existing.contentVersion().equals(str(release, "content_version"))
                && existing.dataMode().equals(str(release, "data_mode"))
                && existing.approvalStatus().equals(str(release, "approval_status")))
              return withOverlay(existing);
            CatalogSnapshot v1 = existing;
            if (v1 == null
                || !v1.releaseId().equals(str(release, "id"))
                || !v1.contentVersion().equals(str(release, "content_version"))
                || !v1.dataMode().equals(str(release, "data_mode"))
                || !v1.approvalStatus().equals(str(release, "approval_status"))) {
              v1 = load(release);
              cached.set(v1);
            }
            return withOverlay(v1);
          });
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException ex) {
      throw new CatalogStorageException("Published database catalog unavailable", ex);
    }
  }

  /**
   * Same REPEATABLE READ transaction as the V1 pointer read. The epoch is bumped in the same
   * transaction as every overlay pointer change, so (V1 snapshot, epoch) fully determines the
   * merged result. Epoch 0 means no S2 publication ever happened: the V1 snapshot is unchanged.
   */
  private CatalogSnapshot withOverlay(CatalogSnapshot v1) {
    Long workEpoch =
        jdbc.queryForObject(
            "SELECT epoch FROM public_work_projection_epoch WHERE singleton_id=1", Long.class);
    Long catalogEpoch =
        jdbc.queryForObject(
            "SELECT epoch FROM active_catalog_publication WHERE singleton_id=1", Long.class);
    if (workEpoch == null || workEpoch < 0 || catalogEpoch == null || catalogEpoch < 0)
      throw new CatalogStorageException("Catalog projection epoch unavailable", null);
    if (workEpoch == 0 && catalogEpoch == 0) return v1;
    var current = merged.get();
    if (current != null
        && current.v1() == v1
        && current.workEpoch() == workEpoch
        && current.catalogEpoch() == catalogEpoch) return current.snapshot();
    CatalogSnapshot snapshot = workEpoch == 0 ? v1 : merge(v1, workEpoch);
    if (catalogEpoch > 0) snapshot = mergeCatalog(snapshot, catalogEpoch);
    merged.set(new Merged(v1, workEpoch, catalogEpoch, snapshot));
    return snapshot;
  }

  /**
   * V3 becomes authoritative after its first publication; an invisible pointer never falls back to
   * V1.
   */
  @SuppressWarnings("unchecked")
  private CatalogSnapshot mergeCatalog(CatalogSnapshot base, long epoch) {
    var pointers =
        jdbc.queryForList(
            "SELECT release_id FROM active_catalog_publication "
                + "WHERE singleton_id=1 AND visible=1");
    if (pointers.isEmpty())
      return new CatalogSnapshot(
          base.releaseId(),
          base.contentVersion() + "+c" + epoch,
          base.dataMode(),
          base.approvalStatus(),
          new Brand("", "", "", "", "", "", List.of()),
          new Contact("", "", "", List.of()),
          List.of(),
          List.of(),
          base.works(),
          base.assets(),
          base.bindings(),
          List.of(),
          List.of(),
          base.overlayReleases());
    String releaseId = (String) pointers.getFirst().get("release_id");
    var rows =
        jdbc.queryForList(
            "SELECT CAST(snapshot_json AS CHAR) AS snapshot_json,content_version,data_mode,published_at "
                + "FROM public_catalog_release WHERE release_id=?",
            releaseId);
    if (rows.isEmpty())
      throw new CatalogStorageException("Active catalog release unavailable", null);
    var row = rows.getFirst();
    final Map<String, Object> root;
    try {
      root =
          new tools.jackson.databind.json.JsonMapper()
              .readValue((String) row.get("snapshot_json"), Map.class);
    } catch (tools.jackson.core.JacksonException malformed) {
      throw new CatalogStorageException("Active catalog release is invalid", malformed);
    }
    var brand = (Map<String, Object>) root.get("brand");
    var contact = (Map<String, Object>) root.get("contact");
    var teamRows = (List<Map<String, Object>>) root.get("team");
    var channelRows = (List<Map<String, Object>>) root.get("channels");
    var businessRows = (List<Map<String, Object>>) root.get("businesses");
    var packageRows = (List<Map<String, Object>>) root.get("packages");
    var team =
        teamRows.stream()
            .filter(JdbcPublishedCatalogRepository::published)
            .map(
                v ->
                    new TeamMember(
                        text(v, "id"),
                        text(v, "name"),
                        text(v, "role"),
                        text(v, "bio"),
                        strings(v.get("specialties")),
                        nullableText(v.get("portraitMediaId"))))
            .toList();
    var channels =
        channelRows.stream()
            .filter(JdbcPublishedCatalogRepository::published)
            .map(
                v ->
                    new Channel(
                        text(v, "id"),
                        text(v, "platform"),
                        text(v, "label"),
                        text(v, "account"),
                        nullableText(v.get("qrMediaId")),
                        text(v, "qrStatus")))
            .toList();
    var businesses =
        businessRows.stream()
            .filter(JdbcPublishedCatalogRepository::published)
            .map(
                v ->
                    new Business(
                        text(v, "id"),
                        text(v, "name"),
                        text(v, "summary"),
                        nullableText(v.get("coverMediaId"))))
            .toList();
    var publishedAt =
        ((java.time.LocalDateTime) row.get("published_at")).toInstant(java.time.ZoneOffset.UTC);
    var packages =
        packageRows.stream()
            .filter(JdbcPublishedCatalogRepository::published)
            .map(
                v ->
                    new CatalogSnapshot.Package(
                        text(v, "id"),
                        text(v, "businessId"),
                        text(v, "name"),
                        text(v, "summary"),
                        strings(v.get("serviceItems")),
                        text(v, "suitableFor"),
                        v.get("durationMinutes") == null
                            ? null
                            : ((Number) v.get("durationMinutes")).intValue(),
                        text(v, "displayPrice"),
                        text(v, "priceType"),
                        strings(v.get("tips")),
                        strings(v.get("notices")),
                        true,
                        publishedAt,
                        nullableText(v.get("coverMediaId"))))
            .toList();
    Map<String, Asset> assets = new LinkedHashMap<>(base.assets());
    var mediaRows =
        jdbc.queryForList(
            "SELECT media_id,alt_text FROM public_catalog_release_media WHERE release_id=? ORDER BY media_id",
            releaseId);
    var variantRows =
        jdbc.queryForList(
            "SELECT media_id,variant,object_key,sha256,byte_length,width,height,content_type "
                + "FROM public_catalog_release_variant WHERE release_id=? ORDER BY media_id,variant",
            releaseId);
    for (var media : mediaRows) {
      String mediaId = str(media, "media_id");
      Map<String, Variant> variants = new LinkedHashMap<>();
      for (var variant : variantRows)
        if (mediaId.equals(str(variant, "media_id")))
          variants.put(
              str(variant, "variant"),
              new Variant(
                  str(variant, "object_key"),
                  str(variant, "sha256"),
                  ((Number) variant.get("byte_length")).longValue(),
                  integer(variant, "width"),
                  integer(variant, "height"),
                  str(variant, "content_type")));
      if (!variants.keySet().equals(Set.of("thumbnail", "preview")))
        throw new CatalogStorageException("Catalog media projection is incomplete", null);
      Asset projected = new Asset(mediaId, str(media, "alt_text"), variants);
      Asset previous = assets.putIfAbsent(mediaId, projected);
      if (previous != null && !previous.equals(projected))
        throw new CatalogStorageException("Catalog media identifier collision", null);
    }
    List<Binding> bindings = new ArrayList<>(base.bindings());
    jdbc.queryForList(
            "SELECT owner_type,owner_id,media_id,position FROM public_catalog_release_binding WHERE release_id=? ORDER BY owner_type,owner_id,position",
            releaseId)
        .forEach(
            v ->
                bindings.add(
                    new Binding(
                        str(v, "owner_type"),
                        str(v, "owner_id"),
                        str(v, "media_id"),
                        integer(v, "position"))));
    String publicMode =
        switch (text(row, "data_mode")) {
          case "DEMO" -> "demo";
          case "PRODUCTION" -> "production";
          default -> throw new CatalogStorageException("Catalog release mode is invalid", null);
        };
    return new CatalogSnapshot(
        base.releaseId(),
        base.contentVersion() + "+c" + epoch,
        publicMode,
        base.approvalStatus(),
        new Brand(
            text(brand, "name"),
            text(brand, "englishName"),
            text(brand, "discoveryHeading"),
            text(brand, "shareTitle"),
            text(brand, "tagline"),
            text(brand, "description"),
            nullableText(brand.get("logoMediaId")),
            team),
        new Contact(
            text(contact, "phone"),
            text(contact, "address"),
            text(contact, "workingHours"),
            channels),
        businesses,
        packages,
        base.works(),
        assets,
        bindings,
        strings(root.get("featuredBusinessIds")),
        strings(root.get("featuredPackageIds")),
        strings(root.get("featuredWorkIds")),
        base.overlayReleases());
  }

  private static boolean published(Map<String, Object> row) {
    return Boolean.TRUE.equals(row.get("published"));
  }

  private static String text(Map<String, Object> row, String key) {
    return row.get(key) == null ? "" : String.valueOf(row.get(key));
  }

  private static String nullableText(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream().map(String::valueOf).toList();
  }

  private static final String ACTIVE =
      " JOIN active_work_publication a ON a.release_id=x.release_id AND a.visible=1 ";

  private CatalogSnapshot merge(CatalogSnapshot v1, long epoch) {
    var releases =
        jdbc.queryForList(
            "SELECT x.release_id,x.work_id,x.work_version,x.title,x.summary,x.cover_media_id,x.subject_scope_ref,x.data_mode,x.published_at FROM public_work_release x"
                + ACTIVE
                + "ORDER BY x.published_at DESC, x.work_id");
    if (releases.size() > 200)
      throw new CatalogStorageException("Overlay exceeds supported read-model capacity", null);
    var businessRows =
        jdbc.queryForList(
            "SELECT x.release_id,x.business_id FROM public_work_release_business x"
                + ACTIVE
                + "ORDER BY x.release_id,x.position");
    var mediaRows =
        jdbc.queryForList(
            "SELECT x.release_id,x.media_id,x.alt_text FROM public_work_release_media x"
                + ACTIVE
                + "ORDER BY x.release_id,x.position");
    var variantRows =
        jdbc.queryForList(
            "SELECT x.release_id,x.media_id,x.variant,x.object_key,x.sha256,x.byte_length,x.width,x.height,x.content_type FROM public_work_release_variant x"
                + ACTIVE
                + "ORDER BY x.release_id,x.media_id,x.variant");
    var v1WorkIds = new java.util.HashSet<String>();
    v1.works().forEach(w -> v1WorkIds.add(w.id()));
    Map<String, Asset> assets = new LinkedHashMap<>(v1.assets());
    List<Work> overlayWorks = new ArrayList<>();
    List<Binding> bindings = new ArrayList<>(v1.bindings());
    Map<String, String> overlayReleases = new LinkedHashMap<>();
    for (var r : releases) {
      String releaseId = str(r, "release_id");
      String workId = str(r, "work_id");
      // Fail-closed: a mode mismatch or an identifier collision makes the overlay work invisible.
      if (!v1.dataMode().equals(str(r, "data_mode")) || v1WorkIds.contains(workId)) {
        continue;
      }
      List<String> mediaIds = new ArrayList<>();
      Map<String, Asset> own = new LinkedHashMap<>();
      boolean consistent = true;
      for (var m : mediaRows) {
        if (!releaseId.equals(str(m, "release_id"))) continue;
        String mediaId = str(m, "media_id");
        Map<String, Variant> entries = new LinkedHashMap<>();
        for (var v : variantRows)
          if (releaseId.equals(str(v, "release_id")) && mediaId.equals(str(v, "media_id")))
            entries.put(
                str(v, "variant"),
                new Variant(
                    str(v, "object_key"),
                    str(v, "sha256"),
                    ((Number) v.get("byte_length")).longValue(),
                    integer(v, "width"),
                    integer(v, "height"),
                    str(v, "content_type")));
        var asset = new Asset(mediaId, str(m, "alt_text"), entries);
        var existing = assets.get(mediaId);
        if (!entries.keySet().equals(Set.of("thumbnail", "preview"))
            || (existing != null && !existing.equals(asset))) {
          consistent = false;
          break;
        }
        mediaIds.add(mediaId);
        own.put(mediaId, asset);
      }
      if (!consistent || mediaIds.isEmpty()) {
        continue;
      }
      assets.putAll(own);
      for (int i = 0; i < mediaIds.size(); i++)
        bindings.add(new Binding("WORK", workId, mediaIds.get(i), i));
      String summary = str(r, "summary");
      overlayWorks.add(
          new Work(
              workId,
              str(r, "title"),
              summary,
              summary,
              values(businessRows, "release_id", releaseId, "business_id"),
              // S2 overlay works carry no package association (S3 scope); the client contract
              // requires a string, so absence is an explicit empty label, never null.
              "",
              "published",
              instant(r, "published_at"),
              str(r, "cover_media_id"),
              mediaIds,
              String.valueOf(integer(r, "work_version")),
              releaseId,
              str(r, "subject_scope_ref")));
      overlayReleases.put(workId, releaseId);
    }
    List<Work> works = new ArrayList<>(overlayWorks);
    works.addAll(v1.works());
    return new CatalogSnapshot(
        v1.releaseId(),
        v1.contentVersion() + "+e" + epoch,
        v1.dataMode(),
        v1.approvalStatus(),
        v1.brand(),
        v1.contact(),
        v1.businesses(),
        v1.packages(),
        works,
        assets,
        bindings,
        v1.featuredBusinesses(),
        v1.featuredPackages(),
        v1.featuredWorks(),
        overlayReleases);
  }

  private List<Map<String, Object>> rows(String table, String release, String order) {
    // Table/order are private code constants, never client-controlled SQL fragments.
    return jdbc.queryForList(
        "SELECT * FROM " + table + " WHERE release_id=? ORDER BY " + order, release);
  }

  private static String str(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  private static int integer(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).intValue();
  }

  private static java.time.Instant instant(Map<String, Object> row, String key) {
    return ((java.time.LocalDateTime) row.get(key)).toInstant(java.time.ZoneOffset.UTC);
  }

  private static List<String> values(
      List<Map<String, Object>> rows, String ownerKey, String owner, String valueKey) {
    return rows.stream()
        .filter(r -> owner.equals(str(r, ownerKey)))
        .map(r -> str(r, valueKey))
        .toList();
  }

  private CatalogSnapshot load(Map<String, Object> release) {
    String id = str(release, "id");
    var skills = rows("team_specialty", id, "member_id,position");
    var team =
        rows("team_member", id, "position").stream()
            .map(
                r ->
                    new TeamMember(
                        str(r, "id"),
                        str(r, "name"),
                        str(r, "role"),
                        str(r, "bio"),
                        values(skills, "member_id", str(r, "id"), "label"),
                        str(r, "portrait_media_id")))
            .toList();
    var brandRow = jdbc.queryForMap("SELECT * FROM studio_brand WHERE release_id=?", id);
    var brand =
        new Brand(
            str(brandRow, "name"),
            str(brandRow, "english_name"),
            str(brandRow, "discovery_heading"),
            str(brandRow, "share_title"),
            str(brandRow, "tagline"),
            str(brandRow, "description"),
            team);
    var channels =
        rows("contact_channel", id, "position").stream()
            .map(
                r ->
                    new Channel(
                        str(r, "id"),
                        str(r, "platform"),
                        str(r, "label"),
                        str(r, "account"),
                        str(r, "qr_media_id"),
                        str(r, "qr_status")))
            .toList();
    var contactRow = jdbc.queryForMap("SELECT * FROM studio_contact WHERE release_id=?", id);
    var contact =
        new Contact(
            str(contactRow, "phone"),
            str(contactRow, "address"),
            str(contactRow, "working_hours"),
            channels);
    var businesses =
        rows("photography_business", id, "position").stream()
            .map(
                r ->
                    new Business(
                        str(r, "id"), str(r, "name"), str(r, "summary"), str(r, "cover_media_id")))
            .toList();
    var lines = rows("package_line", id, "package_id,kind,position");
    var packages =
        rows("catalog_package", id, "position").stream()
            .map(
                r -> {
                  String key = str(r, "id");
                  return new CatalogSnapshot.Package(
                      key,
                      str(r, "business_id"),
                      str(r, "name"),
                      str(r, "summary"),
                      packageLines(lines, key, "SERVICE"),
                      str(r, "suitable_for"),
                      r.get("duration_minutes") == null ? null : integer(r, "duration_minutes"),
                      str(r, "display_price"),
                      str(r, "price_type"),
                      packageLines(lines, key, "TIP"),
                      packageLines(lines, key, "NOTICE"),
                      Boolean.TRUE.equals(r.get("published"))
                          || (r.get("published") instanceof Number n && n.intValue() == 1),
                      instant(r, "published_at"),
                      str(r, "cover_media_id"));
                })
            .toList();
    // Ordered relation tables make media/labels deterministic, independent of row storage order.
    var workBusinesses = rows("work_business", id, "work_id,position");
    var workMedia = rows("work_media", id, "work_id,position");
    var works =
        rows("public_work", id, "position").stream()
            .map(
                r ->
                    new Work(
                        str(r, "id"),
                        str(r, "title"),
                        str(r, "summary"),
                        str(r, "description"),
                        values(workBusinesses, "work_id", str(r, "id"), "business_id"),
                        str(r, "package_label"),
                        str(r, "publication_status"),
                        instant(r, "published_at"),
                        str(r, "cover_media_id"),
                        values(workMedia, "work_id", str(r, "id"), "media_id"),
                        str(r, "candidate_version"),
                        str(r, "binding_version"),
                        str(r, "subject_scope_ref")))
            .toList();
    if (works.size() > 1000 || businesses.size() > 100 || packages.size() > 1000)
      throw new CatalogStorageException("Publication exceeds supported read-model capacity", null);
    var variants = rows("media_variant", id, "media_id,variant");
    Map<String, Asset> assets = new LinkedHashMap<>();
    for (var r : rows("media_asset", id, "id")) {
      Map<String, Variant> entries = new LinkedHashMap<>();
      variants.stream()
          .filter(v -> str(r, "id").equals(str(v, "media_id")))
          .forEach(
              v ->
                  entries.put(
                      str(v, "variant"),
                      new Variant(
                          str(v, "object_key"),
                          str(v, "sha256"),
                          ((Number) v.get("byte_length")).longValue(),
                          integer(v, "width"),
                          integer(v, "height"),
                          str(v, "content_type"))));
      if (!entries.keySet().equals(Set.of("thumbnail", "preview")))
        throw new CatalogStorageException("Both prepared media variants required", null);
      assets.put(str(r, "id"), new Asset(str(r, "id"), str(r, "alt_text"), entries));
    }
    List<Binding> bindings = new ArrayList<>();
    businesses.forEach(
        v -> {
          if (v.coverMediaId() != null)
            bindings.add(new Binding("BUSINESS", v.id(), v.coverMediaId(), 0));
        });
    packages.forEach(
        v -> {
          if (v.coverMediaId() != null)
            bindings.add(new Binding("PACKAGE", v.id(), v.coverMediaId(), 0));
        });
    workMedia.forEach(
        v ->
            bindings.add(
                new Binding(
                    "WORK", str(v, "work_id"), str(v, "media_id"), integer(v, "position"))));
    team.forEach(
        v -> {
          if (v.portraitMediaId() != null)
            bindings.add(new Binding("TEAM_MEMBER", v.id(), v.portraitMediaId(), 0));
        });
    channels.forEach(
        v -> {
          if (v.qrCodeMediaId() != null)
            bindings.add(new Binding("CONTACT_CHANNEL", v.id(), v.qrCodeMediaId(), 0));
        });
    return new CatalogSnapshot(
        id,
        str(release, "content_version"),
        str(release, "data_mode"),
        str(release, "approval_status"),
        brand,
        contact,
        businesses,
        packages,
        works,
        assets,
        bindings,
        rows("home_featured_business", id, "position").stream()
            .map(v -> str(v, "business_id"))
            .toList(),
        rows("home_featured_package", id, "position").stream()
            .map(v -> str(v, "package_id"))
            .toList());
  }

  private static List<String> packageLines(
      List<Map<String, Object>> lines, String id, String kind) {
    return lines.stream()
        .filter(r -> id.equals(str(r, "package_id")) && kind.equals(str(r, "kind")))
        .map(r -> str(r, "text_value"))
        .toList();
  }
}
