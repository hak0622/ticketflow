ALTER TABLE payment_compensation_outbox
    ADD COLUMN last_error VARCHAR(1000) NULL,
    ADD COLUMN failed_at DATETIME(6) NULL;
