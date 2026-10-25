-- V10: refusing a request is a decision of its own, so it carries its own capability.
--
-- Confirming and rejecting answer the same pending question but have different consequences: a
-- confirm occupies a slot and creates an order, a reject only closes the request. Sharing one
-- capability would let an account that may cancel a confirmed order also answer new requests, and
-- would blur the audit trail. The vocabulary is closed, so the constraint is replaced, not widened
-- by convention: only the codes in cn.zhinan.adminsecurity.domain.AdminPermission are admissible.

ALTER TABLE admin_account_permission
    DROP CHECK ck_admin_account_permission_code;

ALTER TABLE admin_account_permission
    ADD CONSTRAINT ck_admin_account_permission_code CHECK (
        permission_code IN (
            'CONTENT_READ','CONTENT_EDIT','CONTENT_PUBLISH',
            'PUBLIC_AUTHORIZATION_REVIEW','PUBLIC_AUTHORIZATION_REVOKE',
            'RESERVATION_READ','RESERVATION_CONFIRM','RESERVATION_REJECT',
            'RESERVATION_CANCEL','RESERVATION_RESCHEDULE',
            'ORDER_READ','OUTBOX_READ','OUTBOX_RECOVER','AUDIT_READ','STAFF_ACCESS_MANAGE'
        )
    );
