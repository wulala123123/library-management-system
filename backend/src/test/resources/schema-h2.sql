CREATE TABLE IF NOT EXISTS `sys_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `token_version` BIGINT NOT NULL DEFAULT 0,
    `username` VARCHAR(50) NOT NULL,
    `password` VARCHAR(200) NOT NULL,
    `real_name` VARCHAR(50) DEFAULT NULL,
    `phone` VARCHAR(20) DEFAULT NULL,
    `email` VARCHAR(100) DEFAULT NULL,
    `avatar` VARCHAR(500) DEFAULT NULL,
    `role` VARCHAR(20) NOT NULL DEFAULT 'READER',
    `status` TINYINT NOT NULL DEFAULT 1,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE (`username`)
);

CREATE TABLE IF NOT EXISTS `book_category` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `parent_id` BIGINT NOT NULL DEFAULT 0,
    `level` TINYINT NOT NULL DEFAULT 1,
    `sort_order` INT NOT NULL DEFAULT 0,
    `status` TINYINT NOT NULL DEFAULT 1,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `book` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `isbn` VARCHAR(20) NOT NULL,
    `title` VARCHAR(200) NOT NULL,
    `author` VARCHAR(200) DEFAULT NULL,
    `publisher` VARCHAR(200) DEFAULT NULL,
    `publish_date` DATE DEFAULT NULL,
    `category_id` BIGINT DEFAULT NULL,
    `price` DECIMAL(10,2) DEFAULT NULL,
    `pages` INT DEFAULT NULL,
    `cover_image` VARCHAR(500) DEFAULT NULL,
    `description` TEXT DEFAULT NULL,
    `location` VARCHAR(100) DEFAULT NULL,
    `stock_total` INT NOT NULL DEFAULT 1,
    `stock_available` INT NOT NULL DEFAULT 1,
    `status` TINYINT NOT NULL DEFAULT 1,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE (`isbn`)
);

CREATE TABLE IF NOT EXISTS `borrow_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `book_id` BIGINT NOT NULL,
    `borrow_date` DATE NOT NULL,
    `due_date` DATE NOT NULL,
    `return_date` DATE DEFAULT NULL,
    `renew_count` TINYINT NOT NULL DEFAULT 0,
    `status` VARCHAR(20) NOT NULL DEFAULT 'BORROWING',
    `remark` VARCHAR(500) DEFAULT NULL,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `fine_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `borrow_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `amount` DECIMAL(10,2) NOT NULL,
    `reason` VARCHAR(500) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'UNPAID',
    `pay_time` DATETIME DEFAULT NULL,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `sys_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(50) DEFAULT NULL,
    `operation` VARCHAR(200) DEFAULT NULL,
    `method` VARCHAR(200) DEFAULT NULL,
    `params` TEXT DEFAULT NULL,
    `ip` VARCHAR(50) DEFAULT NULL,
    `execution_time` BIGINT DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);
CREATE TABLE IF NOT EXISTS `publisher` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(200) NOT NULL,
    `address` VARCHAR(500) DEFAULT NULL,
    `contact_phone` VARCHAR(20) DEFAULT NULL,
    `contact_email` VARCHAR(100) DEFAULT NULL,
    `website` VARCHAR(200) DEFAULT NULL,
    `description` VARCHAR(500) DEFAULT NULL,
    `status` TINYINT NOT NULL DEFAULT 1,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE (`name`)
);

CREATE TABLE IF NOT EXISTS `sys_dept` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `parent_id` BIGINT NOT NULL DEFAULT 0,
    `ancestors` VARCHAR(200) DEFAULT '',
    `name` VARCHAR(100) NOT NULL,
    `sort_order` INT NOT NULL DEFAULT 0,
    `leader` VARCHAR(50) DEFAULT NULL,
    `phone` VARCHAR(20) DEFAULT NULL,
    `email` VARCHAR(100) DEFAULT NULL,
    `status` TINYINT NOT NULL DEFAULT 1,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `bookshelf` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `code` VARCHAR(50) NOT NULL,
    `dept_id` BIGINT DEFAULT NULL,
    `location` VARCHAR(200) DEFAULT NULL,
    `capacity` INT NOT NULL DEFAULT 100,
    `status` TINYINT NOT NULL DEFAULT 1,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `bookshelf_storey` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `bookshelf_id` BIGINT NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `level_num` INT NOT NULL,
    `capacity` INT NOT NULL DEFAULT 20,
    `status` TINYINT NOT NULL DEFAULT 1,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `book_location` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `storey_id` BIGINT NOT NULL,
    `code` VARCHAR(50) NOT NULL,
    `book_id` BIGINT DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'EMPTY',
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `purchase_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(200) NOT NULL,
    `content` CLOB DEFAULT NULL,
    `status` TINYINT NOT NULL DEFAULT 1,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `purchase_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `order_no` VARCHAR(50) NOT NULL,
    `dept_id` BIGINT DEFAULT NULL,
    `template_id` BIGINT DEFAULT NULL,
    `applicant_id` BIGINT NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    `current_node_id` VARCHAR(50) DEFAULT NULL,
    `deadline` DATE DEFAULT NULL,
    `remark` VARCHAR(500) DEFAULT NULL,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `purchase_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `order_id` BIGINT NOT NULL,
    `book_title` VARCHAR(200) NOT NULL,
    `author` VARCHAR(200) DEFAULT NULL,
    `isbn` VARCHAR(20) DEFAULT NULL,
    `publisher` VARCHAR(200) DEFAULT NULL,
    `category_id` BIGINT DEFAULT NULL,
    `quantity` INT NOT NULL DEFAULT 1,
    `instock_quantity` INT NOT NULL DEFAULT 0,
    `price` DECIMAL(10,2) DEFAULT NULL,
    `remark` VARCHAR(500) DEFAULT NULL,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `approval_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `order_id` BIGINT NOT NULL,
    `node_id` VARCHAR(50) NOT NULL,
    `node_name` VARCHAR(100) DEFAULT NULL,
    `approver_id` BIGINT DEFAULT NULL,
    `approver_name` VARCHAR(50) DEFAULT NULL,
    `action` VARCHAR(20) DEFAULT NULL,
    `comment` VARCHAR(500) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `book_resource` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `book_id` BIGINT NOT NULL,
    `file_name` VARCHAR(200) NOT NULL,
    `file_path` VARCHAR(500) NOT NULL,
    `file_type` VARCHAR(50) DEFAULT NULL,
    `file_size` BIGINT DEFAULT NULL,
    `resource_type` VARCHAR(20) NOT NULL DEFAULT 'IMAGE',
    `sort_order` INT NOT NULL DEFAULT 0,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `notice` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `title` VARCHAR(200) NOT NULL,
    `content` TEXT NOT NULL,
    `type` VARCHAR(20) NOT NULL DEFAULT 'NOTICE',
    `status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    `publisher_id` BIGINT DEFAULT NULL,
    `publish_time` DATETIME DEFAULT NULL,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `borrow_code` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(20) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `book_id` BIGINT NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'VALID',
    `expire_time` DATETIME NOT NULL,
    `used_time` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE (`code`)
);

