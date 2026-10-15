CREATE TABLE book_copy (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    book_id BIGINT NOT NULL,
    barcode VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    location VARCHAR(200),
    UNIQUE KEY uk_copy_barcode (barcode),
    KEY idx_copy_book_status (book_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE borrow_record MODIFY due_date DATE NULL COMMENT '空值须结合期限类型解释';
ALTER TABLE borrow_record ADD COLUMN copy_id BIGINT NULL;
ALTER TABLE borrow_record ADD COLUMN due_date_kind VARCHAR(20) NOT NULL DEFAULT 'DATED';
ALTER TABLE borrow_record ADD COLUMN source_key VARCHAR(128) NULL;
ALTER TABLE borrow_record ADD UNIQUE KEY uk_borrow_source (source_key);
ALTER TABLE borrow_record ADD KEY idx_borrow_copy (copy_id);

CREATE TABLE member_profile (
    user_id BIGINT NOT NULL PRIMARY KEY,
    source_key VARCHAR(128) NOT NULL,
    card_no VARCHAR(100),
    plan_name VARCHAR(200),
    service_start DATE,
    service_end DATE,
    used_uses INT,
    total_uses INT NULL COMMENT 'NULL表示原计划不限次数',
    deposit_amount DECIMAL(12,2),
    deposit_status VARCHAR(50),
    paid_fee DECIMAL(12,2),
    UNIQUE KEY uk_member_source (source_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE legacy_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_system VARCHAR(50) NOT NULL,
    record_type VARCHAR(30) NOT NULL,
    source_key VARCHAR(128) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    summary VARCHAR(1000),
    details_json LONGTEXT NOT NULL,
    user_id BIGINT,
    book_id BIGINT,
    source_time DATETIME,
    UNIQUE KEY uk_legacy_source (source_system, record_type, source_key),
    KEY idx_legacy_type (record_type),
    KEY idx_legacy_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE migration_batch (
    batch_key VARCHAR(128) NOT NULL PRIMARY KEY,
    source_hash CHAR(64) NOT NULL,
    summary_json TEXT NOT NULL,
    imported_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE migration_check (
    id INT NOT NULL PRIMARY KEY,
    ok INT NOT NULL,
    CONSTRAINT ck_migration_ok CHECK (ok = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
