package cn.zhinan.appuser;

import cn.zhinan.appuser.AppUserModels.Counts;
import cn.zhinan.appuser.AppUserModels.ShareParticipant;
import cn.zhinan.appuser.AppUserModels.Store;
import cn.zhinan.appuser.AppUserModels.StoredAvatar;
import cn.zhinan.appuser.AppUserModels.StoredReservation;
import cn.zhinan.appuser.AppUserModels.StoredShare;
import cn.zhinan.appuser.AppUserModels.StoredUser;
import cn.zhinan.appuser.AppUserModels.StoredVisit;
import cn.zhinan.appuser.AppUserModels.UserCensus;
import cn.zhinan.appuser.AppUserModels.UserPage;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;

/**
 * Mini-program users and their visit trail. A login records the user only; it does not open a
 * booking customer.
 */
public final class AppUserService {
  private static final int PAGE = 30;

  private final Store store;
  private final String appScope;

  public AppUserService(Store store, String appScope) {
    if (store == null) throw new IllegalArgumentException("store");
    if (appScope == null || !appScope.matches("[A-Za-z0-9._:-]{1,32}")) {
      throw new IllegalArgumentException("app scope");
    }
    this.store = store;
    this.appScope = appScope;
  }

  public StoredUser login(String subject, Instant now) {
    if (subject == null || subject.isBlank() || subject.length() > 128) {
      throw AppUserException.identityRejected();
    }
    String fingerprint = AppUserRules.fingerprint(AppUserRules.PROVIDER, appScope, subject);
    Optional<StoredUser> existing =
        store.findBySubject(AppUserRules.PROVIDER, appScope, fingerprint);
    if (existing.isPresent()) {
      store.touch(existing.get().id(), now);
      return existing.get().seen(now);
    }
    StoredUser created =
        new StoredUser(
            UUID.randomUUID().toString(),
            AppUserRules.PROVIDER,
            appScope,
            fingerprint,
            null,
            null,
            now,
            now,
            now,
            1);
    try {
      return store.insertUser(created);
    } catch (DuplicateKeyException raced) {
      StoredUser winner =
          store
              .findBySubject(AppUserRules.PROVIDER, appScope, fingerprint)
              .orElseThrow(AppUserException::unavailable);
      store.touch(winner.id(), now);
      return winner.seen(now);
    }
  }

  public StoredUser touch(String userId, Instant now) {
    StoredUser user = require(userId);
    store.touch(user.id(), now);
    return user.seen(now);
  }

  public StoredUser profile(String userId) {
    return require(userId);
  }

  public StoredUser rename(String userId, String displayName, Instant now) {
    require(userId);
    return store.updateName(userId, AppUserRules.nickname(displayName), now);
  }

  public StoredUser edit(
      String userId, int version, String displayName, String note, String actorId, Instant now) {
    if (actorId == null || actorId.isBlank() || actorId.length() > 64) {
      throw AppUserException.invalid("ACTOR");
    }
    require(userId);
    return store.updateStaff(
        userId, version, AppUserRules.nickname(displayName), AppUserRules.note(note), now, actorId);
  }

  public void replaceAvatar(String userId, String contentType, byte[] bytes, Instant now) {
    require(userId);
    byte[] jpeg = AppUserAvatars.normalize(contentType, bytes);
    store.replaceAvatar(userId, "image/jpeg", jpeg, now);
  }

  public StoredAvatar avatar(String userId) {
    require(userId);
    return store.findAvatar(userId).orElseThrow(AppUserException::notFound);
  }

  public boolean hasAvatar(String userId) {
    require(userId);
    return store.findAvatar(userId).isPresent();
  }

  public List<StoredReservation> reservations(String userId, Instant now) {
    StoredUser user = require(userId);
    return store.reservations(user.provider(), user.appScope(), user.fingerprint(), now);
  }

  public StoredVisit visit(
      String userId,
      String clientEventId,
      String page,
      String contentId,
      String shareCardId,
      Integer scene,
      Instant now) {
    require(userId);
    String eventId = AppUserRules.clientEventId(clientEventId);
    Optional<StoredVisit> replay = store.findVisitByClient(userId, eventId);
    if (replay.isPresent()) return replay.get();
    String allowedPage = AppUserRules.page(page);
    String content = AppUserRules.optionalId(contentId, "CONTENT_ID");
    String share = knownShare(shareCardId);
    Integer allowedScene = AppUserRules.scene(scene);
    Optional<StoredVisit> recent =
        store.findRecentVisit(userId, allowedPage, content, AppUserRules.dedupSince(now));
    if (recent.isPresent()) return recent.get();
    try {
      return store.insertVisit(
          new StoredVisit(
              UUID.randomUUID().toString(),
              userId,
              eventId,
              allowedPage,
              content,
              share,
              allowedScene,
              now));
    } catch (DuplicateKeyException raced) {
      return store.findVisitByClient(userId, eventId).orElseThrow(AppUserException::unavailable);
    }
  }

  public StoredShare share(
      String userId,
      String page,
      String contentId,
      String parentCardId,
      Integer scene,
      Instant now) {
    require(userId);
    return store.insertShare(
        new StoredShare(
            UUID.randomUUID().toString(),
            userId,
            knownShare(parentCardId),
            AppUserRules.page(page),
            AppUserRules.optionalId(contentId, "CONTENT_ID"),
            AppUserRules.scene(scene),
            now));
  }

  public UserPage list(String query, Instant cursorSeen, String cursorId) {
    return store.listUsers(AppUserRules.searchQuery(query), cursorSeen, cursorId, PAGE);
  }

  public UserCensus census(Instant now) {
    Instant start =
        now.atZone(ZoneId.of("Asia/Shanghai"))
            .toLocalDate()
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .toInstant();
    return store.census(start);
  }

  public List<StoredVisit> visits(String userId) {
    require(userId);
    return store.visits(userId, 100);
  }

  public StoredShare shareCard(String shareId) {
    return store.findShare(shareId).orElseThrow(AppUserException::notFound);
  }

  public List<StoredShare> chain(String shareId) {
    List<StoredShare> newestFirst = new ArrayList<>();
    String cursor = shareId;
    while (cursor != null && newestFirst.size() < 8) {
      StoredShare card = store.findShare(cursor).orElse(null);
      if (card == null) break;
      newestFirst.add(card);
      cursor = card.parentCardId();
    }
    List<StoredShare> oldestFirst = new ArrayList<>();
    for (int index = newestFirst.size() - 1; index >= 0; index--) {
      oldestFirst.add(newestFirst.get(index));
    }
    if (oldestFirst.isEmpty()) throw AppUserException.notFound();
    return oldestFirst;
  }

  public List<ShareParticipant> opens(String shareId) {
    shareCard(shareId);
    return store.opens(shareId);
  }

  public List<ShareParticipant> forwards(String shareId) {
    shareCard(shareId);
    return store.forwards(shareId);
  }

  public Counts counts(String userId) {
    require(userId);
    return store.counts(userId);
  }

  public int[] purge(Instant cutoff) {
    return store.purge(cutoff);
  }

  private StoredUser require(String userId) {
    if (userId == null || !userId.matches("[0-9a-fA-F-]{36}")) throw AppUserException.notFound();
    return store.findUser(userId).orElseThrow(AppUserException::notFound);
  }

  private String knownShare(String shareCardId) {
    String id = AppUserRules.optionalId(shareCardId, "SHARE_CARD_ID");
    if (id == null) return null;
    if (!id.matches("[0-9a-fA-F-]{36}") || !store.shareExists(id)) return null;
    return id;
  }
}
