-- A published work can carry more than nine pictures. Variant size limits are unchanged.

ALTER TABLE public_work_release_media
    MODIFY position SMALLINT NOT NULL,
    DROP CHECK ck_public_work_release_media_position;

ALTER TABLE public_work_release_media
    ADD CONSTRAINT ck_public_work_release_media_position CHECK (position >= 0);
