package cn.zhinan.uniapp.booking.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Storage contract of the customer entry. No SQL, table or column name reaches the service. */
public final class PublicBookingPorts {
  private PublicBookingPorts() {}

  public record SlotRow(
      String slotId,
      Instant startAt,
      Instant endAt,
      String businessZone,
      String displayOffset,
      String status) {}

  public record IdentityRow(String customerId, String displayName) {}

  public record OrderRow(
      String orderId,
      String packageDisplayName,
      String currencyCode,
      long amountMinorUnits,
      Instant startAt,
      Instant endAt,
      String businessZone,
      String displayOffset,
      Instant capturedAt) {}

  public record RequestRow(
      String reservationId,
      String status,
      Instant createdAt,
      Instant expiresAt,
      String packageVersionRef,
      int version,
      SlotRow requestedSlot,
      OrderRow order) {}

  public record CommandRow(String requestSha256, String resultRef) {}

  /** Everything one intake writes in a single transaction. */
  public record RequestDraft(
      String reservationId,
      String commandId,
      String customerId,
      String packageVersionRef,
      String source,
      Instant expiresAt,
      String slotId,
      Instant now,
      String actorId,
      String operation,
      String idempotencyKey,
      String requestSha256) {}

  public interface Repository {
    Optional<IdentityRow> findIdentity(String provider, String appScope, String fingerprint);

    IdentityRow createCustomer(
        String customerId,
        String displayName,
        String provider,
        String appScope,
        String fingerprint,
        Instant now);

    List<SlotRow> listAvailableSlots(Instant from, Instant to, int limit);

    boolean slotAvailable(String slotId);

    Optional<String> findOpenReservationId(String customerId, Instant now);

    Optional<CommandRow> findCommand(String actorId, String operation, String idempotencyKey);

    String insertRequest(RequestDraft draft);

    Optional<RequestRow> findRequest(String customerId, String reservationId);

    List<RequestRow> listRequests(String customerId, int limit);

    <T> T inTransaction(Supplier<T> work);
  }
}
