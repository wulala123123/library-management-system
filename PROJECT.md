# 项目说明与路线

## 目标

建设可独立维护的绘本馆管理系统，让迁入会员与新会员使用一致的会员、套餐、图书、收费和借还流程。保证身份与历史关联可追溯，实体册与库存一致，金额及权益可核对。

## 产品范围

- 馆员：管理会员、图书和实体册，办理借还、采购入库及日常查询。
- 管理员：维护套餐、角色权限、收费与押金，核对迁入资料和经营报表。
- 读者：查看自己的权益和借阅记录，按已启用规则使用自助服务。

首要范围为柜台经营与数据衔接。小程序、线上支付、配送、通知和自动续费按独立接入项评估所需账号、费用与授权。

## 当前阶段

阶段 0 的备份恢复、字段对照与原权益核对已验收，关键规则部分确认；阶段 1 的 B10–B14 已上线；阶段 2 的会员资料、套餐版本、来源预填及权益快照已上线，已核实来源宝宝定向登记完成；阶段 3 的书目与逐册管理各批已验收上线，B34套装目录关系于2026-10-19完成闭环。阶段4的B40-A只读台账与B40-B当天柜台收款已验收上线，V17于2026-10-20启用当天现金/微信实收登记。用户随后要求暂缓套餐及全部资金后续工作，优先推进阶段5可独立实施的柜台借还流程；阶段4其余项目和套餐规则仍未完成。2026-10-21用户确认先推进阶段6的独立只读历史资料衔接；套餐及资金后续继续暂缓。下一工作项及恢复位置由 [PLAN](PLAN.md)维护。

已具备基础借还、逐册迁入、历史资料查询和公网 HTTPS 访问。2026-10-21历史资料只读衔接、借阅来源/数量口径与完整筛选CSV三项合批已验收上线，无迁移，实际维护5分43秒，见[本批记录](docs/archive/2026-10/history-context-2026-10-21.md)。本批完成B60/B61及B62/B65借阅查询子集，统一报表、日期精度与其他导出仍待后续；现行行为由[领域规范](docs/specs/README.md)记录。

当前部署应用代码为 `89c2d24d1a8589c87668201ac60c164284d494fe`/V18，已通过 [Verify 39611862889](https://github.com/wulala123123/library-management-system/actions/runs/39611862889)，使用该运行的准确产物，版本目录为PR合并SHA db687c9；三个服务健康并已开放。此前a39bfaf/V18已上线B50-A柜台多本借阅订单、逐册关联与批量归还，原两份新备、42.2分钟维护及验收见[柜台借还发布](docs/archive/2026-10/counter-circulation-2026-10-21.md)。套餐限制、计次及资金后续仍未启用；当天实收登记及2026-10-20固定起点保持，原[当天收款记录](docs/archive/2026-10/member-finance-receipts-2026-10-20.md)有效。

原长维护失败及V17恢复保留在[恢复记录](docs/archive/2026-10/preupgrade-v17-service-recovery-2026-10-21.md)，已消费入口不重跑。本批已将验证职责、范围、复用和结束条件统一到[测试与发布检查](docs/development/testing.md)，按[维护时间规范](docs/operations/release-validation.md)先验证真实规模和预算；后续只补实际变更风险，不因换聊天或工具调整重做已完成产品全套。服务器操作前仍须现场核对实际版本。

## 总体路线

活动计划为[会员业务整合](docs/plans/member-business-alignment/README.md)。本表维护阶段顺序；任务定义、勾选状态和验收条件由链接的阶段文档维护。

| 阶段 | 目标 | 依赖 | 阶段定义 |
| --- | --- | --- | --- |
| 0 | 建立基线和确认规则 | 无 | [基线与规则](docs/plans/member-business-alignment/stages/00-baseline.md) |
| 1 | 修正数据错误及基础统计 | 0 | [数据修正](docs/plans/member-business-alignment/stages/01-data-corrections.md) |
| 2 | 统一会员档案和套餐权益 | 1 | [会员管理](docs/plans/member-business-alignment/stages/02-membership.md) |
| 3 | 完善图书和逐册管理 | 1，交付顺序在 2 后 | [图书管理](docs/plans/member-business-alignment/stages/03-catalog.md) |
| 4 | 建立收费与押金流程 | 2 | [收费与押金](docs/plans/member-business-alignment/stages/04-payments.md) |
| 5 | 接通套餐规则及借还订单 | 2、3、4 | [借还流程](docs/plans/member-business-alignment/stages/05-circulation.md) |
| 6 | 完善历史衔接与经营统计 | 5 | [历史与报表](docs/plans/member-business-alignment/stages/06-reporting.md) |
| 7 | 完善读者登录和自助服务 | 5、6 | [读者服务](docs/plans/member-business-alignment/stages/07-reader-services.md) |

## 项目完成标准

1. 老会员和新会员在统一入口维护业务资料，权益变更有来源和记录。
2. 套餐、有效期、次数、借阅期限和续借权限在各办理入口一致执行。
3. 每本实体书可追踪，采购、借还、报损、盘点与汇总库存一致。
4. 办卡、续费、退款及押金形成可核对台账，历史期初与新增收支分别记录。
5. 历史与新增业务的来源、统计范围和日期精度明确，页面与导出一致。
6. 关键流程通过适用的数据库、并发、权限与页面验收，发布包可更新和恢复。

技术架构见[架构说明](docs/architecture/README.md)，协作与维护流程见 [WORKFLOW](WORKFLOW.md)。
