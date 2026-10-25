-- V8: immutable package price facts consumed by the atomic confirm-and-create-order command
-- (D-306 §5.1, D-303 structured amount). Q03 real prices belong to 2B; the 2A acceptance run may
-- only carry facts explicitly marked SYNTHETIC.
--
-- Scope rules for this file:
--   * A fact is immutable: there is no updated_at, no version column and no UPDATE path anywhere.
--     Correcting a price means writing a new version_ref, never editing a released one.
--   * data_mode separates synthetic acceptance data from production pricing so a production
--     runtime can never quote a synthetic amount, and acceptance runs can never quote production.
--   * No payment, deposit, refund or delivery column exists anywhere in this migration.
--   * Money is an integer minor-unit amount plus an ISO-4217 code; there is no floating point.

CREATE TABLE admin_package_fact (
    version_ref        CHAR(36)     NOT NULL,
    display_name       VARCHAR(120) NOT NULL,
    currency_code      CHAR(3)      NOT NULL,
    amount_minor_units BIGINT       NOT NULL,
    price_version      VARCHAR(64)  NOT NULL,
    data_mode          VARCHAR(16)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (version_ref),
    CONSTRAINT ck_admin_package_fact_mode CHECK (data_mode IN ('SYNTHETIC', 'PRODUCTION')),
    CONSTRAINT ck_admin_package_fact_amount CHECK (amount_minor_units >= 0),
    CONSTRAINT ck_admin_package_fact_currency CHECK (currency_code REGEXP '^[A-Z][A-Z][A-Z]$'),
    CONSTRAINT ck_admin_package_fact_price_version CHECK (CHAR_LENGTH(price_version) > 0)
) ENGINE = InnoDB;
