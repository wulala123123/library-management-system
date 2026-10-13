# 图书馆管理系统检查与 sub2api 部署方案

> 本文件是修复前的审计快照，旧部署示例已被新的统一配置取代。当前状态见 [修复验收记录](repair-results-2026-10-13.md)，部署请使用 [新版部署文档](deployment.md)。

检查日期：2026 年 10 月 13 日，北京时间。代码基线：`main` 分支，提交 `a44bd51`。

项目已有完整的 Vue 前端和 Spring Boot 后端，适合在现有结构上继续开发。目前存在账号接管、生产数据库缺表和发布流程损坏等上线阻断问题，应先修复，再在 sub2api 服务器进行隔离试运行。当前版本不适合直接开放到公网。

分阶段改动范围、实现方案和验收标准见 [修复计划](repair-plan.md)。

## 验证结果

| 检查项目 | 结果 | 解释 |
| --- | --- | --- |
| 后端编译和打包 | 通过 | Java 17，Maven 3.9.9，生成 `backend/target/library-backend.jar` |
| 后端现有测试 | 28 项通过，0 失败 | 使用测试目录中的 H2 配置，未覆盖生产 MySQL 初始化流程 |
| 前端生产构建 | 通过 | 按已有 `pnpm-lock.yaml` 安装依赖后调用 Vite 构建入口，生成 `frontend/dist` |
| Vue 完整类型检查 | 84 处错误 | 校验工具为 vue-tsc 2.0.29 和项目所用 TypeScript 5.3.3；正常 build 脚本只运行 Vite，跳过类型检查 |
| 前端依赖审计 | 33 条告警 | 15 条 high，18 条 moderate；需根据浏览器、开发服务器、Node 适配器等实际使用路径逐项评估 |
| 服务器 SSH | 连接成功 | 使用现有 `~/.ssh/config` 的 `sub2api` 别名，私钥由 SSH 用于认证，未展示或复制 |
| 部署模板配置检查 | 通过 | 在服务器现有 Compose 中使用占位环境变量验证语法和变量替换，没有创建服务 |
| 生产端到端运行 | 尚未验证 | 当前未在服务器安装或启动项目，也未验证真实 MySQL、Nginx、域名和 HTTPS 下的全部功能 |

本地验证日志位于 `logs/backend-verify.log`、`logs/frontend-typecheck.log`、`logs/frontend-audit.json`、`logs/runtime-audit.log`。本地临时 H2 数据库中的 8 个专项验证均确认对应问题可以复现。这些日志和临时验证程序位于 Git 忽略目录。业务代码和服务器现有服务均未修改。

本机 pnpm 11 安装时提示三个依赖的构建脚本未授权；依赖已安装，Vite 构建入口可运行。这是本次工具环境的限制，不能将该提示当作业务代码构建失败。后续需固定包管理器版本并明确必要依赖的脚本策略。

## 上线前必须修复的问题

### 未登录用户能够重置管理员密码

`UserServiceImpl.java:256` 的 `sendResetCode` 将验证码放进 API 响应，`AuthController.java:40` 的发送和重置接口均允许匿名访问。前端只是把它标成“开发环境直接显示”，后端没有生产环境隔离。

本地实际 HTTP 验证已确认：提交种子管理员手机号即可取得验证码，再调用重置接口修改管理员密码，新密码能够登录。无需短信接收能力。

修复：在真实短信或邮件验证接入前，关闭这两个公共重置接口；接入后禁止向客户端返回验证码，增加验证码尝试次数限制、有效期和一次性消费。前端同时关闭未实现的找回密码入口。仅隐藏提示不能修复 API 漏洞。

### 启动时会恢复已修改的管理员密码

`DataInitializer.java:27` 每次执行都会检查管理员密码是否为 `admin123`，若不同就覆盖。更改管理员密码后，服务重启会重新恢复默认密码。

本地验证通过直接重新执行同一个启动初始化器，确认已修改的密码恢复为默认密码。

