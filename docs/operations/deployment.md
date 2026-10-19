# 部署、更新与恢复

本页维护当前安装、更新、代理和恢复步骤。项目状态见 [PROJECT](../../PROJECT.md)，发布前通过[完整验证](../development/testing.md)，使用目标提交的 `verified-distribution` 及配套校验文件。历史服务器操作与验收见[档案](../archive/README.md)。

## 发布目录

发布 ZIP 只有一个版本目录，包含 `compose.yaml`、`compose.sub2api.yaml`、`backend/target/library-backend.jar`、两个 Dockerfile、`frontend/dist`、代理模板、运维脚本和文档。数据库迁移位于 JAR 内。包中不包含 `.env`、SSH 密钥、运行上传目录或开发依赖。ZIP 附带 SHA256 校验文件。

Linux 主机需要 Docker Compose 2.20 或更高版本、Bash、unzip、sha256sum 和 Python 3；验收脚本另需 OpenSSL。新环境先确认数据库镜像和 JRE 镜像可下载。

## 首次安装

先下载 ZIP 和对应 `.zip.sha256`。配置文件放在安装根目录，版本放在 `releases/`，`current` 指向通过健康检查的版本。不要将密钥写进仓库。

```bash
mkdir -p /opt/library-management-system
# 从 ZIP 中取出 .env.example，并保存为下面的文件，然后填写全部必填项。
editor /opt/library-management-system/.env
chmod 600 /opt/library-management-system/.env
bash deploy.sh /absolute/path/library-management-system-vX.Y.Z.zip install /opt/library-management-system
```

三个数据库密码分别生成；迁移密码支持 24–128 位字母、数字、下划线和连字符，建议 `openssl rand -hex 32`。JWT 建议 `openssl rand -hex 48`。初始管理员密码至少 12 位。首次运行若 `.env` 不存在，脚本只解压、创建空白示例并以非零状态退出；填好后重新执行同一条安装命令，内容相同的待安装版本可以继续启动。

安装根目录及 `.env` 可以使用私有权限；发布源码解压单独采用 `umask 022`，使前端静态资源目录能被 Nginx 工作进程遍历。该设置只作用于解压，不改变 `.env` 或备份的私有权限。不要将前端 `assets` 目录修改为 700。容器健康之外，还须验证页面实际引用的 JS/CSS 返回成功。

使用包含构建上下文缓存修复的部署脚本：同大小文件若沿用固定 ZIP 时间戳，BuildKit 可能复用旧内容，即使 `--no-cache` 也不足以刷新输入文件。脚本在解压后刷新普通文件的修改时间，构建启动后核对实际运行 `/app/app.jar` 与发布包 JAR 的 SHA256；不一致时拒绝切换并回退。时间刷新不改变包内文件内容或包校验和。手动 Compose 更新也应刷新输入文件并核对运行产物，不能只依据健康状态判断版本已更新。

新库不会创建演示读者或图书。应用只在没有管理员时使用配置创建初始管理员；已有管理员密码不会被修改。初始凭据须安全保存，应用重建不会重设密码。

## sub2api

默认前端只监听 `127.0.0.1:18080`，MySQL 和后端均不映射主机端口。正式代理使用外部 `proxy` 网络，Nginx Proxy Manager 的上游填写 `library-web:80`。

安装前在根目录创建 `use-sub2api` 文件，使脚本加载附加 Compose 配置；手动运行时显式使用两个 `-f` 文件：

```bash
touch /opt/library-management-system/use-sub2api
docker compose -p library --project-directory /opt/library-management-system/current \
  --env-file /opt/library-management-system/.env \
  -f /opt/library-management-system/current/compose.yaml \
  -f /opt/library-management-system/current/compose.sub2api.yaml config --quiet
```

