CREATE TABLE IF NOT EXISTS `sys_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(200) NOT NULL COMMENT '密码(BCrypt加密)',
    `real_name` VARCHAR(50) DEFAULT NULL COMMENT '真实姓名',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `avatar` VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    `role` VARCHAR(20) NOT NULL DEFAULT 'READER' COMMENT '角色: ADMIN/LIBRARIAN/READER',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用 0-禁用',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除 1-已删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_role` (`role`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `book_category` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(100) NOT NULL COMMENT '分类名称',
    `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '父分类ID, 0表示顶级分类',
    `level` TINYINT NOT NULL DEFAULT 1 COMMENT '层级',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用 0-禁用',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`),
    KEY `idx_level` (`level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图书分类表';

CREATE TABLE IF NOT EXISTS `book` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `isbn` VARCHAR(20) NOT NULL COMMENT 'ISBN编号',
    `title` VARCHAR(200) NOT NULL COMMENT '书名',
    `author` VARCHAR(200) DEFAULT NULL COMMENT '作者',
    `publisher` VARCHAR(200) DEFAULT NULL COMMENT '出版社',
    `publish_date` DATE DEFAULT NULL COMMENT '出版日期',
    `category_id` BIGINT DEFAULT NULL COMMENT '分类ID',
    `price` DECIMAL(10,2) DEFAULT NULL COMMENT '价格',
    `pages` INT DEFAULT NULL COMMENT '页数',
    `cover_image` VARCHAR(500) DEFAULT NULL COMMENT '封面图片URL',
    `description` TEXT DEFAULT NULL COMMENT '简介',
    `location` VARCHAR(100) DEFAULT NULL COMMENT '馆藏位置',
    `stock_total` INT NOT NULL DEFAULT 1 COMMENT '总库存',
    `stock_available` INT NOT NULL DEFAULT 1 COMMENT '可借库存',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-在馆 0-下架',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_isbn` (`isbn`),
    KEY `idx_title` (`title`),
    KEY `idx_author` (`author`),
    KEY `idx_category_id` (`category_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图书表';

CREATE TABLE IF NOT EXISTS `borrow_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '借阅用户ID',
    `book_id` BIGINT NOT NULL COMMENT '图书ID',
    `borrow_date` DATE NOT NULL COMMENT '借阅日期',
    `due_date` DATE NOT NULL COMMENT '应还日期',
    `return_date` DATE DEFAULT NULL COMMENT '实际归还日期',
    `renew_count` TINYINT NOT NULL DEFAULT 0 COMMENT '续借次数',
    `status` VARCHAR(20) NOT NULL DEFAULT 'BORROWING' COMMENT '状态: BORROWING-借阅中 RETURNED-已归还 OVERDUE-已逾期',
    `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_book_id` (`book_id`),
    KEY `idx_status` (`status`),
    KEY `idx_due_date` (`due_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='借阅记录表';

CREATE TABLE IF NOT EXISTS `fine_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `borrow_id` BIGINT NOT NULL COMMENT '借阅记录ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `amount` DECIMAL(10,2) NOT NULL COMMENT '罚款金额',
    `reason` VARCHAR(500) DEFAULT NULL COMMENT '罚款原因',
    `status` VARCHAR(20) NOT NULL DEFAULT 'UNPAID' COMMENT '状态: UNPAID-未缴 PAID-已缴',
    `pay_time` DATETIME DEFAULT NULL COMMENT '缴纳时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_borrow_id` (`borrow_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='罚款记录表';

CREATE TABLE IF NOT EXISTS `sys_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username` VARCHAR(50) DEFAULT NULL COMMENT '操作用户',
    `operation` VARCHAR(200) DEFAULT NULL COMMENT '操作描述',
    `method` VARCHAR(200) DEFAULT NULL COMMENT '请求方法',
    `params` TEXT DEFAULT NULL COMMENT '请求参数',
    `ip` VARCHAR(50) DEFAULT NULL COMMENT 'IP地址',
    `execution_time` BIGINT DEFAULT NULL COMMENT '执行时长(ms)',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_username` (`username`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统日志表';
