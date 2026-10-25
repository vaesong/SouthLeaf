-- V6: admin customer identity, schedule slots, reservations and orders (D-304, D-305, D-306).
--
-- Scope rules for this file:
--   * Every table is admin-canonical and private. Nothing here is published to the public schema.
--   * There is no payment, deposit, refund or delivery column anywhere in this migration.
--   * Times are UTC instants with microsecond precision; the business time zone travels with the row.
--   * The exclusive slot hold and the one-order-per-reservation rule are enforced by unique keys,
--     never by an application level read before write.

CREATE TABLE booking_customer (
    id            CHAR(36)      NOT NULL,
    status        VARCHAR(16)   NOT NULL,
    display_name  VARCHAR(80)   NOT NULL,
    language      VARCHAR(16)   NULL,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    version       INT           NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT ck_booking_customer_status CHECK (status IN ('ACTIVE', 'DORMANT', 'CLOSED'))
) ENGINE = InnoDB;

-- An external identity is unique per provider and application scope. The subject is stored as a
-- fingerprint so the raw platform identifier never enters the management database.
CREATE TABLE booking_external_identity (
    id                  CHAR(36)     NOT NULL,
    customer_id         CHAR(36)     NOT NULL,
    provider            VARCHAR(32)  NOT NULL,
    app_scope           VARCHAR(32)  NOT NULL,
    subject_fingerprint CHAR(64)     NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    verified_at         DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    version             INT          NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT uq_booking_external_identity_subject
        UNIQUE (provider, app_scope, subject_fingerprint),
    CONSTRAINT fk_booking_external_identity_customer
        FOREIGN KEY (customer_id) REFERENCES booking_customer (id),
    CONSTRAINT ck_booking_external_identity_status
        CHECK (status IN ('PENDING', 'VERIFIED', 'REVOKED'))
) ENGINE = InnoDB;

-- Contact points are repeatable and are never a unique key: the same value may be shared by
-- several customers and no automatic merge is derived from it.
CREATE TABLE booking_contact_point (
    id                  CHAR(36)      NOT NULL,
    customer_id         CHAR(36)      NOT NULL,
    type                VARCHAR(16)   NOT NULL,
    value_cipher        VARBINARY(512) NOT NULL,
    value_masked        VARCHAR(160)  NOT NULL,
    verification_status VARCHAR(16)   NOT NULL,
    is_primary          TINYINT(1)    NOT NULL DEFAULT 0,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    version             INT           NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT fk_booking_contact_point_customer
        FOREIGN KEY (customer_id) REFERENCES booking_customer (id),
    CONSTRAINT ck_booking_contact_point_type
        CHECK (type IN ('PHONE', 'EMAIL', 'WECHAT', 'OTHER')),
    CONSTRAINT ck_booking_contact_point_status
        CHECK (verification_status IN ('UNVERIFIED', 'VERIFIED', 'REJECTED'))
) ENGINE = InnoDB;

CREATE INDEX ix_booking_contact_point_customer ON booking_contact_point (customer_id, type);

