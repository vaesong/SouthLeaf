package cn.zhinan.appuser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class AppUserServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-21T04:00:00Z");
  private static final String SCOPE = "mini-app";

  @Test
  void theSameFingerprintIsOneUserAndDoesNotCreateABookingCustomer() {
    MemoryStore store = new MemoryStore();
    AppUserService service = new AppUserService(store, SCOPE);
    StoredUser first = service.login("openid-a", NOW);
    StoredUser second = service.login("openid-a", NOW.plusSeconds(5));
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(store.users).hasSize(1);
    assertThat(store.bookingInserts).isZero();
    assertThat(service.reservations(first.id(), NOW)).hasSize(1);
    StoredUser other = service.login("openid-b", NOW);
    other = service.rename(other.id(), "同名", NOW);
    service.rename(first.id(), "同名", NOW);
    assertThat(service.reservations(other.id(), NOW)).isEmpty();
  }

  @Test
  void censusCountsTheWholeListRatherThanOnePage() {
    MemoryStore store = new MemoryStore();
    AppUserService service = new AppUserService(store, SCOPE);
    StoredUser named = service.login("openid-a", NOW);
    service.rename(named.id(), "南北", NOW);
    service.login("openid-b", NOW.minus(Duration.ofDays(2)));
    service.share(named.id(), "pages/index/index", null, null, null, NOW);
    UserCensus census = service.census(NOW);
    assertThat(census.total()).isEqualTo(2);
    assertThat(census.seenToday()).isEqualTo(1);
    assertThat(census.unnamed()).isEqualTo(1);
    assertThat(census.withReservation()).isEqualTo(1);
    assertThat(census.shared()).isEqualTo(1);
  }

  @Test
  void aRacingFirstInsertKeepsASingleRow() {
    MemoryStore store = new MemoryStore();
    store.race = true;
    AppUserService service = new AppUserService(store, SCOPE);
    StoredUser user = service.login("openid-a", NOW);
    assertThat(store.users).containsOnlyKeys(user.id());
    assertThat(service.login("openid-a", NOW).id()).isEqualTo(user.id());
  }

  @Test
  void namesRejectControlsAndMoreThanThirtyTwoScalars() {
    assertThatThrownBy(() -> AppUserRules.nickname("好\n名字")).isInstanceOf(AppUserException.class);
    assertThatThrownBy(() -> AppUserRules.nickname("名".repeat(33)))
        .isInstanceOf(AppUserException.class);
    assertThat(AppUserRules.nickname("  枝南  ")).isEqualTo("枝南");
    assertThat(AppUserRules.nickname("   ")).isNull();
    assertThat(AppUserRules.note("第一行\n第二行")).isEqualTo("第一行\n第二行");
  }

  @Test
  void aRejectedAvatarLeavesThePreviousOne() throws Exception {
    MemoryStore store = new MemoryStore();
    AppUserService service = new AppUserService(store, SCOPE);
    StoredUser user = service.login("openid-a", NOW);
    byte[] png = png(16, 16);
    service.replaceAvatar(user.id(), "image/png", png, NOW);
    assertThat(service.avatar(user.id()).contentType()).isEqualTo("image/jpeg");
    assertThatThrownBy(() -> service.replaceAvatar(user.id(), "image/png", "nope".getBytes(), NOW))
        .isInstanceOf(AppUserException.class);
    assertThat(service.avatar(user.id()).content()).isEqualTo(store.avatar.content());
    assertThat(store.avatarWrites).isEqualTo(1);
  }

  @Test
  void aStaleStaffVersionDoesNotOverwrite() {
    MemoryStore store = new MemoryStore();
    AppUserService service = new AppUserService(store, SCOPE);
    StoredUser user = service.login("openid-a", NOW);
    service.edit(user.id(), 1, "新名字", "备注", "operator", NOW);
    assertThatThrownBy(() -> service.edit(user.id(), 1, "旧名字", "备注", "operator", NOW))
        .isInstanceOf(AppUserException.class)
        .extracting(error -> ((AppUserException) error).code())
        .isEqualTo(AppUserException.Code.CONFLICT);
    assertThat(service.profile(user.id()).displayName()).isEqualTo("新名字");
  }

  @Test
  void visitsDedupeForThirtySecondsAndUnknownSharesStayUnlinked() {
    MemoryStore store = new MemoryStore();
    AppUserService service = new AppUserService(store, SCOPE);
    StoredUser user = service.login("openid-a", NOW);
    String firstId = UUID.randomUUID().toString();
    StoredVisit first =
        service.visit(user.id(), firstId, "pages/works/workdetail", "work-1", "missing", 1001, NOW);
    StoredVisit again =
        service.visit(
            user.id(),
            UUID.randomUUID().toString(),
            "pages/works/workdetail",
            "work-1",
            null,
            1001,
            NOW.plusSeconds(10));
    StoredVisit replay =
        service.visit(user.id(), firstId, "pages/index/index", null, null, null, NOW);
    StoredVisit later =
        service.visit(
            user.id(),
            UUID.randomUUID().toString(),
            "pages/works/workdetail",
            "work-1",
            null,
            null,
            NOW.plusSeconds(31));
    assertThat(again.id()).isEqualTo(first.id());
    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(first.shareCardId()).isNull();
    assertThat(later.id()).isNotEqualTo(first.id());
    assertThat(store.visits).hasSize(2);
    assertThat(store.bookingInserts).isZero();
  }

  @Test
  void aShareChainKeepsTwoHops() {
    MemoryStore store = new MemoryStore();
    AppUserService service = new AppUserService(store, SCOPE);
    StoredUser first = service.login("openid-a", NOW);
    StoredUser second = service.login("openid-b", NOW);
    StoredShare origin =
        service.share(first.id(), "pages/works/workdetail", "work-1", null, 1007, NOW);
    StoredShare next =
        service.share(second.id(), "pages/works/workdetail", "work-1", origin.id(), 1008, NOW);
    assertThat(service.chain(next.id()))
        .extracting(StoredShare::id)
        .containsExactly(origin.id(), next.id());
    assertThat(service.forwards(origin.id()))
        .extracting(ShareParticipant::userId)
        .containsExactly(second.id());
  }

  @Test
  void purgeDropsOldTrailsAndKeepsTheUser() {
    MemoryStore store = new MemoryStore();
    AppUserService service = new AppUserService(store, SCOPE);
    StoredUser user = service.login("openid-a", NOW.minus(Duration.ofDays(200)));
    store.visits.add(
        new StoredVisit(
            UUID.randomUUID().toString(),
            user.id(),
            UUID.randomUUID().toString(),
            "pages/index/index",
            null,
            null,
            null,
            NOW.minus(Duration.ofDays(181))));
    service.visit(
        user.id(), UUID.randomUUID().toString(), "pages/index/index", null, null, null, NOW);
    service.purge(NOW.minus(Duration.ofDays(180)));
    assertThat(store.visits).hasSize(1);
    assertThat(store.users).containsKey(user.id());
  }

  @Test
  void aUserTokenIsNotABookingTokenVersion() {
    String secret = "0123456789abcdef0123456789abcdef";
    String userId = "11111111-1111-4111-8111-111111111111";
    AppUserTokens tokens = new AppUserTokens(secret, Duration.ofHours(12));
    String token = tokens.issue(userId, NOW).token();
    assertThat(tokens.userId(token, NOW)).isEqualTo(userId);
    assertThatThrownBy(() -> tokens.userId(token, NOW.plus(Duration.ofHours(13))))
        .isInstanceOf(AppUserException.class);
  }

  @Test
  void thePublicWriterCannotInsertABookingCustomer() throws Exception {
    String source =
        Files.readString(Path.of("src/main/java/cn/zhinan/appuser/JdbcAppUserStore.java"));
    assertThat(source).doesNotContain("INSERT INTO booking_");
    assertThat(source).contains("booking_external_identity");
    assertThat(source).contains("booking_reservation");
    assertThat(AppUserGrantPolicy.GRANTS).doesNotContainKey("booking_customer");
    assertThat(AppUserGrantPolicy.GRANTS.get("booking_reservation")).containsExactly("SELECT");
  }

  @Test
  void theFingerprintUsesTheBookingDigest() {
    assertThat(AppUserRules.fingerprint("WECHAT", "mini-app", "subject-1"))
        .isEqualTo(AppUserRules.digest("WECHAT", "mini-app", "subject-1"))
        .hasSize(64);
  }

  private static byte[] png(int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }

  private static final class MemoryStore implements Store {
    private final java.util.LinkedHashMap<String, StoredUser> users =
        new java.util.LinkedHashMap<>();
    private final List<StoredVisit> visits = new ArrayList<>();
    private final List<StoredShare> shares = new ArrayList<>();
    private StoredAvatar avatar;
    private int avatarWrites;
    private int bookingInserts;
    private boolean race;

    @Override
    public Optional<StoredUser> findBySubject(
        String provider, String appScope, String fingerprint) {
      return users.values().stream()
          .filter(user -> user.fingerprint().equals(fingerprint))
          .findFirst();
    }

    @Override
    public StoredUser insertUser(StoredUser user) {
      if (race) {
        users.put(user.id(), user);
        race = false;
        throw new DuplicateKeyException("duplicate subject");
      }
      if (findBySubject(user.provider(), user.appScope(), user.fingerprint()).isPresent()) {
        throw new DuplicateKeyException("duplicate subject");
      }
      users.put(user.id(), user);
      if (user.fingerprint().equals(AppUserRules.fingerprint("WECHAT", SCOPE, "openid-a"))) {
        // A reservation is attached by fingerprint. Nothing here inserts a booking customer.
      }
      return user;
    }

    @Override
    public void touch(String userId, Instant seenAt) {
      users.computeIfPresent(userId, (id, user) -> user.seen(seenAt));
    }

    @Override
    public Optional<StoredUser> findUser(String userId) {
      return Optional.ofNullable(users.get(userId));
    }

    @Override
    public StoredUser updateName(String userId, String displayName, Instant now) {
      StoredUser current = users.get(userId);
      StoredUser updated =
          new StoredUser(
              current.id(),
              current.provider(),
              current.appScope(),
              current.fingerprint(),
              displayName,
              current.note(),
              current.createdAt(),
              now,
              current.lastSeenAt(),
              current.version() + 1);
      users.put(userId, updated);
      return updated;
    }

    @Override
    public StoredUser updateStaff(
        String userId, int version, String displayName, String note, Instant now, String actorId) {
      StoredUser current = users.get(userId);
      if (current.version() != version) throw AppUserException.conflict();
      StoredUser updated =
          new StoredUser(
              current.id(),
              current.provider(),
              current.appScope(),
              current.fingerprint(),
              displayName,
              note,
              current.createdAt(),
              now,
              current.lastSeenAt(),
              current.version() + 1);
      users.put(userId, updated);
      return updated;
    }

    @Override
    public void replaceAvatar(String userId, String contentType, byte[] content, Instant now) {
      avatarWrites++;
      avatar = new StoredAvatar(contentType, content, now);
    }

    @Override
    public Optional<StoredAvatar> findAvatar(String userId) {
      return Optional.ofNullable(avatar);
    }

    @Override
    public List<StoredReservation> reservations(
        String provider, String appScope, String fingerprint, Instant now) {
      if (!fingerprint.equals(AppUserRules.fingerprint("WECHAT", SCOPE, "openid-a")))
        return List.of();
      return List.of(
          new StoredReservation(
              "res-1", "CONFIRMED", NOW.plusSeconds(3600), "2026-11-22", "10:00", "11:00"));
    }

    @Override
    public Optional<StoredVisit> findVisitByClient(String userId, String clientEventId) {
      return visits.stream()
          .filter(visit -> visit.clientEventId().equals(clientEventId))
          .findFirst();
    }

    @Override
    public Optional<StoredVisit> findRecentVisit(
        String userId, String page, String contentId, Instant since) {
      return visits.stream()
          .filter(visit -> visit.userId().equals(userId) && visit.page().equals(page))
          .filter(visit -> java.util.Objects.equals(visit.contentId(), contentId))
          .filter(visit -> !visit.occurredAt().isBefore(since))
          .findFirst();
    }

    @Override
    public StoredVisit insertVisit(StoredVisit visit) {
      visits.add(visit);
      return visit;
    }

    @Override
    public boolean shareExists(String shareId) {
      return shares.stream().anyMatch(share -> share.id().equals(shareId));
    }

    @Override
    public StoredShare insertShare(StoredShare share) {
      shares.add(share);
      return share;
    }

    @Override
    public UserPage listUsers(String query, Instant cursorSeen, String cursorId, int limit) {
      return new UserPage(List.copyOf(users.values()), null, null);
    }

    @Override
    public List<StoredVisit> visits(String userId, int limit) {
      return visits.stream().filter(visit -> visit.userId().equals(userId)).toList();
    }

    @Override
    public Optional<StoredShare> findShare(String shareId) {
      return shares.stream().filter(share -> share.id().equals(shareId)).findFirst();
    }

    @Override
    public List<ShareParticipant> opens(String shareId) {
      return List.of();
    }

    @Override
    public List<ShareParticipant> forwards(String shareId) {
      return shares.stream()
          .filter(share -> shareId.equals(share.parentCardId()))
          .map(
              share ->
                  new ShareParticipant(
                      share.id(),
                      share.userId(),
                      null,
                      users.get(share.userId()).fingerprint(),
                      share.createdAt()))
          .toList();
    }

    @Override
    public Counts counts(String userId) {
      return new Counts(0, 0, 0);
    }

    @Override
    public UserCensus census(Instant todayStart) {
      int seen =
          (int)
              users.values().stream()
                  .filter(user -> !user.lastSeenAt().isBefore(todayStart))
                  .count();
      int unnamed =
          (int)
              users.values().stream()
                  .filter(user -> user.displayName() == null || user.displayName().isBlank())
                  .count();
      int reserved =
          (int)
              users.values().stream()
                  .filter(
                      user ->
                          !reservations(
                                  user.provider(), user.appScope(), user.fingerprint(), todayStart)
                              .isEmpty())
                  .count();
      int shared =
          (int)
              users.values().stream()
                  .filter(
                      user -> shares.stream().anyMatch(share -> share.userId().equals(user.id())))
                  .count();
      return new UserCensus(users.size(), seen, unnamed, reserved, shared);
    }

    @Override
    public int[] purge(Instant cutoff) {
      visits.removeIf(visit -> visit.occurredAt().isBefore(cutoff));
      shares.removeIf(share -> share.createdAt().isBefore(cutoff));
      return new int[] {0, 0};
    }
  }
}
