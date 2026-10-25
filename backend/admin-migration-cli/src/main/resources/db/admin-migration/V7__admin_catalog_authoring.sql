-- V7: M3 immutable typed catalog authoring revisions and audit ledger.
-- No V1/V2/V5/V6 tables are modified; DDL is migration-owner only.
CREATE TABLE admin_catalog_draft (
    singleton_id TINYINT NOT NULL,
    current_revision INT NOT NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT ck_admin_catalog_draft_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_admin_catalog_draft_revision CHECK (current_revision >= 0)
) ENGINE=InnoDB;

CREATE TABLE admin_catalog_draft_revision (
    revision INT NOT NULL,
    payload_json JSON NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (revision),
    CONSTRAINT ck_admin_catalog_revision_positive CHECK (revision > 0)
) ENGINE=InnoDB;

CREATE TABLE admin_catalog_command (
    command_id CHAR(36) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    operation VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    expected_revision INT NOT NULL,
    result_release_id CHAR(36) NULL,
    outcome VARCHAR(16) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (command_id),
    CONSTRAINT uq_admin_catalog_command UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT ck_admin_catalog_operation CHECK (operation IN ('PUBLISH', 'WITHDRAW')),
    CONSTRAINT ck_admin_catalog_outcome CHECK (outcome IN ('SUCCEEDED'))
) ENGINE=InnoDB;

CREATE TABLE admin_catalog_audit (
    audit_id CHAR(36) NOT NULL,
    command_id CHAR(36) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    operation VARCHAR(24) NOT NULL,
    target_revision INT NOT NULL,
    result_release_id CHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (audit_id),
    CONSTRAINT uq_admin_catalog_audit_command UNIQUE (command_id),
    CONSTRAINT fk_admin_catalog_audit_command FOREIGN KEY (command_id)
      REFERENCES admin_catalog_command (command_id)
) ENGINE=InnoDB;
