-- No current member values or historical data are rewritten or inferred.
CREATE TABLE member_entitlement_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    member_revision BIGINT NOT NULL,
    plan_version_id BIGINT NULL,
    version_json LONGTEXT NULL,
    facts_json LONGTEXT NOT NULL,
    rules_json LONGTEXT NOT NULL,
    source_references_json LONGTEXT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    history_basis VARCHAR(2000) NULL,
    confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_by BIGINT NOT NULL,
    KEY idx_entitlement_member_history (user_id,id),
    CONSTRAINT fk_entitlement_plan_version FOREIGN KEY(plan_version_id) REFERENCES membership_plan_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE member_profile ADD COLUMN current_entitlement_snapshot_id BIGINT NULL;

CREATE TABLE member_entitlement_request (
    user_id BIGINT NOT NULL,
    idempotency_key VARBINARY(128) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_id BIGINT NOT NULL,
    resulting_revision BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(user_id,idempotency_key),
    CONSTRAINT fk_entitlement_request_snapshot FOREIGN KEY(snapshot_id) REFERENCES member_entitlement_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE member_entitlement_change (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    action VARCHAR(30) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    before_json LONGTEXT NOT NULL,
    after_json LONGTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_entitlement_change_member(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
