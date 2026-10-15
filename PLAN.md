# 当前工作

本文件只保存活动计划的执行游标。总体路线由 [PROJECT](PROJECT.md)维护，完整任务状态由对应阶段维护。

## 执行游标

| 项目 | 当前值 |
| --- | --- |
| 活动计划 | [会员业务整合](docs/plans/member-business-alignment/README.md) |
| 下一工作项 | 阶段 2 套餐/来源预填/权益快照合并候选的完整 Verify 及隔离发布 |
| 工作项状态 | B24/B25/B22 界面与接口已定向验收，待新候选发布；真实来源候选未自动应用，未启用借阅限制 |
| 阶段定义 | [阶段 2](docs/plans/member-business-alignment/stages/02-membership.md)，[阶段 0 剩余规则](docs/plans/member-business-alignment/stages/00-baseline.md) |
| 工作分支 | `codex/member-business-batch1`；文档整理与技能接入已保留并提交 |
| 应用代码基线 | 已验收生产 `baf3a9a`，B24 新增模块尚未提交，不属于线上版本 |
| 完整 Verify | 候选 [38729764437](https://github.com/wulala123123/library-management-system/actions/runs/38729764437) 全部通过，产物 `11968959470`；前次合成卡号重复失败已修复 |
| 最近维护结果 | [统一会员档案验收及上线](docs/archive/2026-10/member-profiles-2026-10-15.md)；[第一批](docs/archive/2026-10/member-business-batch1-2026-10-15.md)保留 |
| 恢复依据 | `git status --short`、当前阶段勾选、下述有效执行者与私有证据 |

## 下一步

1. B24/B22/B25 合并候选已经理定向验收，提交后跑完整 Verify，使用对应新产物。
2. 新发布工具已交付，经理核对 V6/V7 最终字节及 API后执行本次新备份、两次隔离恢复升级和发布。
3. 后续 B22 实际存量登记需定向预览和备份，不把来源预览误标为数据已更改；逐套餐数值未核实不启用办理或限制。

## 有效执行者

经理为当前任务。用户授权当前任务内委派和满足门槛后的生产更新，不创建独立长期会话或自动化。经理模型与强度工具不可核实；子代理显式配置按技能默认 `gpt-6.1-sol` / `xhigh`。

| 有效任务 | 执行者 | 唯一可写边界 | 交付与下一动作 |
| --- | --- | --- | --- |
| B24 后端 / v1；B25 后端 / v2 | `/root/statistics_backend` | 已停止写入及进程 | 已验收；证据 `logs/member-business-stage2/entitlement-backend/`，33079 已关闭 |
| B24 前端 / v1；B22/B25 前端 | 经理完成；原 `/root/statistics_frontend` 已中止 | 已完成候选，待正式提交 | 51 项会员用例及类型构建通过；合成浏览器无错误；证据 `logs/member-business-stage2/entitlement-frontend/` |
| B22 后端；完整候选发布准备 / v1 | `/root/mapping_correction` | 已停止写入，无服务器操作 | B22 及工具已交付，经理审查发布准备；证据 `logs/member-business-stage2-complete/` |

状态文档、共享提交与发布资源仅经理写。后端已发准确 API 契约并转交前端；馆员仅必要资料只读、管理员维护完整档案，禁止新增收费/扣次/拦截。

## 已确认与未决

已确认规则维护在[会员规范](docs/specs/membership.md)、[财务规范](docs/specs/finance-reporting.md)：单次与累计上限分开、成功按订单扣一次、到期无宽限禁新借续借、续费起算、退押金条件、管理员核定会费退款及馆员资料权限。

已确认套餐修改仅用于后续办理、现有会员保留快照；新套餐可明确选择不限归还日期；套装核实前按单册条码借还。逐套餐数值、套餐变更差额、成功办理后的撤销/换书及核实后的整套规则仍需决定。卡号暂保守精确唯一并保留历史占用，不自动重用；如要重用需明确规则。未决只阻止相关限制或办理启用，资料、搜索与展示继续。

历史权益核对允许管理员填写历史依据后关联已停用的保留套餐版本；后续新办理只选当前启用版本。新服务期开始日计第 1 天，原实际日期不重算。

## 证据与恢复边界

新阶段私有证据：`logs/member-business-stage2/`。第一批原记录在 `logs/member-business-batch1/`、`logs/member-business-batch1-null-contract/` 及服务器同名 `migrations/` 私有目录。

第一批真实修正已经完成，后续不得重跑整批导入或用旧备份覆盖新业务。首次结果保留；最终代码更新只升级应用。收尾直接 Compose 必须设置 `APP_VERSION`，最后启动后再次核对镜像和字节。

浏览器验收接入用户已登录的 Codex 内置浏览器，已通过第一批不限、空值、统计与 CSV 下载检查；不要再要求用户到 Edge 重复登录。

会员资料独立范围已完成 `baf3a9a` 发布，V5 保留全部旧业务列；两次恢复升级、最后启动字节及只读API核对通过。Codex 浏览器验收通过且未保存资料，当前保留 `/members`。后续发布须采用新目标提交对应完整 Verify 产物。

## 更新规则

任务切换、阻塞解除或完成交付时更新本文件。较长验收进入原阶段或历史档案，已确认规则更新唯一 owner。
