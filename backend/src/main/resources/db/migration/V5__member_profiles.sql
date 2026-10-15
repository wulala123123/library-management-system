ALTER TABLE sys_user ADD COLUMN member_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE member_profile
    ADD COLUMN notes VARCHAR(2000) NULL,
    ADD COLUMN address VARCHAR(500) NULL,
    ADD COLUMN usage_type VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at DATETIME NULL,
    ADD COLUMN updated_by BIGINT NULL,
    ADD COLUMN source_system VARCHAR(50) NULL,
    ADD COLUMN source_archive_id BIGINT NULL,
    ADD COLUMN card_no_key VARBINARY(400) GENERATED ALWAYS AS
        (NULLIF(CONVERT(card_no USING binary), _binary'')) STORED,
    ADD UNIQUE KEY uk_member_card_exact (card_no_key),
    ADD CONSTRAINT ck_member_usage_type CHECK (usage_type IN ('UNKNOWN','LIMITED','UNLIMITED'));

-- Exact bytes reserve every assigned card permanently, including retired cards
-- and profiles belonging to disabled/deleted accounts. Duplicate existing cards
-- deliberately fail the migration above; no historical value is silently fixed.
CREATE TABLE member_card_history (
    card_no VARBINARY(400) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_member_card_owner (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO member_card_history(card_no,user_id)
    SELECT CONVERT(card_no USING binary),user_id FROM member_profile
    WHERE card_no IS NOT NULL AND OCTET_LENGTH(card_no)>0;

CREATE TABLE member_child (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NULL,
    gender VARCHAR(50) NULL,
    birth_date DATE NULL,
    birth_date_precision VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN',
    source_birth_text VARCHAR(300) NULL,
    source_key VARCHAR(128) NULL,
    notes VARCHAR(1000) NULL,
    KEY idx_member_child_owner (user_id,id),
    CONSTRAINT ck_child_precision CHECK (birth_date_precision IN ('DAY','MONTH','YEAR','UNKNOWN')),
    CONSTRAINT ck_child_date_precision CHECK (birth_date IS NULL OR birth_date_precision='DAY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE member_profile_change (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    change_source VARCHAR(50) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    before_json LONGTEXT NOT NULL,
    after_json LONGTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_member_change_owner (user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
