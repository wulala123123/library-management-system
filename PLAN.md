# 当前工作

本文件只保存活动计划的执行游标。总体路线由 [PROJECT](PROJECT.md)维护，完整任务状态由对应阶段维护。

## 执行游标

| 项目 | 当前值 |
| --- | --- |
| 活动计划 | [会员业务整合](docs/plans/member-business-alignment/README.md) |
| 下一工作项 | 阶段3 B32盘点实施及验收；B33–B35及B36出版资料补录保留 |
| 工作项状态 | ff7c5b6结构化库位已上线验收；B32设计v2.1已接纳、规则和权限已确认，后端v1与前端v1.1实现及定向验证已验收，准备目标完整Verify/隔离恢复；尚未上线 |
| 阶段定义 | [阶段 3](docs/plans/member-business-alignment/stages/03-catalog.md)，[阶段 0 剩余规则](docs/plans/member-business-alignment/stages/00-baseline.md) |
| 工作分支 | `codex/member-business-batch1`；文档整理与技能接入已保留并提交 |
| 应用代码基线 | 已验收生产 `ff7c5b67166859116e43c2dd7e2b71d14949eca1`，V1–V10；已推main及工作分支，保留全部既有文档/技能和业务成果 |
| 完整 Verify | [38846639017](https://github.com/wulala123123/library-management-system/actions/runs/38846639017) 全部成功：后端336项、前端179项；产物12023592735及内外包已核验。两次测试配置/顺序失败证据保留 |
| 最近维护结果 | [结构化库位](docs/archive/2026-10/catalog-structured-locations-2026-10-16.md)；[逐册状态](docs/archive/2026-10/catalog-copy-actions-2026-10-16.md)；此前入库/会员/来源登记及第一批保留 |
| 恢复依据 | `git status --short`、当前阶段、下述有效执行者及私有最终验收；无在途发布进程 |

## 下一步

1. B22 七户登记已完成，保留首次预览、V7 新备及隔离复验证据，禁止重跑整批导入或会员完整保存。
2. 入库、逐册动作及结构化库位已上线验收，不重复发布或旧修正；`logs/catalog-stage3/structured-locations-manager/manager-final-acceptance.json`保存本批结果，执行进程已退出。
3. 从B32设计v2.1恢复，实现及定向验证已验收，进入目标完整Verify及最终发布冻结；观察/差异与库存分开，最终短事务包含借阅行锁/定时逾期竞争，后续修正通过原工作流并关联依据。

## 有效执行者

经理为当前任务。用户授权当前任务内委派和满足门槛后的生产更新，不创建独立长期会话或自动化。经理模型与强度工具不可核实；子代理显式配置按技能默认 `gpt-6.1-sol` / `xhigh`。

B36三位执行者均已交付并完成经理返工、验收及发布；专用数据库、服务和进程已退出。B32设计v2.1已接纳，原私有交付保持只读；后续按新边界复用，不重复派发旧任务。

| 有效任务 | 执行者 | 唯一可写边界 | 交付与下一动作 |
| --- | --- | --- | --- |
| B32设计 / v2.1 | 已交付，经理接纳 | `logs/catalog-stage3/stocktake-design/design.md` | SHA3b278ea1954f9bf5；基线依赖已满足，精确API/V11草稿已接纳，进入实施 |
| B32后端 / v1 | `/root/mapping_correction` | `backend/`及`logs/catalog-stage3/stocktake-backend/`；V1–V10迁移及旧业务持久协议冻结；独占本地合成库33079 | v1已验收，44项定向全执行；本地完整执行211/条件跳过169，43旧表结构/值保留；DB与target已交还 |
| B32前端 / v1 | `/root/statistics_backend` | `frontend/`及`logs/catalog-stage3/stocktake-frontend/`；无DB/后端/Git/生产权限 | v1.1已验收，31项定向用例含范围重开回归；类型构建/合成浏览器/audit通过，待后端集成与完整Verify |
| B32发布准备 / v1 | `/root/intake_release_prepare` | 仅`logs/catalog-stage3/stocktake-release/`；旧工具/源码仅读 | 离线准备及实际DDL候选已验收，冻结最终V11/API并补必要离线检查；无网络/DB/Docker/生产 |

B22、查询和统一入库均已交付验收上线，证据见对应档案，原实现及测试服务均已退出。后续不重复派发已完成任务。经理独占公共文档和共享提交/发布；同一可写范围仅一执行者。收费、扣次及套餐拦截继续留待后续阶段。

## 已确认与未决

已确认规则维护在[会员规范](docs/specs/membership.md)、[财务规范](docs/specs/finance-reporting.md)：单次与累计上限分开、成功按订单扣一次、到期无宽限禁新借续借、续费起算、退押金条件、管理员核定会费退款及馆员资料权限。

已确认套餐修改仅用于后续办理、现有会员保留快照；新套餐可明确选择不限归还日期；套装核实前按单册条码借还。逐套餐数值、套餐变更差额、成功办理后的撤销/换书及核实后的整套规则仍需决定。卡号暂保守精确唯一并保留历史占用，不自动重用；如要重用需明确规则。未决只阻止相关限制或办理启用，资料、搜索与展示继续。

历史权益核对允许管理员填写历史依据后关联已停用的保留套餐版本；后续新办理只选当前启用版本。新服务期开始日计第 1 天，原实际日期不重算。

逐册状态、文本/未分配/结构化库位及统一入库/成功新码贴标已启用，生效规则见[图书和借还](docs/specs/catalog-circulation.md)。旧占用不自动迁册。收费、扣次及套餐数值限制仍按后续阶段启用。

B32盘点差异处理及期间继续借还已确认，规则见同一领域规范；尚未实施。
盘点权限也已确认：馆员录入/核对本人草稿，管理员最终确认报告及核实差异；与上述规则一并在B32实施后启用。

## 证据与恢复边界

会员阶段私有证据：`logs/member-business-stage2/`；本批为`logs/catalog-stage3/structured-locations-manager/`及档案链接的交付目录。第一批原记录在 `logs/member-business-batch1/`、`logs/member-business-batch1-null-contract/` 及服务器同名 `migrations/` 私有目录。

第一批真实修正已经完成，后续不得重跑整批导入或用旧备份覆盖新业务。首次结果保留；最终代码更新只升级应用。收尾直接 Compose 必须设置 `APP_VERSION`，最后启动后再次核对镜像和字节。

浏览器验收接入用户已登录的 Codex 内置浏览器，已通过第一批不限、空值、统计与 CSV 下载检查；不要再要求用户到 Edge 重复登录。

会员`231201c`、查询`88dc996`、入库`24463a4`、逐册动作`bedaf62`及库位`ff7c5b6`均完成各自两次新备恢复升级、最后启动字节及独立只读复核。本批WORK为`/opt/library-management-system/migrations/catalog-stage3-structured-locations`，两次新备、成功结果及started保留，不再执行旧入口。当前Codex浏览器`/book-copies`，页面验收无业务写入。后续不能复用旧备或旧产物验收新候选，不能回V9旧写代码。

## 更新规则

任务切换、阻塞解除或完成交付时更新本文件。较长验收进入原阶段或历史档案，已确认规则更新唯一 owner。
