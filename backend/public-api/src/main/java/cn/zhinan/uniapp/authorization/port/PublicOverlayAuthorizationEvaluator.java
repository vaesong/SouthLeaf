package cn.zhinan.uniapp.authorization.port;

import java.util.List;
import java.util.Map;

/**
 * S2 per-work overlay authorization snapshots (ADR-005). Kept as a distinct port so V1 release
 * facts and V2 overlay facts are never resolved through each other.
 */
public interface PublicOverlayAuthorizationEvaluator {
  Map<String, AuthorizationReadResult> evaluate(List<PublicUseContext> contexts);
}
