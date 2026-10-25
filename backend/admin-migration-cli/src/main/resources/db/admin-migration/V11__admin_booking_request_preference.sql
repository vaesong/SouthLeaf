-- V11: the slot a customer asked for, kept apart from the slot an operator grants (D-305, S5/W3).
--
-- Scope rules for this file:
--   * A pending request still holds no occupancy row; this table records intent only.
--   * Confirmation remains the only writer of booking_slot_occupancy and of the order.
--   * One request may name more than one acceptable slot; requested_at orders the preference.

CREATE TABLE booking_request_preference (
    reservation_id CHAR(36)    NOT NULL,
    slot_id        CHAR(36)    NOT NULL,
    requested_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (reservation_id, slot_id),
    CONSTRAINT fk_booking_request_preference_reservation
        FOREIGN KEY (reservation_id) REFERENCES booking_reservation (id),
    CONSTRAINT fk_booking_request_preference_slot
        FOREIGN KEY (slot_id) REFERENCES booking_service_slot (id)
) ENGINE = InnoDB;

CREATE INDEX ix_booking_request_preference_slot
    ON booking_request_preference (slot_id, reservation_id);
