ALTER TABLE book ADD COLUMN catalog_set_book_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE book ADD CONSTRAINT ck_book_set_revision
 CHECK(catalog_set_book_revision BETWEEN 0 AND 9007199254740991);

CREATE TABLE catalog_set (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(200) NOT NULL,
 normalized_name VARCHAR(3600) NOT NULL,
 note TEXT NULL,
 status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL,
 evidence_json LONGTEXT NOT NULL,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 KEY idx_catalog_set_status(status,id),
 KEY idx_catalog_set_name(normalized_name(191)),
 CONSTRAINT ck_catalog_set_status CHECK(status IN ('ACTIVE','RETIRED')),
 CONSTRAINT ck_catalog_set_revision CHECK(revision BETWEEN 1 AND 9007199254740991),
 CONSTRAINT ck_catalog_set_evidence CHECK(JSON_VALID(evidence_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_set_member (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 set_id BIGINT NOT NULL,
 book_id BIGINT NOT NULL,
 status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_set_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operation_key VARBINARY(36) NOT NULL,
 actor_id BIGINT NOT NULL,
 schema_version INT NOT NULL DEFAULT 1,
 mode VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 set_id BIGINT NULL,
 input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_json LONGTEXT NOT NULL,
 preview_json LONGTEXT NOT NULL,
 preview_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME NOT NULL,
 expires_at DATETIME NOT NULL,
 receipt_json LONGTEXT NULL,
 committed_at DATETIME NULL,
 cancelled_at DATETIME NULL,
 cancel_reason VARCHAR(7) CHARACTER SET ascii COLLATE ascii_bin NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_set_request_book (
 request_id BIGINT NOT NULL,
 book_id BIGINT NOT NULL,
 expected_book_revision BIGINT NOT NULL,
 expected_attribute_revision BIGINT NOT NULL,
 expected_book_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expected_source_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(request_id,book_id),
 KEY idx_catalog_set_request_book(book_id,request_id),
 CONSTRAINT fk_catalog_set_request_book_request FOREIGN KEY(request_id) REFERENCES catalog_set_request(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_catalog_set_request_book_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_set_request_book_revision CHECK(expected_book_revision BETWEEN 0 AND 9007199254740991),
 CONSTRAINT ck_catalog_set_request_attribute_revision CHECK(expected_attribute_revision BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_set_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id BIGINT NOT NULL,
 set_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 schema_version INT NOT NULL DEFAULT 1,
 operation VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 before_revision BIGINT NOT NULL,
 after_revision BIGINT NOT NULL,
 changed_fields_json LONGTEXT NOT NULL,
 before_json LONGTEXT NOT NULL,
 after_json LONGTEXT NOT NULL,
 evidence_json LONGTEXT NOT NULL,
 books_json LONGTEXT NOT NULL,
 reason VARCHAR(500) NOT NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
