-- V4: homepage slides bind their own prepared images. Applied V3 stays byte-identical.
-- Owner ids are slide ids, not the jump target, so two slides may point at one work.
ALTER TABLE public_catalog_release_binding
    DROP CHECK ck_public_catalog_binding_owner;

ALTER TABLE public_catalog_release_binding
    ADD CONSTRAINT ck_public_catalog_binding_owner CHECK (
        owner_type IN ('BRAND','TEAM_MEMBER','CONTACT_CHANNEL','BUSINESS','PACKAGE','HERO')
    );
