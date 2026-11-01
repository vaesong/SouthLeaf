package cn.zhinan.adminbooking.port;

import cn.zhinan.adminbooking.domain.OperatingRule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Append-only operating-rule versions. The head row is absent until the first save. */
public final class OperatingRulePorts {
  private OperatingRulePorts() {}

  public record StoredRule(int version, OperatingRule rule, String updatedBy, Instant updatedAt) {}

  public interface OperatingRuleRepository {
    Optional<StoredRule> current();

    List<StoredRule> history();

    /** Locks the head when it exists. An empty result means no version has been saved. */
    Optional<Integer> lockHead();

    void insertVersion(int version, String payloadJson, String updatedBy, Instant updatedAt);

    void insertHead(int version);

    void updateHead(int expectedVersion, int version);
  }
}
