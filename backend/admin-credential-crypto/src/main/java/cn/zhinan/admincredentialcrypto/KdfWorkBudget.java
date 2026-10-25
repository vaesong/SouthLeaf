package cn.zhinan.admincredentialcrypto;

import cn.zhinan.adminsecurity.domain.CredentialDependencyException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** One shared budget across adapter instances in this application classloader; no waiting queue. */
final class KdfWorkBudget {
  private static final Semaphore PERMITS = new Semaphore(2);

  private KdfWorkBudget() {}

  static <T> T run(Supplier<T> operation) {
    if (!PERMITS.tryAcquire())
      throw new CredentialDependencyException(
          CredentialDependencyException.Reason.CAPACITY_EXHAUSTED);
    try {
      return operation.get();
    } finally {
      PERMITS.release();
    }
  }
}
