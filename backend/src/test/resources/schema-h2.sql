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
    `catalog_identity_revision` BIGINT NOT NULL DEFAULT 0 CHECK(catalog_identity_revision>=0),
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
    `catalog_identity_revision` BIGINT NOT NULL DEFAULT 0 CHECK(catalog_identity_revision>=0),
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
    `catalog_identity_revision` BIGINT NOT NULL DEFAULT 0 CHECK(catalog_identity_revision>=0),
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
    barcode VARCHAR(128) NOT NULL, barcode_key VARBINARY(256) GENERATED ALWAYS AS (STRINGTOUTF8(barcode)) UNIQUE,
    status VARCHAR(20) DEFAULT 'AVAILABLE' NOT NULL,
    location VARCHAR(400)
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

-- Technical version zero does not verify or reclassify any legacy business value.
ALTER TABLE book_copy ADD COLUMN IF NOT EXISTS action_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE book_copy ADD COLUMN IF NOT EXISTS last_state_action_id BIGINT NULL;
ALTER TABLE book_copy ADD CONSTRAINT IF NOT EXISTS ck_copy_action_version CHECK (action_version >= 0);

CREATE TABLE IF NOT EXISTS book_copy_action_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARBINARY(128) NOT NULL,
    operator_id BIGINT NOT NULL,
    request_hash CHAR(64)  NOT NULL,
    preview_digest CHAR(64)  NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    action_type VARCHAR(20) NOT NULL,
    request_json CLOB NOT NULL,
    preview_json CLOB NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    committed_at DATETIME NULL,
    receipt_json CLOB NULL,
    CONSTRAINT uk_copy_action_key UNIQUE (idempotency_key),
    CONSTRAINT ck_copy_action_schema CHECK(schema_version=1),
    CONSTRAINT ck_copy_request_type CHECK(action_type IN ('MOVE','DISABLE','ENABLE','WRITE_OFF','RESTORE')),
    CONSTRAINT ck_copy_request_state CHECK ((state='PREVIEWED' AND committed_at IS NULL AND receipt_json IS NULL) OR (state='COMMITTED' AND committed_at IS NOT NULL AND receipt_json IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS book_copy_action (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    copy_id BIGINT NOT NULL,
    book_id BIGINT NOT NULL,
    barcode_snapshot VARCHAR(128) NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    before_status VARCHAR(20) NOT NULL,
    after_status VARCHAR(20) NOT NULL,
    before_location VARCHAR(400) NULL,
    after_location VARCHAR(400) NULL,
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
    CONSTRAINT uk_copy_action_request_copy UNIQUE (request_id,copy_id),
    CONSTRAINT uk_copy_action_flow UNIQUE (stock_flow_id),
    CONSTRAINT uk_copy_action_restore UNIQUE (restores_action_id),
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
);

ALTER TABLE book_copy ADD CONSTRAINT IF NOT EXISTS fk_copy_last_state_action
    FOREIGN KEY(last_state_action_id) REFERENCES book_copy_action(id);
-- Events are append-only through application APIs; no trigger or DB immutability claim.

-- V10 equivalent for isolated H2 tests. Production MySQL migration bytes remain separate.
ALTER TABLE bookshelf ADD COLUMN IF NOT EXISTS layout_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE bookshelf_storey ADD COLUMN IF NOT EXISTS layout_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE book_location ADD COLUMN IF NOT EXISTS layout_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE book_copy ADD COLUMN IF NOT EXISTS registered_location_id BIGINT NULL;
ALTER TABLE book_copy ADD COLUMN IF NOT EXISTS last_location_action_id BIGINT NULL;
ALTER TABLE bookshelf ADD CONSTRAINT IF NOT EXISTS ck_shelf_layout_version CHECK(layout_version>=0);
ALTER TABLE bookshelf_storey ADD CONSTRAINT IF NOT EXISTS ck_storey_layout_version CHECK(layout_version>=0);
ALTER TABLE book_location ADD CONSTRAINT IF NOT EXISTS ck_location_layout_version CHECK(layout_version>=0);
ALTER TABLE book_copy ADD CONSTRAINT IF NOT EXISTS fk_copy_registered_location FOREIGN KEY(registered_location_id) REFERENCES book_location(id);
ALTER TABLE book_copy ADD CONSTRAINT IF NOT EXISTS fk_copy_location_source FOREIGN KEY(last_location_action_id) REFERENCES book_copy_action(id);
CREATE INDEX IF NOT EXISTS idx_copy_registered_location ON book_copy(registered_location_id);
ALTER TABLE book_copy_action_request DROP CONSTRAINT IF EXISTS ck_copy_action_schema;
ALTER TABLE book_copy_action_request ADD CONSTRAINT ck_copy_action_schema CHECK(schema_version IN(1,2));
CREATE TABLE IF NOT EXISTS book_copy_action_location (
 event_id BIGINT NOT NULL PRIMARY KEY, before_location_id BIGINT NULL,after_location_id BIGINT NULL,
 before_source_action_id BIGINT NULL,source_behavior VARCHAR(16) NOT NULL,snapshot_version INT NOT NULL DEFAULT 1,
 before_json CLOB NOT NULL,after_json CLOB NOT NULL,
 CONSTRAINT ck_copy_location_behavior CHECK(source_behavior IN('KEEP','THIS_EVENT')),
 CONSTRAINT ck_copy_location_snapshot CHECK(snapshot_version=1),
 FOREIGN KEY(event_id) REFERENCES book_copy_action(id),FOREIGN KEY(before_location_id) REFERENCES book_location(id),
 FOREIGN KEY(after_location_id) REFERENCES book_location(id),FOREIGN KEY(before_source_action_id) REFERENCES book_copy_action(id)
);
CREATE TABLE IF NOT EXISTS location_structure_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,entity_type VARCHAR(16) NOT NULL,entity_id BIGINT NOT NULL,
 operation VARCHAR(24) NOT NULL,operator_id BIGINT NOT NULL,occurred_at DATETIME NOT NULL,reason VARCHAR(1000) NOT NULL,
 before_version BIGINT NULL,after_version BIGINT NOT NULL,before_json CLOB NULL,after_json CLOB NOT NULL,snapshot_version INT NOT NULL DEFAULT 1,
 CONSTRAINT ck_structure_entity CHECK(entity_type IN('BOOKSHELF','STOREY','LOCATION')),
 CONSTRAINT ck_structure_operation CHECK(operation IN('CREATE','UPDATE','DELETE','ASSIGN','CLEAR')),
 CONSTRAINT ck_structure_snapshot CHECK(snapshot_version=1),
 CONSTRAINT ck_structure_versions CHECK((before_version IS NULL AND after_version=0 AND operation='CREATE') OR (before_version>=0 AND after_version=before_version+1 AND operation<>'CREATE'))
);

-- V11 stocktake test equivalent.
-- Observations and reports are independent evidence. No legacy row is updated.
CREATE TABLE IF NOT EXISTS stocktake_session (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 campaign_key VARCHAR(100) NULL,
 scope_json CLOB NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 revision BIGINT NOT NULL DEFAULT 0,
 baseline_snapshot_id BIGINT NULL,
 final_snapshot_id BIGINT NULL,
 final_receipt_json CLOB NULL,
 created_at DATETIME NOT NULL,
 confirmed_at DATETIME NULL,
 CONSTRAINT ck_stocktake_revision CHECK(revision>=0),
 CONSTRAINT ck_stocktake_state CHECK(state IN ('DRAFT','FINALIZED','CANCELLED')),
 CONSTRAINT ck_stocktake_final CHECK((state='FINALIZED' AND final_snapshot_id IS NOT NULL AND final_receipt_json IS NOT NULL AND confirmed_at IS NOT NULL) OR (state<>'FINALIZED' AND final_snapshot_id IS NULL AND final_receipt_json IS NULL AND confirmed_at IS NULL))
);
CREATE TABLE IF NOT EXISTS stocktake_operation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operation_key VARBINARY(128) NOT NULL,
 actor_id BIGINT NOT NULL,
 operation VARCHAR(32) NOT NULL,
 session_id BIGINT NULL,
 input_hash CHAR(64) NOT NULL,
 request_json CLOB NOT NULL,
 success_receipt_json CLOB NULL,
 committed_at DATETIME NULL,
 CONSTRAINT uk_stocktake_operation_key UNIQUE(operation_key),
 CONSTRAINT ck_stocktake_operation_commit CHECK((success_receipt_json IS NULL AND committed_at IS NULL) OR (success_receipt_json IS NOT NULL AND committed_at IS NOT NULL)),
 CONSTRAINT fk_stocktake_operation_session FOREIGN KEY(session_id) REFERENCES stocktake_session(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE TABLE IF NOT EXISTS stocktake_snapshot (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 session_id BIGINT NOT NULL,
 snapshot_kind VARCHAR(16) NOT NULL,
 source_revision BIGINT NOT NULL,
 schema_version INT NOT NULL DEFAULT 1,
 snapshot_json CLOB NOT NULL,
 digest CHAR(64) NOT NULL,
 creator_id BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 expires_at DATETIME NULL,
 CONSTRAINT uk_stocktake_snapshot_session UNIQUE(session_id,id),
 CONSTRAINT fk_stocktake_snapshot_session FOREIGN KEY(session_id) REFERENCES stocktake_session(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_stocktake_snapshot_kind CHECK(snapshot_kind IN ('BASELINE','REVIEW')),
 CONSTRAINT ck_stocktake_snapshot_version CHECK(schema_version=1 AND source_revision>=0)
);
ALTER TABLE stocktake_session
 ADD CONSTRAINT IF NOT EXISTS fk_stocktake_baseline FOREIGN KEY(id,baseline_snapshot_id) REFERENCES stocktake_snapshot(session_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE stocktake_session
 ADD CONSTRAINT IF NOT EXISTS fk_stocktake_final FOREIGN KEY(id,final_snapshot_id) REFERENCES stocktake_snapshot(session_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT;
CREATE TABLE IF NOT EXISTS stocktake_observation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 session_id BIGINT NOT NULL,
 client_event_key VARBINARY(128) NULL,
 input_hash CHAR(64) NULL,
 kind VARCHAR(16) NOT NULL,
 raw_barcode VARBINARY(1024) NULL,
 observed_placement_json CLOB NULL,
 resolved_copy_id_at_scan BIGINT NULL,
 signature_json CLOB NOT NULL,
 referenced_observation_id BIGINT NULL,
 actor_id BIGINT NOT NULL,
 accepted_at DATETIME NOT NULL,
 client_observed_at DATETIME NULL,
 reason VARCHAR(500) NOT NULL,
 CONSTRAINT uk_stocktake_client_event UNIQUE(session_id,client_event_key),
 CONSTRAINT uk_stocktake_observation_session UNIQUE(session_id,id),
 CONSTRAINT fk_stocktake_observation_session FOREIGN KEY(session_id) REFERENCES stocktake_session(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_stocktake_observation_reference FOREIGN KEY(session_id,referenced_observation_id) REFERENCES stocktake_observation(session_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_stocktake_observed_copy FOREIGN KEY(resolved_copy_id_at_scan) REFERENCES book_copy(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_stocktake_observation_kind CHECK(kind IN ('SCAN','VOID','CORRECTION','COVERAGE','RECHECK'))
);
CREATE TABLE IF NOT EXISTS stocktake_difference (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 session_id BIGINT NOT NULL,
 review_snapshot_id BIGINT NOT NULL,
 difference_key VARBINARY(256) NOT NULL,
 type VARCHAR(48) NOT NULL,
 copy_id BIGINT NULL,
 raw_identity VARBINARY(1024) NULL,
 evidence_json CLOB NOT NULL,
 creator_id BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 CONSTRAINT uk_stocktake_difference UNIQUE(session_id,review_snapshot_id,difference_key),
 CONSTRAINT uk_stocktake_difference_session UNIQUE(session_id,id),
 CONSTRAINT fk_stocktake_difference_snapshot FOREIGN KEY(session_id,review_snapshot_id) REFERENCES stocktake_snapshot(session_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_stocktake_difference_copy FOREIGN KEY(copy_id) REFERENCES book_copy(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE TABLE IF NOT EXISTS stocktake_resolution (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 session_id BIGINT NOT NULL,
 intent_json CLOB NOT NULL,
 state VARCHAR(24) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 actor_id BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 CONSTRAINT uk_stocktake_resolution_session UNIQUE(session_id,id),
 CONSTRAINT fk_stocktake_resolution_session FOREIGN KEY(session_id) REFERENCES stocktake_session(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_stocktake_resolution_state CHECK(state IN ('OPEN','NO_ACTION','AWAITING_LINK','LINKED','REVOKED')),
 CONSTRAINT ck_stocktake_resolution_version CHECK(version>=0)
);
CREATE TABLE IF NOT EXISTS stocktake_resolution_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 resolution_id BIGINT NOT NULL,
 operation_id BIGINT NOT NULL,
 kind VARCHAR(16) NOT NULL,
 source_identity VARBINARY(128) NULL,
 evidence_json CLOB NOT NULL,
 actor_id BIGINT NOT NULL,
 occurred_at DATETIME NOT NULL,
 reason VARCHAR(500) NOT NULL,
 CONSTRAINT uk_stocktake_resolution_source UNIQUE(source_identity),
 CONSTRAINT fk_stocktake_resolution_event FOREIGN KEY(resolution_id) REFERENCES stocktake_resolution(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_stocktake_resolution_operation FOREIGN KEY(operation_id) REFERENCES stocktake_operation(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_stocktake_resolution_event CHECK(kind IN ('DECIDE','INTENT','LINK','REVOKE'))
);
-- Only application append/read APIs; no hard database immutability claim.

CREATE ALIAS IF NOT EXISTS JSON_VALID FOR "com.library.catalogattributes.CatalogAttributeH2Json.valid";
CREATE ALIAS IF NOT EXISTS JSON_EXTRACT FOR "com.library.catalogattributes.CatalogAttributeH2Json.extract";
CREATE ALIAS IF NOT EXISTS JSON_TYPE FOR "com.library.catalogattributes.CatalogAttributeH2Json.type";
CREATE ALIAS IF NOT EXISTS JSON_CONTAINS_PATH FOR "com.library.catalogattributes.CatalogAttributeH2Json.contains";
CREATE TABLE IF NOT EXISTS catalog_book_attribute (
 book_id BIGINT NOT NULL PRIMARY KEY,
 revision BIGINT NOT NULL DEFAULT 0,
 display_title_override VARCHAR(200) NULL,
 display_origin VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN',
 adopted_source_title VARCHAR(200) NULL,
 binding_code VARCHAR(16) NULL,
 binding_origin VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN',
 age_known TINYINT NOT NULL DEFAULT 0,
 age_origin VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN',
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_attribute_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_attribute_revision CHECK(revision>=0),
 CONSTRAINT ck_catalog_display_origin CHECK(display_origin IN ('UNKNOWN','MANUAL','SOURCE')),
 CONSTRAINT ck_catalog_binding_origin CHECK(binding_origin IN ('UNKNOWN','MANUAL','SOURCE')),
 CONSTRAINT ck_catalog_age_origin CHECK(age_origin IN ('UNKNOWN','MANUAL','SOURCE')),
 CONSTRAINT ck_catalog_binding_code CHECK(binding_code IS NULL OR binding_code IN ('BOARD','PAPERBACK','HARDCOVER')),
 CONSTRAINT ck_catalog_age_known CHECK(age_known IN (0,1)),
 CONSTRAINT ck_catalog_display_shape CHECK((display_origin<>'UNKNOWN' OR display_title_override IS NULL) AND (display_origin<>'SOURCE' OR display_title_override IS NOT NULL)),
 CONSTRAINT ck_catalog_binding_shape CHECK((binding_origin<>'UNKNOWN' OR binding_code IS NULL) AND (binding_origin<>'SOURCE' OR binding_code IS NOT NULL)),
 CONSTRAINT ck_catalog_age_shape CHECK((age_origin<>'UNKNOWN' OR age_known=0) AND (age_origin<>'SOURCE' OR age_known=1))
);

CREATE TABLE IF NOT EXISTS catalog_book_age_label (
 book_id BIGINT NOT NULL,
 label_code VARCHAR(16) NOT NULL,
 PRIMARY KEY(book_id,label_code),
 KEY idx_catalog_age_label(label_code,book_id),
 CONSTRAINT fk_catalog_age_attribute FOREIGN KEY(book_id) REFERENCES catalog_book_attribute(book_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_age_code CHECK(label_code IN ('AGE_0_2','AGE_3_4','AGE_5_6','AGE_7_9','AGE_10_12'))
);

CREATE TABLE IF NOT EXISTS catalog_attribute_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operation_key VARBINARY(128) NOT NULL,
 actor_id BIGINT NOT NULL,
 mode VARCHAR(8) NOT NULL,
 input_hash CHAR(64) NOT NULL,
 schema_version INT NOT NULL DEFAULT 1,
 request_json LONGTEXT NOT NULL,
 preview_json LONGTEXT NOT NULL,
 preview_digest CHAR(64) NOT NULL,
 state VARCHAR(12) NOT NULL,
 created_at DATETIME NOT NULL,
 expires_at DATETIME NOT NULL,
 receipt_json LONGTEXT NULL,
 committed_at DATETIME NULL,
 UNIQUE KEY uk_catalog_attribute_key(operation_key),
 KEY idx_catalog_attribute_actor(actor_id,id),
 CONSTRAINT ck_catalog_request_mode CHECK(mode IN ('MANUAL','SOURCE')),
 CONSTRAINT ck_catalog_request_version CHECK(schema_version=1),
 CONSTRAINT ck_catalog_request_input CHECK(JSON_VALID(request_json)),
 CONSTRAINT ck_catalog_request_preview CHECK(JSON_VALID(preview_json)),
 CONSTRAINT ck_catalog_request_receipt CHECK(receipt_json IS NULL OR JSON_VALID(receipt_json)),
 CONSTRAINT ck_catalog_request_state CHECK((state='PREVIEWED' AND receipt_json IS NULL AND committed_at IS NULL) OR (state='COMMITTED' AND receipt_json IS NOT NULL AND committed_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS catalog_attribute_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 book_id BIGINT NOT NULL,
 request_id BIGINT NULL,
 kind VARCHAR(16) NOT NULL,
 before_revision BIGINT NOT NULL,
 after_revision BIGINT NOT NULL,
 schema_version INT NOT NULL DEFAULT 1,
 changed_fields_json LONGTEXT NOT NULL,
 before_json LONGTEXT NOT NULL,
 after_json LONGTEXT NOT NULL,
 source_evidence_json LONGTEXT NULL,
 actor_id BIGINT NOT NULL,
 occurred_at DATETIME NOT NULL,
 reason VARCHAR(500) NOT NULL,
 UNIQUE KEY uk_catalog_event_revision(book_id,after_revision),
 KEY idx_catalog_event_book(book_id,id),
 KEY idx_catalog_event_request(request_id,id),
 CONSTRAINT fk_catalog_event_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_catalog_event_request FOREIGN KEY(request_id) REFERENCES catalog_attribute_request(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_event_revision CHECK(before_revision>=0 AND after_revision=before_revision+1),
 CONSTRAINT ck_catalog_event_version CHECK(schema_version=1),
 CONSTRAINT ck_catalog_event_kind CHECK((kind='LEGACY_TITLE' AND request_id IS NULL AND source_evidence_json IS NULL) OR (kind='MANUAL' AND request_id IS NOT NULL AND source_evidence_json IS NULL) OR (kind='SOURCE' AND request_id IS NOT NULL AND source_evidence_json IS NOT NULL)),
 CONSTRAINT ck_catalog_event_fields CHECK(JSON_VALID(changed_fields_json)),
 CONSTRAINT ck_catalog_event_before CHECK(JSON_VALID(before_json)),
 CONSTRAINT ck_catalog_event_after CHECK(JSON_VALID(after_json)),
 CONSTRAINT ck_catalog_event_source CHECK(source_evidence_json IS NULL OR JSON_VALID(source_evidence_json))
);

CREATE TABLE IF NOT EXISTS catalog_publisher_profile (
 publisher_id BIGINT NOT NULL PRIMARY KEY,
 normalized_name VARCHAR(3600) NOT NULL,
 normalized_key VARBINARY(14400) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_publisher_profile FOREIGN KEY(publisher_id) REFERENCES publisher(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_publisher_profile_revision CHECK(revision>=0),
 KEY idx_catalog_publisher_normalized(normalized_name)
);

CREATE TABLE IF NOT EXISTS catalog_publisher_alias (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 publisher_id BIGINT NOT NULL,
 alias_name VARCHAR(200) NOT NULL,
 normalized_name VARCHAR(3600) NOT NULL,
 alias_key VARBINARY(800) NOT NULL,
 origin VARCHAR(8) NOT NULL DEFAULT 'MANUAL',
 status VARCHAR(8) NOT NULL DEFAULT 'ACTIVE',
 revision BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_publisher_alias FOREIGN KEY(publisher_id) REFERENCES publisher(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_publisher_alias_origin CHECK(origin IN ('MANUAL','SOURCE','LEGACY')),
 CONSTRAINT ck_catalog_publisher_alias_status CHECK(status IN ('ACTIVE','RETIRED')),
 CONSTRAINT ck_catalog_publisher_alias_revision CHECK(revision>=0),
 UNIQUE KEY uk_catalog_publisher_alias(publisher_id,alias_key),
 KEY idx_catalog_publisher_alias_name(normalized_name)
);

CREATE TABLE IF NOT EXISTS catalog_category_profile (
 category_id BIGINT NOT NULL PRIMARY KEY,
 normalized_name VARCHAR(3600) NOT NULL,
 normalized_key VARBINARY(14400) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 dimension VARCHAR(12) NOT NULL DEFAULT 'UNVERIFIED',
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_category_profile FOREIGN KEY(category_id) REFERENCES book_category(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_category_profile_revision CHECK(revision>=0),
 CONSTRAINT ck_catalog_category_dimension CHECK(dimension IN ('TOPIC','LEGACY_AGE','UNVERIFIED')),
 KEY idx_catalog_category_normalized(normalized_name)
);

CREATE TABLE IF NOT EXISTS catalog_category_alias (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 category_id BIGINT NOT NULL,
 alias_name VARCHAR(100) NOT NULL,
 normalized_name VARCHAR(3600) NOT NULL,
 alias_key VARBINARY(400) NOT NULL,
 origin VARCHAR(8) NOT NULL DEFAULT 'MANUAL',
 status VARCHAR(8) NOT NULL DEFAULT 'ACTIVE',
 revision BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_category_alias FOREIGN KEY(category_id) REFERENCES book_category(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_category_alias_origin CHECK(origin IN ('MANUAL','SOURCE','LEGACY')),
 CONSTRAINT ck_catalog_category_alias_status CHECK(status IN ('ACTIVE','RETIRED')),
 CONSTRAINT ck_catalog_category_alias_revision CHECK(revision>=0),
 UNIQUE KEY uk_catalog_category_alias(category_id,alias_key),
 KEY idx_catalog_category_alias_name(normalized_name)
);

CREATE TABLE IF NOT EXISTS catalog_book_publisher (
 book_id BIGINT NOT NULL PRIMARY KEY,
 publisher_id BIGINT NOT NULL,
 source_name VARCHAR(200) NULL,
 relation_kind VARCHAR(12) NOT NULL DEFAULT 'CONFIRMED',
 revision BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_book_publisher_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_catalog_book_publisher_publisher FOREIGN KEY(publisher_id) REFERENCES publisher(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_book_publisher_kind CHECK(relation_kind IN ('CONFIRMED','HISTORICAL')),
 CONSTRAINT ck_catalog_book_publisher_revision CHECK(revision>=0),
 KEY idx_catalog_book_publisher_publisher(publisher_id,book_id)
);

CREATE TABLE IF NOT EXISTS catalog_book_theme (
 book_id BIGINT NOT NULL,
 category_id BIGINT NOT NULL,
 source_name VARCHAR(100) NULL,
 relation_kind VARCHAR(12) NOT NULL DEFAULT 'CONFIRMED',
 revision BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME NOT NULL,
 PRIMARY KEY(book_id,category_id),
 CONSTRAINT fk_catalog_book_theme_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_catalog_book_theme_category FOREIGN KEY(category_id) REFERENCES book_category(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_book_theme_kind CHECK(relation_kind IN ('CONFIRMED','HISTORICAL')),
 CONSTRAINT ck_catalog_book_theme_revision CHECK(revision>=0),
 KEY idx_catalog_book_theme_category(category_id,book_id)
);

CREATE TABLE IF NOT EXISTS catalog_identity_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operation_key VARBINARY(128) NOT NULL,
 book_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 mode VARCHAR(8) NOT NULL,
 input_hash CHAR(64) NOT NULL,
 schema_version INT NOT NULL DEFAULT 1,
 request_json LONGTEXT NOT NULL,
 preview_json LONGTEXT NOT NULL,
 preview_digest CHAR(64) NOT NULL,
 state VARCHAR(12) NOT NULL,
 created_at DATETIME NOT NULL,
 expires_at DATETIME NOT NULL,
 receipt_json LONGTEXT NULL,
 committed_at DATETIME NULL,
 UNIQUE KEY uk_catalog_identity_key(operation_key),
 KEY idx_catalog_identity_actor(actor_id,id),
 CONSTRAINT fk_catalog_identity_request_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_identity_mode CHECK(mode IN ('MANUAL','SOURCE')),
 CONSTRAINT ck_catalog_identity_version CHECK(schema_version=1),
 CONSTRAINT ck_catalog_identity_request_json CHECK(JSON_VALID(request_json)),
 CONSTRAINT ck_catalog_identity_preview_json CHECK(JSON_VALID(preview_json)),
 CONSTRAINT ck_catalog_identity_receipt_json CHECK(receipt_json IS NULL OR JSON_VALID(receipt_json)),
 CONSTRAINT ck_catalog_identity_state CHECK((state='PREVIEWED' AND receipt_json IS NULL AND committed_at IS NULL) OR (state='COMMITTED' AND receipt_json IS NOT NULL AND committed_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS catalog_identity_candidate (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id BIGINT NOT NULL,
 book_id BIGINT NOT NULL,
 entity_kind VARCHAR(12) NOT NULL,
 raw_value VARCHAR(200) NULL,
 normalized_value VARCHAR(3600) NULL,
 match_kind VARCHAR(12) NOT NULL,
 candidate_id BIGINT NULL,
 evidence_json LONGTEXT NULL,
 status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
 created_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_identity_candidate_request FOREIGN KEY(request_id) REFERENCES catalog_identity_request(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_catalog_identity_candidate_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_identity_candidate_entity CHECK(entity_kind IN ('PUBLISHER','CATEGORY')),
 CONSTRAINT ck_catalog_identity_candidate_match CHECK(match_kind IN ('EXACT','NORMALIZED','APPROXIMATE','UNMATCHED')),
 CONSTRAINT ck_catalog_identity_candidate_status CHECK(status IN ('PENDING','CONFIRMED','REJECTED')),
 CONSTRAINT ck_catalog_identity_candidate_evidence CHECK(evidence_json IS NULL OR JSON_VALID(evidence_json)),
 KEY idx_catalog_identity_candidate_book(book_id,id)
);

CREATE TABLE IF NOT EXISTS catalog_identity_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id BIGINT NULL,
 book_id BIGINT NULL,
 entity_kind VARCHAR(16) NOT NULL,
 entity_id BIGINT NULL,
 before_revision BIGINT NOT NULL,
 after_revision BIGINT NOT NULL,
 changed_fields_json LONGTEXT NOT NULL,
 before_json LONGTEXT NOT NULL,
 after_json LONGTEXT NOT NULL,
 evidence_json LONGTEXT NULL,
 actor_id BIGINT NOT NULL,
 occurred_at DATETIME NOT NULL,
 reason VARCHAR(500) NOT NULL,
 CONSTRAINT fk_catalog_identity_event_request FOREIGN KEY(request_id) REFERENCES catalog_identity_request(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_catalog_identity_event_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_identity_event_revision CHECK(before_revision>=0 AND after_revision=before_revision+1),
 CONSTRAINT ck_catalog_identity_event_entity CHECK(entity_kind IN ('PUBLISHER','CATEGORY','BOOK_PUBLISHER','BOOK_THEME','BOOK_METADATA','ALIAS')),
 CONSTRAINT ck_catalog_identity_event_fields CHECK(JSON_VALID(changed_fields_json)),
 CONSTRAINT ck_catalog_identity_event_before CHECK(JSON_VALID(before_json)),
 CONSTRAINT ck_catalog_identity_event_after CHECK(JSON_VALID(after_json)),
 CONSTRAINT ck_catalog_identity_event_evidence CHECK(evidence_json IS NULL OR JSON_VALID(evidence_json)),
 KEY idx_catalog_identity_event_book(book_id,id),
 KEY idx_catalog_identity_event_entity(entity_kind,entity_id,id)
);
ALTER TABLE book ADD COLUMN IF NOT EXISTS publication_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE book ADD CONSTRAINT IF NOT EXISTS ck_book_publication_revision CHECK(publication_revision>=0);

CREATE TABLE IF NOT EXISTS catalog_publication_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operation_key VARBINARY(36) NOT NULL,
 actor_id BIGINT NOT NULL,
 mode VARCHAR(8) NOT NULL,
 input_hash CHAR(64) NOT NULL,
 schema_version INT NOT NULL DEFAULT 1,
 request_json LONGTEXT NOT NULL,
 preview_json LONGTEXT NOT NULL,
 preview_digest CHAR(64) NOT NULL,
 state VARCHAR(12) NOT NULL,
 created_at DATETIME NOT NULL,
 expires_at DATETIME NOT NULL,
 receipt_json LONGTEXT NULL,
 committed_at DATETIME NULL,
 UNIQUE KEY uk_catalog_publication_key(operation_key),
 KEY idx_catalog_publication_actor(actor_id,id),
 CONSTRAINT ck_publication_mode CHECK(mode='MANUAL'),
 CONSTRAINT ck_publication_schema CHECK(schema_version=1),
 CONSTRAINT ck_publication_input CHECK(JSON_VALID(request_json)),
 CONSTRAINT ck_publication_preview CHECK(JSON_VALID(preview_json)),
 CONSTRAINT ck_publication_receipt CHECK(receipt_json IS NULL OR JSON_VALID(receipt_json)),
 CONSTRAINT ck_publication_state CHECK((state='PREVIEWED' AND receipt_json IS NULL AND committed_at IS NULL) OR (state='COMMITTED' AND receipt_json IS NOT NULL AND committed_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS catalog_publication_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 book_id BIGINT NOT NULL,
 request_id BIGINT NULL,
 kind VARCHAR(32) NOT NULL,
 before_revision BIGINT NOT NULL,
 after_revision BIGINT NOT NULL,
 schema_version INT NOT NULL DEFAULT 1,
 changed_fields_json LONGTEXT NOT NULL,
 before_json LONGTEXT NOT NULL,
 after_json LONGTEXT NOT NULL,
 evidence_json LONGTEXT NULL,
 actor_id BIGINT NOT NULL,
 occurred_at DATETIME NOT NULL,
 reason VARCHAR(500) NOT NULL,
 UNIQUE KEY uk_publication_event_revision(book_id,after_revision),
 KEY idx_publication_event_actor(book_id,actor_id,id),
 KEY idx_publication_event_request(request_id,id),
 CONSTRAINT fk_publication_event_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_publication_event_request FOREIGN KEY(request_id) REFERENCES catalog_publication_request(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_publication_event_revision CHECK(before_revision>=0 AND after_revision=before_revision+1),
 CONSTRAINT ck_publication_event_schema CHECK(schema_version=1),
 CONSTRAINT ck_publication_event_kind CHECK((kind='MANUAL' AND request_id IS NOT NULL AND evidence_json IS NOT NULL) OR (kind IN ('LEGACY_METADATA','LEGACY_NORMALIZE','LEGACY_NORMALIZE_BATCH','LEGACY_DELETE') AND request_id IS NULL AND evidence_json IS NULL)),
 CONSTRAINT ck_publication_event_fields CHECK(JSON_VALID(changed_fields_json)),
 CONSTRAINT ck_publication_event_before CHECK(JSON_VALID(before_json)),
 CONSTRAINT ck_publication_event_after CHECK(JSON_VALID(after_json)),
 CONSTRAINT ck_publication_event_evidence CHECK(evidence_json IS NULL OR JSON_VALID(evidence_json))
);

ALTER TABLE book ADD COLUMN IF NOT EXISTS catalog_set_book_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE book ADD CONSTRAINT IF NOT EXISTS ck_book_set_revision
 CHECK(catalog_set_book_revision BETWEEN 0 AND 9007199254740991);

CREATE TABLE IF NOT EXISTS catalog_set (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(400) NOT NULL,
 normalized_name VARCHAR(3600) NOT NULL,
 note TEXT NULL,
 status VARCHAR(8) NOT NULL,
 revision BIGINT NOT NULL,
 evidence_json LONGTEXT NOT NULL,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 KEY idx_catalog_set_status(status,id),
 KEY idx_catalog_set_name(normalized_name),
 CONSTRAINT ck_catalog_set_status CHECK(status IN ('ACTIVE','RETIRED')),
 CONSTRAINT ck_catalog_set_revision CHECK(revision BETWEEN 1 AND 9007199254740991),
 CONSTRAINT ck_catalog_set_evidence CHECK(JSON_VALID(evidence_json))
);

CREATE TABLE IF NOT EXISTS catalog_set_member (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 set_id BIGINT NOT NULL,
 book_id BIGINT NOT NULL,
 status VARCHAR(8) NOT NULL,
 revision BIGINT NOT NULL,
 evidence_json LONGTEXT NOT NULL,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 UNIQUE KEY uk_catalog_set_member(set_id,book_id),
 KEY idx_catalog_set_member_book(book_id,status,set_id),
 KEY idx_catalog_set_member_status(set_id,status,book_id),
 CONSTRAINT fk_catalog_set_member_set FOREIGN KEY(set_id) REFERENCES catalog_set(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_catalog_set_member_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_set_member_status CHECK(status IN ('ACTIVE','RETIRED')),
 CONSTRAINT ck_catalog_set_member_revision CHECK(revision BETWEEN 1 AND 9007199254740991),
 CONSTRAINT ck_catalog_set_member_evidence CHECK(JSON_VALID(evidence_json))
);

CREATE TABLE IF NOT EXISTS catalog_set_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operation_key VARBINARY(36) NOT NULL,
 actor_id BIGINT NOT NULL,
 schema_version INT NOT NULL DEFAULT 1,
 mode VARCHAR(8) NOT NULL,
 operation VARCHAR(20) NOT NULL,
 set_id BIGINT NULL,
 input_hash CHAR(64) NOT NULL,
 request_json LONGTEXT NOT NULL,
 preview_json LONGTEXT NOT NULL,
 preview_digest CHAR(64) NOT NULL,
 state VARCHAR(12) NOT NULL,
 created_at DATETIME NOT NULL,
 expires_at DATETIME NOT NULL,
 receipt_json LONGTEXT NULL,
 committed_at DATETIME NULL,
 cancelled_at DATETIME NULL,
 cancel_reason VARCHAR(7) NULL,
 UNIQUE KEY uk_catalog_set_request_key(operation_key),
 KEY idx_catalog_set_request_actor(actor_id,id),
 KEY idx_catalog_set_request_set(set_id,state,expires_at),
 CONSTRAINT fk_catalog_set_request_set FOREIGN KEY(set_id) REFERENCES catalog_set(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_set_request_schema CHECK(schema_version=1),
 CONSTRAINT ck_catalog_set_request_mode CHECK(mode='MANUAL'),
 CONSTRAINT ck_catalog_set_request_operation CHECK(operation IN ('CREATE','UPDATE_METADATA','ADD_MEMBERS','RETIRE_MEMBERS','RESTORE_MEMBERS','RETIRE_SET','RESTORE_SET')),
 CONSTRAINT ck_catalog_set_request_target CHECK((operation='CREATE' AND (state IN ('PREVIEWED','CANCELLED') AND set_id IS NULL OR state='COMMITTED' AND set_id IS NOT NULL)) OR (operation<>'CREATE' AND set_id IS NOT NULL)),
 CONSTRAINT ck_catalog_set_request_input CHECK(JSON_VALID(request_json)),
 CONSTRAINT ck_catalog_set_request_preview CHECK(JSON_VALID(preview_json)),
 CONSTRAINT ck_catalog_set_request_receipt CHECK(receipt_json IS NULL OR JSON_VALID(receipt_json)),
 CONSTRAINT ck_catalog_set_request_expiry CHECK(expires_at>created_at),
 CONSTRAINT ck_catalog_set_request_state CHECK((state='PREVIEWED' AND receipt_json IS NULL AND committed_at IS NULL AND cancelled_at IS NULL AND cancel_reason IS NULL) OR (state='COMMITTED' AND receipt_json IS NOT NULL AND committed_at IS NOT NULL AND cancelled_at IS NULL AND cancel_reason IS NULL) OR (state='CANCELLED' AND receipt_json IS NULL AND committed_at IS NULL AND cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL AND cancel_reason IN ('EDIT','ABANDON')))
);

CREATE TABLE IF NOT EXISTS catalog_set_request_book (
 request_id BIGINT NOT NULL,
 book_id BIGINT NOT NULL,
 expected_book_revision BIGINT NOT NULL,
 expected_attribute_revision BIGINT NOT NULL,
 expected_book_digest CHAR(64) NOT NULL,
 expected_source_digest CHAR(64) NOT NULL,
 PRIMARY KEY(request_id,book_id),
 KEY idx_catalog_set_request_book(book_id,request_id),
 CONSTRAINT fk_catalog_set_request_book_request FOREIGN KEY(request_id) REFERENCES catalog_set_request(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_catalog_set_request_book_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_set_request_book_revision CHECK(expected_book_revision BETWEEN 0 AND 9007199254740991),
 CONSTRAINT ck_catalog_set_request_attribute_revision CHECK(expected_attribute_revision BETWEEN 0 AND 9007199254740991)
);

CREATE TABLE IF NOT EXISTS catalog_set_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id BIGINT NOT NULL,
 set_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 schema_version INT NOT NULL DEFAULT 1,
 operation VARCHAR(20) NOT NULL,
 before_revision BIGINT NOT NULL,
 after_revision BIGINT NOT NULL,
 changed_fields_json LONGTEXT NOT NULL,
 before_json LONGTEXT NOT NULL,
 after_json LONGTEXT NOT NULL,
 evidence_json LONGTEXT NOT NULL,
 books_json LONGTEXT NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 occurred_at DATETIME NOT NULL,
 UNIQUE KEY uk_catalog_set_event_request(request_id),
 UNIQUE KEY uk_catalog_set_event_revision(set_id,after_revision),
 KEY idx_catalog_set_event_actor(set_id,actor_id,id),
 CONSTRAINT fk_catalog_set_event_request FOREIGN KEY(request_id) REFERENCES catalog_set_request(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_catalog_set_event_set FOREIGN KEY(set_id) REFERENCES catalog_set(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_set_event_schema CHECK(schema_version=1),
 CONSTRAINT ck_catalog_set_event_operation CHECK(operation IN ('CREATE','UPDATE_METADATA','ADD_MEMBERS','RETIRE_MEMBERS','RESTORE_MEMBERS','RETIRE_SET','RESTORE_SET')),
 CONSTRAINT ck_catalog_set_event_revision CHECK(before_revision>=0 AND after_revision=before_revision+1 AND after_revision<=9007199254740991),
 CONSTRAINT ck_catalog_set_event_fields CHECK(JSON_VALID(changed_fields_json)),
 CONSTRAINT ck_catalog_set_event_before CHECK(JSON_VALID(before_json)),
 CONSTRAINT ck_catalog_set_event_after CHECK(JSON_VALID(after_json)),
 CONSTRAINT ck_catalog_set_event_evidence CHECK(JSON_VALID(evidence_json)),
 CONSTRAINT ck_catalog_set_event_books CHECK(JSON_VALID(books_json))
);

CREATE ALIAS IF NOT EXISTS JSON_UNQUOTE FOR "com.library.catalogsets.CatalogSetH2Json.unquote";

CREATE ALIAS IF NOT EXISTS B34_JSON_VALUE_HASH FOR "com.library.catalogsets.CatalogSetH2Json.valueHash";

-- B50 counter operations only. No historical rows or definitions are changed.
CREATE TABLE IF NOT EXISTS circulation_request (
 operation_key VARBINARY(36) NOT NULL PRIMARY KEY,
 actor_id BIGINT NOT NULL,
 operation VARCHAR(6) NULL,
 state VARCHAR(12) NOT NULL,
 input_hash CHAR(64) NULL,
 request_json LONGTEXT NULL,
 preview_json LONGTEXT NULL,
 guard_json LONGTEXT NULL,
 preview_digest CHAR(64) NULL,
 business_date DATE NULL,
 created_at DATETIME NOT NULL,
 expires_at DATETIME NULL,
 committed_at DATETIME NULL,
 cancelled_at DATETIME NULL,
 receipt_json LONGTEXT NULL,
 KEY idx_circulation_request_actor(actor_id,created_at),
 CONSTRAINT ck_circulation_request_actor CHECK(actor_id BETWEEN 1 AND 9007199254740991),
 CONSTRAINT ck_circulation_operation CHECK(operation IS NULL OR operation IN ('BORROW','RETURN')),
 CONSTRAINT ck_circulation_request_state CHECK(
  (state='RESERVED' AND operation IS NULL AND receipt_json IS NULL AND committed_at IS NULL AND cancelled_at IS NULL) OR
  (state='PREVIEWED' AND operation IS NOT NULL AND request_json IS NOT NULL AND input_hash IS NOT NULL AND preview_json IS NOT NULL AND guard_json IS NOT NULL AND preview_digest IS NOT NULL AND business_date IS NOT NULL AND expires_at IS NOT NULL AND receipt_json IS NULL AND committed_at IS NULL AND cancelled_at IS NULL) OR
  (state='COMMITTED' AND operation IS NOT NULL AND request_json IS NOT NULL AND input_hash IS NOT NULL AND preview_json IS NOT NULL AND guard_json IS NOT NULL AND preview_digest IS NOT NULL AND business_date IS NOT NULL AND expires_at IS NOT NULL AND receipt_json IS NOT NULL AND committed_at IS NOT NULL AND cancelled_at IS NULL) OR
  (state='CANCELLED' AND receipt_json IS NULL AND committed_at IS NULL AND cancelled_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS circulation_order (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_key VARBINARY(36) NOT NULL,
 user_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 business_date DATE NOT NULL,
 item_count INT NOT NULL,
 remark VARCHAR(500) NOT NULL,
 UNIQUE KEY uk_circulation_order_request(request_key),
 KEY idx_circulation_order_user(user_id,id),
 CONSTRAINT ck_circulation_order_ids CHECK(user_id BETWEEN 1 AND 9007199254740991 AND actor_id BETWEEN 1 AND 9007199254740991),
 CONSTRAINT ck_circulation_order_count CHECK(item_count BETWEEN 1 AND 10)
);

CREATE TABLE IF NOT EXISTS circulation_order_item (
 order_id BIGINT NOT NULL,
 ordinal INT NOT NULL,
 record_id BIGINT NOT NULL,
 book_id BIGINT NOT NULL,
 copy_id BIGINT NOT NULL,
 book_title VARCHAR(200) NOT NULL,
 barcode VARCHAR(64) NOT NULL,
 PRIMARY KEY(order_id,ordinal),
 UNIQUE KEY uk_circulation_order_record(record_id),
 CONSTRAINT ck_circulation_item_ids CHECK(order_id BETWEEN 1 AND 9007199254740991 AND record_id BETWEEN 1 AND 9007199254740991 AND book_id BETWEEN 1 AND 9007199254740991 AND copy_id BETWEEN 1 AND 9007199254740991),
 CONSTRAINT ck_circulation_item_ordinal CHECK(ordinal BETWEEN 1 AND 10)
);
