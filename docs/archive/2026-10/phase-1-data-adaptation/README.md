# 第一阶段：资料导入与系统适配

历史档案。2026-10-22用户认可第一阶段开发范围完成，确认其计划与执行记录归档。当前工作见[根目录PLAN](../../../../PLAN.md)及[第二阶段工程清理计划](../../../plans/phase-2-engineering-cleanup/README.md)，现行行为由[领域规范](../../../specs/README.md)维护。

本目录集中保存原计划、任务状态和执行游标；原已实现、已取消与暂缓状态分别保留。归档不表示所有原规划功能都已实现，也不使旧工单或已消费操作入口重新生效。私有运行证据仍由原记录定位，不随本次公开文档整理移动。

## 归档内容

- [原根目录PLAN](PLAN.md)：完整执行游标、版本基线、历史交付与恢复依据。
- [会员业务整合计划](member-business-alignment/README.md)：第一阶段原规划及交付边界。
- [原子阶段索引](member-business-alignment/stages/README.md)：原阶段0–7的完整定义和任务编号。
- [问题覆盖](member-business-alignment/coverage.md)：原问题与任务的对应关系。
- [来源字段与权益核对](member-business-alignment/source-fields.md)：来源语义、空值和历史权益依据。
- [当月验收档案](../README.md)：各次实现、验证、上线及恢复结果。

## 第一阶段内部的原子阶段

这里的0–7属于第一阶段内部拆分；第二阶段工程清理使用独立计划。

| 子阶段 | 原目标 | 原依赖 | 历史定义 |
| --- | --- | --- | --- |
| 0 | 建立基线和确认规则 | 无 | [基线与规则](member-business-alignment/stages/00-baseline.md) |
| 1 | 修正数据错误及基础统计 | 0 | [数据修正](member-business-alignment/stages/01-data-corrections.md) |
| 2 | 统一会员档案和套餐权益 | 1 | [会员管理](member-business-alignment/stages/02-membership.md) |
| 3 | 完善图书和逐册管理 | 1，原交付顺序在2后 | [图书管理](member-business-alignment/stages/03-catalog.md) |
| 4 | 建立收费与押金流程 | 2 | [收费与押金](member-business-alignment/stages/04-payments.md) |
| 5 | 接通套餐规则及借还订单 | 2、3、4 | [借还流程](member-business-alignment/stages/05-circulation.md) |
| 6 | 完善历史衔接与经营统计 | 5 | [历史与报表](member-business-alignment/stages/06-reporting.md) |
| 7 | 原读者登录与自助服务，已退出范围 | — | [原读者服务计划](member-business-alignment/stages/07-reader-services.md) |

2026-10-22按大阶段整理时，原12份子计划和原PLAN完整迁入本目录，第二阶段计划迁入独立目录；同步修正公开文档链接。正文中的历史结论、任务编号和原勾选状态保留。
