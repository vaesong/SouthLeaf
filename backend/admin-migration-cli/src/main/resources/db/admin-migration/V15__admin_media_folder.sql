-- V15: folders for the media library. Existing images stay in the root (folder_id NULL).
-- Applied V5 and V14 stay unchanged.

CREATE TABLE admin_media_folder (
  folder_id CHAR(36) NOT NULL,
  parent_id CHAR(36) NULL,
  name VARCHAR(40) NOT NULL,
  parent_key CHAR(36) AS (IFNULL(parent_id, '00000000-0000-0000-0000-000000000000')) STORED,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (folder_id),
  UNIQUE KEY uk_admin_media_folder_sibling (parent_key, name),
  CONSTRAINT fk_admin_media_folder_parent FOREIGN KEY (parent_id) REFERENCES admin_media_folder (folder_id),
  CONSTRAINT ck_admin_media_folder_name CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 40),
  CONSTRAINT ck_admin_media_folder_not_self CHECK (parent_id IS NULL OR parent_id <> folder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE admin_media_asset
  ADD COLUMN folder_id CHAR(36) NULL,
  ADD CONSTRAINT fk_admin_media_asset_folder FOREIGN KEY (folder_id) REFERENCES admin_media_folder (folder_id);
