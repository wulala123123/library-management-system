# 当前工作

本文件只保存活动计划的执行游标。总体路线由 [PROJECT](PROJECT.md)维护，完整任务状态由对应阶段维护。

## 执行游标

| 项目 | 当前值 |
| --- | --- |
| 活动计划 | [会员业务整合](docs/plans/member-business-alignment/README.md) |
| 下一工作项 | 阶段3 B36完整Verify、两次最新全备隔离恢复升级及发布验收，随后接B32盘点；B33–B35资料任务保留 |
| 工作项状态 | B36实现/定向验收通过；首候选31bb8b6的Verify38845930076仅因新测试固定本机端口失败，单文件修正已验收，重跑完整Verify；生产仍bedaf62 |
| 阶段定义 | [阶段 3](docs/plans/member-business-alignment/stages/03-catalog.md)，[阶段 0 剩余规则](docs/plans/member-business-alignment/stages/00-baseline.md) |
| 工作分支 | `codex/member-business-batch1`；文档整理与技能接入已保留并提交 |
| 应用代码基线 | 已验收生产 `bedaf62c1d294ad33e3522fa779081b56b2b5605`，V1–V9；已推main及工作分支，保留全部既有文档/技能和业务成果 |
| 完整 Verify | [38836726543](https://github.com/wulala123123/library-management-system/actions/runs/38836726543) 全部成功：后端278项、前端152项；产物12019931692及内外包已核验。首次38835810914失败与修正保留 |
| 最近维护结果 | [逐册状态与文本位置](docs/archive/2026-10/catalog-copy-actions-2026-10-16.md)；[统一实体入库](docs/archive/2026-10/catalog-intake-2026-10-16.md)；此前会员/来源登记及第一批保留 |
| 恢复依据 | `git status --short`、当前阶段、下述交付与`logs/catalog-stage3/structured-locations-manager/`；经理执行完整CI与发布，子代理无在途进程 |

## 下一步

1. B22 七户登记已完成，保留首次预览、V7 新备及隔离复验证据，禁止重跑整批导入或会员完整保存。
2. 入库及逐册动作已上线验收，不重复发布或执行旧修正；私有 `logs/catalog-stage3/copy-actions-manager/manager-final-acceptance.json` 保存本批结果，所有执行进程已退出。
3. B36候选已接纳：用目标完整Verify产物、已审V10/DDL/GET执行本批两次最新V9备份隔离恢复升级及发布核对，不能复用上一批备份/started。B32设计v2.1与权限已确认，待本批上线后实施；旧占用保留，不自动迁册或以盘点观察覆盖库存。

## 有效执行者

经理为当前任务。用户授权当前任务内委派和满足门槛后的生产更新，不创建独立长期会话或自动化。经理模型与强度工具不可核实；子代理显式配置按技能默认 `gpt-6.1-sol` / `xhigh`。

V9原三位执行者均已交付并完成发布；专用数据库、服务和进程已退出。B36设计已验收，无新增业务阻塞；复用原代理按以下新任务边界执行。

| 有效任务 | 执行者 | 唯一可写边界 | 交付与下一动作 |
| --- | --- | --- | --- |
| B36后端及HTTP补验 / v1 | 已交付，经理接纳 | `logs/catalog-stage3/structured-locations-backend/` | 33文件/108项及干净41→43表DDL通过；固定JAR实际HTTP初始化/回执核对及GET样例通过；本地服务已退出 |
| B36 CI端口修正 / v1 | 已交付，经理接纳 | `logs/catalog-stage3/structured-locations-backend/ci-port-repair/` | 13项Java正则校验通过，兼容CI3306与本机33079、拒其他库名；仅测试一处正则变化，业务/迁移保持 |
| B36发布准备 / v1 | 已交付，经理接纳工具准备 | `logs/catalog-stage3/structured-locations-release/` | 61项离线检查/148次预期拒绝通过；待后端实际元数据、最终GET及完整CI产物，未执行服务器操作 |
| B36前端 / v1.1 | 已交付，经理接纳 | `logs/catalog-stage3/structured-locations-frontend/acceptance-v1.1.json` | 32文件，原72项/构建及修订20项/构建通过；NULL保留与不可用地址筛选已复验；原失败证据保留 |
| B32盘点设计 / v2.1 | 已交付，经理接纳 | `logs/catalog-stage3/stocktake-design/design.md` | 定时逾期写者、borrow_record行锁/NOWAIT及确认权限已固定，仍依赖B36最终验收；未实施 |

B22、查询和统一入库均已交付验收上线，证据见对应档案，原实现及测试服务均已退出。后续不重复派发已完成任务。经理独占公共文档和共享提交/发布；同一可写范围仅一执行者。收费、扣次及套餐拦截继续留待后续阶段。

## 已确认与未决

已确认规则维护在[会员规范](docs/specs/membership.md)、[财务规范](docs/specs/finance-reporting.md)：单次与累计上限分开、成功按订单扣一次、到期无宽限禁新借续借、续费起算、退押金条件、管理员核定会费退款及馆员资料权限。

已确认套餐修改仅用于后续办理、现有会员保留快照；新套餐可明确选择不限归还日期；套装核实前按单册条码借还。逐套餐数值、套餐变更差额、成功办理后的撤销/换书及核实后的整套规则仍需决定。卡号暂保守精确唯一并保留历史占用，不自动重用；如要重用需明确规则。未决只阻止相关限制或办理启用，资料、搜索与展示继续。

历史权益核对允许管理员填写历史依据后关联已停用的保留套餐版本；后续新办理只选当前启用版本。新服务期开始日计第 1 天，原实际日期不重算。

逐册状态与文本位置已启用，生效规则见[图书和借还](docs/specs/catalog-circulation.md)。结构化库位已确认是多册地址，暂不强制容量上限，旧占用不自动迁册；关联功能尚未启用。统一入库及成功回执新码贴标已启用。收费、扣次及套餐数值限制仍按后续阶段启用。

B32盘点差异处理及期间继续借还已确认，规则见同一领域规范；当前仅登记决定，待结构化库位批次后实施。
盘点权限也已确认：馆员录入/核对本人草稿，管理员最终确认报告及核实差异；与上述规则一并在B32实施后启用。

## 证据与恢复边界

会员阶段私有证据：`logs/member-business-stage2/`；本批为`logs/catalog-stage3/copy-actions-manager/`及当次档案链接的交付目录。第一批原记录在 `logs/member-business-batch1/`、`logs/member-business-batch1-null-contract/` 及服务器同名 `migrations/` 私有目录。

第一批真实修正已经完成，后续不得重跑整批导入或用旧备份覆盖新业务。首次结果保留；最终代码更新只升级应用。收尾直接 Compose 必须设置 `APP_VERSION`，最后启动后再次核对镜像和字节。

浏览器验收接入用户已登录的 Codex 内置浏览器，已通过第一批不限、空值、统计与 CSV 下载检查；不要再要求用户到 Edge 重复登录。

会员`231201c`、查询`88dc996`、入库`24463a4`及逐册动作`bedaf62`均完成各自两次新备恢复升级、最后启动字节及独立只读复核。本批服务器WORK为`/opt/library-management-system/migrations/catalog-stage3-copy-actions`，两次新备、成功结果与started保留，不再执行旧入口。当前Codex浏览器`/book-copies`，页面验收无业务写入。后续不得复用旧备份或旧产物作为新候选验收，不能回旧V8写代码。

## 更新规则

任务切换、阻塞解除或完成交付时更新本文件。较长验收进入原阶段或历史档案，已确认规则更新唯一 owner。
