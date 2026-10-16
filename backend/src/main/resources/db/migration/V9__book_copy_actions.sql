-- Technical version zero does not verify or reclassify any legacy business value.
ALTER TABLE book_copy
    ADD COLUMN action_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_state_action_id BIGINT NULL,
    ADD CONSTRAINT ck_copy_action_version CHECK (action_version >= 0);

CREATE TABLE book_copy_action_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARBINARY(128) NOT NULL,
    operator_id BIGINT NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preview_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    action_type VARCHAR(20) NOT NULL,
    request_json LONGTEXT NOT NULL,
    preview_json LONGTEXT NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    committed_at DATETIME NULL,
    receipt_json LONGTEXT NULL,
    UNIQUE KEY uk_copy_action_key (idempotency_key),
    KEY idx_copy_action_owner (operator_id,created_at),
    CONSTRAINT ck_copy_action_schema CHECK(schema_version=1),
    CONSTRAINT ck_copy_request_type CHECK(action_type IN ('MOVE','DISABLE','ENABLE','WRITE_OFF','RESTORE')),
    CONSTRAINT ck_copy_request_state CHECK ((state='PREVIEWED' AND committed_at IS NULL AND receipt_json IS NULL) OR (state='COMMITTED' AND committed_at IS NOT NULL AND receipt_json IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE book_copy_action (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    copy_id BIGINT NOT NULL,
    book_id BIGINT NOT NULL,
    barcode_snapshot VARCHAR(64) NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    before_status VARCHAR(20) NOT NULL,
    after_status VARCHAR(20) NOT NULL,
    before_location VARCHAR(200) NULL,
    after_location VARCHAR(200) NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    before_state_action_id BIGINT NULL,
    delta_total INT NOT NULL,
    delta_available INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    operator_id BIGINT NOT NULL,
    occurred_at DATETIME NOT NULL,
    stock_flow_id BIGINT NULL,
    restores_action_id BIGINT NULL,
    UNIQUE KEY uk_copy_action_request_copy (request_id,copy_id),
    UNIQUE KEY uk_copy_action_flow (stock_flow_id),
    UNIQUE KEY uk_copy_action_restore (restores_action_id),
    KEY idx_copy_action_history (copy_id,id),
    KEY idx_copy_action_book (book_id,id),
    CONSTRAINT ck_copy_action_ordinal CHECK(ordinal>0 AND ordinal<=100),
    CONSTRAINT ck_copy_event_version CHECK(before_version>=0 AND after_version>before_version),
    CONSTRAINT ck_copy_event_delta CHECK(delta_total BETWEEN -1 AND 1 AND delta_available BETWEEN -1 AND 1),
    CONSTRAINT ck_copy_event_flow CHECK((action_type='MOVE' AND stock_flow_id IS NULL AND delta_total=0 AND delta_available=0) OR (action_type IN ('DISABLE','ENABLE','WRITE_OFF','RESTORE') AND stock_flow_id IS NOT NULL)),
    CONSTRAINT ck_copy_event_restore CHECK((action_type='RESTORE' AND restores_action_id IS NOT NULL) OR (action_type<>'RESTORE' AND restores_action_id IS NULL)),
    CONSTRAINT fk_copy_event_request FOREIGN KEY(request_id) REFERENCES book_copy_action_request(id),
    CONSTRAINT fk_copy_event_copy FOREIGN KEY(copy_id) REFERENCES book_copy(id),
    CONSTRAINT fk_copy_event_book FOREIGN KEY(book_id) REFERENCES book(id),
    CONSTRAINT fk_copy_event_flow FOREIGN KEY(stock_flow_id) REFERENCES stock_flow(id),
    CONSTRAINT fk_copy_event_restore FOREIGN KEY(restores_action_id) REFERENCES book_copy_action(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE book_copy ADD CONSTRAINT fk_copy_last_state_action
    FOREIGN KEY(last_state_action_id) REFERENCES book_copy_action(id);
-- Events are append-only through application APIs; no trigger or DB immutability claim.
