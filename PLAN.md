# 当前工作

本文件只保存活动计划的执行游标。总体路线由 [PROJECT](PROJECT.md)维护，完整任务状态由对应阶段维护。

## 执行游标

| 项目 | 当前值 |
| --- | --- |
| 活动计划 | [会员业务整合](docs/plans/member-business-alignment/README.md) |
| 下一工作项 | 阶段 2 统一会员资料、搜索、现有权益展示及权限 |
| 工作项状态 | 进行中；B24–B25 完整套餐版本及后续收费/借还限制另行验收 |
| 阶段定义 | [阶段 2](docs/plans/member-business-alignment/stages/02-membership.md)，[阶段 0 剩余规则](docs/plans/member-business-alignment/stages/00-baseline.md) |
| 工作分支 | `codex/member-business-batch1`；文档整理与技能接入已保留并提交 |
| 应用代码基线 | 已验收生产及本地 HEAD `f98539403c1a6d18afcc217de254938ecdbfed2a`，新阶段实现尚未提交 |
| 完整 Verify | [38724073975](https://github.com/wulala123123/library-management-system/actions/runs/38724073975) 全部通过；产物 `11968080287` 只对应已发布第一批 |
| 最近维护结果 | [B00–B02、B10–B14 第一批验收及上线](docs/archive/2026-10/member-business-batch1-2026-10-15.md) |
| 恢复依据 | `git status --short`、当前阶段勾选、下述有效执行者与私有证据 |

## 下一步

1. 优先接收下述阶段 2 已派任务，经理直接验收；不要重复派工或交叉写入。
2. 核对迁入与新会员同入口、缺失/不限/零值、乐观版本、卡号唯一、权限及来源预填只读；完成独立范围后明确 B24–B25 依赖。
3. 未提交实现不能用旧生产 CI 证明通过；候选需定向验证，正式发布仍通过目标提交完整 Verify、隔离升级与备份校验。

## 有效执行者

经理为当前任务。用户授权当前任务内委派和满足门槛后的生产更新，不创建独立长期会话或自动化。经理模型与强度工具不可核实；子代理显式配置按技能默认 `gpt-6.1-sol` / `xhigh`。

| 有效任务 | 执行者 | 唯一可写边界 | 交付与下一动作 |
| --- | --- | --- | --- |
| 阶段 2 后端 / v1 | `/root/statistics_backend` | 后端 member 模块、必要用户候选/Profile、V5 新迁移及相应用例/H2支持 | 当前任务子代理最终输出；经理核对事务、权限、升级及字段契约 |
| 阶段 2 前端 / v1 | `/root/statistics_frontend` | `frontend/src` 的 member/API/types、必要导航、用户/借阅候选/profile 及定向测试 | 当前任务子代理最终输出；经理核对新旧同入口及保留失败输入 |

状态文档、共享提交与发布资源仅经理写。后端已发准确 API 契约并转交前端；馆员仅必要资料只读、管理员维护完整档案，禁止新增收费/扣次/拦截。

## 已确认与未决

已确认规则维护在[会员规范](docs/specs/membership.md)、[财务规范](docs/specs/finance-reporting.md)：单次与累计上限分开、成功按订单扣一次、到期无宽限禁新借续借、续费起算、退押金条件、管理员核定会费退款及馆员资料权限。

逐套餐数值、套餐变更差额、成功办理后的撤销/换书、套装及不限期选项仍需核实。卡号暂保守精确唯一并保留历史占用，不自动重用；如要重用需明确规则。未决只阻止相关限制或办理启用，资料、搜索与展示继续。

## 证据与恢复边界

新阶段私有证据：`logs/member-business-stage2/`。第一批原记录在 `logs/member-business-batch1/`、`logs/member-business-batch1-null-contract/` 及服务器同名 `migrations/` 私有目录。

第一批真实修正已经完成，后续不得重跑整批导入或用旧备份覆盖新业务。首次结果保留；最终代码更新只升级应用。收尾直接 Compose 必须设置 `APP_VERSION`，最后启动后再次核对镜像和字节。

浏览器验收接入用户已登录的 Codex 内置浏览器，已通过第一批不限、空值、统计与 CSV 下载检查；不要再要求用户到 Edge 重复登录。

## 更新规则

任务切换、阻塞解除或完成交付时更新本文件。较长验收进入原阶段或历史档案，已确认规则更新唯一 owner。
