-- Apply once, with apps stopped. Back up existing data; never infer provider/paymentKey for legacy rows.
-- Verify existing payment/status column type before applying (MySQL enum vs varchar).
ALTER TABLE payment MODIFY COLUMN status ENUM('PENDING','COMPLETED','FAILED','CANCELED') NOT NULL,
 ADD COLUMN provider VARCHAR(255) NULL,
 ADD COLUMN pg_payment_key VARCHAR(200) NULL,
 ADD COLUMN confirm_key VARCHAR(255) NULL,
 ADD COLUMN cancel_key VARCHAR(255) NULL,
 ADD COLUMN cancel_reason VARCHAR(255) NULL,
 ADD COLUMN recovery_phase VARCHAR(32) NULL,
 ADD COLUMN next_retry_at DATETIME(6) NULL,
 ADD COLUMN last_checked_at DATETIME(6) NULL,
 ADD COLUMN last_pg_status VARCHAR(255) NULL,
 ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
 ADD COLUMN review_required BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN last_error VARCHAR(1000) NULL,
 ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN updated_at DATETIME(6) NULL,
 ADD COLUMN canceled_at DATETIME(6) NULL,
 ADD UNIQUE KEY uk_payment_pg_key(pg_payment_key),
 ADD KEY idx_payment_recovery(recovery_phase,next_retry_at);
ALTER TABLE payment_compensation_outbox ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
-- Legacy unresolved/failed PG records require reconciliation; these fields cannot reconstruct missing PG identifiers.
-- local ddl-auto=update is not a substitute for backfilling version/retry_count/review_required on existing rows.