CREATE TABLE booking_customer_relationship (
    id                  CHAR(36)      NOT NULL,
    customer_id         CHAR(36)      NOT NULL,
    related_customer_id CHAR(36)      NOT NULL,
    relation_type       VARCHAR(16)   NOT NULL,
    purpose             VARCHAR(32)   NOT NULL,
    verification_status VARCHAR(16)   NOT NULL,
    valid_from          DATETIME(6)   NOT NULL,
    valid_until         DATETIME(6)   NULL,
    revoked             TINYINT(1)    NOT NULL DEFAULT 0,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    version             INT           NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT fk_booking_customer_relationship_customer
        FOREIGN KEY (customer_id) REFERENCES booking_customer (id),
    CONSTRAINT fk_booking_customer_relationship_related
        FOREIGN KEY (related_customer_id) REFERENCES booking_customer (id),
    CONSTRAINT ck_booking_customer_relationship_status
        CHECK (verification_status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED'))
) ENGINE = InnoDB;

CREATE INDEX ix_booking_customer_relationship_customer
    ON booking_customer_relationship (customer_id, verification_status);

-- A link request carries no access effect until it is approved.
CREATE TABLE booking_customer_link_request (
    id                    CHAR(36)      NOT NULL,
    requester_customer_id CHAR(36)      NOT NULL,
    candidate_ref         VARCHAR(64)   NOT NULL,
    method                VARCHAR(40)   NOT NULL,
    status                VARCHAR(16)   NOT NULL,
    expires_at            DATETIME(6)   NOT NULL,
    reviewed_at           DATETIME(6)   NULL,
    reviewed_by           VARCHAR(64)   NULL,
    created_at            DATETIME(6)   NOT NULL,
    version               INT           NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT fk_booking_customer_link_request_customer
        FOREIGN KEY (requester_customer_id) REFERENCES booking_customer (id),
    CONSTRAINT ck_booking_customer_link_request_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'))
) ENGINE = InnoDB;

-- Slots are materialised rows, not rules evaluated on the fly. A slot id is stable so a
-- reservation can point at it without re-deriving the schedule.
CREATE TABLE booking_service_slot (
    id             CHAR(36)     NOT NULL,
    start_at       DATETIME(6)  NOT NULL,
    end_at         DATETIME(6)  NOT NULL,
    business_zone  VARCHAR(64)  NOT NULL,
    display_offset VARCHAR(16)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    capacity       INT          NOT NULL DEFAULT 1,
    rule_version   VARCHAR(64)  NOT NULL,
    revision       INT          NOT NULL DEFAULT 1,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_booking_service_slot_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_booking_service_slot_capacity CHECK (capacity = 1),
    CONSTRAINT ck_booking_service_slot_range CHECK (end_at > start_at)
) ENGINE = InnoDB;

CREATE INDEX ix_booking_service_slot_window
    ON booking_service_slot (status, start_at, id);

-- A reservation is the only owner of a slot hold. A pending request holds no occupancy row.
CREATE TABLE booking_reservation (
    id                 CHAR(36)      NOT NULL,
    customer_id        CHAR(36)      NOT NULL,
    package_version_ref VARCHAR(64)  NOT NULL,
    source             VARCHAR(16)   NOT NULL,
    status             VARCHAR(24)   NOT NULL,
    expires_at         DATETIME(6)   NOT NULL,
    slot_id            CHAR(36)      NULL,
    schedule_revision  INT           NOT NULL DEFAULT 0,
    confirmed_at       DATETIME(6)   NULL,
    confirmed_by       VARCHAR(64)   NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    version            INT           NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT fk_booking_reservation_customer
        FOREIGN KEY (customer_id) REFERENCES booking_customer (id),
    CONSTRAINT fk_booking_reservation_slot
        FOREIGN KEY (slot_id) REFERENCES booking_service_slot (id),
    CONSTRAINT ck_booking_reservation_status
        CHECK (status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'REJECTED', 'EXPIRED', 'CANCELLED'))
) ENGINE = InnoDB;

CREATE INDEX ix_booking_reservation_status ON booking_reservation (status, created_at, id);
CREATE INDEX ix_booking_reservation_customer ON booking_reservation (customer_id, created_at);

-- The exclusive hold. The primary key on slot_id is the concurrency guard: a second writer for the
-- same slot fails here instead of silently double booking.
CREATE TABLE booking_slot_occupancy (
    slot_id        CHAR(36)    NOT NULL,
    reservation_id CHAR(36)    NOT NULL,
    assigned_at    DATETIME(6) NOT NULL,
    revision       INT         NOT NULL DEFAULT 1,
    PRIMARY KEY (slot_id),
    CONSTRAINT uq_booking_slot_occupancy_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_booking_slot_occupancy_slot
        FOREIGN KEY (slot_id) REFERENCES booking_service_slot (id),
    CONSTRAINT fk_booking_slot_occupancy_reservation
        FOREIGN KEY (reservation_id) REFERENCES booking_reservation (id)
) ENGINE = InnoDB;

CREATE TABLE booking_slot_assignment_history (
    id               CHAR(36)      NOT NULL,
    slot_id          CHAR(36)      NOT NULL,
    reservation_id   CHAR(36)      NOT NULL,
    previous_slot_id CHAR(36)      NULL,
    assigned_at      DATETIME(6)   NOT NULL,
    reason_code      VARCHAR(40)   NOT NULL,
    revision         INT           NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT fk_booking_slot_assignment_history_slot
        FOREIGN KEY (slot_id) REFERENCES booking_service_slot (id),
    CONSTRAINT fk_booking_slot_assignment_history_reservation
        FOREIGN KEY (reservation_id) REFERENCES booking_reservation (id)
) ENGINE = InnoDB;

CREATE INDEX ix_booking_slot_assignment_history_reservation
    ON booking_slot_assignment_history (reservation_id, assigned_at);

