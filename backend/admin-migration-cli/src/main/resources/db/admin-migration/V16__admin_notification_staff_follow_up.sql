-- A staff follow-up is neither a sent notification nor a dead letter.
ALTER TABLE booking_notification_outbox
    DROP CHECK ck_booking_notification_outbox_status;

ALTER TABLE booking_notification_outbox
    ADD CONSTRAINT ck_booking_notification_outbox_status
        CHECK (status IN ('PENDING', 'LEASED', 'SENT', 'FAILED', 'STAFF'));
