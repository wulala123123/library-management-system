# 批次5：工具、文档与分发内容收口

状态：本地收口已验收，未上线；外部审计及完整Linux Verify未通过前不能发布。目标、基线和共用护栏见[第二阶段总计划](README.md)，当前执行位置见[根目录PLAN](../../../PLAN.md)。

## 范围

按用途与引用整理一次性迁入/修正工具、日常维护和发布运行工具、历史计划及分发包输入。主要入口为[打包脚本](../../../scripts/package_release.py)、[容器验收脚本](../../../scripts/container-acceptance.sh)及其[原流程保护](../../../scripts/test_container_circulation.py)。

## 任务

- [x] 将第一阶段原PLAN与12份原子计划集中归档，第二阶段使用独立目录，完成正文保全、引用迁移和公开文档输入核对。
- [x] 为工具核对日常用途、CI、发布包、恢复及历史证据引用，确定保持、归档或可移除范围。
- [x] 精简不再需要的活动入口，按实际引用整理工具位置及文档，保留原记录和一次性入口冻结状态。
- [x] 核对分发包运行/维护所需输入；调整时同步脚本和引用，排除私有证据、凭据及可重建缓存。
- [x] 运行相应文档、工具及实际打包内容检查，收口当前恢复入口并汇总第二阶段交付。

## 验证与完成条件

- 文档与差异检查通过，旧链接和恢复依据仍可定位；归档不改写原结论、任务状态或已部署迁移。
- 工具变化按实际影响运行现有纯测试；原容器流程字节及五轮业务钩子保持，不能通过替换冻结哈希掩盖修改。
- 分发包输入变化核对实际包内文件，保持运行、更新和恢复所需入口，私有资料不进入公开包。
- 约定整理完成且必要检查通过，即结束本批；正式生产发布另按运维要求执行。

## 交付记录

2026-10-22：第一阶段归档和原子计划保全由`6c3e909`独立保存；新增[工具用途与维护边界](../../development/tools.md)，登记23个工具的用途、CI/实际包/恢复引用，补充数据承接入口冻结说明。原记录和私有路径保持，未重跑任何旧批次。

- 删除`jekins测试项目/.github/workflows/maven.yml`，53行/1378字节；该目录仅剩嵌套旧工作流，GitHub只加载仓库根`.github/workflows`，当前Verify/Release使用根入口，源码/打包均无调用；恢复可用`git show 109a769:jekins测试项目/.github/workflows/maven.yml`。根Maven工作流继续保留，无法仅凭本地证据排除其作为远端必需状态检查的用途。依据见[GitHub工作流规范](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#about-yaml-syntax-for-workflows)。
- 23个工具共458,198字节，均保留原路径；包括手工密码散列辅助、受控导入/修正及其合成CI测试。实际验收、备份恢复和来源追溯仍使用它们，取消后续开发不构成删除依据。分发脚本和容器验收脚本原字节保持，未强行拆分或修改冻结哈希。
- 文档检查、差异检查、全部分发Shell语法通过；发布预算8、上传恢复10、冻结容器流程36项纯检查通过。原容器字节及五轮钩子保持。Shell运维测试在Git Bash调用Windows Python时无法启动无扩展名Docker替身，保留失败证据；脚本未改，Linux完整Verify仍为发布门槛。
- 实际本地检查包为根`dist/library-management-system-engineering-cleanup-local-check.zip`及校验文件，约48.60 MiB、294文件。逐项核对工具、技能、前端dist、后端JAR和公开文档，文件集与源码输入一致；私有日志、凭据、上传、历史头像和缓存均排除。该检查包不是CI验收产物，不能用于生产。
- 固定最终JAR及全部非前端输入，仅对比`109a769`前端与候选，ZIP 51,042,389→50,953,272字节，减少89,117字节（约87 KiB）；后续计划状态文本会使检查包少量变化。旧本地JAR由50,653,762重建为51,050,275字节，不是绑定相同源码的对比，不把其差额归因于本次整理，也不宣称后端JAR缩小。
- 相对`6c3e909`业务main/前端生产源码及Vite配置净减189行（82增/271删）；测试净增143行；另移除53行旧工作流，锁文件新增194行。18份Flyway SHA保持。
- 目录空间按同一文件长度口径复测：旧HTTP上传实际清理645 MiB；计入新验证资料、约191.87 MiB隔离库及48.60 MiB本地检查包后，排除前端依赖链接重复计数的可比目录净减约384 MiB。依赖目录重新安装后逻辑计数减少约440 MiB，但pnpm链接可能重复计数，不计作唯一物理磁盘释放量。无历史资料移动或压缩收益；20 GiB私有资料保持。

私有证据位于`logs/engineering-cleanup/`：`tool-inventory.json`、`private-history-disposition.json`、`obsolete-workflow-candidate.json`、`distribution-final.json`、`distribution-controlled-comparison.json`、`space-final.json`、`tool-check-results.json`及`final-container-pure.log`；各源码批次另有准确内容清单。所有员工交付已由经理验收，测试服务与端口已关闭。五批本地实现和定向行为检查收口；依赖审计官方POST超时、Linux代理/运维/完整Verify待可达隔离环境补证，历史原资料/新MySQL目录清理的自动审批限制保持。没有生产操作。
