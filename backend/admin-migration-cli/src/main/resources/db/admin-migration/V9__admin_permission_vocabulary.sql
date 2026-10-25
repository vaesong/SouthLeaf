-- V9: the account-permission vocabulary must cover every capability the runtime checks.
--
-- V2 froze the CHECK list before the outbox pending-work surface existed, so OUTBOX_READ and
-- OUTBOX_RECOVER could never be granted: the endpoints were reachable only in unit tests, never
-- by a real account. This migration widens the same constraint to the exact vocabulary of
-- cn.zhinan.adminsecurity.domain.AdminPermission (S6 outbox review and manual recovery).
--
-- Only the constraint is replaced; rows, keys and grants are untouched, and no default account or
-- default credential is introduced.

ALTER TABLE admin_account_permission
    DROP CHECK admin_account_permission_chk_1;

ALTER TABLE admin_account_permission
    ADD CONSTRAINT ck_admin_account_permission_code CHECK (
        permission_code IN (
            'CONTENT_READ','CONTENT_EDIT','CONTENT_PUBLISH',
            'PUBLIC_AUTHORIZATION_REVIEW','PUBLIC_AUTHORIZATION_REVOKE',
            'RESERVATION_READ','RESERVATION_CONFIRM','RESERVATION_CANCEL','RESERVATION_RESCHEDULE',
            'ORDER_READ','OUTBOX_READ','OUTBOX_RECOVER','AUDIT_READ','STAFF_ACCESS_MANAGE'
        )
    );
