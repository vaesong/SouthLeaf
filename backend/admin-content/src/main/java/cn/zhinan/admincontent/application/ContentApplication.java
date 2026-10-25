package cn.zhinan.admincontent.application;

import cn.zhinan.admincontent.domain.AuthorizationGate;
import cn.zhinan.admincontent.domain.ContentException;
import cn.zhinan.admincontent.domain.ContentFailure;
import cn.zhinan.admincontent.domain.ContentMode;
import cn.zhinan.admincontent.domain.ContentModels.MediaAsset;
import cn.zhinan.admincontent.domain.ContentModels.ProcessedImage;
import cn.zhinan.admincontent.domain.ContentModels.PublicationResult;
import cn.zhinan.admincontent.domain.ContentModels.PublicationState;
import cn.zhinan.admincontent.domain.ContentModels.Publishability;
import cn.zhinan.admincontent.domain.ContentModels.UploadResult;
import cn.zhinan.admincontent.domain.ContentModels.WorkCreated;
import cn.zhinan.admincontent.domain.ContentModels.WorkView;
import cn.zhinan.admincontent.domain.ContentRules;
import cn.zhinan.admincontent.domain.WorkDraft;
import cn.zhinan.admincontent.port.ContentPorts.ImageProcessor;
import cn.zhinan.admincontent.port.ContentPorts.MediaObjectWriter;
import cn.zhinan.admincontent.port.ContentPorts.MediaRepository;
import cn.zhinan.admincontent.port.ContentPorts.PublicationCommand;
import cn.zhinan.admincontent.port.ContentPorts.PublicationRepository;
import cn.zhinan.admincontent.port.ContentPorts.StoredWork;
import cn.zhinan.admincontent.port.ContentPorts.WorkRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * S2 single-work use cases. Capability checks happen in the runtime adapter before any call; this
 * class owns ordering: object writes precede the database transaction and are never "compensated"
 * by public fallbacks (D-302 §13.2).
 */
public final class ContentApplication {
  private final ImageProcessor images;
  private final MediaObjectWriter objects;
  private final MediaRepository media;
  private final WorkRepository works;
  private final PublicationRepository publications;
  private final ContentMode mode;
  private final Clock clock;

  public ContentApplication(
      ImageProcessor images,
      MediaObjectWriter objects,
      MediaRepository media,
      WorkRepository works,
      PublicationRepository publications,
      ContentMode mode,
      Clock clock) {
    this.images = images;
    this.objects = objects;
    this.media = media;
    this.works = works;
    this.publications = publications;
    this.mode = mode;
    this.clock = clock;
  }

  public ContentMode mode() {
    return mode;
  }

  public UploadResult upload(
      String actorId, String idempotencyKey, byte[] bytes, String declaredContentType) {
    ContentRules.idempotencyKey(idempotencyKey);
    String requestSha256 =
        ContentRules.sha256(ContentRules.sha256(bytes) + "\n" + declaredContentType);
    var stored = media.findUpload(actorId, idempotencyKey);
    if (stored.isPresent())
      return replay(stored.get().requestSha256(), requestSha256, stored.get().result());
    final ProcessedImage processed;
    try {
      processed = images.process(bytes, declaredContentType);
    } catch (ContentException rejected) {
      if (rejected.failure() != ContentFailure.MEDIA_REJECTED) throw rejected;
      // A concurrent duplicate of the same rejected request is harmless; the outcome is identical.
      media.saveRejected(
          actorId, idempotencyKey, requestSha256, rejected.reason(), clock.instant());
      throw rejected;
    }
    for (var variant : processed.variants())
      objects.write(variant.metadata().objectKey(), variant.bytes());
    try {
      return media.saveReady(
          actorId, idempotencyKey, requestSha256, ContentRules.newId(), processed, clock.instant());
    } catch (ContentException race) {
      if (!"IDEMPOTENCY_RACE".equals(race.reason())) throw race;
      var winner =
          media
              .findUpload(actorId, idempotencyKey)
              .orElseThrow(() -> new ContentException(ContentFailure.STORAGE_UNAVAILABLE));
      return replay(winner.requestSha256(), requestSha256, winner.result());
    }
  }

