package cn.zhinan.uniapp.authorization.port;

import java.util.List;
import java.util.Map;

/**
 * Current scoped fact evaluation in one consistent batch; no persistence or transport dependency.
 */
public interface PublicAuthorizationEvaluator {
  Map<String, AuthorizationReadResult> evaluate(List<PublicUseContext> contexts);
}
