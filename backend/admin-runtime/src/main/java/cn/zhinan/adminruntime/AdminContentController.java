package cn.zhinan.adminruntime;

import cn.zhinan.admincontent.application.CatalogApplication;
import cn.zhinan.admincontent.application.ContentApplication;
import cn.zhinan.admincontent.domain.CatalogDraft;
import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import cn.zhinan.admincontent.domain.ContentModels.MediaAsset;
import cn.zhinan.admincontent.domain.ContentModels.PublicationResult;
import cn.zhinan.admincontent.domain.ContentModels.WorkView;
import cn.zhinan.admincontent.domain.ContentRules;
import cn.zhinan.admincontent.domain.WorkDraft;
import cn.zhinan.adminsecurity.application.AdminSessionService;
import cn.zhinan.adminsecurity.domain.AdminAuthenticationException;
import cn.zhinan.adminsecurity.domain.AdminPermission;
import cn.zhinan.adminsecurity.domain.AdminPrincipal;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * S2-B single-work management API (D-403 §8). Every request re-authenticates the server session and
 * checks the capability; hidden UI controls are not a boundary. Object keys never leave here.
 */
@RestController
@ResponseBody
@RequestMapping("/api/admin/v1")
@ConditionalOnWebApplication(type = Type.SERVLET)
class AdminContentController {
  private static final int MAX_DRAFT_BYTES = 16 * 1024;
  private static final int MAX_CATALOG_BYTES = 262_144;
  private static final int MAX_EMPTY_BODY_BYTES = 64;
  private static final Pattern ETAG = Pattern.compile("\"v([1-9][0-9]{0,8})\"");
  private static final JsonFactory STRICT_JSON =
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final Pattern CATALOG_ETAG = Pattern.compile("\"c(0|[1-9][0-9]{0,8})\"");
  private static final JsonMapper CATALOG_JSON = JsonMapper.builder(STRICT_JSON).build();

  private final ContentApplication content;
  private final CatalogApplication catalog;
  private final AdminSessionService sessions;

  AdminContentController(
      ContentApplication content, CatalogApplication catalog, AdminSessionService sessions) {
    this.content = content;
    this.catalog = catalog;
    this.sessions = sessions;
  }

  // ---------------------------------------------------------------- M3 catalog authoring

