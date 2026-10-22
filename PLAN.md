# 当前工作

本文件只保存当前执行游标；总体目标见[PROJECT](PROJECT.md)，实现与验收按[WORKFLOW](WORKFLOW.md)执行。

## 第二阶段发布完成

| 项目 | 当前值 |
| --- | --- |
| 阶段档案 | [工程清理与代码精简](docs/archive/2026-10/phase-2-engineering-cleanup/README.md)，五批实现与完整隔离验收完成，计划按WORKFLOW归档 |
| 当前批次 | 2026-10-22用户授权的第二阶段发布及经理验收完成，[发布档案](docs/archive/2026-10/engineering-cleanup-release-2026-10-22.md)已归档。CI与服务器员工均已交付，无在途执行者；主代理完成页面、实际下载及状态文档收尾 |
| 下一工作项 | 当前授权工作剩余0；按用户后续反馈确定新范围。私有完整证据位于`logs/engineering-cleanup/release-20261022/`，最终经理验收见`manager/final-acceptance.json`。不重跑已消费发布、导入、修正或恢复入口 |
| 当前状态 | 已上线`5f6cfed3c3a333112da42c5bef5a2efb80cbd895`/V18；准确Verify39766772871、产物12420718952及运行字节已验收。后端1084项/前端848项通过，前端0已知漏洞，后端2条已有不可达评审/0未评审；Nginx和容器恢复通过。生产248次GET、15次CSV及11组报告比较通过，页面和3份实际CSV下载通过。北京时间19:26:39–19:34:00维护441.17秒并恢复营业 |
| 工作分支 | `codex/engineering-cleanup`；本地起点`1500166`，承接已准备的工程清理文档改动 |
| 工时估算 | 五批实现、补证、已确认历史清理及本次发布剩余0；本次准确完整CI实测13分23秒，生产维护7分21秒。保留项没有新增删除结论；未来进一步处置先补来源/归档条件 |
| 交付方式 | 分批独立提交；分别记录工作目录、构建包和代码变化，必要检查达标即交付 |
| 第一阶段归档 | [原PLAN完整记录](docs/archive/2026-10/phase-1-data-adaptation/PLAN.md)，旧执行者及一次性入口只供历史追溯 |

## 保留的运行基线

- 生产应用为`5f6cfed3c3a333112da42c5bef5a2efb80cbd895`/V18；[Verify 39766772871](https://github.com/wulala123123/library-management-system/actions/runs/39766772871)，实际checkout`e6c9f8e65bd17aa881e0cfa754cca05318c1fb7a`与候选tree一致，准确包及原校验在`logs/engineering-cleanup/release-20261022/ci/accepted/`。current为`/opt/library-management-system/releases/library-management-system-ci-e6c9f8e65bd17aa881e0cfa754cca05318c1fb7a`；旧`f79e646`版本目录保留，PR #2仍为草稿。后续状态文档提交不改变已部署应用源。
- 最新正式备份及恢复证据位于服务器`/opt/library-management-system/migrations/history-query-engineering-e35077d6c1c448aeab446c05450e9326/`下的`final-maintenance-backup/`、`final-backup-restore-proof.json`和`final-preopen-comparison.json`。正式13个job全部退出0、两个隔离环境资源及自有worker进程0；原MySQL、环境及其他9个运行服务保持。恢复前重新核对实际版本和新增业务，不消费旧批准或回灌旧备。
- 生产原MySQL、环境、上传和正常新增业务保持；当天实收固定起点2026-10-20。既有发布、启用、恢复及定向修正入口继续冻结，不重跑、不回灌旧备。
- 已部署Flyway内容、会员独立身份、来源键、历史关联及未知值语义按[领域规范](docs/specs/README.md)保留。

## 范围与恢复

- 第一阶段由用户认可完成并归档；已取消的五项扩展、读者登录/自助完善和日期精度扩展不恢复实施。套餐、计次及资金后续继续暂缓。
- 第二阶段本地整理及本次生产发布已完成；后续源码变更按实际影响验证，新的正式发布另满足目标完整Verify和运维要求。
- 归档与删除先核对明确路径、引用和恢复依据；原始会员、财务、凭据与私有证据不进入Git、CI或公开分发。
- 早期整组压缩副本已撤销，原压缩清单仅表示当时状态。本轮按逐项证据清理，重复二进制可通过保留副本和`binary-cleanup-plan.json`恢复原路径；合成物理库只保留源码、夹具及原结果，后续复验在新隔离实例重建，不续跑已消费入口。真实备份、上传、凭据和唯一历史产物继续保持私有。
- 2026-10-22实际清理333份重复JAR/ZIP（5.18 GiB）及52个合成MySQL目录（10.14 GiB），合计16,452,774,634字节（15.32 GiB）；结束时logs约5.07 GiB，后续发布新证据另计。前端构建减少19.27%，业务源码净减189行；这些与旧上传清理645 MiB分别记录，不重复计算。
- 本轮53个MySQL目录共12,208文件通过独占读取检查；仅52个有明确合成来源的目录被删除。`logs/circulation-stage5/backend-v2/mysql-data`因准确来源绑定不足保留（176,999,855字节）；系统MySQL84服务原PID与Running状态保持。删除无错误，最终路径、哈希及空间复核见私有`final-verification-and-space.json`。
- 2026-10-22用户手动清理后，本地复核合成MySQL数据目录、过时本地检查ZIP及其sha文件均已不存在，删除前普通文件长度合计252,157,110字节（240.48 MiB）；准确CI验收包保留。旧上传清理645 MiB及初收口净减约384 MiB是此前统计，后续新增CI证据另计，不作为当前净值；依赖链接长度也不计作唯一物理磁盘释放量。
- 任务切换或批次交付时更新本文件；长清单与历史过程进入对应计划或档案，根目录不再承载第一阶段执行者台账。
