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

新应用健康检查失败时脚本重新启动上一应用版本；已完成的数据库迁移保留。本次迁移增加表、索引和认证字段，旧表和数据不删除。以后开发不得随意删除或重命名上一版本需要的字段。回退只允许选择已验证兼容且安全的版本，不能自动回退到最初存在密码重置漏洞的版本。

应用回退不等于数据库回退。若后续迁移不兼容，需要先在隔离环境恢复备份，确认业务后另行切换。不要在服务器上执行 `docker compose down -v`。

## 备份和隔离恢复

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