  @GetMapping("/content/catalog")
  ResponseEntity<?> catalog(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.CONTENT_READ,
        (principal, requestId) -> {
          var stored = catalog.currentDraft();
          CatalogDraft draft =
              stored.isEmpty() ? CatalogDraft.empty() : readCatalog(stored.get().payloadJson());
          int revision = stored.map(r -> r.revision()).orElse(0);
          return okBuilder(requestId)
              .eTag(catalogEtag(revision))
              .body(new Envelope<>("OK", requestId, new CatalogView(revision, draft)));
        });
  }

  @GetMapping("/content/catalog/versions")
  ResponseEntity<?> catalogVersions(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.CONTENT_READ,
        (principal, requestId) ->
            ok(
                requestId,
                catalog.history().stream()
                    .map(v -> new CatalogVersionView(v.revision(), v.updatedBy(), v.updatedAt()))
                    .toList()));
  }

  @GetMapping("/content/catalog/versions/{revision}")
  ResponseEntity<?> catalogVersion(HttpServletRequest request, @PathVariable int revision) {
    return handle(
        request,
        AdminPermission.CONTENT_READ,
        (principal, requestId) -> {
          var stored =
              catalog
                  .version(revision)
                  .orElseThrow(
                      () ->
                          new ContentException(
                              ContentFailure.NOT_FOUND, "CATALOG_VERSION_UNKNOWN"));
          return ok(
              requestId, new CatalogView(stored.revision(), readCatalog(stored.payloadJson())));
        });
  }

  @PutMapping("/content/catalog")
  ResponseEntity<?> saveCatalog(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.CONTENT_EDIT,
        (principal, requestId) -> {
          int expected = catalogIfMatch(request);
          byte[] bytes = body(request, MAX_CATALOG_BYTES);
          CatalogDraft draft = parseCatalog(bytes);
          String json;
          try {
            json = CATALOG_JSON.writeValueAsString(draft);
          } catch (tools.jackson.core.JacksonException failure) {
            throw new ContentException(ContentFailure.INVALID_REQUEST, "CATALOG_JSON_INVALID");
          }
          int revision = catalog.saveDraft(principal.accountId(), expected, draft, json);
          return okBuilder(requestId)
              .eTag(catalogEtag(revision))
              .body(new Envelope<>("OK", requestId, new CatalogView(revision, draft)));
        });
  }

  @GetMapping("/content/catalog/preview")
  ResponseEntity<?> previewCatalog(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.CONTENT_READ,
        (principal, requestId) -> {
          var stored = catalog.currentDraft();
          CatalogDraft draft =
              stored.isEmpty() ? CatalogDraft.empty() : readCatalog(stored.get().payloadJson());
          return ok(requestId, catalog.preview(draft));
        });
  }

  @GetMapping("/content/catalog/commands")
  ResponseEntity<?> catalogCommandByKey(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.CONTENT_READ,
        (principal, requestId) -> {
          String key = ContentRules.idempotencyKey(request.getHeader("Idempotency-Key"));
          var result =
              catalog
                  .findCommand(principal.accountId(), key)
                  .orElseThrow(
                      () -> new ContentException(ContentFailure.NOT_FOUND, "COMMAND_UNKNOWN"));
          return ok(requestId, result);
        });
  }

  @PostMapping("/content/catalog/publish")
  ResponseEntity<?> publishCatalog(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.CONTENT_PUBLISH,
        (principal, requestId) -> {
          int expected = catalogIfMatch(request);
          String key = ContentRules.idempotencyKey(request.getHeader("Idempotency-Key"));
          requireEmptyObject(request);
          var stored =
              catalog
                  .currentDraft()
                  .orElseThrow(
                      () ->
                          new ContentException(
                              ContentFailure.DRAFT_INCOMPLETE, "CATALOG_DRAFT_MISSING"));
          CatalogDraft currentDraft = readCatalog(stored.payloadJson());
          String publicSnapshot;
          try {
            publicSnapshot = CATALOG_JSON.writeValueAsString(currentDraft.publicationSnapshot());
          } catch (tools.jackson.core.JacksonException failure) {
            throw new ContentException(ContentFailure.INVALID_REQUEST, "CATALOG_SNAPSHOT_INVALID");
          }
          var result =
              catalog.publish(principal.accountId(), expected, key, currentDraft, publicSnapshot);
          return ok(requestId, result);
        });
  }

  @PostMapping("/content/catalog/withdraw")
  ResponseEntity<?> withdrawCatalog(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.CONTENT_PUBLISH,
        (principal, requestId) -> {
          int expected = catalogIfMatch(request);
          String key = ContentRules.idempotencyKey(request.getHeader("Idempotency-Key"));
          requireEmptyObject(request);
          catalog.withdraw(principal.accountId(), expected, key);
          return ok(requestId, new CatalogActionView("WITHDRAWN", expected));
        });
  }

  // ---------------------------------------------------------------- media

  @PostMapping("/media/uploads")
  ResponseEntity<?> upload(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.CONTENT_EDIT,
        (principal, requestId) -> {
          String key = ContentRules.idempotencyKey(request.getHeader("Idempotency-Key"));
          final List<Part> parts;
          try {
            parts = new ArrayList<>(request.getParts());
          } catch (IllegalStateException tooLarge) {
            throw new ContentException(ContentFailure.MEDIA_TOO_LARGE, "MEDIA_TOO_LARGE");
          } catch (IOException | ServletException malformed) {
            throw new ContentException(ContentFailure.INVALID_REQUEST, "MULTIPART_INVALID");
          }
          if (parts.size() != 1 || !"file".equals(parts.get(0).getName()))
            throw new ContentException(ContentFailure.INVALID_REQUEST, "MULTIPART_SINGLE_FILE");
          Part part = parts.get(0);
          if (part.getSize() > ContentRules.MAX_UPLOAD_BYTES)
            throw new ContentException(ContentFailure.MEDIA_TOO_LARGE, "MEDIA_TOO_LARGE");
          final byte[] bytes;
          try (var input = part.getInputStream()) {
            bytes = input.readNBytes(ContentRules.MAX_UPLOAD_BYTES + 1);
          } catch (IOException unreadable) {
            throw new ContentException(ContentFailure.INVALID_REQUEST, "MULTIPART_INVALID");
          }
          if (bytes.length > ContentRules.MAX_UPLOAD_BYTES)
            throw new ContentException(ContentFailure.MEDIA_TOO_LARGE, "MEDIA_TOO_LARGE");
          String declared = part.getContentType() == null ? "" : part.getContentType().trim();
          var result = content.upload(principal.accountId(), key, bytes, declared);
          var asset =
              content
                  .mediaAsset(result.mediaId())
                  .orElseThrow(() -> new ContentException(ContentFailure.STORAGE_UNAVAILABLE));
          return ok(requestId, new UploadView(media(asset), result.replayed()));
        });
  }

  @GetMapping("/media")
  ResponseEntity<?> mediaList(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.CONTENT_READ,
        (principal, requestId) ->
            ok(
                requestId,
                content.recentMedia().stream().map(AdminContentController::media).toList()));
  }

  @GetMapping("/media/{mediaId}")
  ResponseEntity<?> mediaItem(HttpServletRequest request, @PathVariable String mediaId) {
    return handle(
        request,
        AdminPermission.CONTENT_READ,
        (principal, requestId) ->
            ok(
                requestId,
                media(
                    content
                        .mediaAsset(mediaId)
                        .orElseThrow(
                            () ->
                                new ContentException(ContentFailure.NOT_FOUND, "MEDIA_UNKNOWN")))));
  }

  @GetMapping("/media/{mediaId}/{variant}/content")
  ResponseEntity<?> mediaContent(
      HttpServletRequest request, @PathVariable String mediaId, @PathVariable String variant) {
    return handle(
        request,
        AdminPermission.CONTENT_READ,
        (principal, requestId) -> {
          if (request.getHeader("Range") != null)
            throw new ContentException(ContentFailure.INVALID_REQUEST, "RANGE_UNSUPPORTED");
          byte[] bytes = content.mediaContent(mediaId, variant);
          return ResponseEntity.ok()
              .cacheControl(CacheControl.noStore())
              .header("X-Request-Id", requestId)
              .header("X-Content-Type-Options", "nosniff")
              .header("Content-Type", content.mediaContentType(mediaId, variant))
              .header("Content-Disposition", "inline")
              .body(bytes);
        });
  }

  // ---------------------------------------------------------------- works

  @GetMapping("/works")
  ResponseEntity<?> works(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.CONTENT_READ,
        (principal, requestId) ->
            ok(requestId, content.listWorks().stream().map(AdminContentController::work).toList()));
  }

  @PostMapping("/works")
  ResponseEntity<?> createWork(HttpServletRequest request) {
    return handle(
        request,
        AdminPermission.CONTENT_EDIT,
        (principal, requestId) -> {
          String key = ContentRules.idempotencyKey(request.getHeader("Idempotency-Key"));
          requireEmptyObject(request);
          var created = content.createWork(principal.accountId(), key);
          var view = content.work(created.workId());
          return okBuilder(requestId)
              .eTag(etag(view.version()))
              .body(
                  new Envelope<>("OK", requestId, new CreatedView(work(view), created.replayed())));
        });
  }

  @GetMapping("/works/{workId}")
  ResponseEntity<?> work(HttpServletRequest request, @PathVariable String workId) {
    return handle(
        request,
        AdminPermission.CONTENT_READ,
        (principal, requestId) -> {
          var view = content.work(workId);
          return okBuilder(requestId)
              .eTag(etag(view.version()))
              .body(new Envelope<>("OK", requestId, work(view)));
        });
  }

  @PutMapping("/works/{workId}")
  ResponseEntity<?> saveDraft(HttpServletRequest request, @PathVariable String workId) {
    return handle(
        request,
        AdminPermission.CONTENT_EDIT,
        (principal, requestId) -> {
          int expected = ifMatch(request);
          WorkDraft draft = readDraft(request);
          int version = content.saveDraft(principal.accountId(), workId, expected, draft);
          var view = content.work(workId);
          if (view.version() != version)
            throw new ContentException(ContentFailure.VERSION_CONFLICT, "VERSION_CONFLICT");
          return okBuilder(requestId)
              .eTag(etag(version))
              .body(new Envelope<>("OK", requestId, work(view)));
        });
  }

  @GetMapping("/works/{workId}/preview")
  ResponseEntity<?> preview(HttpServletRequest request, @PathVariable String workId) {
    return handle(
        request,
        AdminPermission.CONTENT_READ,
        (principal, requestId) -> {
          var view = content.work(workId);
          var publishability = content.preview(workId);
          return okBuilder(requestId)
              .eTag(etag(view.version()))
              .body(
                  new Envelope<>(
                      "OK",
                      requestId,
                      new PreviewView(
                          work(view),
                          publishability.publishable(),
                          publishability.reason(),
                          publishability.gateState(),
                          content.mode().acceptedProvenance())));
        });
  }

  @PostMapping("/works/{workId}/publish")
  ResponseEntity<?> publish(HttpServletRequest request, @PathVariable String workId) {
    return publication(request, workId, true);
  }

  @PostMapping("/works/{workId}/withdraw")
  ResponseEntity<?> withdraw(HttpServletRequest request, @PathVariable String workId) {
    return publication(request, workId, false);
  }

  private ResponseEntity<?> publication(
      HttpServletRequest request, String workId, boolean publish) {
    return handle(
        request,
        AdminPermission.CONTENT_PUBLISH,
        (principal, requestId) -> {
          String key = ContentRules.idempotencyKey(request.getHeader("Idempotency-Key"));
          int expected = ifMatch(request);
          requireEmptyObject(request);
          PublicationResult result =
              publish
                  ? content.publish(principal.accountId(), workId, expected, key)
                  : content.withdraw(principal.accountId(), workId, expected, key);
          return ok(requestId, command(result));
        });
  }

  @GetMapping("/commands/{commandId}")
  ResponseEntity<?> commandStatus(HttpServletRequest request, @PathVariable String commandId) {
    return handle(
        request,
        AdminPermission.CONTENT_READ,
        (principal, requestId) -> ok(requestId, command(content.command(commandId))));
  }

  // ---------------------------------------------------------------- plumbing

  @FunctionalInterface
  private interface Handler {
    ResponseEntity<?> apply(AdminPrincipal principal, String requestId);
  }

  private ResponseEntity<?> handle(
      HttpServletRequest request, AdminPermission permission, Handler handler) {
    String requestId = UUID.randomUUID().toString();
    String token = sessionCookie(request);
    if (token == null)
      return error(401, "AUTHENTICATION_REQUIRED", "Authentication is required.", null, requestId);
    final AdminPrincipal principal;
    try {
      principal = sessions.authenticateWithExpiry(token).principal();
    } catch (AdminAuthenticationException invalid) {
      return error(401, "AUTHENTICATION_REQUIRED", "Authentication is required.", null, requestId);
    } catch (RuntimeException unavailable) {
      return error(
          503,
          "AUTHORIZATION_UNAVAILABLE",
          "Authorization is temporarily unavailable.",
          null,
          requestId);
    }
    if (!principal.permissions().contains(permission))
      return error(
          403, "CAPABILITY_REQUIRED", "The required capability is missing.", null, requestId);
    try {
      return handler.apply(principal, requestId);
    } catch (ContentException failure) {
      return failure(failure, requestId);
    } catch (RuntimeException unexpected) {
      return error(
          503,
          "CONTENT_SERVICE_UNAVAILABLE",
          "The content service is temporarily unavailable.",
          null,
          requestId);
    }
  }

  private static ResponseEntity<?> failure(ContentException failure, String requestId) {
    return switch (failure.failure()) {
      case INVALID_REQUEST ->
          error(400, "INVALID_REQUEST", "The request is invalid.", failure.reason(), requestId);
      case PRECONDITION_REQUIRED ->
          error(
              428,
              "PRECONDITION_REQUIRED",
              "A strong If-Match revision is required.",
              null,
              requestId);
      case MEDIA_UNSUPPORTED_TYPE ->
          error(415, "MEDIA_TYPE_UNSUPPORTED", "Only JPEG and PNG are accepted.", null, requestId);
      case MEDIA_TOO_LARGE ->
          error(413, "MEDIA_TOO_LARGE", "The file exceeds the configured limit.", null, requestId);
      case MEDIA_REJECTED ->
          error(422, "MEDIA_REJECTED", "The image failed validation.", failure.reason(), requestId);
      case MEDIA_NOT_READY ->
          error(
              422,
              "MEDIA_NOT_READY",
              "Only READY media can be bound.",
              failure.reason(),
              requestId);
      case NOT_FOUND ->
          error(404, "RESOURCE_UNAVAILABLE", "The resource is unavailable.", null, requestId);
      case VERSION_CONFLICT ->
          error(
              409, "VERSION_CONFLICT", "The revision changed; reload and review.", null, requestId);
      case IDEMPOTENCY_CONFLICT ->
          error(
              409,
              "IDEMPOTENCY_KEY_REUSE",
              "The key was used for another request.",
              null,
              requestId);
      case DRAFT_INCOMPLETE, PUBLICATION_BLOCKED ->
          error(422, "PUBLICATION_BLOCKED", "Publication is blocked.", failure.reason(), requestId);
      case NOT_PUBLISHED ->
          error(409, "NOT_PUBLISHED", "The work is not currently published.", null, requestId);
      case STORAGE_UNAVAILABLE ->
          error(
              503,
              "CONTENT_SERVICE_UNAVAILABLE",
              "The content service is temporarily unavailable.",
              null,
              requestId);
    };
  }

  private static String sessionCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) return null;
    String value = null;
    int count = 0;
    for (Cookie cookie : cookies)
      if (AdminAuthController.SESSION_COOKIE.equals(cookie.getName())) {
        count++;
        value = cookie.getValue();
      }
    return count == 1 ? value : null;
  }

  private record CatalogView(int revision, CatalogDraft draft) {}

  private record CatalogVersionView(int revision, String updatedBy, java.time.Instant updatedAt) {}

  private record CatalogActionView(String status, int revision) {}

  private static CatalogDraft parseCatalog(byte[] bytes) {
    try {
      CatalogDraft draft = CATALOG_JSON.readValue(bytes, CatalogDraft.class);
      if (draft == null)
        throw new ContentException(ContentFailure.INVALID_REQUEST, "CATALOG_JSON_INVALID");
      return draft;
    } catch (tools.jackson.core.JacksonException | IllegalArgumentException malformed) {
      throw new ContentException(ContentFailure.INVALID_REQUEST, "CATALOG_JSON_INVALID");
    }
  }

  private static CatalogDraft readCatalog(String json) {
    try {
      return CATALOG_JSON.readValue(json, CatalogDraft.class);
    } catch (tools.jackson.core.JacksonException | IllegalArgumentException malformed) {
      throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "CATALOG_JSON_INVALID");
    }
  }

  private static int catalogIfMatch(HttpServletRequest request) {
    String header = request.getHeader("If-Match");
    if (header == null || header.isBlank())
      throw new ContentException(ContentFailure.PRECONDITION_REQUIRED);
    var match = CATALOG_ETAG.matcher(header.trim());
    if (!match.matches())
      throw new ContentException(ContentFailure.INVALID_REQUEST, "IF_MATCH_INVALID");
    return Integer.parseInt(match.group(1));
  }

  private static String catalogEtag(int revision) {
    return "\"c" + revision + "\"";
  }

  private static int ifMatch(HttpServletRequest request) {
    String header = request.getHeader("If-Match");
    if (header == null || header.isBlank())
      throw new ContentException(ContentFailure.PRECONDITION_REQUIRED);
    var match = ETAG.matcher(header.trim());
    if (!match.matches())
      throw new ContentException(ContentFailure.INVALID_REQUEST, "IF_MATCH_INVALID");
    return Integer.parseInt(match.group(1));
  }

  private static String etag(int version) {
    return "\"v" + version + "\"";
  }

  private static byte[] body(HttpServletRequest request, int max) {
    String type = request.getContentType();
    if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
      throw new ContentException(ContentFailure.INVALID_REQUEST, "JSON_REQUIRED");
    if (request.getContentLengthLong() > max)
      throw new ContentException(ContentFailure.INVALID_REQUEST, "BODY_TOO_LARGE");
    try {
      byte[] bytes = request.getInputStream().readNBytes(max + 1);
      if (bytes.length > max)
        throw new ContentException(ContentFailure.INVALID_REQUEST, "BODY_TOO_LARGE");
      return bytes;
    } catch (IOException unreadable) {
      throw new ContentException(ContentFailure.INVALID_REQUEST, "BODY_UNREADABLE");
    }
  }

  private static void requireEmptyObject(HttpServletRequest request) {
    byte[] bytes = body(request, MAX_EMPTY_BODY_BYTES);
    try (JsonParser parser = STRICT_JSON.createParser(bytes)) {
      if (parser.nextToken() != JsonToken.START_OBJECT
          || parser.nextToken() != JsonToken.END_OBJECT
          || parser.nextToken() != null)
        throw new ContentException(ContentFailure.INVALID_REQUEST, "EMPTY_OBJECT_REQUIRED");
    } catch (JacksonException malformed) {
      throw new ContentException(ContentFailure.INVALID_REQUEST, "JSON_MALFORMED");
    }
  }

  /** Strict draft reader: exact field set, no unknown/duplicate fields, no coercion. */
  static WorkDraft readDraft(HttpServletRequest request) {
    byte[] bytes = body(request, MAX_DRAFT_BYTES);
    try (JsonParser parser = STRICT_JSON.createParser(bytes)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) invalid();
      String title = null, summary = null, scope = null, cover = null;
      boolean coverSeen = false;
      List<String> businesses = null;
      List<WorkDraft.MediaBinding> media = null;
      JsonToken token;
      while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
        if (token != JsonToken.PROPERTY_NAME) invalid();
        String field = parser.currentName();
        JsonToken value = parser.nextToken();
        switch (field) {
          case "title" -> title = string(parser, value);
          case "summary" -> summary = string(parser, value);
          case "subjectScopeRef" -> scope = string(parser, value);
          case "coverMediaId" -> {
            coverSeen = true;
            cover = value == JsonToken.VALUE_NULL ? null : string(parser, value);
          }
          case "businessIds" -> {
            if (value != JsonToken.START_ARRAY) invalid();
            businesses = new ArrayList<>();
            JsonToken item;
            while ((item = parser.nextToken()) != JsonToken.END_ARRAY) {
              businesses.add(string(parser, item));
              if (businesses.size() > ContentRules.MAX_BUSINESSES) invalid();
            }
          }
          case "media" -> {
            if (value != JsonToken.START_ARRAY) invalid();
            media = new ArrayList<>();
            JsonToken item;
            while ((item = parser.nextToken()) != JsonToken.END_ARRAY) {
              if (item != JsonToken.START_OBJECT) invalid();
              String mediaId = null, alt = null;
              JsonToken inner;
              while ((inner = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (inner != JsonToken.PROPERTY_NAME) invalid();
                String name = parser.currentName();
                JsonToken innerValue = parser.nextToken();
                if ("mediaId".equals(name)) mediaId = string(parser, innerValue);
                else if ("altText".equals(name)) alt = string(parser, innerValue);
                else invalid();
              }
              if (mediaId == null || alt == null) invalid();
              media.add(new WorkDraft.MediaBinding(mediaId, alt));
              if (media.size() > ContentRules.MAX_MEDIA) invalid();
            }
          }
          default -> invalid();
        }
      }
      if (parser.nextToken() != null
          || title == null
          || summary == null
          || scope == null
          || !coverSeen
          || businesses == null
          || media == null) invalid();
      return new WorkDraft(title, summary, scope, businesses, media, cover);
    } catch (JacksonException malformed) {
      throw new ContentException(ContentFailure.INVALID_REQUEST, "JSON_MALFORMED");
    }
  }

  private static String string(JsonParser parser, JsonToken token) {
    if (token != JsonToken.VALUE_STRING || parser.getTextLength() > 1024) invalid();
    return parser.getText();
  }

  private static void invalid() {
    throw new ContentException(ContentFailure.INVALID_REQUEST, "DRAFT_JSON_INVALID");
  }

  private static <T> ResponseEntity<Envelope<T>> ok(String requestId, T data) {
    return okBuilder(requestId).body(new Envelope<>("OK", requestId, data));
  }

  private static ResponseEntity.BodyBuilder okBuilder(String requestId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Request-Id", requestId)
        .header("X-Content-Type-Options", "nosniff");
  }

  private static ResponseEntity<ErrorEnvelope> error(
      int status, String code, String message, String reason, String requestId) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header("X-Request-Id", requestId)
        .header("X-Content-Type-Options", "nosniff")
        .body(new ErrorEnvelope(code, message, reason, requestId));
  }

  private static MediaView media(MediaAsset asset) {
    return new MediaView(
        asset.mediaId(),
        asset.status(),
        asset.sourceFormat(),
        asset.sourceWidth(),
        asset.sourceHeight(),
        asset.createdAt().toString(),
        asset.variants().stream()
            .map(
                v ->
                    new VariantView(
                        v.variant(), v.width(), v.height(), v.byteLength(), v.contentType()))
            .toList());
  }

  private static WorkSummaryView work(WorkView view) {
    var d = view.draft();
    return new WorkSummaryView(
        view.workId(),
        view.version(),
        d.title(),
        d.summary(),
        d.subjectScopeRef(),
        d.businessIds(),
        d.media().stream().map(m -> new BindingView(m.mediaId(), m.altText())).toList(),
        d.coverMediaId(),
        view.updatedAt().toString(),
        view.publicationState(),
        view.publishedVersion());
  }

  private static CommandView command(PublicationResult r) {
    return new CommandView(
        r.commandId(),
        r.operation(),
        "SUCCEEDED",
        r.workId(),
        r.targetVersion(),
        r.completedAt().toString(),
        r.replayed());
  }

  record Envelope<T>(String code, String requestId, T data) {}

  record ErrorEnvelope(String code, String message, String reason, String requestId) {}

  record VariantView(String variant, int width, int height, int byteLength, String contentType) {}

  record MediaView(
      String mediaId,
      String status,
      String sourceFormat,
      int sourceWidth,
      int sourceHeight,
      String createdAt,
      List<VariantView> variants) {}

  record UploadView(MediaView media, boolean replayed) {}

  record BindingView(String mediaId, String altText) {}

  record WorkSummaryView(
      String workId,
      int version,
      String title,
      String summary,
      String subjectScopeRef,
      List<String> businessIds,
      List<BindingView> media,
      String coverMediaId,
      String updatedAt,
      String publicationState,
      Integer publishedVersion) {}

  record CreatedView(WorkSummaryView work, boolean replayed) {}

  record PreviewView(
      WorkSummaryView work,
      boolean publishable,
      String blockingReason,
      String authorizationState,
      String acceptedProvenance) {}

  record CommandView(
      String commandId,
      String operation,
      String status,
      String workId,
      int targetVersion,
      String completedAt,
      boolean replayed) {}
}
