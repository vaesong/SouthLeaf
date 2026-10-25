package cn.zhinan.adminsecurity.port;

import cn.zhinan.adminsecurity.domain.AuthenticationEvent;

/**
 * Append durably or fail. Successful session issuance audit stays in AdminSessionStore's
 * transaction.
 */
public interface AuthenticationEventSink {
  void append(AuthenticationEvent event);
}
