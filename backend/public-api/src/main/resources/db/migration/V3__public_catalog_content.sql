-- V3: immutable full-catalog publication overlay. V1/V2 remain untouched and are used only
-- until the first V3 release is published. Withdrawals install an explicit invisible pointer.
CREATE TABLE public_catalog_release (
    release_id CHAR(36) NOT NULL,
    revision INT NOT NULL,
    content_version VARCHAR(64) NOT NULL,
    data_mode VARCHAR(16) NOT NULL,
    snapshot_json JSON NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (release_id),
    CONSTRAINT ck_public_catalog_release_revision CHECK (revision > 0),
    CONSTRAINT ck_public_catalog_release_mode CHECK (data_mode IN ('DEMO', 'PRODUCTION'))
) ENGINE=InnoDB;

CREATE TABLE active_catalog_publication (
    singleton_id TINYINT NOT NULL,
    release_id CHAR(36) NULL,
    visible TINYINT(1) NOT NULL,
    epoch BIGINT NOT NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT ck_active_catalog_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_active_catalog_visible CHECK (visible IN (0,1)),
    CONSTRAINT ck_active_catalog_epoch CHECK (epoch >= 0),
    CONSTRAINT fk_active_catalog_release FOREIGN KEY (release_id)
      REFERENCES public_catalog_release (release_id)
) ENGINE=InnoDB;

INSERT INTO active_catalog_publication (singleton_id, release_id, visible, epoch)
VALUES (1, NULL, 0, 0);

-- Media is copied only after the publication writer rechecks READY and both prepared variants.
-- Object keys remain server-side metadata and are never part of public JSON DTOs.
CREATE TABLE public_catalog_release_media (
    release_id CHAR(36) NOT NULL,
    media_id CHAR(36) NOT NULL,
    alt_text VARCHAR(120) NOT NULL,
    PRIMARY KEY (release_id, media_id),
    CONSTRAINT fk_public_catalog_media_release FOREIGN KEY (release_id)
      REFERENCES public_catalog_release (release_id)
) ENGINE=InnoDB;

CREATE TABLE public_catalog_release_variant (
    release_id CHAR(36) NOT NULL,
    media_id CHAR(36) NOT NULL,
    variant VARCHAR(16) NOT NULL,
    object_key VARCHAR(80) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    byte_length INT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    content_type VARCHAR(16) NOT NULL,
    PRIMARY KEY (release_id, media_id, variant),
    CONSTRAINT fk_public_catalog_variant_media FOREIGN KEY (release_id, media_id)
      REFERENCES public_catalog_release_media (release_id, media_id),
    CONSTRAINT ck_public_catalog_variant_name CHECK (variant IN ('thumbnail','preview')),
    CONSTRAINT ck_public_catalog_variant_type CHECK (content_type IN ('image/jpeg','image/png'))
) ENGINE=InnoDB;

CREATE TABLE public_catalog_release_binding (
    release_id CHAR(36) NOT NULL,
    media_id CHAR(36) NOT NULL,
    owner_type VARCHAR(24) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    position INT NOT NULL,
    PRIMARY KEY (release_id, owner_type, owner_id, position),
    CONSTRAINT fk_public_catalog_binding_media FOREIGN KEY (release_id, media_id)
      REFERENCES public_catalog_release_media (release_id, media_id),
    CONSTRAINT ck_public_catalog_binding_owner CHECK
      (owner_type IN ('BRAND','TEAM_MEMBER','CONTACT_CHANNEL','BUSINESS','PACKAGE'))
) ENGINE=InnoDB;
