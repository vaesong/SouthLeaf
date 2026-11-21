package cn.zhinan.appuser;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Rows the user service reads and writes. Login never creates a booking customer. */
public final class AppUserModels {
  private AppUserModels() {}

  public record StoredUser(
      String id,
      String provider,
      String appScope,
      String fingerprint,
      String displayName,
      String note,
      Instant createdAt,
      Instant updatedAt,
      Instant lastSeenAt,
      int version) {

    public StoredUser seen(Instant when) {
      return new StoredUser(
          id, provider, appScope, fingerprint, displayName, note, createdAt, when, when, version);
    }
  }

  public record StoredAvatar(String contentType, byte[] content, Instant updatedAt) {}

  public record StoredVisit(
      String id,
      String userId,
      String clientEventId,
      String page,
      String contentId,
      String shareCardId,
      Integer scene,
      Instant occurredAt) {}

  public record StoredShare(
      String id,
      String userId,
      String parentCardId,
      String page,
      String contentId,
      Integer scene,
      Instant createdAt) {}

  public record StoredReservation(
      String reservationId,
      String displayStatus,
      Instant expiresAt,
      String localDate,
      String localStart,
      String localEnd) {}

  public record ShareParticipant(
      String shareId, String userId, String displayName, String fingerprint, Instant occurredAt) {}

  public record Counts(int visits, int shares, int forwards) {}

  /** Whole-list totals. These stay correct after the visible page is only a slice. */
  public record UserCensus(
      int total, int seenToday, int unnamed, int withReservation, int shared) {}

  public record UserPage(List<StoredUser> users, Instant nextSeenAt, String nextId) {}

  public interface Store {
    Optional<StoredUser> findBySubject(String provider, String appScope, String fingerprint);

    StoredUser insertUser(StoredUser user);

    void touch(String userId, Instant seenAt);

    Optional<StoredUser> findUser(String userId);

    StoredUser updateName(String userId, String displayName, Instant now);

    StoredUser updateStaff(
        String userId, int version, String displayName, String note, Instant now, String actorId);

    void replaceAvatar(String userId, String contentType, byte[] content, Instant now);

    Optional<StoredAvatar> findAvatar(String userId);

    List<StoredReservation> reservations(
        String provider, String appScope, String fingerprint, Instant now);

    Optional<StoredVisit> findVisitByClient(String userId, String clientEventId);

    Optional<StoredVisit> findRecentVisit(
        String userId, String page, String contentId, Instant since);

    StoredVisit insertVisit(StoredVisit visit);

    boolean shareExists(String shareId);

    StoredShare insertShare(StoredShare share);

    UserPage listUsers(String query, Instant cursorSeen, String cursorId, int limit);

    List<StoredVisit> visits(String userId, int limit);

    Optional<StoredShare> findShare(String shareId);

    List<ShareParticipant> opens(String shareId);

    List<ShareParticipant> forwards(String shareId);

    Counts counts(String userId);

    UserCensus census(Instant todayStart);

    int[] purge(Instant cutoff);
  }
}
