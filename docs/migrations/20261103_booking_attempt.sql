-- MySQL 8. Apply while all booking/payment/expiry/compensation writers are stopped.
CREATE TABLE booking_attempt (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    concert_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    booking_id BIGINT NULL,
    status VARCHAR(24) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    concert_title VARCHAR(255) NOT NULL,
    last_error VARCHAR(500) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    next_retry_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_attempt_booking UNIQUE (booking_id),
    INDEX idx_attempt_recovery (status, next_retry_at),
    INDEX idx_attempt_concert (concert_id)
);

-- Existing active bookings already consumed stock. Backfill WITHOUT decrementing Redis.
INSERT INTO booking_attempt
(id, concert_id, user_id, booking_id, status, result_status, concert_title, retry_count, created_at, next_retry_at)
SELECT CONCAT('legacy-', b.id), b.concert_id, b.user_id, b.id, 'BOOKED', 'BOOKED', c.title, 0, b.created_at, NOW(6)
FROM booking b JOIN concert c ON c.id = b.concert_id
WHERE b.status IN ('PENDING_PAYMENT', 'CONFIRMED');

-- Export each row to seed its Redis hash + active-owner key (no TTL).
-- HSET booking:attempt:<id> owner <concert_id>:<user_id> state HELD remaining 0 admissionExpires 0
-- SET booking:active:concert:<concert_id>:user:<user_id> <id>
SELECT id, concert_id, user_id FROM booking_attempt WHERE id LIKE 'legacy-%';
-- Audit legacy CANCELLED bookings and pending/failed outboxes manually: CANCELLED alone
-- does not prove whether Redis was restored. Do not automatically seed/restore them.
