# 开发与验证

## 工具链

Java 17、Maven 3.9、Node 24 和 pnpm。Node 与 pnpm 的精确要求以[前端包配置](../../frontend/package.json)、[Node 版本文件](../../frontend/.node-version)和锁文件为准。

## 本地启动

创建独立空开发数据库，配置 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DB`、`MYSQL_USER` 和 `MYSQL_PASSWORD`。开发账号需要迁移权限，应用通过 Flyway 初始化，旧演示 SQL 不用于新生产库。

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

开发配置首次初始化的账号仅用于隔离开发环境，已有管理员密码保留。生产凭据从受保护配置读取。

另一个终端运行前端：

```bash
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```

前端开发端口为 5173，API 默认在 8080 的 `/api`。代理支持 API 与上传目录；Swagger 默认只在开发环境开放。项目不依赖 Redis。

## 验证入口

- [测试与发布检查](testing.md)：验证职责、范围、证据复用、结束条件及数据库、容器、CI 入口。
- [项目技能](skills.md)：项目经理技能、适用场景与维护入口。
- [系统架构](../architecture/README.md)：模块与数据边界。
- [工作流程](../../WORKFLOW.md)：任务完成、文档同步和交付要求。
