-- B50 counter operations only. No historical rows or definitions are changed.
CREATE TABLE circulation_request (
 operation_key VARBINARY(36) NOT NULL PRIMARY KEY,
 actor_id BIGINT NOT NULL,
 operation VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NULL,
 state VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 request_json LONGTEXT NULL,
 preview_json LONGTEXT NULL,
 guard_json LONGTEXT NULL,
 preview_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE circulation_order (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE circulation_order_item (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
