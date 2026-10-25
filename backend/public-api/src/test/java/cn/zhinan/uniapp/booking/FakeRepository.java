package cn.zhinan.uniapp.booking;

import cn.zhinan.uniapp.booking.port.PublicBookingPorts.CommandRow;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.IdentityRow;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.OrderRow;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.Repository;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.RequestDraft;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.RequestRow;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.SlotRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** In-memory intake ledger used by the unit and controller tests; no database, no network. */
final class FakeRepository implements Repository {
  final Map<String, IdentityRow> identities = new LinkedHashMap<>();
  final Map<String, RequestRow> requests = new LinkedHashMap<>();
  final Map<String, CommandRow> commands = new LinkedHashMap<>();
  final List<String> audits = new ArrayList<>();
  final List<String> outbox = new ArrayList<>();
  final Map<String, SlotRow> slots = new LinkedHashMap<>();
  final Map<String, OrderRow> orders = new LinkedHashMap<>();
  boolean slotAvailable = true;
  int transactions;

  @Override
  public Optional<IdentityRow> findIdentity(String provider, String appScope, String fingerprint) {
    return Optional.ofNullable(identities.get(provider + "|" + appScope + "|" + fingerprint));
  }

  @Override
  public IdentityRow createCustomer(
      String customerId,
      String displayName,
      String provider,
      String appScope,
      String fingerprint,
      Instant now) {
    IdentityRow row = new IdentityRow(customerId, displayName);
    identities.put(provider + "|" + appScope + "|" + fingerprint, row);
    return row;
  }

  @Override
  public List<SlotRow> listAvailableSlots(Instant from, Instant to, int limit) {
    return slots.values().stream()
        .filter(slot -> !slot.startAt().isBefore(from) && slot.startAt().isBefore(to))
        .limit(limit)
        .toList();
  }

  @Override
  public boolean slotAvailable(String slotId) {
    return slotAvailable && slots.containsKey(slotId);
  }

  @Override
  public Optional<String> findOpenReservationId(String customerId, Instant now) {
    return requests.values().stream()
        .filter(
            row ->
                "PENDING_CONFIRMATION".equals(row.status())
                    && row.expiresAt().isAfter(now)
                    && row.reservationId() != null)
        .map(RequestRow::reservationId)
        .findFirst();
  }

  @Override
  public Optional<CommandRow> findCommand(String actorId, String operation, String idempotencyKey) {
    return Optional.ofNullable(commands.get(actorId + "|" + operation + "|" + idempotencyKey));
  }

  @Override
  public String insertRequest(RequestDraft draft) {
    commands.put(
        draft.actorId() + "|" + draft.operation() + "|" + draft.idempotencyKey(),
        new CommandRow(draft.requestSha256(), draft.reservationId()));
    SlotRow slot = slots.get(draft.slotId());
    requests.put(
        draft.reservationId(),
        new RequestRow(
            draft.reservationId(),
            "PENDING_CONFIRMATION",
            draft.now(),
            draft.expiresAt(),
            draft.packageVersionRef(),
            1,
            slot,
            orders.get(draft.reservationId())));
    audits.add("RESERVATION:CREATE:" + draft.reservationId());
    outbox.add("RESERVATION_PENDING_CONFIRMATION:" + draft.reservationId());
    return draft.reservationId();
  }

  @Override
  public Optional<RequestRow> findRequest(String customerId, String reservationId) {
    return Optional.ofNullable(requests.get(reservationId));
  }

  @Override
  public List<RequestRow> listRequests(String customerId, int limit) {
    return requests.values().stream().limit(limit).toList();
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    transactions++;
    return work.get();
  }
}
