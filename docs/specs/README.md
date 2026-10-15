# 领域规范

本目录维护已生效业务的含义、约束和已知限制。待实施方案在活动计划中定义，验证并启用后再更新相应规范。

| 主题 | 唯一维护位置 |
| --- | --- |
| 登录身份、角色与会员权益边界 | [账号和会员](membership.md) |
| 书目、实体册、借阅、归还与续借 | [图书和借还](catalog-circulation.md) |
| 金额含义、库存及历史统计口径 | [财务和统计](finance-reporting.md) |

字段物理结构以 [Flyway](../../backend/src/main/resources/db/migration)为准。规则变更按 [WORKFLOW](../../WORKFLOW.md)同步实现与验证；新增独立领域在此索引登记。
