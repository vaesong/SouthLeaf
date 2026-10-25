-- C3 lifecycle facts are private to the dedicated account CLI. Runtime receives no grants here.
-- No account/password/token payload is persisted beyond the existing versioned credential hash.
CREATE TABLE admin_account_command (
  command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  command_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_account_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expected_security_version BIGINT NULL,
  result_security_version BIGINT NOT NULL,
  payload_sha256 BINARY(32) NULL,
  execution_identity VARCHAR(288) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_admin_account_command_target
    FOREIGN KEY (target_account_id) REFERENCES admin_account(account_id),
  CONSTRAINT ck_admin_account_command_type
    CHECK (command_type IN ('BOOTSTRAP','CHANGE_PASSWORD','DISABLE_ACCOUNT','REPLACE_PERMISSIONS')),
  CONSTRAINT ck_admin_account_command_expected_version
    CHECK (expected_security_version IS NULL OR expected_security_version >= 0),
  CONSTRAINT ck_admin_account_command_result_version
    CHECK (result_security_version >= 0),
  CONSTRAINT ck_admin_account_command_payload
    CHECK (
      (command_type IN ('BOOTSTRAP','CHANGE_PASSWORD') AND payload_sha256 IS NULL)
      OR (command_type IN ('DISABLE_ACCOUNT','REPLACE_PERMISSIONS') AND payload_sha256 IS NOT NULL)
    ),
  INDEX ix_admin_account_command_target_time (target_account_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE admin_bootstrap_guard (
  guard_id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
  completed_at DATETIME(6) NULL,
  bootstrap_command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
  CONSTRAINT ck_admin_bootstrap_guard_id CHECK (guard_id = 1),
  CONSTRAINT ck_admin_bootstrap_guard_completion
    CHECK ((completed_at IS NULL) = (bootstrap_command_id IS NULL)),
  CONSTRAINT fk_admin_bootstrap_guard_command
    FOREIGN KEY (bootstrap_command_id) REFERENCES admin_account_command(command_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

INSERT INTO admin_bootstrap_guard(guard_id, completed_at, bootstrap_command_id)
VALUES (1, NULL, NULL);
