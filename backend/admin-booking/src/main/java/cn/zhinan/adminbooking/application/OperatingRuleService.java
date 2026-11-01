package cn.zhinan.adminbooking.application;

import cn.zhinan.adminbooking.domain.BookingException;
import cn.zhinan.adminbooking.domain.BookingFailure;
import cn.zhinan.adminbooking.domain.BookingRules;
import cn.zhinan.adminbooking.domain.OperatingRule;
import cn.zhinan.adminbooking.port.BookingPorts.CommandLedger;
import cn.zhinan.adminbooking.port.BookingPorts.StoredCommand;
import cn.zhinan.adminbooking.port.BookingPorts.TransactionRunner;
import cn.zhinan.adminbooking.port.OperatingRulePorts.OperatingRuleRepository;
import cn.zhinan.adminbooking.port.OperatingRulePorts.StoredRule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Appends an operating-rule version. A new version does not rewrite slots or requests already
 * stored.
 */
public final class OperatingRuleService {
  static final String OPERATION = "SAVE_OPERATING_RULE";

  private final OperatingRuleRepository rules;
  private final CommandLedger commands;
  private final TransactionRunner transactions;
  private final Clock clock;

  public OperatingRuleService(
      OperatingRuleRepository rules,
      CommandLedger commands,
      TransactionRunner transactions,
      Clock clock) {
    this.rules = rules;
    this.commands = commands;
    this.transactions = transactions;
    this.clock = clock;
  }

  public Optional<StoredRule> current() {
    return rules.current();
  }

  public List<StoredRule> history() {
    return rules.history();
  }

  public SaveResult save(
      String actorId, int expectedVersion, String idempotencyKey, OperatingRule rule) {
    String actor = BookingRules.ref(actorId);
    String key = BookingRules.idempotencyKey(idempotencyKey);
    if (expectedVersion < 0)
      throw new BookingException(BookingFailure.INVALID_REQUEST, "RULE_VERSION_INVALID");
    String digest = sha256(rule.canonicalJson());
    return transactions.inTransaction(
        () -> {
          Optional<StoredCommand> prior = commands.find(actor, OPERATION, key);
          if (prior.isPresent()) return replay(prior.get(), digest);
          Optional<Integer> locked = rules.lockHead();
          int current = locked.orElse(0);
          if (current != expectedVersion)
            throw new BookingException(BookingFailure.VERSION_CONFLICT, "RULE_VERSION_CONFLICT");
          int next = Math.addExact(current, 1);
          Instant now = clock.instant();
          rules.insertVersion(next, rule.canonicalJson(), actor, now);
          if (locked.isEmpty()) rules.insertHead(next);
          else rules.updateHead(current, next);
          commands.record(actor, OPERATION, key, digest, Integer.toString(next), now);
          return new SaveResult(next, rule, false);
        });
  }

  private SaveResult replay(StoredCommand prior, String digest) {
    if (!prior.requestSha256().equals(digest))
      throw new BookingException(BookingFailure.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_KEY_REUSE");
    int version;
    try {
      version = Integer.parseInt(prior.resultId());
    } catch (NumberFormatException invalid) {
      throw new BookingException(BookingFailure.STORAGE_UNAVAILABLE, "RULE_COMMAND_INVALID");
    }
    StoredRule stored =
        rules.history().stream()
            .filter(rule -> rule.version() == version)
            .findFirst()
            .orElseThrow(
                () ->
                    new BookingException(
                        BookingFailure.STORAGE_UNAVAILABLE, "RULE_VERSION_MISSING"));
    return new SaveResult(version, stored.rule(), true);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  public record SaveResult(int version, OperatingRule rule, boolean replayed) {}
}
