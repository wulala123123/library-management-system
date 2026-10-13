CREATE TABLE IF NOT EXISTS `publisher` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(200) NOT NULL COMMENT '出版社名称',
    `address` VARCHAR(500) DEFAULT NULL COMMENT '出版社地址',
    `contact_phone` VARCHAR(20) DEFAULT NULL COMMENT '联系电话',
    `contact_email` VARCHAR(100) DEFAULT NULL COMMENT '联系邮箱',
    `website` VARCHAR(200) DEFAULT NULL COMMENT '官网地址',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '简介',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用 0-禁用',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出版社表';

CREATE TABLE IF NOT EXISTS `sys_dept` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '父部门ID',
    `ancestors` VARCHAR(200) DEFAULT '' COMMENT '祖级列表',
    `name` VARCHAR(100) NOT NULL COMMENT '部门名称',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `leader` VARCHAR(50) DEFAULT NULL COMMENT '负责人',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '联系电话',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用 0-禁用',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部门表';

CREATE TABLE IF NOT EXISTS `bookshelf` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(100) NOT NULL COMMENT '书架名称',
    `code` VARCHAR(50) NOT NULL COMMENT '书架编号',
    `dept_id` BIGINT DEFAULT NULL COMMENT '所属部门ID',
    `location` VARCHAR(200) DEFAULT NULL COMMENT '物理位置描述',
    `capacity` INT NOT NULL DEFAULT 100 COMMENT '容量(册)',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用 0-禁用',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_dept_id` (`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='书架表';

CREATE TABLE IF NOT EXISTS `bookshelf_storey` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `bookshelf_id` BIGINT NOT NULL COMMENT '所属书架ID',
    `name` VARCHAR(100) NOT NULL COMMENT '层名称',
    `level_num` INT NOT NULL COMMENT '层号',
    `capacity` INT NOT NULL DEFAULT 20 COMMENT '容量(册)',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用 0-禁用',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_bookshelf_id` (`bookshelf_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='书架层表';

