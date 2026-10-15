# 当前工作

本文件只保存活动计划的执行游标。总体路线由 [PROJECT](PROJECT.md)维护，完整任务状态由对应阶段维护。

## 执行游标

| 项目 | 当前值 |
| --- | --- |
| 活动计划 | [会员业务整合](docs/plans/member-business-alignment/README.md) |
| 下一工作项 | 第一批 B10–B14 交付验收、完整 CI 与发布；B03 剩余规则确认 |
| 工作项状态 | 首次真实修正已核对；NULL 接口契约修复已接纳，准备新候选完整 CI，整批未完成 |
| 阶段定义 | [阶段 0 基线与规则](docs/plans/member-business-alignment/stages/00-baseline.md) |
| 工作分支 | `codex/member-business-batch1`，保留既有文档整理与技能接入差异 |
| 应用代码基线 | 生产 `9ebf526`；候选 `e70b33ff104ec37ee65c777376993616d7820ccd`，已推送 main |
| 完整 Verify | [38721357111](https://github.com/wulala123123/library-management-system/actions/runs/38721357111) 已全部通过；分发产物 `11965677465` |
| 最近维护结果 | [文档体系整理已验证](docs/archive/2026-10/documentation-maintenance-2026-10-15.md)，[项目经理技能](docs/development/skills.md)已接入；本地变更待提交 |
| 恢复依据 | `git status --short`、本文件及当前阶段的勾选状态 |

## 下一步

1. B00–B02 已验证；完整备份已在新隔离项目恢复，数据库全表与上传文件一致，生产未变。来源快照继续使用原迁入批次。
2. `e70b33f` 的真实修正及部署已完成；后置验收发现 `/legacy-data/members` 省略 NULL `total_uses`，页面显示待核实。服务已恢复且健康；由原后端代理修复显式空值契约，补字段存在断言后提交新候选。
3. 真实修正先核对预览、来源哈希及备份，执行后验证无关记录与幂等；更新阶段和生效规范。

## 本批执行者与恢复

经理为当前任务；用户授权当前任务内委派及满足门槛后的生产更新，不创建独立长期会话或自动化。
经理实际模型与强度不可由工具核实；子代理均按技能默认显式指定 `gpt-6.1-sol` / `xhigh`。

| 有效任务 | 执行者 | 唯一可写边界 | 交付与下一动作 |
| --- | --- | --- | --- |
| B10–B11 / v2（取代 v1） | `/root/mapping_correction` | 导入解析、独立修正脚本及其测试；Verify 仅新增对应调用行 | 返工已接纳：13 项单测、MySQL 8.4 事务及维护账号权限/触发器实测通过；停止写入 |
| B12–B13 后端 / v1 | `/root/statistics_backend` | 后端统计 controller/service/dto/mapper 与测试 | 已交付并接纳，7 项定向测试通过，停止写入 |
| B12–B14 前端 / v1 | `/root/statistics_frontend` | 前端统计、空值展示、API 类型及定向测试 | 已交付并接纳，34 项测试及构建通过，停止写入 |
| B11–B14 隔离升级准备 / v1 | `/root/statistics_backend` | 仅私有 `logs/member-business-batch1/verified_upgrade_trial.py` | 已交付并完成脚本审查，等待通过的 CI 产物，由经理运行 |
| B10/B14 NULL 接口契约 / v1 | `/root/statistics_backend` | LegacyDataController、必要 DTO/定向测试；私有隔离脚本加字段存在断言 | 已接纳：实际 NON_NULL 配置先复现失败，再通过 7 项定向验收；已停止写入，二次隔离用独立目录/资源 |
| 阶段 2 接续准备 / v1 | `/root/statistics_frontend` | 只读阶段 2、会员接口及表单，无文件写入 | 已交付：会员资料/搜索/状态展示可独立推进；套餐完整权益待 B24–B25，馆员编辑与敏感字段权限待确认 |

私有现场与运行证据入口：`logs/member-business-batch1/`；服务器对应迁入目录下保存备份及真实核对结果。
`e70b33f` 分发包 SHA256：`1868432e171a74cdc3fb2c267b1ee47f800868f859da091ac11285bb12ebf811`。服务器 `migrations/member-business-batch1/` 的隔离、预览、`correction-verified.json`、`deployment-complete.json` 均存在；`online-verification.json` 尚未通过。首次修正结果必须保留，新候选只升级代码，不重复修改已修正数据。
中断后优先接收上述执行者已有交付，不重复派工；经理负责所有状态文档及共享发布资源。

## 未决事项

阶段 0 中 B03 的单次限额、累计在借、计次、到期宽限、续费起算和退款规则需要逐项确定。它们只阻止依赖这些选择的限制启用；基线核对和已明确映射错误的准备可继续。

## 更新规则

任务切换、阻塞解除或完成交付时更新本文件。只保留最新游标与恢复依据；较长验收记录进入阶段记录，完成计划按 WORKFLOW 归档。
