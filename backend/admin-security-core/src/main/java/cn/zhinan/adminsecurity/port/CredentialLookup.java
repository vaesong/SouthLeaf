package cn.zhinan.adminsecurity.port;

import cn.zhinan.adminsecurity.domain.CredentialSnapshot;
import java.util.Optional;

/** Read current primary-store facts. Empty means absent; dependency failure must propagate. */
public interface CredentialLookup {
  Optional<CredentialSnapshot> find(String accountId);
}
