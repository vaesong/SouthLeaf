package cn.zhinan.adminbooking.application;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.BookingRules;
import cn.zhinan.adminbooking.domain.OutboxRules;
import cn.zhinan.adminbooking.port.BookingPorts.BookingAudit;
import cn.zhinan.adminbooking.port.BookingPorts.CommandLedger;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxMessage;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxWorker;
import cn.zhinan.adminbooking.port.BookingPorts.StoredCommand;
import cn.zhinan.adminbooking.port.BookingPorts.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Operator-facing read and recovery operations for notification failures. */
public final class OutboxOperations {
  private static final String RECOVER_OPERATION = "OUTBOX_MANUAL_RECOVERY";

  private final OutboxWorker worker;
  private final CommandLedger commands;
  private final BookingAudit audit;
  private final TransactionRunner transactions;
  private final Clock clock;

  public OutboxOperations(
      OutboxWorker worker,
      CommandLedger commands,
      BookingAudit audit,
      TransactionRunner transactions,
      Clock clock) {
    this.worker = worker;
    this.commands = commands;
    this.audit = audit;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** Returns only terminal delivery failures; callers must further minimize the response DTO. */
  public List<OutboxMessage> deadLetters(int limit) {
    return worker.deadLetters(OutboxRules.leaseBatch(limit));
  }

  public int pendingCount() {
    return worker.pendingCount(clock.instant());
  }

  /**
   * Records one deliberate retry decision atomically with the queue transition and idempotency
   * ledger. It preserves the automatic-attempt count: a manual action permits one more attempt but
   * does not silently reset the finite retry budget. The reason is a stable code, never free text.
   */
  public RecoveryResult retryDeadLetter(
      String actorId, String idempotencyKey, String messageId, String reasonCode) {
    String actor = BookingRules.ref(actorId);
    String key = BookingRules.idempotencyKey(idempotencyKey);
    String message = BookingRules.uuid(messageId);
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,39}"))
      throw new BookingException(BookingFailure.INVALID_REQUEST, "OUTBOX_REASON_INVALID");
    String reason = reasonCode;
    String digest = BookingRules.requestDigest(message, reason);
    Instant now = clock.instant();

    return transactions.inTransaction(
        () -> {
          Optional<StoredCommand> stored = commands.find(actor, RECOVER_OPERATION, key);
          if (stored.isPresent()) {
            if (!stored.get().requestSha256().equals(digest))
              throw new BookingException(
                  BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
            return new RecoveryResult(stored.get().resultId(), message, true);
          }
          if (!worker.retryDeadLetter(message, now))
            throw new BookingException(BookingFailure.VERSION_CONFLICT, "OUTBOX_NOT_RECOVERABLE");

          String commandId = BookingRules.newId();
          audit.record(actor, commandId, "NOTIFICATION", message, "MANUAL_RETRY", reason, now);
          commands.record(actor, RECOVER_OPERATION, key, digest, commandId, now);
          return new RecoveryResult(commandId, message, false);
        });
  }

  public record RecoveryResult(String commandId, String messageId, boolean replayed) {}
}
