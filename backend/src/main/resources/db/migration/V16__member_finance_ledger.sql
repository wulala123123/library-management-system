-- B40-A: manager allocated V16. New empty tables only; no backfill.
CREATE TABLE member_finance_operation (
    id VARBINARY(36) NOT NULL PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    operation_key VARBINARY(128) NOT NULL,
    reserve_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'NULL only for missing-key cancellation tombstone',
    editor_id VARBINARY(36) NOT NULL,
    editor_epoch BIGINT NOT NULL,
    last_claim_key VARBINARY(36) NULL,
    last_claim_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_claim_from_epoch BIGINT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    draft_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    draft_input_json LONGTEXT NULL,
    request_id VARBINARY(36) NULL,
    preview_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cancel_key VARBINARY(36) NULL,
    cancel_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cancel_input_json LONGTEXT NULL,
    cancel_result_json LONGTEXT NULL,
    result_json LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    draft_expires_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    active_actor_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN state IN ('RESERVED','DRAFT_READY','PREVIEWED') THEN actor_id ELSE NULL END) STORED,
    UNIQUE KEY uk_fin_operation_key (actor_id, operation_key),
    UNIQUE KEY uk_fin_active_actor (active_actor_id),
    UNIQUE KEY uk_fin_operation_request (request_id),
    CONSTRAINT ck_fin_editor_epoch CHECK (editor_epoch >= 1),
    CONSTRAINT ck_fin_reservation_origin CHECK (reserve_hash IS NOT NULL OR
        (state='CANCELLED' AND cancel_key IS NOT NULL AND draft_hash IS NULL AND request_id IS NULL)),
    CONSTRAINT ck_fin_operation_state CHECK (state IN ('RESERVED','DRAFT_READY','PREVIEWED','COMMITTED','CANCELLED','EXPIRED','REJECTED')),
    CONSTRAINT ck_fin_draft_json CHECK (draft_input_json IS NULL OR JSON_VALID(draft_input_json)),
    CONSTRAINT ck_fin_operation_shape CHECK (
        (state='RESERVED' AND draft_hash IS NULL AND draft_input_json IS NULL AND request_id IS NULL AND preview_digest IS NULL) OR
        (state='DRAFT_READY' AND draft_hash IS NOT NULL AND draft_input_json IS NOT NULL AND request_id IS NULL AND preview_digest IS NULL) OR
        (state IN ('PREVIEWED','COMMITTED','CANCELLED','EXPIRED','REJECTED') AND draft_input_json IS NULL
         AND ((request_id IS NULL AND preview_digest IS NULL AND state IN ('CANCELLED','EXPIRED','REJECTED')) OR
              (request_id IS NOT NULL AND preview_digest IS NOT NULL AND draft_hash IS NOT NULL)))),
    CONSTRAINT ck_fin_operation_result CHECK (
        (state IN ('RESERVED','DRAFT_READY','PREVIEWED') AND result_json IS NULL AND resolved_at IS NULL) OR
        (state IN ('COMMITTED','CANCELLED','EXPIRED','REJECTED') AND result_json IS NOT NULL AND JSON_VALID(result_json) AND resolved_at IS NOT NULL)),
    CONSTRAINT ck_fin_claim_tuple CHECK (
        (last_claim_key IS NULL AND last_claim_hash IS NULL AND last_claim_from_epoch IS NULL) OR
        (last_claim_key IS NOT NULL AND last_claim_hash IS NOT NULL AND last_claim_from_epoch IS NOT NULL AND last_claim_from_epoch >= 1)),
    CONSTRAINT ck_fin_cancel_tuple CHECK (
        (cancel_key IS NULL AND cancel_hash IS NULL AND cancel_input_json IS NULL AND cancel_result_json IS NULL) OR
        (cancel_key IS NOT NULL AND cancel_hash IS NOT NULL AND cancel_input_json IS NOT NULL AND JSON_VALID(cancel_input_json)
         AND cancel_result_json IS NOT NULL AND JSON_VALID(cancel_result_json) AND state IN ('COMMITTED','CANCELLED','EXPIRED','REJECTED'))),
    CONSTRAINT ck_fin_draft_expiry CHECK (draft_expires_at > created_at),
    CONSTRAINT fk_fin_operation_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
    CONSTRAINT fk_fin_operation_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE member_finance_account (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    fund VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    opening_entry_id BIGINT NULL,
    scope_cutoff_at DATETIME(6) NULL COMMENT 'UTC exclusive historical bound; fixed at first financial commit',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_fin_account (user_id, fund, currency),
    CONSTRAINT ck_fin_fund CHECK (fund IN ('MEMBER_FEE','DEPOSIT')),
    CONSTRAINT ck_fin_currency CHECK (currency='CNY'),
    CONSTRAINT ck_fin_revision CHECK (revision >= 0),
    CONSTRAINT ck_fin_opening_pointer CHECK (opening_entry_id IS NULL OR scope_cutoff_at IS NOT NULL),
    CONSTRAINT fk_fin_account_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE member_finance_request (
    id VARBINARY(36) NOT NULL PRIMARY KEY COMMENT 'server secure UUID; not a timestamp',
    account_id BIGINT NOT NULL,
    operation_id VARBINARY(36) NOT NULL,
    actor_id BIGINT NOT NULL,
    operation_key VARBINARY(128) NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preview_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_json LONGTEXT NOT NULL,
    dependencies_json LONGTEXT NOT NULL,
    preview_json LONGTEXT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_json LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    UNIQUE KEY uk_fin_operation (actor_id, operation_key),
    UNIQUE KEY uk_fin_request_operation (operation_id),
    UNIQUE KEY uk_fin_request_identity (id, operation_id),
    UNIQUE KEY uk_fin_request_account (id, account_id),
    KEY idx_fin_request_account (account_id, created_at),
    CONSTRAINT ck_fin_action CHECK (action IN ('OPENING','RECEIPT','REFUND','CORRECT')),
    CONSTRAINT ck_fin_request_state CHECK (state IN ('PREVIEWED','COMMITTED','CANCELLED','EXPIRED','REJECTED')),
    CONSTRAINT ck_fin_request_json CHECK (JSON_VALID(input_json) AND JSON_VALID(dependencies_json) AND JSON_VALID(preview_json)),
    CONSTRAINT ck_fin_result CHECK (
        (state='PREVIEWED' AND result_json IS NULL AND resolved_at IS NULL) OR
        (state<>'PREVIEWED' AND result_json IS NOT NULL AND JSON_VALID(result_json) AND resolved_at IS NOT NULL)),
    CONSTRAINT ck_fin_ttl CHECK (expires_at > created_at),
    CONSTRAINT fk_fin_request_account FOREIGN KEY (account_id) REFERENCES member_finance_account(id),
    CONSTRAINT fk_fin_request_operation FOREIGN KEY (operation_id) REFERENCES member_finance_operation(id),
    CONSTRAINT fk_fin_request_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE member_finance_operation
    ADD CONSTRAINT fk_fin_operation_request FOREIGN KEY (request_id, id) REFERENCES member_finance_request(id, operation_id);

CREATE TABLE member_finance_entry (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    request_id VARBINARY(36) NOT NULL,
    ordinal TINYINT UNSIGNED NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    opening_received DECIMAL(19,4) NULL,
    opening_refunded DECIMAL(19,4) NULL,
    cutoff_at DATETIME(6) NULL,
    actual_at DATETIME(6) NULL,
    method_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    root_fact_key VARBINARY(128) NULL COMMENT 'only initial base entry; replacements follow replaces_entry_id',
    reverses_entry_id BIGINT NULL,
    replaces_entry_id BIGINT NULL,
    business_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_key VARBINARY(128) NOT NULL,
    policy_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    operator_id BIGINT NOT NULL,
    recorded_at DATETIME(6) NOT NULL COMMENT 'UTC server/database time',
    UNIQUE KEY uk_fin_entry_account (id, account_id),
    UNIQUE KEY uk_fin_entry_ordinal (request_id, ordinal),
    UNIQUE KEY uk_fin_root_fact (root_fact_key),
    UNIQUE KEY uk_fin_reversal (reverses_entry_id),
    UNIQUE KEY uk_fin_replacement (replaces_entry_id),
    KEY idx_fin_ledger (account_id, id),
    KEY idx_fin_actual (account_id, actual_at, id),
    KEY idx_fin_business (business_type, business_key),
    CONSTRAINT ck_fin_kind CHECK (kind IN ('OPENING','RECEIPT','REFUND','REVERSAL')),
    CONSTRAINT ck_fin_amount CHECK (amount >= 0 AND (kind IN ('OPENING','REVERSAL') OR amount > 0)),
    CONSTRAINT ck_fin_amount_scale CHECK (amount=TRUNCATE(amount,2)),
    CONSTRAINT ck_fin_opening_amount CHECK (
        (kind='OPENING' AND opening_received IS NOT NULL AND opening_refunded IS NOT NULL
         AND opening_received >= 0 AND opening_refunded >= 0
         AND opening_received=TRUNCATE(opening_received,2) AND opening_refunded=TRUNCATE(opening_refunded,2)
         AND opening_received >= opening_refunded AND amount=opening_received-opening_refunded
         AND cutoff_at IS NOT NULL AND actual_at IS NULL AND method_code IS NULL) OR
        (kind<>'OPENING' AND opening_received IS NULL AND opening_refunded IS NULL AND cutoff_at IS NULL)),
    CONSTRAINT ck_fin_cash_fields CHECK (kind NOT IN ('RECEIPT','REFUND') OR (actual_at IS NOT NULL AND method_code IS NOT NULL)),
    CONSTRAINT ck_fin_reverses CHECK (
        (kind='REVERSAL' AND reverses_entry_id IS NOT NULL AND replaces_entry_id IS NULL AND root_fact_key IS NULL) OR
        (kind<>'REVERSAL' AND reverses_entry_id IS NULL
         AND ((replaces_entry_id IS NULL AND root_fact_key IS NOT NULL) OR
              (replaces_entry_id IS NOT NULL AND root_fact_key IS NULL)))),
    CONSTRAINT ck_fin_evidence_json CHECK (JSON_VALID(evidence_json)),
    CONSTRAINT fk_fin_entry_account FOREIGN KEY (account_id) REFERENCES member_finance_account(id),
    CONSTRAINT fk_fin_entry_request FOREIGN KEY (request_id, account_id) REFERENCES member_finance_request(id, account_id),
    CONSTRAINT fk_fin_entry_operator FOREIGN KEY (operator_id) REFERENCES sys_user(id),
    CONSTRAINT fk_fin_reverses FOREIGN KEY (reverses_entry_id, account_id) REFERENCES member_finance_entry(id, account_id),
    CONSTRAINT fk_fin_replaces FOREIGN KEY (replaces_entry_id, account_id) REFERENCES member_finance_entry(id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE member_finance_account
    ADD CONSTRAINT fk_fin_opening_entry FOREIGN KEY (opening_entry_id, id) REFERENCES member_finance_entry(id, account_id);

CREATE TABLE member_finance_allocation (
    refund_entry_id BIGINT NOT NULL,
    lot_entry_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    PRIMARY KEY (refund_entry_id, lot_entry_id),
    KEY idx_fin_alloc_lot (lot_entry_id, account_id),
    CONSTRAINT ck_fin_alloc_amount CHECK (amount > 0),
    CONSTRAINT ck_fin_alloc_scale CHECK (amount=TRUNCATE(amount,2)),
    CONSTRAINT fk_fin_alloc_refund FOREIGN KEY (refund_entry_id, account_id) REFERENCES member_finance_entry(id, account_id),
    CONSTRAINT fk_fin_alloc_lot FOREIGN KEY (lot_entry_id, account_id) REFERENCES member_finance_entry(id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE member_finance_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARBINARY(36) NOT NULL,
    account_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    before_revision BIGINT NOT NULL,
    after_revision BIGINT NOT NULL,
    before_json LONGTEXT NOT NULL,
    after_json LONGTEXT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_fin_audit_request (request_id),
    KEY idx_fin_audit_account (account_id, id),
    CONSTRAINT ck_fin_audit_revision CHECK (before_revision >= 0 AND after_revision=before_revision+1),
    CONSTRAINT ck_fin_audit_json CHECK (JSON_VALID(before_json) AND JSON_VALID(after_json)),
    CONSTRAINT fk_fin_audit_request FOREIGN KEY (request_id, account_id) REFERENCES member_finance_request(id, account_id),
    CONSTRAINT fk_fin_audit_operator FOREIGN KEY (operator_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Cross-row invariants belong to the single transactional service, under user/account locks:
-- operation editor epoch fences every new draft/preview/confirm/cancel; one open operation per actor;
-- cancel fields freeze one accepted command including reason and nullable previewDigest; replay exact JSON;
-- missing-key cancel creates a terminal operation tombstone; never delete registry keys or reuse them;
-- no request exists before stored DRAFT_READY input; draft copied to request then cleared atomically;
-- operation/request states and terminal result move together; expired/cancelled unpreviewed draft raw input cleared;
-- policy confirmed; account immutable identity; current reader/admin; opening pointer kind/cutoff;
-- no extra opening; reversal original kind and exact amount; replacement same kind/root lineage;
-- each refund allocation sum equals amount; effective lot remaining >= 0; effective total >= 0;
-- cutoff non-overlap; current eligibility; true generated IDs; revision/audit/receipt atomic commit.
-- No direct SQL/business UPDATE/DELETE endpoints for entry/allocation/audit; no CASCADE deletes.