CREATE TABLE IF NOT EXISTS `stock_flow` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `book_id` BIGINT NOT NULL,
    `location_id` BIGINT DEFAULT NULL,
    `flow_type` VARCHAR(20) NOT NULL,
    `quantity` INT NOT NULL DEFAULT 1,
    `related_id` BIGINT DEFAULT NULL,
    `operator_id` BIGINT DEFAULT NULL,
    `remark` VARCHAR(500) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);
CREATE TABLE IF NOT EXISTS permission_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operator_id BIGINT, operator_name VARCHAR(50),
    target_user_id BIGINT, target_user_name VARCHAR(50),
    action VARCHAR(50), old_role VARCHAR(20), new_role VARCHAR(20),
    detail VARCHAR(500), create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS book_normalize_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, book_id BIGINT NOT NULL,
    old_title VARCHAR(200), new_title VARCHAR(200),
    old_author VARCHAR(200), new_author VARCHAR(200),
    old_isbn VARCHAR(20), new_isbn VARCHAR(20),
    old_publisher VARCHAR(200), new_publisher VARCHAR(200),
    operator_name VARCHAR(50), status VARCHAR(20),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS inventory_mutex (id INT NOT NULL PRIMARY KEY);
MERGE INTO inventory_mutex (id) KEY(id) VALUES (1);

