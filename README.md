# 智慧图书馆管理系统

Vue 3、TypeScript、Element Plus 前端与 Java 17、Spring Boot 3、MyBatis Plus、MySQL 8 后端。提供读者注册、图书分类与目录、借还续借、借阅码、采购、库存、文件、用户和权限日志。

本次修复尚未部署到服务器。实现与验收状态见 [修复记录](docs/repair-results-2026-10-13.md)，最新放行结论见 [部署复审](docs/release-readiness-2026-10-13.md)。部署前先阅读 [部署与恢复](docs/deployment.md) 和 [数据库迁移](docs/database-migrations.md)。

## 本地开发

使用 Java 17、Maven 3.9、Node 24.19.0、pnpm 11.25.0 和独立 MySQL 8 数据库。前端版本在 `.node-version`、`packageManager`、锁文件和 CI 中固定。

创建一个空的开发数据库，设置 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DB`、`MYSQL_USER`、`MYSQL_PASSWORD`，开发账号需能执行迁移。不要手工导入旧版 SQL 演示数据；应用会执行完整 Flyway 迁移。

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

开发配置首次创建 `admin / local-admin-dev123`，仅限本机开发。已有管理员保留原密码。生产必须设置独立 JWT、数据库和管理员凭据，不会使用这些开发默认值。

在另一个终端启动前端：

```bash
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```

前端在 `http://localhost:5173`，API 在 `http://localhost:8080/api`。开发代理同时支持上传文件。Swagger 仅开发环境默认启用；生产默认关闭。项目不依赖 Redis。

## 测试与构建

```bash
mvn -B -ntp -f backend/pom.xml verify
pnpm --dir frontend test
pnpm --dir frontend build
pnpm --dir frontend audit --audit-level=high
```

后端默认测试使用 H2；真实 MySQL 用例只有在设置 `MYSQL_TEST_URL` 时执行，并要求专用、可丢弃的 `library_repair_tests` 数据库。测试账号使用 `MYSQL_TEST_USER`、`MYSQL_TEST_PASSWORD`，需要创建/删除测试库和临时账号。绝不能指向业务数据库。MySQL 测试会验证升级、并发、DML 权限、审计回滚和生产 HTTP 文件限制。

前端 `build` 强制执行 `vue-tsc --noEmit`。依赖构建脚本只允许明确列出的 esbuild、Parcel watcher 和 vue-demi。后端审计使用 `scripts/audit_backend.py`，参数格式见 CI 的 Verify 工作流。

```bash
python3 scripts/package_release.py vX.Y.Z
# 仅在全新隔离 Docker 主机运行：
LIBRARY_ACCEPTANCE=1 bash scripts/container-acceptance.sh dist/library-management-system-vX.Y.Z.zip
```

产物在 `dist/`。Release 工作流使用同一锁文件和验证流程，验证失败不会发布，重复版本不会覆盖既有 Release。

## 核心业务约定

- 登录验证码与账号密码一起提交后端，一次性消费。缺失、错误、过期或重放都会失败。
- 找回密码请联系管理员；匿名短信或邮件重置尚未实现，不提供伪验证码入口。
- JWT 每次请求核对数据库中的账号状态、角色与版本；改密码、禁用、删除或改角色后旧令牌失效。
- 目录生成码，核销才借出；未核销的码不扣库存、不预留库存。借阅期限默认 30 天，最多同时借阅 10 本，逾期未还计入上限并阻止重复借阅及删除。
- 同一记录归还可重复提交，库存只恢复一次。续借最多两次。
- 采购相同 ISBN 合并库存；书名、作者或出版社冲突需先核对。空 ISBN 使用采购明细生成的内部编号。
- 头像和封面最多 5MiB，资源最多 50MiB。上传、数据库和日志使用独立持久化目录。

## 项目结构

`frontend/src` 为页面、API 和类型；`backend/src/main` 为后端及生产迁移；`backend/src/test` 为回归用例；`compose.yaml` 是统一部署入口；`scripts` 管理审计、打包、备份和验收。SSH 私钥和 `.env` 不属于发布包。
