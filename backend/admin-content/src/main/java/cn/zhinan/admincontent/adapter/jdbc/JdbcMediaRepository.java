package cn.zhinan.admincontent.adapter.jdbc;

import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import cn.zhinan.admincontent.domain.ContentModels.MediaAsset;
import cn.zhinan.admincontent.domain.ContentModels.ProcessedImage;
import cn.zhinan.admincontent.domain.ContentModels.UploadResult;
import cn.zhinan.admincontent.domain.ContentModels.Variant;
import cn.zhinan.admincontent.port.ContentPorts.MediaRepository;
import cn.zhinan.admincontent.port.ContentPorts.StoredUpload;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

/** Business-writer adapter for media facts in the management schema. */
public final class JdbcMediaRepository implements MediaRepository {
  private static final String ASSET_COLUMNS =
      "media_id,status,source_format,source_width,source_height,created_at";
  private final ContentJdbc db;

  public JdbcMediaRepository(DataSource businessWriter) {
    this.db = new ContentJdbc(businessWriter);
  }

  @Override
  public Optional<StoredUpload> findUpload(String actorId, String key) {
    return db.read(
        () ->
            db
                .jdbc
                .query(
                    "SELECT request_sha256,outcome,media_id,result_code FROM admin_media_upload_command WHERE actor_id=? AND idempotency_key=?",
                    (rs, n) ->
                        new StoredUpload(
                            rs.getString(1),
                            new UploadResult(
                                rs.getString(2), rs.getString(3), rs.getString(4), false)),
                    actorId,
                    key)
                .stream()
                .findFirst());
  }

  @Override
  public UploadResult saveReady(
      String actorId,
      String key,
      String requestSha256,
      String mediaId,
      ProcessedImage image,
      Instant now) {
    try {
      return db.write(
          () -> {
            db.jdbc.update(
                "INSERT INTO admin_media_asset (media_id,status,source_format,source_sha256,source_byte_length,source_width,source_height,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                mediaId,
                "READY",
                image.sourceFormat(),
                image.sourceSha256(),
                image.sourceByteLength(),
                image.sourceWidth(),
                image.sourceHeight(),
                actorId,
                ContentJdbc.ts(now));
            for (var encoded : image.variants()) {
              Variant v = encoded.metadata();
              db.jdbc.update(
                  "INSERT INTO admin_media_variant (media_id,variant,object_key,sha256,byte_length,width,height,content_type) VALUES (?,?,?,?,?,?,?,?)",
                  mediaId,
                  v.variant(),
                  v.objectKey(),
                  v.sha256(),
                  v.byteLength(),
                  v.width(),
                  v.height(),
                  v.contentType());
            }
            db.jdbc.update(
                "INSERT INTO admin_media_upload_command (actor_id,idempotency_key,request_sha256,outcome,media_id,result_code,completed_at) VALUES (?,?,?,?,?,?,?)",
                actorId,
                key,
                requestSha256,
                "READY",
                mediaId,
                "MEDIA_READY",
                ContentJdbc.ts(now));
            return new UploadResult("READY", mediaId, "MEDIA_READY", false);
          });
    } catch (ContentException failure) {
      throw failure;
    } catch (DuplicateKeyException race) {
      throw new ContentException(ContentFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_RACE");
    }
  }

  @Override
  public UploadResult saveRejected(
      String actorId, String key, String requestSha256, String code, Instant now) {
    try {
      db.jdbc.update(
          "INSERT INTO admin_media_upload_command (actor_id,idempotency_key,request_sha256,outcome,media_id,result_code,completed_at) VALUES (?,?,?,?,NULL,?,?)",
          actorId,
          key,
          requestSha256,
          "REJECTED",
          code,
          ContentJdbc.ts(now));
    } catch (DuplicateKeyException concurrentSameKey) {
      // Same actor+key already recorded; the caller still reports the rejection it observed.
    } catch (DataAccessException ex) {
      throw ContentJdbc.storage(ex);
    }
    return new UploadResult("REJECTED", null, code, false);
  }

  @Override
  public Optional<MediaAsset> find(String mediaId) {
    return Optional.ofNullable(findAll(List.of(mediaId)).get(mediaId));
  }

  @Override
  public Map<String, MediaAsset> findAll(Collection<String> mediaIds) {
    if (mediaIds.isEmpty()) return Map.of();
    return db.read(() -> load("WHERE media_id IN (:ids)", Map.of("ids", List.copyOf(mediaIds))));
  }

  @Override
  public List<MediaAsset> recent(int limit) {
    return db.read(
        () ->
            List.copyOf(
                load(
                        "WHERE media_id IN (SELECT media_id FROM (SELECT media_id FROM admin_media_asset ORDER BY created_at DESC, media_id LIMIT :limit) recent)",
                        Map.of("limit", limit))
                    .values()));
  }

  private Map<String, MediaAsset> load(String where, Map<String, ?> parameters) {
    var assets =
        db.named.queryForList(
            "SELECT "
                + ASSET_COLUMNS
                + " FROM admin_media_asset "
                + where
                + " ORDER BY created_at DESC, media_id",
            parameters);
    if (assets.isEmpty()) return Map.of();
    var ids = assets.stream().map(r -> (String) r.get("media_id")).toList();
    var variants =
        db.named.queryForList(
            "SELECT media_id,variant,object_key,sha256,byte_length,width,height,content_type FROM admin_media_variant WHERE media_id IN (:ids) ORDER BY media_id, variant",
            Map.of("ids", ids));
    var result = new LinkedHashMap<String, MediaAsset>();
    for (var row : assets) {
      String id = (String) row.get("media_id");
      var own = new ArrayList<Variant>();
      for (var v : variants) if (id.equals(v.get("media_id"))) own.add(variant(v));
      result.put(
          id,
          new MediaAsset(
              id,
              (String) row.get("status"),
              (String) row.get("source_format"),
              ((Number) row.get("source_width")).intValue(),
              ((Number) row.get("source_height")).intValue(),
              ContentJdbc.instant(row.get("created_at")),
              own));
    }
    return result;
  }

  @Override
  public Optional<Variant> variant(String mediaId, String variant) {
    return db.read(
        () ->
            db
                .named
                .queryForList(
                    "SELECT v.media_id,v.variant,v.object_key,v.sha256,v.byte_length,v.width,v.height,v.content_type FROM admin_media_variant v JOIN admin_media_asset a ON a.media_id=v.media_id WHERE v.media_id=:id AND v.variant=:variant AND a.status='READY'",
                    Map.of("id", mediaId, "variant", variant))
                .stream()
                .findFirst()
                .map(JdbcMediaRepository::variant));
  }

  static Variant variant(Map<String, Object> v) {
    return new Variant(
        (String) v.get("variant"),
        (String) v.get("object_key"),
        (String) v.get("sha256"),
        ((Number) v.get("byte_length")).intValue(),
        ((Number) v.get("width")).intValue(),
        ((Number) v.get("height")).intValue(),
        (String) v.get("content_type"));
  }
}
