-- S2-B single-work content slice (D-507 §12, D-106 §13, ADR-005).
-- Business facts live here; the public projection is written only by the publication writer.
-- No default rows, no account data, no authorization evidence bodies.

CREATE TABLE admin_media_asset (
  media_id CHAR(36) NOT NULL,
  status VARCHAR(20) NOT NULL,
  source_format VARCHAR(8) NOT NULL,
  source_sha256 CHAR(64) NOT NULL,
  source_byte_length INT NOT NULL,
  source_width INT NOT NULL,
  source_height INT NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (media_id),
  CONSTRAINT ck_admin_media_status CHECK (status IN
    ('RECEIVING','PROCESSING','READY','REJECTED','FAILED_RETRYABLE','WITHDRAWN')),
  CONSTRAINT ck_admin_media_format CHECK (source_format IN ('JPEG','PNG')),
  CONSTRAINT ck_admin_media_sha CHECK (REGEXP_LIKE(source_sha256, '^[0-9a-f]{64}$', 'c')),
  CONSTRAINT ck_admin_media_size CHECK (source_byte_length BETWEEN 1 AND 4194304),
  CONSTRAINT ck_admin_media_dims CHECK (source_width BETWEEN 1 AND 8192 AND source_height BETWEEN 1 AND 8192)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_media_variant (
  media_id CHAR(36) NOT NULL,
  variant VARCHAR(16) NOT NULL,
  object_key VARCHAR(80) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  byte_length INT NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  content_type VARCHAR(16) NOT NULL,
  PRIMARY KEY (media_id, variant),
  CONSTRAINT fk_admin_media_variant_asset FOREIGN KEY (media_id) REFERENCES admin_media_asset (media_id),
  CONSTRAINT ck_admin_media_variant_name CHECK (variant IN ('thumbnail','preview')),
  CONSTRAINT ck_admin_media_variant_type CHECK (content_type IN ('image/jpeg','image/png')),
  CONSTRAINT ck_admin_media_variant_key CHECK (REGEXP_LIKE(object_key, '^[0-9a-f]{64}\\.(jpg|png)$', 'c')),
  CONSTRAINT ck_admin_media_variant_sha CHECK (REGEXP_LIKE(sha256, '^[0-9a-f]{64}$', 'c')),
  CONSTRAINT ck_admin_media_variant_key_sha CHECK (LEFT(object_key, 64) = sha256),
  CONSTRAINT ck_admin_media_variant_size CHECK (byte_length BETWEEN 1 AND 4194304),
  CONSTRAINT ck_admin_media_variant_dims CHECK (width BETWEEN 1 AND 8192 AND height BETWEEN 1 AND 8192)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_media_upload_command (
  actor_id VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  request_sha256 CHAR(64) NOT NULL,
  outcome VARCHAR(12) NOT NULL,
  media_id CHAR(36) NULL,
  result_code VARCHAR(40) NOT NULL,
  completed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (actor_id, idempotency_key),
  CONSTRAINT fk_admin_upload_media FOREIGN KEY (media_id) REFERENCES admin_media_asset (media_id),
  CONSTRAINT ck_admin_upload_outcome CHECK (
    (outcome = 'READY' AND media_id IS NOT NULL) OR (outcome = 'REJECTED' AND media_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_work_case (
  work_id CHAR(36) NOT NULL,
  current_version INT NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  create_idempotency_key VARCHAR(64) NOT NULL,
  create_request_sha256 CHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (work_id),
  UNIQUE KEY uk_admin_work_create (created_by, create_idempotency_key),
  CONSTRAINT ck_admin_work_version CHECK (current_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_work_version (
  work_id CHAR(36) NOT NULL,
  version INT NOT NULL,
  title VARCHAR(80) NOT NULL,
  summary VARCHAR(400) NOT NULL,
  subject_scope_ref VARCHAR(64) NOT NULL,
  cover_media_id CHAR(36) NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (work_id, version),
  CONSTRAINT fk_admin_work_version_case FOREIGN KEY (work_id) REFERENCES admin_work_case (work_id),
  CONSTRAINT ck_admin_work_version_positive CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_work_version_business (
  work_id CHAR(36) NOT NULL,
  version INT NOT NULL,
  position TINYINT NOT NULL,
  business_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (work_id, version, position),
  UNIQUE KEY uk_admin_work_version_business (work_id, version, business_id),
  CONSTRAINT fk_admin_work_business_version FOREIGN KEY (work_id, version)
    REFERENCES admin_work_version (work_id, version),
  CONSTRAINT ck_admin_work_business_position CHECK (position BETWEEN 0 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_work_version_media (
  work_id CHAR(36) NOT NULL,
  version INT NOT NULL,
  media_id CHAR(36) NOT NULL,
  position TINYINT NOT NULL,
  alt_text VARCHAR(120) NOT NULL,
  PRIMARY KEY (work_id, version, media_id),
  UNIQUE KEY uk_admin_work_media_position (work_id, version, position),
  CONSTRAINT fk_admin_work_media_version FOREIGN KEY (work_id, version)
    REFERENCES admin_work_version (work_id, version),
  CONSTRAINT fk_admin_work_media_asset FOREIGN KEY (media_id) REFERENCES admin_media_asset (media_id),
  CONSTRAINT ck_admin_work_media_position CHECK (position BETWEEN 0 AND 8)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A15 authority input. S2 management APIs never write this table; S2 fixtures load TEST_SYNTHETIC rows.
CREATE TABLE admin_work_authorization_gate (
  work_id CHAR(36) NOT NULL,
  authority_revision VARCHAR(64) NOT NULL,
  state VARCHAR(12) NOT NULL,
  subject_scope_ref VARCHAR(64) NOT NULL,
  media_scope_sha256 CHAR(64) NOT NULL,
  purpose VARCHAR(32) NOT NULL,
  channel VARCHAR(32) NOT NULL,
  valid_from DATETIME(6) NOT NULL,
  valid_until DATETIME(6) NOT NULL,
  provenance VARCHAR(24) NOT NULL,
  fact_sha256 CHAR(64) NOT NULL,
  recorded_at DATETIME(6) NOT NULL,
  PRIMARY KEY (work_id),
  CONSTRAINT fk_admin_gate_case FOREIGN KEY (work_id) REFERENCES admin_work_case (work_id),
  CONSTRAINT ck_admin_gate_state CHECK (state IN ('VALID','INVALID','REVOKED','UNKNOWN')),
  CONSTRAINT ck_admin_gate_provenance CHECK (provenance IN ('TEST_SYNTHETIC','VERIFIED_AUTHORITY')),
  CONSTRAINT ck_admin_gate_window CHECK (valid_from < valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_work_publication_state (
  work_id CHAR(36) NOT NULL,
  state VARCHAR(12) NOT NULL,
  published_version INT NOT NULL,
  release_id CHAR(36) NOT NULL,
  last_command_id CHAR(36) NOT NULL,
  changed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (work_id),
  CONSTRAINT fk_admin_publication_state_case FOREIGN KEY (work_id) REFERENCES admin_work_case (work_id),
  CONSTRAINT ck_admin_publication_state CHECK (state IN ('PUBLISHED','WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_publication_command (
  command_id CHAR(36) NOT NULL,
  actor_id VARCHAR(64) NOT NULL,
  operation VARCHAR(10) NOT NULL,
  work_id CHAR(36) NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  request_sha256 CHAR(64) NOT NULL,
  target_version INT NOT NULL,
  release_id CHAR(36) NOT NULL,
  outcome VARCHAR(12) NOT NULL,
  completed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (command_id),
  UNIQUE KEY uk_admin_publication_command (actor_id, operation, work_id, idempotency_key),
  CONSTRAINT fk_admin_publication_command_case FOREIGN KEY (work_id) REFERENCES admin_work_case (work_id),
  CONSTRAINT ck_admin_publication_operation CHECK (operation IN ('PUBLISH','WITHDRAW')),
  CONSTRAINT ck_admin_publication_outcome CHECK (outcome = 'SUCCEEDED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_publication_audit (
  audit_id CHAR(36) NOT NULL,
  command_id CHAR(36) NOT NULL,
  actor_id VARCHAR(64) NOT NULL,
  operation VARCHAR(10) NOT NULL,
  work_id CHAR(36) NOT NULL,
  work_version INT NOT NULL,
  release_id CHAR(36) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (audit_id),
  CONSTRAINT fk_admin_publication_audit_command FOREIGN KEY (command_id)
    REFERENCES admin_publication_command (command_id),
  CONSTRAINT ck_admin_publication_audit_operation CHECK (operation IN ('PUBLISH','WITHDRAW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
