-- No defaults or seed plans are introduced. Existing business tables are untouched.
CREATE TABLE membership_plan (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    draft_json LONGTEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    selected_version_id BIGINT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    KEY idx_membership_plan_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE membership_plan_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    version_number BIGINT NOT NULL,
    definition_json LONGTEXT NOT NULL,
    confirmation_basis VARCHAR(2000) NOT NULL,
    idempotency_key VARBINARY(128) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    UNIQUE KEY uk_membership_plan_version (plan_id,version_number),
    UNIQUE KEY uk_membership_plan_idempotency (plan_id,idempotency_key),
    CONSTRAINT fk_membership_version_plan FOREIGN KEY (plan_id) REFERENCES membership_plan(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE membership_plan_change (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    action VARCHAR(30) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    before_json LONGTEXT NOT NULL,
    after_json LONGTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_membership_plan_change (plan_id,id),
    CONSTRAINT fk_membership_change_plan FOREIGN KEY (plan_id) REFERENCES membership_plan(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
