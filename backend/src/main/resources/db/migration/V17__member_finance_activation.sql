-- B40-B: manager allocated V17; create an empty activation anchor only.
-- The reviewed deployment activation step inserts its immutable singleton.
CREATE TABLE member_finance_activation (
    policy_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    activation_date DATE NOT NULL,
    cutoff_at DATETIME(6) NOT NULL COMMENT 'UTC instant of activation_date 00:00 in Asia/Shanghai',
    financial_time_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC database time of explicit deployment activation',
    CONSTRAINT ck_fin_activation_key CHECK (policy_key = 'B40_RECEIPT_V1'),
    CONSTRAINT ck_fin_activation_zone CHECK (financial_time_zone = 'Asia/Shanghai')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