配置 `CORS_ORIGINS` 为实际域名。检查 NPM 当前 Docker 地址，再设置 `EDGE_PROXY_CIDR`，上次检查是 `172.20.0.4/32`，部署时必须重新确认。只有这个来源可提供转发后的客户端地址。前端向后端覆盖 `X-Real-IP`，后端仅信任 `FRONTEND_INTERNAL_IP`。不要设置全网可信代理。

检查 `APP_NETWORK_SUBNET` 与现有 Docker/VPN 网络没有重叠；默认 `172.30.88.0/24`，若调整，须同步修改 `FRONTEND_INTERNAL_IP`。镜像中 Nginx 使用官方模板机制替换 `EDGE_PROXY_CIDR`，不要把未替换的模板直接当普通 Nginx 配置加载。

服务器 Ubuntu 25.10 的系统维护与本项目上线分开安排。当前 `library.webuihub.com` 使用仅 DNS 的 A 记录指向 `136.110.120.0`，Nginx Proxy Manager Proxy Host 3 转发到 `library-web:80`，绑定 Let's Encrypt 证书 6，启用 HTTP 到 HTTPS 跳转及 HTTP/2。证书有效期至 2027 年 1 月 13 日，由 NPM 的续期任务管理；ACME HTTP 验证继续使用现有 80 端口。公开域名无需 SSH 隧道，本机端口仍可用于运维。

服务器 `.env` 的 `CORS_ORIGINS` 已包含 `https://library.webuihub.com`，`EDGE_PROXY_CIDR=172.20.0.4/32`，安装根目录保留 `use-sub2api` 标记。后续更新需加载两个 Compose 配置以保留代理网络。NPM 上游额外配置 52MiB 请求体上限和 120 秒代理超时，与应用上传入口一致。私钥仅保存在服务器 NPM 的持久化证书目录；证书申请与续期不需要将 DNS API 密钥写入项目。

## 更新与回退

```bash
bash deploy.sh /absolute/path/library-management-system-vNEXT.zip update /opt/library-management-system
```

更新先暂停前后端、备份数据库、上传文件和环境配置，再构建新版本并等待实际容器健康。数据库使用固定 Compose 项目 `library` 的命名卷，版本目录的替换不会删除数据。保留 `.env` 和旧版本目录。备份失败会恢复旧服务并中止更新。

直接调用 Compose 恢复或检查服务时，必须显式设置 `APP_VERSION` 为当前版本目录名，或通过会导出该值的 `scripts/lib.sh` 入口执行。不要依赖缺省的 `local` 标签；服务健康不能证明运行版本正确。收尾的最后一次启动后再次核对实际镜像标签、运行 JAR 和前端资源字节。

新应用健康检查失败时脚本重新启动上一应用版本；已完成的数据库迁移保留。本次迁移增加表、索引和认证字段，旧表和数据不删除。以后开发不得随意删除或重命名上一版本需要的字段。回退只允许选择已验证兼容且安全的版本，不能自动回退到最初存在密码重置漏洞的版本。

应用回退不等于数据库回退。若后续迁移不兼容，需要先在隔离环境恢复备份，确认业务后另行切换。不要在服务器上执行 `docker compose down -v`。

V7→V8 统一实体入库更新需使用已验收的专项发布流程：最终备份后保持旧前后端停止，先在第二个全新隔离项目恢复升级，再启动候选；失败时冻结本项目前后端并保留数据库。旧版会重新开放直接改汇总库存入口，不能使用上述 `deploy.sh` 的自动旧应用回退；修复或回退前须验收入口冻结机制。专项工具、目标提交和两次新备证据由当批 PLAN 与验收档案定位。

V8→V9 逐册动作沿用上述专项流程，增加新动作表、册版本和状态来源指针的精确校验。V8入库表及回执可能已有真实业务，须逐值保留，不再要求其为空；只有本次新增动作表在升级后、开放办理前应为空，旧册技术版本为0、来源指针为NULL。完整Verify还须验证已有停用/报损状态和动作回执的备份恢复。旧V8不理解这些状态且借还不增加版本，失败后只能停止本项目前后端、保留数据库并前向修复，不能自动重启旧写代码。本批无运行时写开关、预览周期开关或触发器。V9启用状态与最终绑定由PLAN及当批验收记录确认。

