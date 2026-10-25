-- Separate management schema and history in production. Never run on the public runtime schema.
CREATE TABLE admin_account (
  account_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  enabled BOOLEAN NOT NULL CHECK (enabled IN (0,1)),
  security_version BIGINT NOT NULL CHECK (security_version >= 0),
  credential_hash VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE admin_account_permission (
  account_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  permission_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (account_id,permission_code),
  FOREIGN KEY (account_id) REFERENCES admin_account(account_id),
  CHECK (permission_code IN (
    'CONTENT_READ','CONTENT_EDIT','CONTENT_PUBLISH',
    'PUBLIC_AUTHORIZATION_REVIEW','PUBLIC_AUTHORIZATION_REVOKE',
    'RESERVATION_READ','RESERVATION_CONFIRM','RESERVATION_CANCEL','RESERVATION_RESCHEDULE',
    'ORDER_READ','AUDIT_READ','STAFF_ACCESS_MANAGE'
  ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE admin_session (
  token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  account_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  security_version BIGINT NOT NULL CHECK (security_version >= 0),
  issued_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6),
  FOREIGN KEY (account_id) REFERENCES admin_account(account_id),
  CHECK (expires_at > issued_at AND expires_at <= issued_at + INTERVAL 8 HOUR),
  INDEX ix_admin_session_account (account_id,expires_at),
  INDEX ix_admin_session_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE admin_security_audit (
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  actor_account_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  CHECK (action IN ('ADMIN_SESSION_OPENED','ADMIN_SESSION_REVOKED')),
  UNIQUE KEY ux_admin_audit_session_action (target_session_id,action),
  INDEX ix_admin_audit_actor_time (actor_account_id,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