  private static UploadResult replay(
      String storedDigest, String requestDigest, UploadResult result) {
    if (!storedDigest.equals(requestDigest))
      throw new ContentException(ContentFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSED");
    if ("REJECTED".equals(result.outcome()))
      throw new ContentException(ContentFailure.MEDIA_REJECTED, result.resultCode());
    return new UploadResult(result.outcome(), result.mediaId(), result.resultCode(), true);
  }

  public Optional<MediaAsset> mediaAsset(String mediaId) {
    return media.find(ContentRules.uuid(mediaId));
  }

  public List<MediaAsset> recentMedia() {
    return media.recent(50);
  }

  /** Private admin preview of a READY derivative; never exposes object keys. */
  public byte[] mediaContent(String mediaId, String variant) {
    if (!"thumbnail".equals(variant) && !"preview".equals(variant))
      throw new ContentException(ContentFailure.NOT_FOUND, "VARIANT_UNKNOWN");
    var stored =
        media
            .variant(ContentRules.uuid(mediaId), variant)
            .orElseThrow(() -> new ContentException(ContentFailure.NOT_FOUND, "MEDIA_UNKNOWN"));
    byte[] bytes = objects.read(stored.objectKey());
    if (bytes.length != stored.byteLength() || !ContentRules.sha256(bytes).equals(stored.sha256()))
      throw new ContentException(ContentFailure.STORAGE_UNAVAILABLE, "MEDIA_INTEGRITY");
    return bytes;
  }

  public String mediaContentType(String mediaId, String variant) {
    return media
        .variant(ContentRules.uuid(mediaId), variant)
        .orElseThrow(() -> new ContentException(ContentFailure.NOT_FOUND, "MEDIA_UNKNOWN"))
        .contentType();
  }

  public WorkCreated createWork(String actorId, String idempotencyKey) {
    ContentRules.idempotencyKey(idempotencyKey);
    return works.create(
        actorId, idempotencyKey, ContentRules.sha256("CREATE_WORK"), clock.instant());
  }

  public WorkView work(String workId) {
    StoredWork stored =
        works
            .find(ContentRules.uuid(workId))
            .orElseThrow(() -> new ContentException(ContentFailure.NOT_FOUND, "WORK_UNKNOWN"));
    PublicationState state = publications.states(List.of(stored.workId())).get(stored.workId());
    return view(stored, state);
  }

  public List<WorkView> listWorks() {
    List<StoredWork> stored = works.list(100);
    Map<String, PublicationState> states =
        publications.states(stored.stream().map(StoredWork::workId).toList());
    return stored.stream().map(work -> view(work, states.get(work.workId()))).toList();
  }

  private static WorkView view(StoredWork stored, PublicationState state) {
    return new WorkView(
        stored.workId(),
        stored.version(),
        stored.draft(),
        stored.updatedAt(),
        state == null ? "NEVER_PUBLISHED" : state.state(),
        state == null ? null : state.publishedVersion(),
        state == null ? null : state.releaseId());
  }

  public int saveDraft(String actorId, String workId, int expectedVersion, WorkDraft draft) {
    draft.validated();
    var assets = media.findAll(draft.mediaIds());
    for (String id : draft.mediaIds()) {
      MediaAsset asset = assets.get(id);
      if (asset == null || !"READY".equals(asset.status()))
        throw new ContentException(ContentFailure.MEDIA_NOT_READY, "MEDIA_NOT_READY");
    }
    return works.saveDraft(
        ContentRules.uuid(workId), expectedVersion, draft, actorId, clock.instant());
  }

  /** Read-only publishability summary; the publish transaction re-checks everything. */
  public Publishability preview(String workId) {
    WorkView view = work(workId);
    try {
      view.draft().requireComplete();
    } catch (ContentException incomplete) {
      return new Publishability(false, incomplete.reason(), gateState(view.workId()));
    }
    Optional<AuthorizationGate> gate = publications.gate(view.workId());
    String reason =
        AuthorizationGate.blockingReason(
            gate.orElse(null),
            view.draft().subjectScopeRef(),
            ContentRules.mediaScopeDigest(view.draft().mediaIds()),
            clock.instant(),
            mode);
    return new Publishability(
        reason == null, reason, gate.map(AuthorizationGate::state).orElse("MISSING"));
  }

  private String gateState(String workId) {
    return publications.gate(workId).map(AuthorizationGate::state).orElse("MISSING");
  }

  public PublicationResult publish(
      String actorId, String workId, int expectedVersion, String idempotencyKey) {
    return publications.publish(
        command(actorId, "PUBLISH", workId, expectedVersion, idempotencyKey));
  }

  public PublicationResult withdraw(
      String actorId, String workId, int expectedVersion, String idempotencyKey) {
    return publications.withdraw(
        command(actorId, "WITHDRAW", workId, expectedVersion, idempotencyKey));
  }

  /** Unknown-commit lookup by the same idempotency key; absence means "not committed". */
  public Optional<PublicationResult> publicationCommand(
      String actorId, String operation, String workId, String idempotencyKey) {
    if (!"PUBLISH".equals(operation) && !"WITHDRAW".equals(operation))
      throw new ContentException(ContentFailure.INVALID_REQUEST, "OPERATION_INVALID");
    return publications.findCommand(
        actorId, operation, ContentRules.uuid(workId), ContentRules.idempotencyKey(idempotencyKey));
  }

  public PublicationResult command(String commandId) {
    return publications
        .commandById(ContentRules.uuid(commandId))
        .orElseThrow(() -> new ContentException(ContentFailure.NOT_FOUND, "COMMAND_UNKNOWN"));
  }

  private PublicationCommand command(
      String actorId, String operation, String workId, int expectedVersion, String key) {
    ContentRules.idempotencyKey(key);
    ContentRules.uuid(workId);
    if (expectedVersion < 1) throw new ContentException(ContentFailure.PRECONDITION_REQUIRED);
    String digest = ContentRules.sha256(operation + "\n" + workId + "\n" + expectedVersion);
    return new PublicationCommand(
        actorId, operation, workId, expectedVersion, key, digest, clock.instant());
  }
}
