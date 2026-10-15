# 智慧图书馆管理系统

基于 Vue 3、TypeScript、Element Plus、Java 17、Spring Boot 3、MyBatis Plus 和 MySQL 8 的绘本馆管理系统，提供会员账号、图书、借还续借、借阅码、采购、库存、历史资料和权限管理。

## 项目入口

| 需要了解 | 入口 |
| --- | --- |
| 项目目标、范围与当前阶段 | [PROJECT](PROJECT.md) |
| 当前任务及下一步 | [PLAN](PLAN.md) |
| 开发交付与文档维护方式 | [WORKFLOW](WORKFLOW.md) |
| 代理工作规范 | [AGENTS](AGENTS.md) |
| 全部文档分类 | [文档中心](docs/README.md) |

## 开发和使用

- [本地启动](docs/development/README.md)
- [测试与发布检查](docs/development/testing.md)
- [当前业务规范](docs/specs/README.md)
- [部署与恢复](docs/operations/deployment.md)
- [发布说明](docs/releases/release-notes.md)

## 代码目录

- `frontend/src/`：页面、组件、API 类型和路由。
- `backend/src/main/`：后端业务、权限和数据库迁移。
- `backend/src/test/`：后端测试。
- `scripts/`：文档检查、打包、审计和运维验收。
- `docs/`：领域文档、活动计划和历史档案。

运行数据、原始业务快照和凭据使用受保护位置保存，不进入代码或分发包。
