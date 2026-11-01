-- V14: accept a 30MB source image and remember its display file name.
-- Applied V5 stays unchanged. Variant rows remain limited to 4MB.
-- The original upload is not stored; source_byte_length is only the measured input size.

ALTER TABLE admin_media_asset
    DROP CHECK ck_admin_media_size;

ALTER TABLE admin_media_asset
    ADD CONSTRAINT ck_admin_media_size CHECK (source_byte_length BETWEEN 1 AND 31457280);

ALTER TABLE admin_media_asset
    ADD COLUMN source_file_name VARCHAR(120) NULL,
    ADD CONSTRAINT ck_admin_media_source_file_name CHECK (
        source_file_name IS NULL OR CHAR_LENGTH(source_file_name) BETWEEN 1 AND 120
    );
