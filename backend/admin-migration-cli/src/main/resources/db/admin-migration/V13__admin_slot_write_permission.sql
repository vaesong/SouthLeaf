-- V13: capability for opening and closing slots.
--
-- SLOT_WRITE is not granted here. Accounts receive it through the existing account CLI
-- (bootstrap or replace-permissions), together with every capability that account must keep.
-- BOOKING_RULES_WRITE stays in the vocabulary; this script only replaces the check.

ALTER TABLE admin_account_permission
    DROP CHECK ck_admin_account_permission_code;

ALTER TABLE admin_account_permission
    ADD CONSTRAINT ck_admin_account_permission_code CHECK (
        permission_code IN (
            'CONTENT_READ','CONTENT_EDIT','CONTENT_PUBLISH',
            'PUBLIC_AUTHORIZATION_REVIEW','PUBLIC_AUTHORIZATION_REVOKE',
            'RESERVATION_READ','RESERVATION_CONFIRM','RESERVATION_REJECT',
            'RESERVATION_CANCEL','RESERVATION_RESCHEDULE',
            'ORDER_READ','OUTBOX_READ','OUTBOX_RECOVER','AUDIT_READ',
            'STAFF_ACCESS_MANAGE','BOOKING_RULES_WRITE','SLOT_WRITE'
        )
    );
