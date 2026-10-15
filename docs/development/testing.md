# 测试与发布检查

## 本地检查

```bash
python3 scripts/check_docs.py
git diff --check
mvn -B -ntp -f backend/pom.xml verify
pnpm --dir frontend test
pnpm --dir frontend build
pnpm --dir frontend audit --audit-level=high
```

按变更范围选择检查。纯文档修改运行文档检查和差异检查；发布文档路径或打包入口变化时，另核对分发包内容。前端 build 包含完整类型检查。

## 隔离要求

- 后端快速测试默认使用 H2。设置 `MYSQL_TEST_URL` 后执行真实 MySQL 用例，目标只能是专用、可丢弃的 `library_repair_tests`；账号和密码分别使用 `MYSQL_TEST_USER`、`MYSQL_TEST_PASSWORD`。
- 导入试验只使用脚本允许的独立 `library_transfer_trial_*` 数据库。
- Linux 隔离主机可设置 `CI_NGINX_HTTP=true` 验证生产 Nginx 路由、上传边界和真实 HTTP 流程。
- 测试数据使用合成样例，真实会员资料和财务快照不进入 CI。跳过用例、未运行检查及失败结果如实记录。

## 完整发布门槛

[Verify 工作流](../../.github/workflows/verify.yml)验证后端安全、升级和并发，迁入事务，前端安装、类型、测试与构建，依赖审计，Shell 运维以及实际容器更新、回退和备份恢复。

```bash
python3 scripts/package_release.py vX.Y.Z
# 只在全新隔离 Docker 主机运行
LIBRARY_ACCEPTANCE=1 bash scripts/container-acceptance.sh dist/library-management-system-vX.Y.Z.zip
```

产物在 `dist/`；只有通过目标提交 Verify 的 `verified-distribution` 及配套校验文件用于生产。分发包的公开文档随目录自动收集，凭据与私有运行数据保持排除。

## 文档检查范围

文档检查验证固定入口、目录索引可达性、本地链接与标题片段、活动文件命名、长度边界和任务编号唯一性。增加文件、拆分主题或归档时在本地运行，Verify 同步执行。
