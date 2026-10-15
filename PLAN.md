# 当前工作

本文件只保存活动计划的执行游标。总体路线由 [PROJECT](PROJECT.md)维护，完整任务状态由对应阶段维护。

## 执行游标

| 项目 | 当前值 |
| --- | --- |
| 活动计划 | [会员业务整合](docs/plans/member-business-alignment/README.md) |
| 下一工作项 | 阶段 2 B22 来源候选预填、B25 权益快照与版本关联 |
| 工作项状态 | 会员资料独立范围已上线；B24 已实现并定向验收，B22/B25 进行中；尚未发布后续套餐能力 |
| 阶段定义 | [阶段 2](docs/plans/member-business-alignment/stages/02-membership.md)，[阶段 0 剩余规则](docs/plans/member-business-alignment/stages/00-baseline.md) |
| 工作分支 | `codex/member-business-batch1`；文档整理与技能接入已保留并提交 |
| 应用代码基线 | 已验收生产 `baf3a9a`，B24 新增模块尚未提交，不属于线上版本 |
| 完整 Verify | 候选 [38729764437](https://github.com/wulala123123/library-management-system/actions/runs/38729764437) 全部通过，产物 `11968959470`；前次合成卡号重复失败已修复 |
| 最近维护结果 | [统一会员档案验收及上线](docs/archive/2026-10/member-profiles-2026-10-15.md)；[第一批](docs/archive/2026-10/member-business-batch1-2026-10-15.md)保留 |
| 恢复依据 | `git status --short`、当前阶段勾选、下述有效执行者与私有证据 |

## 下一步

1. 接收 B22 来源预览及表单交付，核对空槽不造宝宝、只按来源键更新、不从年龄推生日、原总次数不可预填。
2. B25 新模块先实施，待 B22 后端停止写入才放行 MemberService 集成，前端 B22 交付后再派 B25。
3. B24/B22/B25 合并候选再跑正式发布门槛，减少重复全量验收；逐套餐数值未核实不启用办理或限制。

## 有效执行者

经理为当前任务。用户授权当前任务内委派和满足门槛后的生产更新，不创建独立长期会话或自动化。经理模型与强度工具不可核实；子代理显式配置按技能默认 `gpt-6.1-sol` / `xhigh`。

| 有效任务 | 执行者 | 唯一可写边界 | 交付与下一动作 |
| --- | --- | --- | --- |
| B24 后端 / v1 已验收；B25 后端 / v1 | `/root/statistics_backend` | 新 MemberEntitlement 模块、V7、新用例/H2；MemberService 暂不允许修改 | B25 实施中，等待 B22 停止后再集成；证据 `logs/member-business-stage2/entitlement-backend/` |
| B24 前端 / v1 已验收；B22 前端 / v1 | `/root/statistics_frontend` | member API、sourceReview、MemberFormDialog及来源/表单用例；不改套餐模块 | B22 实施中；证据 `logs/member-business-stage2/source-prefill-frontend/` |
| B22 核对已验收；B22 后端 / v1 | `/root/mapping_correction` | MemberService 仅来源预览/宝宝来源键校验及辅助类、新 SourcePreviewTest；不改迁移/H2或旧会员测试 | B22 实施中；证据 `logs/member-business-stage2/source-prefill-backend/` |

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
