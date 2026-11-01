-- V12: versioned booking operating rules, and the capability that may append a version.
--
-- A missing head row means the studio has not saved a rule. There is no seeded timezone,
-- lifetime or duration. Each save inserts a new payload row and moves the pointer; old rows stay.
-- BOOKING_RULES_WRITE is not granted here. Accounts receive it through the existing
-- account CLI (bootstrap or replace-permissions), the same path as every other capability.

CREATE TABLE admin_booking_operating_rule (
    singleton_id TINYINT NOT NULL,
    current_version INT NOT NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT ck_admin_booking_operating_rule_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_admin_booking_operating_rule_version CHECK (current_version >= 0)
) ENGINE=InnoDB;

CREATE TABLE admin_booking_operating_rule_version (
    version INT NOT NULL,
    payload_json JSON NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (version),
    CONSTRAINT ck_admin_booking_operating_rule_version_positive CHECK (version >= 1)
) ENGINE=InnoDB;

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
            'STAFF_ACCESS_MANAGE','BOOKING_RULES_WRITE'
        )
    );
