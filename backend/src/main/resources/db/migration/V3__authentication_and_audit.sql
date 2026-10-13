ALTER TABLE sys_user ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE permission_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operator_id BIGINT, operator_name VARCHAR(50),
    target_user_id BIGINT, target_user_name VARCHAR(50),
    action VARCHAR(50), old_role VARCHAR(20), new_role VARCHAR(20),
    detail VARCHAR(500), create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE book_normalize_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, book_id BIGINT NOT NULL,
    old_title VARCHAR(200), new_title VARCHAR(200),
    old_author VARCHAR(200), new_author VARCHAR(200),
    old_isbn VARCHAR(20), new_isbn VARCHAR(20),
    old_publisher VARCHAR(200), new_publisher VARCHAR(200),
    operator_name VARCHAR(50), status VARCHAR(20),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inventory_mutex (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO inventory_mutex (id) VALUES (1);
CREATE INDEX idx_active_user_book ON borrow_record(user_id, book_id, status, deleted);
ALTER TABLE book ADD CONSTRAINT chk_book_stock CHECK (stock_total >= 0 AND stock_available >= 0 AND stock_available <= stock_total);