V9→V10结构化库位继续使用专项流程；实际启用状态由PLAN确认。两次当时最新完整V9备份分别在全新环境恢复，先核对原V9全结构及全部旧值，再升级并核对独立已审的V10增量；不能沿用V9首次发布时的空动作表/零册版本假设。完整Verify还须保留非空结构化关联、地址改名历史和V1/V2原回执的容器重建、更新及备份恢复。最终备份后旧前后端保持停止至验收通过，最后启动核对实际镜像、JAR、全部静态和HTTP字节。旧V9不理解结构化位置指针，失败必须停止本项目前后端并确认已停、保留数据库与新增业务并前向修复，不能自动启动V9写代码或回灌旧备。

V10→V11盘点已验收启用，结果见[当次档案](../archive/2026-10/catalog-stocktake-2026-10-16.md)，当前代码由PLAN确认。发布须绑定最终完整Verify提交及产物、V11原字节、独立已审DDL/API和发布工具。两次当时最新完整V10数据库、uploads和环境备份须分别全新隔离恢复，升级前核对完整43张旧表结构和值，升级后只接受已审七张新表及约束。非空草稿、封存报告、待关联意图与成功关联的持久化由隔离容器验收覆盖，生产只做只读核对。最终新备后旧前后端持续停止至第二轮恢复升级验收通过；失败停止应用并确认已停，保留数据库及新增业务、前向修复，不自动重启旧V10写代码或覆盖旧备。

V12→V13出版社/分类身份使用专项发布流程。完整Verify须覆盖九张非空新表的关系、别名、审计、原请求及回执经容器重建、更新和完整备份恢复保持。两次当时最新完整V12新备分别在全新隔离项目恢复，升级前核对54张旧表完整结构和全部原列值，升级后只允许已审九表、三表技术版本列及其精确非负CHECK；V1–V12原字节保持。B33审计及其他旧业务可非空，不能沿用旧批次空态假设。三列为0及九表为空只用于本次业务开放前核对。最终新备起旧前后端保持停止；失败冻结本项目前后端并保留数据库前向修复，不重启V12写代码、不回灌旧备。最终核对APP_VERSION、实际JAR、全部静态/HTTP字节、uploads、环境及其他服务，生产只读验收不创建预览或确认。精确候选与结果由PLAN和当批档案定位。

V13→V14出版资料补录继续使用专项流程；实际启用状态由PLAN确认。目标完整Verify须覆盖两张非空请求/事件表、原回执及书目出版字段/非零版本经容器重建、更新、失败更新和完整备份恢复保持。两次当时最新完整V13新备分别全新隔离恢复，升级前核对63张旧表完整结构和值；升级后只接受已审两表、book.publication_revision及精确非负CHECK，V1–V13原字节保持。技术版本为0及两表为空只用于业务开放前核对，既有B35关系、别名、维度及原操作者回执允许非空且须保留。最终新备后旧前后端持续停止至候选验收成功；失败冻结应用、保留数据库并前向修复，不重启V13旧写代码或回灌旧备。生产仅GET及浏览器只读检查，不能借验收创建真实预览或确认。

V14→V15套装目录使用专项流程，实际启用状态由PLAN确认。完整Verify须保留非空套装/稳定成员、退役恢复、取消及成功请求/事件/原回执和非零书目身份版本的容器生命周期。两次当时最新完整V14新备分别全新隔离恢复，升级前核对65张旧表完整结构和全部原列值；升级后仅允许已审五表、book.catalog_set_book_revision及精确安全整数范围CHECK，V1–V14字节保持。原B36出版请求、事件、回执及版本允许非空，不能复用首次开放空态假设；五表为空及新版本为0仅在本批开放前核对。最终新备后旧前后端持续停止至第二轮升级验收通过；失败冻结应用、保留数据库并前向修复，不启动V14旧身份写入口或回灌旧备。最终运行字节及其他服务核对通过后，生产只GET和浏览器只读验收，不建立真实套装、预览、确认或取消请求。

