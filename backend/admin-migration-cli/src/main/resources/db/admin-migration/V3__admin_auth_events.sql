-- Authentication failures/upgrade hints are separate from atomic successful session audits.
-- Runtime receives INSERT only. No account provisioning, defaults or secret-bearing payload.
CREATE TABLE admin_auth_event (
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  occurred_at DATETIME(6) NOT NULL,
  event_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_account_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  CONSTRAINT fk_admin_auth_event_actor FOREIGN KEY (actor_account_id) REFERENCES admin_account(account_id),
  CONSTRAINT ck_admin_auth_event_type CHECK (event_type IN ('LOGIN_DENIED','CREDENTIAL_UPGRADE_REQUIRED')),
  CONSTRAINT ck_admin_auth_event_upgrade_actor CHECK (event_type <> 'CREDENTIAL_UPGRADE_REQUIRED' OR actor_account_id IS NOT NULL),
  INDEX ix_admin_auth_event_time (occurred_at),
  INDEX ix_admin_auth_event_actor_time (actor_account_id,occurred_at),
  INDEX ix_admin_auth_event_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
