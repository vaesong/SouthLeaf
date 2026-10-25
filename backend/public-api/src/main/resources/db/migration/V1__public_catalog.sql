CREATE TABLE catalog_release (
id VARCHAR(64) PRIMARY KEY,
content_version VARCHAR(128) NOT NULL UNIQUE,
data_mode VARCHAR(16) NOT NULL CHECK (data_mode IN ('demo','production')),
approval_status VARCHAR(16) NOT NULL CHECK (approval_status IN ('DEMO_ONLY','APPROVED')),
created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
CHECK (data_mode <> 'production' OR approval_status = 'APPROVED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE active_publication (
singleton_id TINYINT PRIMARY KEY CHECK (singleton_id=1),
release_id VARCHAR(64) NOT NULL, FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE media_asset (
release_id VARCHAR(64) NOT NULL,
id VARCHAR(128) NOT NULL, alt_text VARCHAR(1024) NOT NULL, PRIMARY KEY(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE media_variant (
release_id VARCHAR(64) NOT NULL,
media_id VARCHAR(128) NOT NULL,
variant VARCHAR(16) NOT NULL CHECK (variant IN ('thumbnail','preview')),
object_key VARCHAR(255) NOT NULL, sha256 CHAR(64) NOT NULL,
byte_length BIGINT NOT NULL CHECK(byte_length BETWEEN 1 AND 4194304),
width INT NOT NULL CHECK(width BETWEEN 1 AND 8192), height INT NOT NULL CHECK(height BETWEEN 1 AND 8192),
content_type VARCHAR(32) NOT NULL CHECK(content_type IN ('image/png','image/jpeg')),
PRIMARY KEY(release_id,media_id,variant), FOREIGN KEY(release_id,media_id) REFERENCES media_asset(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE studio_brand (
release_id VARCHAR(64) NOT NULL,
name VARCHAR(255) NOT NULL, english_name VARCHAR(255) NOT NULL,
discovery_heading VARCHAR(255) NOT NULL, share_title VARCHAR(255) NOT NULL,
tagline VARCHAR(1024) NOT NULL, description TEXT NOT NULL, PRIMARY KEY(release_id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE studio_contact (
release_id VARCHAR(64) NOT NULL,
phone VARCHAR(64) NOT NULL, address VARCHAR(1024) NOT NULL, working_hours VARCHAR(1024) NOT NULL, PRIMARY KEY(release_id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE team_member (
release_id VARCHAR(64) NOT NULL,
id VARCHAR(128) NOT NULL, name VARCHAR(255) NOT NULL, role VARCHAR(255) NOT NULL,
bio TEXT NOT NULL, portrait_media_id VARCHAR(128), position INT NOT NULL CHECK(position>=0),
PRIMARY KEY(release_id,id), UNIQUE(release_id,position),
FOREIGN KEY(release_id,portrait_media_id) REFERENCES media_asset(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE team_specialty (
release_id VARCHAR(64) NOT NULL,
member_id VARCHAR(128) NOT NULL, position INT NOT NULL CHECK(position>=0), label VARCHAR(255) NOT NULL,
PRIMARY KEY(release_id,member_id,position), FOREIGN KEY(release_id,member_id) REFERENCES team_member(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE contact_channel (
release_id VARCHAR(64) NOT NULL,
id VARCHAR(128) NOT NULL, platform VARCHAR(32) NOT NULL CHECK(platform IN ('wechat','douyin','xiaohongshu')),
label VARCHAR(255) NOT NULL, account VARCHAR(255) NOT NULL, qr_media_id VARCHAR(128),
qr_status VARCHAR(16) NOT NULL CHECK(qr_status IN ('CONFIRMED','PLACEHOLDER','UNCONFIGURED')),
position INT NOT NULL CHECK(position>=0), PRIMARY KEY(release_id,id), UNIQUE(release_id,position),
FOREIGN KEY(release_id,qr_media_id) REFERENCES media_asset(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE photography_business (
release_id VARCHAR(64) NOT NULL,
id VARCHAR(128) NOT NULL, name VARCHAR(255) NOT NULL, summary TEXT NOT NULL,
cover_media_id VARCHAR(128), position INT NOT NULL CHECK(position>=0),
PRIMARY KEY(release_id,id), UNIQUE(release_id,position),
FOREIGN KEY(release_id,cover_media_id) REFERENCES media_asset(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE catalog_package (
release_id VARCHAR(64) NOT NULL,
id VARCHAR(128) NOT NULL, business_id VARCHAR(128) NOT NULL,
name VARCHAR(255) NOT NULL, summary TEXT NOT NULL, suitable_for TEXT NOT NULL,
duration_minutes INT CHECK(duration_minutes>0), display_price VARCHAR(255) NOT NULL,
price_type VARCHAR(16) NOT NULL CHECK(price_type IN ('INQUIRY','FROM','FIXED','UNDISCLOSED')),
published BOOLEAN NOT NULL, published_at DATETIME(6) NOT NULL, cover_media_id VARCHAR(128),
position INT NOT NULL CHECK(position>=0), PRIMARY KEY(release_id,id), UNIQUE(release_id,position),
FOREIGN KEY(release_id,business_id) REFERENCES photography_business(release_id,id),
FOREIGN KEY(release_id,cover_media_id) REFERENCES media_asset(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE package_line (
release_id VARCHAR(64) NOT NULL,
package_id VARCHAR(128) NOT NULL, kind VARCHAR(16) NOT NULL CHECK(kind IN ('SERVICE','TIP','NOTICE')),
position INT NOT NULL CHECK(position>=0), text_value TEXT NOT NULL, PRIMARY KEY(release_id,package_id,kind,position),
FOREIGN KEY(release_id,package_id) REFERENCES catalog_package(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE public_work (
release_id VARCHAR(64) NOT NULL,
id VARCHAR(128) NOT NULL, title VARCHAR(255) NOT NULL, summary TEXT NOT NULL, description TEXT NOT NULL,
package_label VARCHAR(255) NOT NULL, publication_status VARCHAR(16) NOT NULL CHECK(publication_status IN ('published','draft','withdrawn')),
published_at DATETIME(6) NOT NULL, cover_media_id VARCHAR(128) NOT NULL,
candidate_version VARCHAR(128) NOT NULL, binding_version VARCHAR(128) NOT NULL, subject_scope_ref VARCHAR(128) NOT NULL,
position INT NOT NULL CHECK(position>=0), PRIMARY KEY(release_id,id), UNIQUE(release_id,position),
INDEX ix_work_published(release_id,publication_status,published_at,id),
FOREIGN KEY(release_id,cover_media_id) REFERENCES media_asset(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE work_business (
release_id VARCHAR(64) NOT NULL,
work_id VARCHAR(128) NOT NULL, business_id VARCHAR(128) NOT NULL,
position INT NOT NULL CHECK(position>=0), PRIMARY KEY(release_id,work_id,business_id), UNIQUE(release_id,work_id,position),
FOREIGN KEY(release_id,work_id) REFERENCES public_work(release_id,id),
FOREIGN KEY(release_id,business_id) REFERENCES photography_business(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE work_media (
release_id VARCHAR(64) NOT NULL,
work_id VARCHAR(128) NOT NULL, media_id VARCHAR(128) NOT NULL,
position INT NOT NULL CHECK(position>=0), PRIMARY KEY(release_id,work_id,media_id), UNIQUE(release_id,work_id,position),
FOREIGN KEY(release_id,work_id) REFERENCES public_work(release_id,id),
FOREIGN KEY(release_id,media_id) REFERENCES media_asset(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE home_featured_business (
release_id VARCHAR(64) NOT NULL,
business_id VARCHAR(128) NOT NULL, position INT NOT NULL CHECK(position BETWEEN 0 AND 2),
PRIMARY KEY(release_id,business_id), UNIQUE(release_id,position),
FOREIGN KEY(release_id,business_id) REFERENCES photography_business(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE home_featured_package (
release_id VARCHAR(64) NOT NULL,
package_id VARCHAR(128) NOT NULL, position INT NOT NULL CHECK(position BETWEEN 0 AND 2),
PRIMARY KEY(release_id,package_id), UNIQUE(release_id,position),
FOREIGN KEY(release_id,package_id) REFERENCES catalog_package(release_id,id),
FOREIGN KEY (release_id) REFERENCES catalog_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE public_authorization (
work_id VARCHAR(128) PRIMARY KEY, release_id VARCHAR(64) NOT NULL,
candidate_version VARCHAR(128) NOT NULL, binding_version VARCHAR(128) NOT NULL, subject_scope_ref VARCHAR(128) NOT NULL,
purpose VARCHAR(64) NOT NULL, channel VARCHAR(64) NOT NULL,
state VARCHAR(16) NOT NULL CHECK(state IN ('VALID','INVALID','UNKNOWN','REVOKED')),
valid_from DATETIME(6) NOT NULL, valid_until DATETIME(6) NOT NULL,
authority_revision VARCHAR(128) NOT NULL, fact_digest VARCHAR(128) NOT NULL, evidence_ref VARCHAR(255) NOT NULL,
provenance VARCHAR(32) NOT NULL CHECK(provenance IN ('DEMO_GENERATED','VERIFIED_AUTHORITY')),
CHECK(valid_until>valid_from),
UNIQUE(work_id,release_id), FOREIGN KEY(release_id,work_id) REFERENCES public_work(release_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE authorization_media (
work_id VARCHAR(128) NOT NULL, release_id VARCHAR(64) NOT NULL, media_id VARCHAR(128) NOT NULL,
PRIMARY KEY(work_id,media_id), FOREIGN KEY(work_id,release_id) REFERENCES public_authorization(work_id,release_id),
FOREIGN KEY(release_id,media_id) REFERENCES media_asset(release_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