CREATE TABLE IF NOT EXISTS book_copy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, book_id BIGINT NOT NULL,
    barcode VARCHAR(64) NOT NULL, barcode_key VARBINARY(256) GENERATED ALWAYS AS (STRINGTOUTF8(barcode)) UNIQUE,
    status VARCHAR(20) DEFAULT 'AVAILABLE' NOT NULL,
    location VARCHAR(200)
);
ALTER TABLE borrow_record ALTER COLUMN due_date DROP NOT NULL;
ALTER TABLE borrow_record ADD COLUMN IF NOT EXISTS copy_id BIGINT;
ALTER TABLE borrow_record ADD COLUMN IF NOT EXISTS due_date_kind VARCHAR(20) DEFAULT 'DATED' NOT NULL;
ALTER TABLE borrow_record ADD COLUMN IF NOT EXISTS source_key VARCHAR(128) UNIQUE;
CREATE TABLE IF NOT EXISTS member_profile (
    user_id BIGINT PRIMARY KEY, source_key VARCHAR(128) NOT NULL UNIQUE,
    card_no VARCHAR(100), plan_name VARCHAR(200), service_start DATE, service_end DATE,
    used_uses INT, total_uses INT, deposit_amount DECIMAL(12,2), deposit_status VARCHAR(50), paid_fee DECIMAL(12,2)
);
CREATE TABLE IF NOT EXISTS legacy_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, source_system VARCHAR(50) NOT NULL,
    record_type VARCHAR(30) NOT NULL, source_key VARCHAR(128) NOT NULL,
    display_name VARCHAR(255) NOT NULL, summary VARCHAR(1000), details_json LONGTEXT NOT NULL,
    user_id BIGINT, book_id BIGINT, source_time TIMESTAMP,
    UNIQUE (source_system, record_type, source_key)
);
CREATE TABLE IF NOT EXISTS migration_batch (
    batch_key VARCHAR(128) PRIMARY KEY, source_hash CHAR(64) NOT NULL,
    summary_json TEXT NOT NULL, imported_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS migration_check (id INT PRIMARY KEY, ok INT NOT NULL CHECK (ok = 1));

ALTER TABLE member_profile ADD COLUMN IF NOT EXISTS notes VARCHAR(2000);
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS member_revision BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE member_profile ADD COLUMN IF NOT EXISTS address VARCHAR(500);
ALTER TABLE member_profile ADD COLUMN IF NOT EXISTS usage_type VARCHAR(20) DEFAULT 'UNKNOWN' NOT NULL;
ALTER TABLE member_profile ADD COLUMN IF NOT EXISTS revision BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE member_profile ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE member_profile ADD COLUMN IF NOT EXISTS updated_by BIGINT;
ALTER TABLE member_profile ADD COLUMN IF NOT EXISTS source_system VARCHAR(50);
ALTER TABLE member_profile ADD COLUMN IF NOT EXISTS source_archive_id BIGINT;
ALTER TABLE member_profile ADD COLUMN IF NOT EXISTS card_no_key VARBINARY(400)
    GENERATED ALWAYS AS (CASE WHEN card_no IS NULL OR LENGTH(card_no)=0 THEN NULL ELSE CAST(card_no AS VARBINARY(400)) END);
CREATE UNIQUE INDEX IF NOT EXISTS uk_member_card_exact ON member_profile(card_no_key);
CREATE TABLE IF NOT EXISTS member_card_history (
    card_no VARBINARY(400) PRIMARY KEY, user_id BIGINT NOT NULL,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS member_child (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
    name VARCHAR(100), gender VARCHAR(50), birth_date DATE,
    birth_date_precision VARCHAR(10) DEFAULT 'UNKNOWN' NOT NULL,
    source_birth_text VARCHAR(300), source_key VARCHAR(128), notes VARCHAR(1000),
    CONSTRAINT ck_child_precision CHECK (birth_date_precision IN ('DAY','MONTH','YEAR','UNKNOWN')),
    CONSTRAINT ck_child_date_precision CHECK (birth_date IS NULL OR birth_date_precision='DAY')
);
CREATE TABLE IF NOT EXISTS member_profile_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, operator_id BIGINT NOT NULL,
    change_source VARCHAR(50) NOT NULL, change_reason VARCHAR(500) NOT NULL,
    before_json CLOB NOT NULL, after_json CLOB NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS membership_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(200) NOT NULL, draft_json CLOB NOT NULL,
    revision BIGINT DEFAULT 0 NOT NULL, enabled BOOLEAN DEFAULT FALSE NOT NULL, selected_version_id BIGINT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, updated_by BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS membership_plan_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id BIGINT NOT NULL, version_number BIGINT NOT NULL,
    definition_json CLOB NOT NULL, confirmation_basis VARCHAR(2000) NOT NULL, idempotency_key VARBINARY(128) NOT NULL,
    request_hash CHAR(64) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, created_by BIGINT NOT NULL,
    UNIQUE(plan_id,version_number), UNIQUE(plan_id,idempotency_key),
    FOREIGN KEY(plan_id) REFERENCES membership_plan(id)
);
CREATE TABLE IF NOT EXISTS membership_plan_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id BIGINT NOT NULL, operator_id BIGINT NOT NULL,
    action VARCHAR(30) NOT NULL, change_reason VARCHAR(500) NOT NULL, before_json CLOB NOT NULL, after_json CLOB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, FOREIGN KEY(plan_id) REFERENCES membership_plan(id)
);