## 备份和隔离恢复

V15→V16管理员只读台账子批只新增六表，无真实期初回填或财务写入口。目标完整Verify须覆盖六表非空合成期初/收退/冲正/分摊、协议终态及审计经重建、更新、失败更新和恢复保持，原B34/B36及更早门槛继续执行。两份当时最新完整V15新备分别在全新隔离项目恢复升级，70旧表结构和全部原列值、V1–V15字节保持，仅接受已审六表；生产新表为空仅用于本次只读能力开放前，不作为以后业务升级的空态假设。保留非空B34目录、关系、请求、审计和原回执，不能复用B34首次开放时空表校验。生产仅GET/浏览器核对，不建立账户或财务记录；失败冻结应用、保留数据库并前向修复，不自动回灌旧备。候选、实际DDL/API、备份及启用状态由PLAN和当批证据绑定。

本批首次隔离演练失败且生产从未进入最终停写、第二新备、候选安装或迁移时，经理可按已审专项入口恢复原V15服务：先证明原进程终态、隔离资源验空，原容器/镜像/current、70表原值、19类元数据及全部next-ID与初备一致，备份/env/uploads及其他服务保持。只读检查通过后另绑定一次性批准，仅以准确APP_VERSION执行原前后端的`up --no-deps --no-build --no-recreate`；启动后再核运行/HTTP字节及完整保值。任一检查失败保持或重新冻结前后端，保留数据库；不回灌初备、不改旧失败记录或重跑已消费入口。已进入最终边界或生产已升级时不适用此例外。

专项流程单独启动前后端时使用 `up ... --no-deps ... backend frontend`，在调用前后核对 MySQL 原容器 ID、健康、镜像与配置摘要、挂载和网络身份。发布目录切换会改变相对初始化脚本的 bind 来源；应用启动不得隐式协调并重建 MySQL。首次建立全新项目的 MySQL 仍按独立创建步骤执行。Docker inspect 的 Mounts 数组无稳定顺序，按每项完整 Type/Name/Source/Destination/RW/Propagation 多重集比较，保留重复数量；其他有序配置不排序。

```bash
bash /opt/library-management-system/current/scripts/backup.sh /absolute/path/new-backup-directory
```

备份期间暂停前后端。成功后自动启动原服务；备份含明文环境凭据，目录权限默认仅拥有者可访问，应放到受保护的独立存储中。恢复必须选择从未创建容器或卷的新 Compose 项目，避免覆盖当前业务数据。

复制 `.env` 到隔离恢复位置，保留数据库密码、JWT 和管理员凭据，修改前端端口、内部子网和前端 IP，并确保没有 `use-sub2api` 标记：

```bash
LIBRARY_PROJECT_NAME=library-recovery LIBRARY_ENV_FILE=/absolute/path/recovery.env \
  bash /opt/library-management-system/current/scripts/restore.sh /absolute/path/backup-directory
```

恢复必须从备份中 `application-version.txt` 记录的对应版本运行脚本，不同版本会被拒绝；示例中的 `current` 只有在与备份版本一致时才适用。恢复环境不能包含 `use-sub2api` 标记。脚本核对校验和，恢复数据库与上传文件，启动并等待健康。切换真实流量前还要核对用户、库存、借阅记录和文件。数据库账号及迁移账号的密码轮换需更新数据库本身，单改 `.env` 不会修改已有 MySQL 卷内的账号密码。

原仓库跟踪的 3 张历史头像已按原路径、原字节整理到 `legacy-data/uploads/`，本机运行目录中的文件保留。它们不随发布包分发、不在新库自动导入。接管旧库应备份和迁移完整的真实上传目录；只有数据库头像路径匹配且原文件缺失时才核对该参考归档，不能用它替代完整备份。