CREATE TABLE IF NOT EXISTS `book_location` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `storey_id` BIGINT NOT NULL COMMENT '所属书架层ID',
    `code` VARCHAR(50) NOT NULL COMMENT '库位编号',
    `book_id` BIGINT DEFAULT NULL COMMENT '存放图书ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'EMPTY' COMMENT '状态: EMPTY-空闲 OCCUPIED-占用',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_storey_id` (`storey_id`),
    KEY `idx_book_id` (`book_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库位表';

CREATE TABLE IF NOT EXISTS `purchase_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(200) NOT NULL COMMENT '模板名称',
    `content` JSON DEFAULT NULL COMMENT '模板内容(JSON格式流程定义)',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用 0-禁用',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购审批模板表';

CREATE TABLE IF NOT EXISTS `purchase_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `order_no` VARCHAR(50) NOT NULL COMMENT '采购单号',
    `dept_id` BIGINT DEFAULT NULL COMMENT '采购部门ID',
    `template_id` BIGINT DEFAULT NULL COMMENT '审批模板ID',
    `applicant_id` BIGINT NOT NULL COMMENT '申请人ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT-草稿 PENDING-审批中 APPROVED-已通过 REJECTED-已驳回 COMPLETED-已完成',
    `current_node_id` VARCHAR(50) DEFAULT NULL COMMENT '当前审批节点ID',
    `deadline` DATE DEFAULT NULL COMMENT '截止日期',
    `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_dept_id` (`dept_id`),
    KEY `idx_applicant_id` (`applicant_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购单表';

CREATE TABLE IF NOT EXISTS `purchase_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `order_id` BIGINT NOT NULL COMMENT '采购单ID',
    `book_title` VARCHAR(200) NOT NULL COMMENT '图书名称',
    `author` VARCHAR(200) DEFAULT NULL COMMENT '作者',
    `isbn` VARCHAR(20) DEFAULT NULL COMMENT 'ISBN',
    `publisher` VARCHAR(200) DEFAULT NULL COMMENT '出版社',
    `category_id` BIGINT DEFAULT NULL COMMENT '图书分类ID',
    `quantity` INT NOT NULL DEFAULT 1 COMMENT '采购数量',
    `instock_quantity` INT NOT NULL DEFAULT 0 COMMENT '已入库数量',
    `price` DECIMAL(10,2) DEFAULT NULL COMMENT '单价',
    `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购项目表';

CREATE TABLE IF NOT EXISTS `approval_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `order_id` BIGINT NOT NULL COMMENT '采购单ID',
    `node_id` VARCHAR(50) NOT NULL COMMENT '审批节点ID',
    `node_name` VARCHAR(100) DEFAULT NULL COMMENT '节点名称',
    `approver_id` BIGINT DEFAULT NULL COMMENT '审批人ID',
    `approver_name` VARCHAR(50) DEFAULT NULL COMMENT '审批人姓名',
    `action` VARCHAR(20) DEFAULT NULL COMMENT '操作: APPROVE-通过 REJECT-驳回',
    `comment` VARCHAR(500) DEFAULT NULL COMMENT '审批意见',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批记录表';

CREATE TABLE IF NOT EXISTS `book_resource` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `book_id` BIGINT NOT NULL COMMENT '图书ID',
    `file_name` VARCHAR(200) NOT NULL COMMENT '文件名',
    `file_path` VARCHAR(500) NOT NULL COMMENT '文件路径',
    `file_type` VARCHAR(50) DEFAULT NULL COMMENT '文件类型(pdf/jpg/png/mp4等)',
    `file_size` BIGINT DEFAULT NULL COMMENT '文件大小(字节)',
    `resource_type` VARCHAR(20) NOT NULL DEFAULT 'IMAGE' COMMENT '资源类型: IMAGE-图片 DOCUMENT-文档 VIDEO-视频',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_book_id` (`book_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图书资源/附件表';

CREATE TABLE IF NOT EXISTS `notice` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `title` VARCHAR(200) NOT NULL COMMENT '公告标题',
    `content` TEXT NOT NULL COMMENT '公告内容',
    `type` VARCHAR(20) NOT NULL DEFAULT 'NOTICE' COMMENT '类型: NOTICE-通知 ANNOUNCEMENT-公告',
    `status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT-草稿 PUBLISHED-已发布',
    `publisher_id` BIGINT DEFAULT NULL COMMENT '发布人ID',
    `publish_time` DATETIME DEFAULT NULL COMMENT '发布时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`),
    KEY `idx_publisher_id` (`publisher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知公告表';

CREATE TABLE IF NOT EXISTS `borrow_code` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `code` VARCHAR(20) NOT NULL COMMENT '借阅码',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `book_id` BIGINT NOT NULL COMMENT '图书ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'VALID' COMMENT '状态: VALID-有效 USED-已使用 EXPIRED-已过期',
    `expire_time` DATETIME NOT NULL COMMENT '过期时间',
    `used_time` DATETIME DEFAULT NULL COMMENT '使用时间',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_book_id` (`book_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='借阅码表';

CREATE TABLE IF NOT EXISTS `stock_flow` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `book_id` BIGINT NOT NULL COMMENT '图书ID',
    `location_id` BIGINT DEFAULT NULL COMMENT '库位ID',
    `flow_type` VARCHAR(20) NOT NULL COMMENT '流水类型: IN-入库 OUT-出库 BORROW-借出 RETURN-归还',
    `quantity` INT NOT NULL DEFAULT 1 COMMENT '数量',
    `related_id` BIGINT DEFAULT NULL COMMENT '关联ID(借阅记录ID/采购单ID等)',
    `operator_id` BIGINT DEFAULT NULL COMMENT '操作人ID',
    `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_book_id` (`book_id`),
    KEY `idx_flow_type` (`flow_type`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存流水表';