CREATE TABLE IF NOT EXISTS member_entitlement_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, member_revision BIGINT NOT NULL,
    plan_version_id BIGINT, version_json CLOB, facts_json CLOB NOT NULL, rules_json CLOB NOT NULL,
    source_references_json CLOB NOT NULL, reason VARCHAR(1000) NOT NULL, history_basis VARCHAR(2000),
    confirmed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, confirmed_by BIGINT NOT NULL,
    FOREIGN KEY(plan_version_id) REFERENCES membership_plan_version(id)
);
ALTER TABLE member_profile ADD COLUMN IF NOT EXISTS current_entitlement_snapshot_id BIGINT;
CREATE TABLE IF NOT EXISTS member_entitlement_request (
    user_id BIGINT NOT NULL, idempotency_key VARBINARY(128) NOT NULL, request_hash CHAR(64) NOT NULL,
    snapshot_id BIGINT NOT NULL, resulting_revision BIGINT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY(user_id,idempotency_key), FOREIGN KEY(snapshot_id) REFERENCES member_entitlement_snapshot(id)
);
CREATE TABLE IF NOT EXISTS member_entitlement_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, operator_id BIGINT NOT NULL,
    action VARCHAR(30) NOT NULL, reason VARCHAR(1000) NOT NULL, before_json CLOB NOT NULL, after_json CLOB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS inventory_intake_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, idempotency_key VARBINARY(128) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL, preview_digest CHAR(64) NOT NULL,
    source_type VARCHAR(20) NOT NULL, purchase_order_id BIGINT, operator_id BIGINT NOT NULL,
    request_json CLOB NOT NULL, preview_json CLOB NOT NULL, state VARCHAR(20) NOT NULL,
    expires_at DATETIME NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at DATETIME, receipt_json CLOB,
    CONSTRAINT ck_intake_source CHECK ((source_type='MANUAL' AND purchase_order_id IS NULL) OR (source_type='PURCHASE' AND purchase_order_id IS NOT NULL)),
    CONSTRAINT ck_intake_state CHECK ((state='PREVIEWED' AND confirmed_at IS NULL AND receipt_json IS NULL) OR (state='COMMITTED' AND confirmed_at IS NOT NULL AND receipt_json IS NOT NULL))
);
CREATE TABLE IF NOT EXISTS inventory_intake_copy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, intake_id BIGINT NOT NULL, line_key VARBINARY(128) NOT NULL,
    copy_ordinal INT NOT NULL CHECK(copy_ordinal>0), book_id BIGINT NOT NULL, copy_id BIGINT NOT NULL UNIQUE,
    purchase_item_id BIGINT, stock_flow_id BIGINT NOT NULL,
    barcode_input VARCHAR(20) NOT NULL CHECK(barcode_input IN ('SCANNED','GENERATED')),
    UNIQUE(intake_id,line_key,copy_ordinal),
    FOREIGN KEY(intake_id) REFERENCES inventory_intake_request(id),
    FOREIGN KEY(copy_id) REFERENCES book_copy(id), FOREIGN KEY(stock_flow_id) REFERENCES stock_flow(id)
);