修复：删除每次启动强制覆盖密码的行为。管理员初始化只能在不存在管理员时执行，并使用部署时提供的初始凭据；不得覆盖已有账号密码。生产初始化还应去掉示例用户或逐一禁用、改密。

### 禁用账号和降权后旧令牌仍具有管理员权限

`JwtAuthenticationFilter.java:34` 只校验 JWT 签名和有效期，再直接使用令牌中的角色。它不检查数据库中的账号状态、是否已删除、当前角色，也没有密码变更后的令牌版本校验。默认有效期为 24 小时。

本地实际 HTTP 验证已确认：管理员修改密码、账号被禁用并降为读者后，原来的令牌仍能读取 `/users`。

修复：认证时检查当前用户状态和角色，并增加令牌版本或撤销机制。禁用、删除、修改密码、变更角色应使相关旧令牌失效。

### 图形验证码只在前端校验

`LoginRequest.java` 只有用户名和密码字段，`AuthController.java:29` 不验证图形验证码。前端先调用验证码接口，再调用登录接口，二者没有服务端关联。

本地实际 HTTP 请求不提供验证码也能登录。现有限流仍存在，但验证码不能提供预期保护。

修复：将验证码标识和验证码字段传到登录接口，在服务端同一次登录流程中验证并消费。保留登录限流，并明确可信代理来源。

### 生产初始化缺少业务表

`backend/docker-compose.yml:16` 只挂载 `init.sql`，其中只有 6 张基础表。出版社、书架、采购、通知等 13 张扩展表位于 `migration_v2.sql`，不会由现有 Compose 执行。

即使手工执行两个 SQL 文件，生产脚本仍缺少实体使用的 `permission_log` 和 `book_normalize_log`。测试 H2 脚本包含这两张表，因此当前 28 项测试不能发现这个生产问题。用户创建、修改等接口还会写权限日志，可能出现业务已完成但接口返回错误的情况。

修复：补齐 MySQL 迁移脚本，使用版本迁移管理工具，并在空 MySQL 数据库上验证所有实体表和核心接口。业务操作和对应审计日志需要合理的事务边界。两个现有脚本还固定使用 `library_db`，不能直接通过修改 `MYSQL_DB` 改库名。

