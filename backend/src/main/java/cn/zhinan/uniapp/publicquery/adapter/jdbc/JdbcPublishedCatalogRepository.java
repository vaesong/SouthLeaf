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
              return existing;
            CatalogSnapshot loaded = load(release);
            cached.set(loaded);
            return loaded;
          });
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException ex) {
      throw new CatalogStorageException("Published database catalog unavailable", ex);
    }
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
