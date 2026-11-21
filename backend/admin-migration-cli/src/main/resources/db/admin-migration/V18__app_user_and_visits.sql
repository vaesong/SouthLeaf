-- V18: mini-program users and visit trails (A17, D-310).
--
-- These tables are private to southleaf_admin. A login does not insert a booking customer.
-- Raw WeChat identifiers are not stored. USER_READ and USER_PROFILE_WRITE are granted to
-- accounts that already handle reservations, which is the single operator on this deployment.

ALTER TABLE admin_account_permission
    DROP CHECK ck_admin_account_permission_code;

ALTER TABLE admin_account_permission
    ADD CONSTRAINT ck_admin_account_permission_code CHECK (
        permission_code IN (
            'CONTENT_READ','CONTENT_EDIT','CONTENT_PUBLISH',
            'PUBLIC_AUTHORIZATION_REVIEW','PUBLIC_AUTHORIZATION_REVOKE',
            'RESERVATION_READ','RESERVATION_CONFIRM','RESERVATION_REJECT',
            'RESERVATION_CANCEL','RESERVATION_RESCHEDULE',
            'ORDER_READ','OUTBOX_READ','OUTBOX_RECOVER','AUDIT_READ',
            'STAFF_ACCESS_MANAGE','BOOKING_RULES_WRITE','SLOT_WRITE',
            'USER_READ','USER_PROFILE_WRITE'
        )
    );

INSERT INTO admin_account_permission (account_id, permission_code)
SELECT account_id, 'USER_READ'
FROM admin_account_permission
WHERE permission_code = 'RESERVATION_READ';

INSERT INTO admin_account_permission (account_id, permission_code)
SELECT account_id, 'USER_PROFILE_WRITE'
FROM admin_account_permission
WHERE permission_code = 'RESERVATION_READ';

CREATE TABLE app_user (
    id                  CHAR(36)     NOT NULL,
    provider            VARCHAR(32)  NOT NULL,
    app_scope           VARCHAR(64)  NOT NULL,
    subject_fingerprint CHAR(64)     NOT NULL,
    display_name        VARCHAR(128) NULL,
    note                VARCHAR(2000) NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    last_seen_at        DATETIME(6)  NOT NULL,
    version             INT          NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT uq_app_user_subject UNIQUE (provider, app_scope, subject_fingerprint)
) ENGINE = InnoDB;

CREATE INDEX ix_app_user_seen ON app_user (last_seen_at, id);

CREATE TABLE app_user_avatar (
    user_id      CHAR(36)     NOT NULL,
    content_type VARCHAR(32)  NOT NULL,
    byte_size    INT          NOT NULL,
    content      MEDIUMBLOB   NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_app_user_avatar_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_app_user_avatar_size CHECK (byte_size > 0 AND byte_size <= 524288)
) ENGINE = InnoDB;

CREATE TABLE app_user_audit (
    id          CHAR(36)    NOT NULL,
    actor_id    VARCHAR(64) NOT NULL,
    user_id     CHAR(36)    NOT NULL,
    field_name  VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_app_user_audit_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB;

CREATE TABLE share_card (
    id             CHAR(36)    NOT NULL,
    user_id        CHAR(36)    NOT NULL,
    parent_card_id CHAR(36)    NULL,
    page           VARCHAR(64) NOT NULL,
    content_id     VARCHAR(64) NULL,
    scene          INT         NULL,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_share_card_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_share_card_parent FOREIGN KEY (parent_card_id) REFERENCES share_card (id)
) ENGINE = InnoDB;

CREATE INDEX ix_share_card_parent ON share_card (parent_card_id, created_at);
CREATE INDEX ix_share_card_user ON share_card (user_id, created_at);

CREATE TABLE visit_event (
    id              CHAR(36)    NOT NULL,
    user_id         CHAR(36)    NOT NULL,
    client_event_id CHAR(36)    NOT NULL,
    page            VARCHAR(64) NOT NULL,
    content_id      VARCHAR(64) NULL,
    share_card_id   CHAR(36)    NULL,
    scene           INT         NULL,
    occurred_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_visit_event_client UNIQUE (user_id, client_event_id),
    CONSTRAINT fk_visit_event_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_visit_event_share FOREIGN KEY (share_card_id) REFERENCES share_card (id)
) ENGINE = InnoDB;

CREATE INDEX ix_visit_event_user ON visit_event (user_id, occurred_at, id);
CREATE INDEX ix_visit_event_share ON visit_event (share_card_id, occurred_at);
