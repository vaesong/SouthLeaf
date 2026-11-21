package cn.zhinan.adminbooking.port;

import cn.zhinan.adminbooking.domain.BookingModels.ServiceSlot;
import java.time.Instant;
import java.util.List;

/** Reads and conditional writes used only by the staff slot-opening use case. */
public final class SlotOpeningPorts {
  private SlotOpeningPorts() {}

  /** A slot whose start falls on the day being opened, plus whether an occupancy row exists. */
  public record DaySlot(ServiceSlot slot, boolean occupied) {}

  /**
   * A slot shown on the week board. {@code availability} is {@code OPEN}, {@code OCCUPIED} or
   * {@code PENDING}. Closed slots are not returned.
   */
  public record BoardSlot(ServiceSlot slot, String availability) {}

  /**
   * A pending request's preferred slot. {@code slotId} and the instants are null when the request
   * did not name a slot. This is intent only; it does not occupy the slot.
   */
  public record PendingAsk(
      String reservationId,
      String slotId,
      Instant startAt,
      Instant endAt,
      Instant expiresAt,
      int version) {}

  public interface SlotGuard {
    /** Locks slot rows whose start is in {@code [from, to)} and reports occupancy. */
    List<DaySlot> lockSlotsStartingBetween(Instant from, Instant to);

    boolean pendingConfirmation(String slotId);

    /** Closes an open slot only when the revision still matches. Does not look at occupancy. */
    int closeIfOpen(String slotId, int expectedRevision, Instant now);

    List<BoardSlot> listBoard(Instant from, Instant to, int limit);

    /** Pending requests, whether or not their preferred slot falls in the visible week. */
    List<PendingAsk> listPendingAsks(int limit);
  }
}
