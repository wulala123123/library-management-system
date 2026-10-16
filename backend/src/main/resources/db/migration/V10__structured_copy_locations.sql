-- Technical defaults never validate, migrate or normalize legacy placement/binding data.
ALTER TABLE bookshelf ADD COLUMN layout_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_shelf_layout_version CHECK (layout_version >= 0);
ALTER TABLE bookshelf_storey ADD COLUMN layout_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_storey_layout_version CHECK (layout_version >= 0);
ALTER TABLE book_location ADD COLUMN layout_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_location_layout_version CHECK (layout_version >= 0);
ALTER TABLE book_copy ADD COLUMN registered_location_id BIGINT NULL,
    ADD COLUMN last_location_action_id BIGINT NULL,
    ADD KEY idx_copy_registered_location (registered_location_id),
    ADD CONSTRAINT fk_copy_registered_location FOREIGN KEY (registered_location_id) REFERENCES book_location(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_copy_location_source FOREIGN KEY (last_location_action_id) REFERENCES book_copy_action(id) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE book_copy_action_request DROP CHECK ck_copy_action_schema;
ALTER TABLE book_copy_action_request ADD CONSTRAINT ck_copy_action_schema CHECK (schema_version IN (1,2));

CREATE TABLE book_copy_action_location (
    event_id BIGINT NOT NULL PRIMARY KEY,
    before_location_id BIGINT NULL,
    after_location_id BIGINT NULL,
    before_source_action_id BIGINT NULL,
    source_behavior VARCHAR(16) NOT NULL,
    snapshot_version INT NOT NULL DEFAULT 1,
    before_json LONGTEXT NOT NULL,
    after_json LONGTEXT NOT NULL,
    KEY idx_copy_location_before (before_location_id,event_id),
    KEY idx_copy_location_after (after_location_id,event_id),
    CONSTRAINT ck_copy_location_behavior CHECK (source_behavior IN ('KEEP','THIS_EVENT')),
    CONSTRAINT ck_copy_location_snapshot CHECK (snapshot_version=1),
    CONSTRAINT ck_copy_location_before_json CHECK (JSON_VALID(before_json)),
    CONSTRAINT ck_copy_location_after_json CHECK (JSON_VALID(after_json)),
    CONSTRAINT fk_copy_location_event FOREIGN KEY (event_id) REFERENCES book_copy_action(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_copy_location_before FOREIGN KEY (before_location_id) REFERENCES book_location(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_copy_location_after FOREIGN KEY (after_location_id) REFERENCES book_location(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_copy_location_before_source FOREIGN KEY (before_source_action_id) REFERENCES book_copy_action(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE location_structure_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    entity_type VARCHAR(16) NOT NULL,
    entity_id BIGINT NOT NULL,
    operation VARCHAR(24) NOT NULL,
    operator_id BIGINT NOT NULL,
    occurred_at DATETIME NOT NULL,
    reason VARCHAR(500) NOT NULL,
    before_version BIGINT NULL,
    after_version BIGINT NOT NULL,
    before_json LONGTEXT NULL,
    after_json LONGTEXT NOT NULL,
    snapshot_version INT NOT NULL DEFAULT 1,
    KEY idx_location_structure_history (entity_type,entity_id,id),
    CONSTRAINT ck_structure_entity CHECK (entity_type IN ('BOOKSHELF','STOREY','LOCATION')),
    CONSTRAINT ck_structure_operation CHECK (operation IN ('CREATE','UPDATE','DELETE','ASSIGN','CLEAR')),
    CONSTRAINT ck_structure_snapshot CHECK (snapshot_version=1),
    CONSTRAINT ck_structure_versions CHECK ((before_version IS NULL AND after_version=0 AND operation='CREATE') OR (before_version>=0 AND after_version=before_version+1 AND operation<>'CREATE')),
    CONSTRAINT ck_structure_before_json CHECK (before_json IS NULL OR JSON_VALID(before_json)),
    CONSTRAINT ck_structure_after_json CHECK (JSON_VALID(after_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Application append/read APIs only. No trigger or database immutability claim.
