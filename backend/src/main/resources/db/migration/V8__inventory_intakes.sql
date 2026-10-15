-- Additive intake records only. Existing book/copy/loan/source values are preserved.
CREATE TABLE inventory_intake_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARBINARY(128) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preview_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    purchase_order_id BIGINT NULL,
    operator_id BIGINT NOT NULL,
    request_json LONGTEXT NOT NULL,
    preview_json LONGTEXT NOT NULL,
    state VARCHAR(20) NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at DATETIME NULL,
    receipt_json LONGTEXT NULL,
    UNIQUE KEY uk_intake_idempotency (idempotency_key),
    KEY idx_intake_purchase (purchase_order_id,id),
    KEY idx_intake_operator (operator_id,created_at),
    CONSTRAINT ck_intake_source CHECK ((source_type='MANUAL' AND purchase_order_id IS NULL) OR (source_type='PURCHASE' AND purchase_order_id IS NOT NULL)),
    CONSTRAINT ck_intake_state CHECK ((state='PREVIEWED' AND confirmed_at IS NULL AND receipt_json IS NULL) OR (state='COMMITTED' AND confirmed_at IS NOT NULL AND receipt_json IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inventory_intake_copy (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    intake_id BIGINT NOT NULL,
    line_key VARBINARY(128) NOT NULL,
    copy_ordinal INT NOT NULL,
    book_id BIGINT NOT NULL,
    copy_id BIGINT NOT NULL,
    purchase_item_id BIGINT NULL,
    stock_flow_id BIGINT NOT NULL,
    barcode_input VARCHAR(20) NOT NULL,
    UNIQUE KEY uk_intake_copy_identity (copy_id),
    UNIQUE KEY uk_intake_copy_position (intake_id,line_key,copy_ordinal),
    KEY idx_intake_item (purchase_item_id,intake_id),
    KEY idx_intake_book (book_id,intake_id),
    CONSTRAINT ck_intake_copy_ordinal CHECK(copy_ordinal>0),
    CONSTRAINT ck_intake_barcode_input CHECK(barcode_input IN ('SCANNED','GENERATED')),
    CONSTRAINT fk_intake_copy_request FOREIGN KEY(intake_id) REFERENCES inventory_intake_request(id),
    CONSTRAINT fk_intake_copy_copy FOREIGN KEY(copy_id) REFERENCES book_copy(id),
    CONSTRAINT fk_intake_copy_flow FOREIGN KEY(stock_flow_id) REFERENCES stock_flow(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE book_copy
    ADD COLUMN barcode_key VARBINARY(256) GENERATED ALWAYS AS (CONVERT(barcode USING binary)) STORED,
    ADD UNIQUE KEY uk_copy_barcode_exact (barcode_key);
-- V4's collation-based unique key would incorrectly reject case/trailing-space variants.
ALTER TABLE book_copy DROP INDEX uk_copy_barcode;
