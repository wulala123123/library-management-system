# 工具用途与维护边界

本页说明仓库工具的用途、执行边界和分发依据。日常验证命令见[测试与发布检查](testing.md)，真实快照、运行日志和凭据留在私有目录。

## 工具分组

| 用途 | 入口 | 保留依据 |
| --- | --- | --- |
| 安装及更新 | [deploy.sh](../../deploy.sh)、[分发打包](../../scripts/package_release.py) | 部署及Verify实际包验收使用；打包在源码仓库执行 |
| 备份及恢复 | [公共Shell](../../scripts/lib.sh)、[备份](../../scripts/backup.sh)、[恢复](../../scripts/restore.sh)、[上传元数据](../../scripts/restore_uploads.py) | 运行、更新、完整备份恢复所需，保留原入口 |
| 发布预算 | [release_validation_plan.py](../../scripts/release_validation_plan.py) | 纯代表项选择及已测成本预算，不能授权上线 |
| 文档及依赖检查 | [文档检查](../../scripts/check_docs.py)、[后端审计](../../scripts/audit_backend.py) | 本地及Verify使用 |
| 隔离容器验收 | [container-acceptance.sh](../../scripts/container-acceptance.sh)、[smoke](../../scripts/container_smoke.py)、[借还](../../scripts/container_circulation.py)、[收款](../../scripts/container_finance_receipts.py)、[收款契约](../../scripts/container_finance_receipts_contract.json) | Verify对实际分发包进行更新、回退、持久化和恢复检查；原流程字节与五轮钩子冻结 |
| 纯工具测试 | [发布预算](../../scripts/test_release_validation_plan.py)、[上传恢复](../../scripts/test_restore_uploads.py)、[流程保护](../../scripts/test_container_circulation.py)、[Shell运维](../../scripts/operations.test.sh) | CI检查；流程保护也从解包后的实际分发内容运行 |
| 受控数据承接 | [准备](../../scripts/prepare_huiben_migration.py)、[导入](../../scripts/apply_huiben_migration.py)、[定向修正](../../scripts/correct_huiben_unlimited_uses.py)、[初始密码散列辅助](../../scripts/MigrationPasswordHashes.java) | 后三类涉及数据或私有结果；保留来源及恢复语义，操作按[数据承接](../operations/data-transfer.md)单独核对 |
| 合成导入验收 | [导入事务](../../scripts/test_huiben_import.py)、[定向修正](../../scripts/test_huiben_unlimited_uses.py) | Verify使用合成样例及专用可丢弃数据库，不运行真实历史批次 |

工具目录现有23个文件均有以上用途。密码散列辅助是手工编译运行的独立CLI，不由业务应用自动调用；保留不表示恢复读者登录开发。受控工具保留在原位置，减少历史引用及恢复路径迁移。

## 已消费入口

第一阶段已执行的导入、会员定向修正、启用、发布及恢复入口保持冻结，完整结论见[第一阶段档案](../archive/2026-10/phase-1-data-adaptation/README.md)。工具存在、测试通过或恢复历史文件均不授权重跑；新批次必须按当前计划、来源预览、备份及目标范围重新核对。

## 分发与私有资料

打包保留运行、维护及实际分发验收依赖；公开文档由文档检查后自动收集。`logs/`、真实上传、`legacy-data/`、凭据、依赖缓存及测试构建残留不作为分发输入；后端仅包含构建JAR，前端仅包含dist。

历史私有资料以完整证据链保存。清理或压缩前核对绝对路径、引用、占用、全文件校验与原路径恢复方法；同盘移动不计为磁盘释放，构建产物缺失不计为应用包缩小。