MySQL 镜像的初始化目录只在首次创建数据库时执行。已有数据卷不会因添加 SQL 文件或重启容器自动升级，后续更新必须执行版本迁移。参见 [MySQL 官方镜像说明](https://hub.docker.com/_/mysql/)。

### 发布包不能按现有说明正常运行

`.github/workflows/release.yml:49` 把 JAR 放在压缩包顶层，而 `backend/Dockerfile:7` 要求 `target/library-backend.jar`。前端产物打包成 `frontend-dist`，Compose 却挂载 `../frontend/dist`。发布包还没有复制 `sql` 目录。

此外，ZIP 外面套了一层版本目录，`deploy.sh` 却假设解压后配置文件就在安装目录根部。其解压命令还在绝对 ZIP 路径前重复添加 `/tmp/library-deploy/`，得到错误路径。

修复：统一发布目录与 Docker COPY、挂载路径，包含所有数据库迁移，并在 CI 中实际验证发布包解压和容器启动。Release 工作流目前跳过后端测试、使用 `npm install` 而非仓库的 pnpm 锁文件，还会删除已有 Release 和标签，需要一并整理。

### 现有部署脚本不适合持续更新

`deploy.sh:58` 清空安装目录内的普通文件和目录；若上传文件存放其中，会随更新被删除。隐藏的 `.env` 通常不被这个通配符删除，MySQL 命名卷也不会因普通 `compose down` 自动消失，但这并不保证上传文件和运行目录安全。

脚本固定检查 80、8080，无法验证改端口后的项目。在 sub2api 上，80 返回的可能是已有反向代理、8080 返回的可能是现有 sub2api 服务。固定等待 10 秒也不能证明项目已就绪。

修复：按版本保存发布目录，独立保存数据库、上传文件和日志，使用项目自己的健康检查；更新前备份，失败时保留上一版镜像。不要直接执行现有 `deploy.sh`。

## 核心业务问题

### 并发借还可能产生错误库存

`BorrowRecordServiceImpl.java:57` 读取图书库存，再在 `:99` 将旧值减一写回；归还在 `:122` 用相同方式加一。没有行锁、条件更新或版本校验。事务可以保证一次操作提交或回滚，但不能阻止两次操作读取同一个旧值。

按代码推演，最后一册同时被两个人借走时，两个请求可能都成功，库存最终仍是 0；同时归还两本书时可能只加回一册。同一借阅记录重复并发归还、借阅码重复使用也需要保证只生效一次。

修复：使用数据库条件扣减、锁定或乐观锁，并检查影响行数；借阅记录和借阅码采用条件状态转换。补充真实 MySQL 下的并发测试。本次未执行并发压力测试。

### 逾期状态绕过未归还校验

`BorrowRecordServiceImpl.java:70`、`:80` 只统计 `BORROWING`，`UserServiceImpl.java:180` 和 `BookServiceImpl.java:122` 的删除保护也只判断这个状态。定时任务会将逾期记录改为 `OVERDUE`，但这些书仍未归还。

本地隔离验证确认：已有逾期借阅的同一本书可以再次借阅；只有逾期未还记录的用户可以被删除。借阅上限和删除图书保护同样需要覆盖逾期状态。

修复：统一“未归还”状态规则，包含 `BORROWING` 和 `OVERDUE`，并明确逾期后的续借规则。

### 图书目录和借阅码流程相互冲突

`frontend/src/views/book-catalog/index.vue:187` 和详情弹窗 `BookDetailDialog.vue:117` 先调用自助借阅，创建借阅记录、扣减库存，再生成借阅码。但 `BorrowCodeServiceImpl.java:104` 使用借阅码时又尝试创建借阅记录。

对于正常未还的借阅，兑换随后生成的码会被重复借阅规则拒绝。如果刚借走最后一册，生成借阅码也可能因无库存失败，用户看到“借阅失败”，实际上已经产生借阅记录。

修复：确定业务流程。若借阅码用于柜台领取，目录点击时只生成码，核销时才借阅；若目录点击即完成借阅，借阅码应改为关联已存在记录的凭证，核销不得再次扣库存。

### 已有 ISBN 的采购入库会失败

`PurchaseServiceImpl.java:185`、`:194` 总是插入新图书。生产 `book.isbn` 有唯一索引，因此补购已有 ISBN 的书会触发重复键错误，而不是增加已有图书库存。

修复：按照既有 ISBN 合并库存，保留采购入库流水，并对并发入库和重复请求设置保护。

## 前端和维护问题

### 上传图片在 Nginx 下可能返回 404

`backend/nginx.conf:28` 的普通 `/uploads/` 前缀会被 `:34` 的图片扩展名正则覆盖；例如 `/uploads/avatars/...jpg` 会去前端静态目录找文件，无法转发到后端上传目录。`/api/uploads/...jpg` 也存在同样问题。

修复：将 API 和上传路径改成 `location ^~ /api/`、`location ^~ /uploads/`，使它们优先于普通静态资源正则。该判断基于源码和 [Nginx 官方 location 匹配规则](https://nginx.org/en/docs/http/ngx_http_core_module.html#location)，尚未在本项目运行中的 Nginx 上验证。

### 类型错误被构建命令掩盖

84 处 Vue 类型检查错误主要来自请求封装与 DTO 类型不匹配、缺少响应类型、页面使用不存在或错误导入的字段。例如 `request.ts` 限制请求体为 `Record<string, unknown>`，但调用方传入不带索引签名的接口或 `null`；采购页面从分类 API 错误导入 `BookCategory`。

修复：统一 API 响应类型和请求参数定义，添加 `typecheck` 脚本，将类型检查纳入 CI 和发布前检查。前端目前没有自动化测试，优先覆盖登录、借还和借阅码流程。

### 文件和依赖管理需要整理

- 后端没有挂载上传目录，容器替换时上传文件可能丢失。`file.upload-dir` 与 `file.upload-path` 使用不同配置名，需要统一或同时明确设置。
- 全局 multipart 单文件上限为 10MB，图书资源接口却宣称允许 50MB，需要统一前端提示、代理限制和后端限制。
- 当前锁文件中的 Vite、Axios 等依赖产生安全告警；部分告警只涉及开发服务器或 Node 适配器，不等于浏览器生产页面能够利用全部告警。升级后重新构建、检查类型并跑核心功能。
- 本次没有执行后端依赖漏洞扫描，因此不能将后端编译通过当作依赖安全结论。Spring Boot 等依赖升级需单独验证兼容性。
- 前端主 JS 约 1.09MB，gzip 约 361KB。可按页面和组件拆包，但应排在安全和功能修复之后。
- 初始化 SQL 含示例用户和联系方式，`backend/uploads` 中有已跟踪的运行图片。生产种子数据与演示数据应分开，运行上传文件应独立保存。

## sub2api 服务器的部署条件

| 项目 | SSH 检查结果 |
| --- | --- |
| 连接 | `ssh sub2api`，当前指向 `136.110.120.0` |
| 系统 | Ubuntu 25.10 |
| CPU | 2 核 |
| 内存 | 总计约 3.8GiB，可用约 2.7GiB |
| Swap | 2GiB，已使用约 431MiB |
| 磁盘 | 根盘约 77GiB，剩余约 25GiB |
| 容器环境 | Docker 29.6.1，Compose 5.3.1 |
| 现有代理 | Nginx Proxy Manager，占用公网 80 和 443，管理端口 81 仅绑定本机 |
| 已占用业务端口 | 本机 8080 为 sub2api，8090 为 octopus-plus，7863 为 workbuddy2api |
| 可复用网络 | Nginx Proxy Manager 已接入外部 Docker 网络 `proxy` |
| 建议试运行端口 | 本机 `127.0.0.1:18080`，检查时未占用 |

从资源快照推断，服务器可用于少量用户的图书馆项目试运行。建议在本机或 CI 构建，在服务器只运行产物，并设置内存和日志限制。实际承载能力仍需根据上线后的内存、CPU、数据库和磁盘增长评估。

Ubuntu 25.10 官方支持截至 2026 年 7 月，检查时已经超过支持期。长期运行应安排备份、升级到受支持的 LTS 系统并验证现有容器。系统升级涉及已有业务，应单独规划维护窗口。参见 [Ubuntu 25.10 官方发布说明](https://documentation.ubuntu.com/release-notes/25.10/)。

## 建议部署结构

```text
用户浏览器
    ↓ HTTPS 443
现有 Nginx Proxy Manager
    ↓ proxy 网络中的 library-web:80
图书馆前端 Nginx
    ↓ 项目自己的网络 /api
Spring Boot 后端
    ↓ 项目自己的网络
独立 MySQL 8.0
```

项目独立放在 `/opt/library-management-system`。不复用 sub2api 的 PostgreSQL 或 Redis。后端 8080、数据库 3306 都不发布到主机；前端临时发布到 `127.0.0.1:18080`，并通过 `proxy` 网络接入现有代理。

容器里的 Nginx Proxy Manager 不能用它自己的 `127.0.0.1:18080` 访问主机服务，应通过共享网络访问 `library-web:80`。连接外部 Compose 网络的规则参见 [Docker Compose 网络文档](https://docs.docker.com/compose/how-tos/networking/)。绑定主机 `127.0.0.1` 可用于 SSH 隧道试运行，参见 [Docker 端口发布文档](https://docs.docker.com/engine/network/port-publishing/)。

部署模板见同目录 `sub2api-compose.example.yml`。配置语法和变量替换已通过服务器现有 Compose 检查，尚未启动容器验证。它沿用现有 Dockerfile 的 JAR 路径，因此上传目录必须保留 `backend/target/library-backend.jar` 与 `frontend/dist`。模板会顺序读取 SQL 目录中的脚本，但仍需先补齐缺失表；它不能自动修复当前源码中的安全漏洞。

## 实施顺序

1. 关闭或修复匿名密码重置，删除启动覆盖密码，补齐 JWT 撤销和服务端验证码校验。
2. 补齐 MySQL 版本迁移，统一未归还状态、库存并发和借阅码业务流程。
3. 修复 Nginx 路由和上传持久化，统一生产环境密钥、数据库账号和上传大小。
4. 修复 84 处类型错误，统一锁文件、CI 和 Release 打包方式，验证发布产物。
5. 在空 MySQL 中完成首次初始化测试和核心功能测试，测试更新、重启、备份恢复。
6. 先用 SSH 隧道试运行，再配置用户选择的域名和 HTTPS。

### 修复后的配置和启动

以下步骤用于修复后的版本。当前检查没有执行这些部署命令。

在本机或 CI 完成后端测试、JAR 打包、前端类型检查和构建后，上传保留目录结构的发布产物。服务器目录示例：

```text
/opt/library-management-system/
    .env
    backend/
        Dockerfile
        nginx.conf
        target/library-backend.jar
        sql/init.sql
        sql/migration_v2.sql
        sql/后续补齐并验证的迁移脚本
    frontend/dist/
    docs/sub2api-compose.example.yml
```

在项目根目录创建仅管理员可读的 `.env`，使用独立随机值，禁止提交到 Git：

```dotenv
MYSQL_ROOT_PASSWORD=替换为独立的强随机密码
MYSQL_PASSWORD=替换为图书馆数据库账号的强随机密码
JWT_SECRET=替换为至少64个ASCII字符的强随机密钥
CORS_ORIGINS=https://你的图书馆域名
```

数据库名暂时固定为 `library_db`，与当前 SQL 保持一致。数据库普通账号在模板中固定为 `library_app`，后台不使用 root。

```bash
cd /opt/library-management-system
chmod 600 .env
docker compose --env-file .env -p library -f docs/sub2api-compose.example.yml config --quiet
docker compose --env-file .env -p library -f docs/sub2api-compose.example.yml up -d --build
docker compose --env-file .env -p library -f docs/sub2api-compose.example.yml ps
curl --fail http://127.0.0.1:18080/api/actuator/health
```

试运行时，在本机保持 SSH 隧道：

```powershell
ssh -N -L 18080:127.0.0.1:18080 sub2api
```

浏览器访问 `http://localhost:18080`。正式发布需将域名解析到服务器，在现有 Nginx Proxy Manager 中添加代理，目标为 `http://library-web:80`，申请 HTTPS 证书并启用 HTTPS。域名尚未指定，本次未变更 DNS 或代理配置。

上线前验证：管理员首次初始化、改密后重启、禁用及降权后旧令牌拒绝访问、读者注册登录、借阅归还续借、借阅码核销、已有 ISBN 采购入库、头像和封面上传、容器重建后文件保留，以及全部管理模块不因缺表报错。

### 持续更新与备份

每次发布记录 Git 提交和镜像版本，先备份数据库和上传文件，再执行经过验证的数据库迁移，最后替换应用容器并检查健康状态。模板仅负责初次初始化；已有 MySQL 数据卷的升级需单独运行迁移工具。

不得在日常更新中执行 `docker compose down -v`，也不要运行清空安装目录的旧脚本。按上一版镜像回退应用前，要确认数据库迁移对上一版兼容；涉及破坏性结构变更时，需要明确的数据恢复方案。

数据库和上传文件都要备份，至少保留一份服务器外的副本。定期实际恢复验证；只存在备份文件不代表能够恢复。

## 后续开发建议

继续保留当前前后端分离结构，将借还、库存和采购规则集中在服务层。优先建立版本化 MySQL 迁移、统一的 API 类型和核心业务测试，再扩展新模块。

建议按“账号安全”“数据库与核心业务”“部署与发布”“功能扩展”拆成独立变更，确保每一步可验证、可回退。每次发布同时运行后端测试、前端类型检查和构建；部署相关变更增加空 MySQL 初始化与发布包验证。
