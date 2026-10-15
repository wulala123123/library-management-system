# 系统架构

系统由 Vue 前端、Spring Boot API 和 MySQL 数据库组成，使用 Docker Compose 运行。此处维护模块边界及跨模块约束；详细业务口径见[领域规范](../specs/README.md)。

## 模块与数据流

| 模块 | 入口 | 主要职责 |
| --- | --- | --- |
| 前端 | [frontend/src](../../frontend/src) | 页面、表单、API 类型、路由和交互 |
| HTTP 与权限 | [后端控制器](../../backend/src/main/java/com/library/controller) | 请求校验、角色权限和响应 |
| 业务服务 | [后端服务](../../backend/src/main/java/com/library/service) | 借还、账号、采购等业务事务 |
| 数据访问 | [Mapper](../../backend/src/main/java/com/library/mapper)、[SQL Mapper](../../backend/src/main/resources/mapper) | 查询、锁和持久化 |
| 模式升级 | [Flyway](../../backend/src/main/resources/db/migration) | 版本化结构升级 |
| 运维 | [scripts](../../scripts)、[deploy.sh](../../deploy.sh) | 打包、验收、备份和发布 |

请求通过反向代理进入 API，认证及方法权限检查后由服务层组织事务。实体册、借阅记录和汇总库存需要在同一业务动作中保持一致。

## 数据边界

- `sys_user` 管理登录身份；迁入会员权益保存在 `member_profile`，当前还未完整接入日常经营。
- `book` 管理书目及汇总库存，`book_copy` 管理唯一馆内条码和实体册状态。
- `borrow_record` 保存借还明细及其关联；原始资料由 `legacy_record` 和迁移批次记录追溯。
- 上传文件、MySQL 数据和配置独立持久化，代码版本更换不清空这些数据。

新增会员、费用和订单能力应沿用这些身份与来源关联。具体扩展方案属于相应阶段；实际启用后再将稳定边界更新到本页及所属主题规范。
