# 当前工作

本文件只保存活动计划的执行游标。总体路线由 [PROJECT](PROJECT.md)维护，完整任务状态由对应阶段维护。

## 执行游标

| 项目 | 当前值 |
| --- | --- |
| 活动计划 | [会员业务整合](docs/plans/member-business-alignment/README.md) |
| 下一工作项 | 已按用户要求在B35完成后暂停；恢复需用户指示，B34、B36及后续阶段不自动接续 |
| 工作项状态 | B35实现、完整验证及线上验收完成；0ed85b7/V13生效，后端504/前端287全部通过，两次最新完整V12新备隔离升级及GET-only/浏览器验收通过；未自动采用真实来源值 |
| 阶段定义 | [阶段 3](docs/plans/member-business-alignment/stages/03-catalog.md)，[阶段 0 剩余规则](docs/plans/member-business-alignment/stages/00-baseline.md) |
| 工作分支 | `codex/member-business-batch1`；文档整理与技能接入已保留并提交 |
| 应用代码基线 | 已验收生产 `0ed85b7c05de85b280fa423ec7239af325a36a64`，V1–V13；已推main及工作分支，保留全部既有文档/技能和业务成果 |
| 完整 Verify | 生产应用0ed85b7的[38941033076](https://github.com/wulala123123/library-management-system/actions/runs/38941033076)全部成功：后端504/前端287无失败或跳过；产物12063312587及内外包已核验 |
| 最近维护结果 | [盘点](docs/archive/2026-10/catalog-stocktake-2026-10-16.md)；[结构化库位](docs/archive/2026-10/catalog-structured-locations-2026-10-16.md)；[逐册状态](docs/archive/2026-10/catalog-copy-actions-2026-10-16.md)；此前入库/会员/来源登记及第一批保留 |
| 恢复依据 | [B35验收档案](docs/archive/2026-10/catalog-identities-2026-10-17.md)及私有`logs/catalog-stage3/b35-manager/manager-final-acceptance.json`；专属进程已退出、隔离资源已清理，不重跑已消费发布入口 |

## 下一步

1. B22 七户登记已完成，保留首次预览、V7 新备及隔离复验证据，禁止重跑整批导入或会员完整保存。
2. 入库、逐册动作及结构化库位已上线验收，不重复发布或旧修正；`logs/catalog-stage3/stocktake-manager/manager-final-acceptance.json`保存本批结果，执行进程已退出。
3. B33-A和B35均已上线；B35仅MANUAL核实身份、多主题、维度和别名，SOURCE模式关闭；生产未自动采用来源值，未经核实不采用值或自动合并。
4. 当前暂停，无在途任务。用户恢复后先核现场，再选择B34套装关联或B36出版信息/简介补录；收费、扣次和套餐拦截仍按后续依赖推进。

## 有效执行者

经理为当前任务。用户授权当前任务内委派和满足门槛后的生产更新，不创建独立长期会话或自动化。经理模型与强度工具不可核实；B35后端/前端继承当前会话配置，工具未提供实际模型核实；发布准备显式按技能配置为 `gpt-6.1-sol` / `xhigh`。

B32后端/前端及发布准备均已交付，经理完成返工、验收和发布；专用数据库、服务和发布执行进程已退出。原证据保持只读，后续按新边界复用。

| 有效任务 | 执行者 | 唯一可写边界 | 交付与下一动作 |
| --- | --- | --- | --- |
| B33/B35设计 / v1 | 已交付，经理接纳 | `logs/catalog-stage3/catalog-metadata-design/design.md`只读 | SHA4075013c；先核实BOOK实际来源键/值域/同书多册冲突，不自动套用年龄/出版社/套装含义 |
| BOOK来源工具 / v1.1 | 已交付，经理接纳 | `logs/catalog-stage3/catalog-metadata-source/`冻结 | 71文件哈希、43离线及27真实MySQL合成检查通过；进程退出、33079释放 |
| BOOK实际来源核对 | 已完成，经理验收 | `logs/catalog-stage3/catalog-metadata-manager/`来源证据冻结 | inventory及六字段compare只读成功，原文私有；未采用值、客户端已退出 |
| B33-A契约 | 已交付，经理接纳 | `logs/catalog-stage3/catalog-metadata-design/contract-b33a.md`冻结 | SHAb8647cde；单书目V12四表、新DTO及旧title共同revision |
| B33-A后端 / v1 | 已上线，经理验收 | 生产a3ba1fd；源码与V12冻结 | Verify后端461项、0失败，V12与旧表保值、非空持久化通过 |
| B33-A前端 / v1 | 已上线，经理验收 | 生产a3ba1fd；浏览器只读通过 | Verify前端242项、0失败，AGE ANY/UNKNOWN、展示名和CSV通过 |
| B33-A发布工具 / v1.2 | 已交付，经理接纳冻结 | `logs/catalog-stage3/catalog-attributes-release/`及public容器新增段冻结 | 1036文件hash、36离线+13受影响检查通过；实际DDL/API已审定 |
| 实际DDL补证 | 已交付，经理验收 | 后端证据`metadata-export-v1/`冻结，33079释放 | 实际8结果集、50→54表，旧定义不变；自有进程退出 |
| B33-A发布 | 已完成，经理验收 | `logs/catalog-stage3/catalog-attributes-manager/release-v2/`冻结 | 两轮最新V11新备隔离升级、部署、GET-only verify通过；生产业务采用仍0 |
| B35后端 / v2.1 | 已上线，经理验收 | 生产0ed85b7；证据`logs/catalog-stage3/b35-backend/` | 21文件及7报告hash已核；完整CI及排序ABA补测通过，MANUAL启用、SOURCE拒绝 |
| B35前端 / v2.2 | 已上线，经理验收 | 生产0ed85b7；证据`logs/catalog-stage3/b35-frontend/` | 真实JSON、跨页主题、顺序规范化和原key恢复通过；两处尾部空行整理后最终CI及线上浏览器验收通过 |
| B35发布准备 / v1.2 | 已交付，经理接纳冻结 | `logs/catalog-stage3/b35-release/`及公共容器脚本冻结 | gpt-6.1-sol/xhigh；经理32项离线复验、实际DDL/API及最终六项绑定通过，目标CI与产物已核验 |
| B35发布 | 已完成，经理验收 | `logs/catalog-stage3/b35-manager/`最终证据只读 | 两轮最新完整V12新备隔离升级、54旧表保值、最后启动字节、GET-only及浏览器验收通过；进程退出并暂停 |

B22、查询和统一入库均已交付验收上线，证据见对应档案，原实现及测试服务均已退出。后续不重复派发已完成任务。经理独占公共文档和共享提交/发布；同一可写范围仅一执行者。收费、扣次及套餐拦截继续留待后续阶段。

## 已确认与未决

已确认规则维护在[会员规范](docs/specs/membership.md)、[财务规范](docs/specs/finance-reporting.md)：单次与累计上限分开、成功按订单扣一次、到期无宽限禁新借续借、续费起算、退押金条件、管理员核定会费退款及馆员资料权限。

已确认套餐修改仅用于后续办理、现有会员保留快照；新套餐可明确选择不限归还日期；套装核实前按单册条码借还。逐套餐数值、套餐变更差额、成功办理后的撤销/换书及核实后的整套规则仍需决定。卡号暂保守精确唯一并保留历史占用，不自动重用；如要重用需明确规则。未决只阻止相关限制或办理启用，资料、搜索与展示继续。

历史权益核对允许管理员填写历史依据后关联已停用的保留套餐版本；后续新办理只选当前启用版本。新服务期开始日计第 1 天，原实际日期不重算。

逐册状态、文本/未分配/结构化库位及统一入库/成功新码贴标已启用，生效规则见[图书和借还](docs/specs/catalog-circulation.md)。旧占用不自动迁册。收费、扣次及套餐数值限制仍按后续阶段启用。

B32有限范围盘点及角色边界已启用，生效规则见同一领域规范；更大范围/多人/离线协作另批。B33-A已上线展示名、年龄原标签、装帧、来源采用/手工维护/筛选；真实来源值仍未自动采用，须由管理员逐书预览确认。精确年龄数值范围仍另行确认。

2026-10-17用户确认B35保留原登记分类、另设可多选主题分类及年龄独立；现已按此上线。管理员逐书手工预览确认身份、维度和别名，SOURCE仍拒绝，不自动合并；生效规则见图书规范。按用户要求完成并验收后暂停，不自动接续其他任务。

## 证据与恢复边界

会员阶段私有证据：`logs/member-business-stage2/`；本批为`logs/catalog-stage3/b35-manager/`、`b35-release/`及档案链接的交付目录。盘点及此前证据保持只读。第一批原记录在 `logs/member-business-batch1/`、`logs/member-business-batch1-null-contract/` 及服务器同名 `migrations/` 私有目录。

第一批真实修正已经完成，后续不得重跑整批导入或用旧备份覆盖新业务。首次结果保留；最终代码更新只升级应用。收尾直接 Compose 必须设置 `APP_VERSION`，最后启动后再次核对镜像和字节。

浏览器验收接入用户已登录的 Codex 内置浏览器，已通过第一批不限、空值、统计与 CSV 下载检查；不要再要求用户到 Edge 重复登录。

会员、查询、入库、逐册动作及结构化库位原验收证据保留。盘点`18ecdb6`已通过两次最新完整V10新备隔离恢复升级、最后启动字节及独立只读复核；WORK为`/opt/library-management-system/migrations/catalog-stage3-stocktake`，成功证据与started保留，不重跑旧入口。Codex浏览器`/stocktakes`，线上验收无业务写入。新候选不得复用旧备/旧产物，不自动回旧V10写代码。

## 更新规则

任务切换、阻塞解除或完成交付时更新本文件。较长验收进入原阶段或历史档案，已确认规则更新唯一 owner。
