-- Accept a camera original up to 12000px on a side, and any number of pictures on one work.
-- Stored thumbnail and preview objects stay within the existing 4MB and 8192px variant checks.

ALTER TABLE admin_media_asset
    DROP CHECK ck_admin_media_dims;

ALTER TABLE admin_media_asset
    ADD CONSTRAINT ck_admin_media_dims CHECK (
        source_width BETWEEN 1 AND 12000 AND source_height BETWEEN 1 AND 12000
    );

ALTER TABLE admin_work_version_media
    MODIFY position SMALLINT NOT NULL,
    DROP CHECK ck_admin_work_media_position;

ALTER TABLE admin_work_version_media
    ADD CONSTRAINT ck_admin_work_media_position CHECK (position >= 0);
