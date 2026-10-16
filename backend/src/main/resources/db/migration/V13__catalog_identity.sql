/* B35 catalog identity metadata. Legacy book.publisher/category_id values are intentionally untouched. */
ALTER TABLE book ADD COLUMN catalog_identity_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE publisher ADD COLUMN catalog_identity_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE book_category ADD COLUMN catalog_identity_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE book ADD CONSTRAINT ck_book_identity_revision CHECK(catalog_identity_revision>=0);
ALTER TABLE publisher ADD CONSTRAINT ck_publisher_identity_revision CHECK(catalog_identity_revision>=0);
ALTER TABLE book_category ADD CONSTRAINT ck_category_identity_revision CHECK(catalog_identity_revision>=0);

CREATE TABLE catalog_publisher_profile (
 publisher_id BIGINT NOT NULL PRIMARY KEY,
 normalized_name VARCHAR(3600) NOT NULL,
 normalized_key VARBINARY(14400) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_publisher_profile FOREIGN KEY(publisher_id) REFERENCES publisher(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_publisher_profile_revision CHECK(revision>=0),
 KEY idx_catalog_publisher_normalized(normalized_name(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_publisher_alias (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 publisher_id BIGINT NOT NULL,
 alias_name VARCHAR(200) NOT NULL,
 normalized_name VARCHAR(3600) NOT NULL,
 alias_key VARBINARY(800) NOT NULL,
 origin VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MANUAL',
 status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
 revision BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_publisher_alias FOREIGN KEY(publisher_id) REFERENCES publisher(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_publisher_alias_origin CHECK(origin IN ('MANUAL','SOURCE','LEGACY')),
 CONSTRAINT ck_catalog_publisher_alias_status CHECK(status IN ('ACTIVE','RETIRED')),
 CONSTRAINT ck_catalog_publisher_alias_revision CHECK(revision>=0),
 UNIQUE KEY uk_catalog_publisher_alias(publisher_id,alias_key),
 KEY idx_catalog_publisher_alias_name(normalized_name(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_category_profile (
 category_id BIGINT NOT NULL PRIMARY KEY,
 normalized_name VARCHAR(3600) NOT NULL,
 normalized_key VARBINARY(14400) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 dimension VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNVERIFIED',
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_category_profile FOREIGN KEY(category_id) REFERENCES book_category(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_category_profile_revision CHECK(revision>=0),
 CONSTRAINT ck_catalog_category_dimension CHECK(dimension IN ('TOPIC','LEGACY_AGE','UNVERIFIED')),
 KEY idx_catalog_category_normalized(normalized_name(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_category_alias (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 category_id BIGINT NOT NULL,
 alias_name VARCHAR(100) NOT NULL,
 normalized_name VARCHAR(3600) NOT NULL,
 alias_key VARBINARY(400) NOT NULL,
 origin VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MANUAL',
 status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
 revision BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_category_alias FOREIGN KEY(category_id) REFERENCES book_category(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_category_alias_origin CHECK(origin IN ('MANUAL','SOURCE','LEGACY')),
 CONSTRAINT ck_catalog_category_alias_status CHECK(status IN ('ACTIVE','RETIRED')),
 CONSTRAINT ck_catalog_category_alias_revision CHECK(revision>=0),
 UNIQUE KEY uk_catalog_category_alias(category_id,alias_key),
 KEY idx_catalog_category_alias_name(normalized_name(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_book_publisher (
 book_id BIGINT NOT NULL PRIMARY KEY,
 publisher_id BIGINT NOT NULL,
 source_name VARCHAR(200) NULL,
 relation_kind VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CONFIRMED',
 revision BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_book_publisher_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_catalog_book_publisher_publisher FOREIGN KEY(publisher_id) REFERENCES publisher(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_book_publisher_kind CHECK(relation_kind IN ('CONFIRMED','HISTORICAL')),
 CONSTRAINT ck_catalog_book_publisher_revision CHECK(revision>=0),
 KEY idx_catalog_book_publisher_publisher(publisher_id,book_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_book_theme (
 book_id BIGINT NOT NULL,
 category_id BIGINT NOT NULL,
 source_name VARCHAR(100) NULL,
 relation_kind VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CONFIRMED',
 revision BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME NOT NULL,
 PRIMARY KEY(book_id,category_id),
 CONSTRAINT fk_catalog_book_theme_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_catalog_book_theme_category FOREIGN KEY(category_id) REFERENCES book_category(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_book_theme_kind CHECK(relation_kind IN ('CONFIRMED','HISTORICAL')),
 CONSTRAINT ck_catalog_book_theme_revision CHECK(revision>=0),
 KEY idx_catalog_book_theme_category(category_id,book_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_identity_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operation_key VARBINARY(128) NOT NULL,
 book_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 mode VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 schema_version INT NOT NULL DEFAULT 1,
 request_json LONGTEXT NOT NULL,
 preview_json LONGTEXT NOT NULL,
 preview_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_identity_candidate (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id BIGINT NOT NULL,
 book_id BIGINT NOT NULL,
 entity_kind VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 raw_value VARCHAR(200) NULL,
 normalized_value VARCHAR(3600) NULL,
 match_kind VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 candidate_id BIGINT NULL,
 evidence_json LONGTEXT NULL,
 status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
 created_at DATETIME NOT NULL,
 CONSTRAINT fk_catalog_identity_candidate_request FOREIGN KEY(request_id) REFERENCES catalog_identity_request(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_catalog_identity_candidate_book FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_catalog_identity_candidate_entity CHECK(entity_kind IN ('PUBLISHER','CATEGORY')),
 CONSTRAINT ck_catalog_identity_candidate_match CHECK(match_kind IN ('EXACT','NORMALIZED','APPROXIMATE','UNMATCHED')),
 CONSTRAINT ck_catalog_identity_candidate_status CHECK(status IN ('PENDING','CONFIRMED','REJECTED')),
 CONSTRAINT ck_catalog_identity_candidate_evidence CHECK(evidence_json IS NULL OR JSON_VALID(evidence_json)),
 KEY idx_catalog_identity_candidate_book(book_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_identity_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id BIGINT NULL,
 book_id BIGINT NULL,
 entity_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