-- The order is created once per confirmed reservation.
CREATE TABLE booking_order (
    id                    CHAR(36)     NOT NULL,
    source_reservation_id CHAR(36)     NOT NULL,
    customer_id           CHAR(36)     NOT NULL,
    lifecycle_status      VARCHAR(16)  NOT NULL,
    fulfilment_status     VARCHAR(24)  NOT NULL,
    snapshot_id           CHAR(36)     NOT NULL,
    current_revision      INT          NOT NULL DEFAULT 1,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    version               INT          NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT uq_booking_order_source_reservation UNIQUE (source_reservation_id),
    CONSTRAINT fk_booking_order_reservation
        FOREIGN KEY (source_reservation_id) REFERENCES booking_reservation (id),
    CONSTRAINT fk_booking_order_customer
        FOREIGN KEY (customer_id) REFERENCES booking_customer (id),
    CONSTRAINT ck_booking_order_lifecycle
        CHECK (lifecycle_status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_booking_order_fulfilment
        CHECK (fulfilment_status IN ('UNSCHEDULED', 'SCHEDULED', 'NEEDS_ATTENTION'))
) ENGINE = InnoDB;

-- The snapshot is immutable: no updated_at, no version, no update path in the adapter.
CREATE TABLE booking_order_snapshot (
    id                  CHAR(36)      NOT NULL,
    order_id            CHAR(36)      NOT NULL,
    package_version_ref VARCHAR(64)   NOT NULL,
    package_display_name VARCHAR(120) NOT NULL,
    currency_code       CHAR(3)       NOT NULL,
    amount_minor_units  BIGINT        NOT NULL,
    price_version       VARCHAR(64)   NOT NULL,
    slot_id             CHAR(36)      NULL,
    start_at            DATETIME(6)   NULL,
    end_at              DATETIME(6)   NULL,
    business_zone       VARCHAR(64)   NULL,
    display_offset      VARCHAR(16)   NULL,
    schedule_revision   INT           NOT NULL DEFAULT 0,
    customer_id         CHAR(36)      NOT NULL,
    source              VARCHAR(16)   NOT NULL,
    captured_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_booking_order_snapshot_order UNIQUE (order_id),
    CONSTRAINT fk_booking_order_snapshot_order
        FOREIGN KEY (order_id) REFERENCES booking_order (id),
    CONSTRAINT ck_booking_order_snapshot_amount CHECK (amount_minor_units >= 0)
) ENGINE = InnoDB;

-- Revisions are append only. The unique key on (order_id, revision_no) keeps the sequence dense.
CREATE TABLE booking_order_revision (
    order_id       CHAR(36)      NOT NULL,
    revision_no    INT           NOT NULL,
    command_id     CHAR(36)      NOT NULL,
    actor_ref      VARCHAR(64)   NOT NULL,
    changed_fields VARCHAR(400)  NOT NULL,
    reason_code    VARCHAR(40)   NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (order_id, revision_no),
    CONSTRAINT fk_booking_order_revision_order
        FOREIGN KEY (order_id) REFERENCES booking_order (id)
) ENGINE = InnoDB;

-- The idempotency ledger. The scope is actor plus operation plus client key.
CREATE TABLE booking_command (
    id              CHAR(36)     NOT NULL,
    actor_id        VARCHAR(64)  NOT NULL,
    operation       VARCHAR(48)  NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    request_sha256  CHAR(64)     NOT NULL,
    result_ref      VARCHAR(64)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_booking_command_scope UNIQUE (actor_id, operation, idempotency_key)
) ENGINE = InnoDB;

-- The outbox is written inside the business transaction. It is a lease and retry queue, never a
-- promise of exactly once delivery.
CREATE TABLE booking_notification_outbox (
    id            CHAR(36)     NOT NULL,
    intent        VARCHAR(48)  NOT NULL,
    recipient_ref VARCHAR(64)  NOT NULL,
    object_ref    VARCHAR(64)  NOT NULL,
    due_at        DATETIME(6)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    lease_until   DATETIME(6)  NULL,
    lease_owner   VARCHAR(64)  NULL,
    last_error    VARCHAR(64)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_booking_notification_outbox_status
        CHECK (status IN ('PENDING', 'LEASED', 'SENT', 'FAILED'))
) ENGINE = InnoDB;

CREATE INDEX ix_booking_notification_outbox_due
    ON booking_notification_outbox (status, due_at, id);

-- The lease owner makes the claim auditable and lets a late worker be recognised as stale.
CREATE INDEX ix_booking_notification_outbox_lease
    ON booking_notification_outbox (lease_until, lease_owner);

CREATE TABLE booking_audit (
    id          CHAR(36)      NOT NULL,
    actor_id    VARCHAR(64)   NOT NULL,
    command_id  CHAR(36)      NOT NULL,
    object_type VARCHAR(32)   NOT NULL,
    object_id   VARCHAR(64)   NOT NULL,
    action      VARCHAR(32)   NOT NULL,
    reason_code VARCHAR(40)   NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE INDEX ix_booking_audit_object ON booking_audit (object_type, object_id, created_at);
