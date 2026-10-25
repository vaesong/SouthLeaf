-- S2-B per-work public overlay (ADR-005). V1 release tables stay untouched and read-only.
-- Rows are inserted only by the management publication writer; public readers get SELECT only.

CREATE TABLE public_work_release (
  release_id CHAR(36) NOT NULL,
  work_id CHAR(36) NOT NULL,
  work_version INT NOT NULL,
  title VARCHAR(80) NOT NULL,
  summary VARCHAR(400) NOT NULL,
  cover_media_id CHAR(36) NOT NULL,
  subject_scope_ref VARCHAR(64) NOT NULL,
  data_mode VARCHAR(16) NOT NULL,
  published_at DATETIME(6) NOT NULL,
  PRIMARY KEY (release_id),
  KEY ix_public_work_release_work (work_id, work_version),
  CONSTRAINT ck_public_work_release_mode CHECK (data_mode IN ('demo','production')),
  CONSTRAINT ck_public_work_release_version CHECK (work_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE public_work_release_business (
  release_id CHAR(36) NOT NULL,
  position TINYINT NOT NULL,
  business_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (release_id, position),
  UNIQUE KEY uk_public_work_release_business (release_id, business_id),
  CONSTRAINT fk_public_work_release_business FOREIGN KEY (release_id) REFERENCES public_work_release (release_id),
  CONSTRAINT ck_public_work_release_business_position CHECK (position BETWEEN 0 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE public_work_release_media (
  release_id CHAR(36) NOT NULL,
  media_id CHAR(36) NOT NULL,
  position TINYINT NOT NULL,
  alt_text VARCHAR(120) NOT NULL,
  PRIMARY KEY (release_id, media_id),
  UNIQUE KEY uk_public_work_release_media_position (release_id, position),
  CONSTRAINT fk_public_work_release_media FOREIGN KEY (release_id) REFERENCES public_work_release (release_id),
  CONSTRAINT ck_public_work_release_media_position CHECK (position BETWEEN 0 AND 8)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE public_work_release_variant (
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
  CONSTRAINT fk_public_work_release_variant FOREIGN KEY (release_id, media_id)
    REFERENCES public_work_release_media (release_id, media_id),
  CONSTRAINT ck_public_work_release_variant_name CHECK (variant IN ('thumbnail','preview')),
  CONSTRAINT ck_public_work_release_variant_type CHECK (content_type IN ('image/jpeg','image/png')),
  CONSTRAINT ck_public_work_release_variant_key CHECK (REGEXP_LIKE(object_key, '^[0-9a-f]{64}\\.(jpg|png)$', 'c')),
  CONSTRAINT ck_public_work_release_variant_key_sha CHECK (LEFT(object_key, 64) = sha256),
  CONSTRAINT ck_public_work_release_variant_size CHECK (byte_length BETWEEN 1 AND 4194304)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE public_work_release_authorization (
  release_id CHAR(36) NOT NULL,
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
  PRIMARY KEY (release_id),
  CONSTRAINT fk_public_work_release_authorization FOREIGN KEY (release_id) REFERENCES public_work_release (release_id),
  CONSTRAINT ck_public_work_release_authorization_state CHECK (state = 'VALID'),
  CONSTRAINT ck_public_work_release_authorization_provenance CHECK (provenance IN ('TEST_SYNTHETIC','VERIFIED_AUTHORITY')),
  CONSTRAINT ck_public_work_release_authorization_window CHECK (valid_from < valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE active_work_publication (
  work_id CHAR(36) NOT NULL,
  release_id CHAR(36) NOT NULL,
  visible TINYINT(1) NOT NULL,
  changed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (work_id),
  CONSTRAINT fk_active_work_publication_release FOREIGN KEY (release_id) REFERENCES public_work_release (release_id),
  CONSTRAINT ck_active_work_publication_visible CHECK (visible IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE public_work_projection_epoch (
  singleton_id TINYINT NOT NULL,
  epoch BIGINT NOT NULL,
  changed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (singleton_id),
  CONSTRAINT ck_public_work_projection_singleton CHECK (singleton_id = 1),
  CONSTRAINT ck_public_work_projection_epoch CHECK (epoch >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO public_work_projection_epoch (singleton_id, epoch, changed_at)
VALUES (1, 0, UTC_TIMESTAMP(6));
