# 当前工作

本文件只保存活动计划的执行游标。总体路线由 [PROJECT](PROJECT.md)维护，完整任务状态由对应阶段维护。

## 执行游标

| 项目 | 当前值 |
| --- | --- |
| 活动计划 | [会员业务整合](docs/plans/member-business-alignment/README.md) |
| 下一工作项 | 审查历史完整V15响应作为兼容expected的严格等价政策，再准备全新发布入口 |
| 工作项状态 | v7原V15基线第2264项409失败保持；原V15已按全新入口严格恢复，三服务/公网94静态/浏览器正常，未升级V16；新政策尚未接纳 |
| 阶段定义 | [阶段 4](docs/plans/member-business-alignment/stages/04-payments.md)，[阶段 0 剩余规则](docs/plans/member-business-alignment/stages/00-baseline.md) |
| 工作分支 | `codex/member-business-batch1`；文档整理与技能接入已保留并提交 |
| 应用代码基线 | 生产V15/`b80bef7001d8d3ee30ee0dbcf891897e9b61b783`正常；B40-A原候选`046b6fa`冻结未上线，新候选`2a8fdc7abf14e2e181045c79a0ac0179f69e1d71`待验收 |
| 完整 Verify | 新候选[39185737165](https://github.com/wulala123123/library-management-system/actions/runs/39185737165)成功711/579，新两组回归各30且无跳过；五轮非空财务及全部旧容器门槛通过，准确产物12157804436已核 |
| 最近维护结果 | [套装目录](docs/archive/2026-10/catalog-sets-2026-10-19.md)；此前出版资料/出版社/属性/盘点/逐册/会员及第一批成果保留 |
| 恢复依据 | B40-A由下表及`manager/recovery-current-session.json`定位；[V15恢复档案](docs/archive/2026-10/preupgrade-service-recovery-2026-10-19.md)已验收，v5/v6及恢复入口已消费，不重跑 |

## 下一步

1. B22 七户登记已完成，保留首次预览、V7 新备及隔离复验证据，禁止重跑整批导入或会员完整保存。
2. 入库、逐册动作及结构化库位已上线验收，不重复发布或旧修正；`logs/catalog-stage3/stocktake-manager/manager-final-acceptance.json`保存本批结果，执行进程已退出。
3. B33-A和B35均已上线；B35仅MANUAL核实身份、多主题、维度和别名，SOURCE模式关闭；生产未自动采用来源值，未经核实不采用值或自动合并。
4. B36出版补录已上线，规则和全部验收见[当次记录](docs/archive/2026-10/catalog-publication-2026-10-18.md)，专属隔离资源已清理；保留首次失败与修正证据，不再消费旧入口。
5. B34已完整闭环，生产V15正常；v1/v2失败及v3成功证据均冻结。实体配套及整套收费/扣次仍待后续，不复用旧备或已消费发布入口。
6. B40-A仅管理员三个GET和台账/来源页面；V16六表仅新增不回填。实现/实际响应及完整CI已通过；经理另核36结构证据、79340原单元格、19类元数据及719旧列保持，绑定最终CI JAR、DDL/API/源码清单；尚未上线。

## 有效执行者

经理为当前任务。用户授权当前任务内委派和满足门槛后的生产更新，不创建独立长期会话或自动化。经理模型与强度工具不可核实；本批子代理按技能默认显式使用`gpt-6.1-sol`/`xhigh`。

B32后端/前端及发布准备均已交付，经理完成返工、验收和发布；专用数据库、服务和发布执行进程已退出。原证据保持只读，后续按新边界复用。

| 有效任务 | 执行者 | 唯一可写边界 | 交付与下一动作 |
| --- | --- | --- | --- |
| B33/B35来源、契约及原发布 | 已交付/验收上线，全部冻结 | 对应档案及`logs/catalog-stage3/`原目录只读 | B35最近完整CI504/287、两轮恢复和线上验收通过；不重复旧派工和修正 |
| B36全批 | 经理接纳/已上线，原返工任务已取消 | 原`publication-*`、`b36-*`证据冻结 | 完整CI554/327、两次新备隔离升级及最终只读/浏览器通过；所有本地发布进程退出，不重跑 |
| B34全批 | 经理接纳/已上线，全批冻结 | `logs/catalog-stage3/b34-*`及对应档案只读 | 完整CI622/410、两新备隔离/生产独立GET/浏览器/最终备份及资源复核通过，`b34-manager/manager-final-acceptance.json` |
| B40设计 / v1.1a | 经理接纳，设计及补证冻结 | `logs/member-business-stage4/ledger-design/`只读 | 正式引擎109定义/100例/13端点通过；NUMBER只作数据库投影的实际MySQL反例已经理澄清，结构Schema不变、原v1.1封存 |
| B40-A后端 / v1 | 经理接纳，原子代理停写 | `backend/src/`及`logs/member-business-stage4/b40-read-backend/`冻结 | 18源码/5绑定/26证据hash、80定向、26响应Schema及实际V15→V16/70旧表保值已核；`manager/backend-v1-acceptance.json`及计数澄清 |
| B40-A前端 / v1.1 | 经理接纳，原子代理停写 | `frontend/src/`及`logs/member-business-stage4/b40-read-frontend/`冻结 | 14源码/6绑定/358证据hash、169定向/类型构建、浏览器及26实际响应衔接通过；仅显式全串匹配加固，原疑虑未复现 |
| B40-A容器门槛 / v1 | 经理接纳，原子代理停写 | `scripts/container-acceptance.sh`及`logs/member-business-stage4/b40-container-gate/`冻结 | 五处增量及旧字节保持；定向和完整CI五轮真实非空生命周期通过 |
| B40-A实际metadata / v1 | 经理接纳，原交付冻结 | `logs/member-business-stage4/b40-metadata-export/`只读 | MySQL8.0.44实际70→76表/719→805列，仅六表86列29CHECK17FK；最终六绑定为`manager/final-input-bindings-v1.2.json`，仅私有合同措辞修准，应用未变 |
| B40-A发布设计 / v1 | 经理接纳，原交付冻结 | `logs/member-business-stage4/ledger-release-preparation/`只读 | 30离线/35引用及最终六绑定/原CI包通过；仅设计与拒绝骨架，不可发布 |
| B40-A发布编排 / v5 | 失败终态，全部冻结 | `logs/member-business-stage4/b40-runtime-release-v5/`只读 | 实际合成已通过；第一新备V15的2316项B34冻结与旧链通过，V16第2266项reviews返回CATALOG_BUSY失败；`manager/failure-v5-dc936e802c2b45bfa38d600648a3e0d0/`保存原响应 |
| B40-A验收复用评估 / v1 | 经理接纳，原代理停写 | `logs/member-business-stage4/b40-validation-reuse-review/`只读 | 六文件/30输入核对；缺少跨备等价及运行条件证明，沿用v5完整门槛、不重跑已通过CI；`manager/validation-reuse-review-acceptance.json` |
| B40-A来源查询修复 / v1 | 经理接纳，执行者及验收代理已停写 | 原修复与`b40-source-query-review/`只读 | 已核3源码/测试、34证据和5输入；语义/15秒超时/原断言保持；合成单次计时不能证明原409根因，新CI及两新备必须覆盖第2266项完整GET |
| B40-A升级前恢复 / v1 | 经理已验收完成，全部冻结 | 原交付、`b40-preupgrade-recovery-review/`及服务器专用operator只读 | 16离线/10封版检查及实际完整保值通过；准确原容器一次启动后再核70表/19类/next-ID、字节和公网资源，三服务健康，未迁移/回灌；`manager/preupgrade-v15-final-acceptance.json` |
| B40-A新发布编排 / v6 | 合成失败终态，全批冻结 | `b40-runtime-release-v6/`只读，原入口不重跑 | 59文件上传通过；V15原种子guard要求固定18089及.89地址，v6的新地址被拒，尚未执行原B34种子；独立核三项目验空、生产原ID/配置/健康及其他服务保持，`manager/runtime-v6-failure-independent-empty.json` |
| B40-A新发布编排 / v7 | 第一新备失败终态，原入口冻结 | 原准备/绑定/运行证据只读 | 新合成已验收；原V15基线第2264项reviews返回409/CATALOG_BUSY，未生成frozen或升级V16；三个项目独立验空，`manager/v7-failure-probe-1b4613f8747f49799985b1170db546c5/`及`manager/collected-v7-d21c3b45b29b4a239979e7e0a47ba771/` |
| B40-A v7升级前恢复 | 经理已接纳完成，原代理停写 | `b40-preupgrade-recovery-v7/`及新operator全部冻结 | 16准确离线/21范围/10封版及实际完整check通过；一次原容器启动后核70/19/next-ID/env/uploads/其他服务、94公网静态和浏览器，`manager/preupgrade-v7-final-acceptance.json`；原v1/v7恢复均不可重跑 |
| B40-A旧V15基线等价评估 | `/root/release_v6_prepare`沿用配置 | 仅新`b40-v15-baseline-equivalence-review/` | 只审历史完整V15响应作为expected的条件；两新备/完整保值及候选全部GET保持，尚未接纳新政策或准备发布入口 |
| B40-A生产只读诊断 | `/root/b34_backend`沿用配置 | 仅`logs/member-business-stage4/b40-live-v15-diagnosis/`；原oracle冻结 | 已接纳4文件/16输入及独立988/27页核对，原200正确、非来源预算；v1.1补齐PIPE并经Linux定向核验；实际一次原GET返回200/37条及新前后RR保持，原409保留、后179项未测；`manager/live-v15-single-case-actual-acceptance.json` |
| B40-A旧B34 oracle / v1.2 | 经理接纳，原代理停写 | `logs/member-business-stage4/b40-runtime-b34-v2/`只读，原v1.1冻结 | 19检查91反例、10交付/17只读输入及单函数差异已核；有效馆员safe成功/缺失404，private/owner拒绝保持；`manager/b34-oracle-v1.2-acceptance.json`，待v4实际 |
| B40-A实际导出 / v1.2 | 经理，已冻结 | `logs/member-business-stage4/b40-runtime-export/`只读 | 19类/70表原列hash及下一主键值保留，12定向通过，旧版本原字节封存；实际隔离接入待补 |
| B40-A旧B34 oracle / v1.1 | 经理接纳，原代理停写 | `logs/member-business-stage4/b40-runtime-b34/`只读 | 22证据/15测试81反例及原v1字节封存通过；全部现有账号入GET、缺角色明确报告；`manager/b34-oracle-v1.1-acceptance.json` |
| B40-A财务 oracle / v1 | 经理接纳准备，原代理停写 | `logs/member-business-stage4/b40-runtime-finance/`只读 | 334证据/33定向/26原实际响应通过；精确cp313引擎包已核，实际Linux/SQL/HTTP待集成；`manager/finance-oracle-v1-acceptance.json` |
| B40-A合成接入 / v1 | 经理接纳，原代理停写 | `logs/member-business-stage4/b40-runtime-synthetic/`只读 | 10证据/10定向49反例通过，准确两原包重新提取已核；实际全新合成库执行待编排，不重跑完整CI或在真实恢复库造样例 |

B22、查询和统一入库均已交付验收上线，证据见对应档案，原实现及测试服务均已退出。后续不重复派发已完成任务。经理独占公共文档和共享提交/发布；同一可写范围仅一执行者。收费、扣次及套餐拦截继续留待后续阶段。

## 已确认与未决

已确认规则维护在[会员规范](docs/specs/membership.md)、[财务规范](docs/specs/finance-reporting.md)：单次与累计上限分开、成功按订单扣一次、到期无宽限禁新借续借、续费起算、退押金条件、管理员核定会费退款及馆员资料权限。

2026-10-18用户确认会费退款只影响财务台账；服务期或次数如需变化，必须另行办理并审计。阶段4尚未实施，不据此改动历史权益或金额。

2026-10-19金额及支付规则已确认，见财务规范：柜台仅现金、微信；暂不开放期初登记，继续只读核对。原对话已有答复，不重复询问。欠费权威尚未明确，只阻止押金实退等相关动作，不阻止独立只读台账与隔离验证。

已确认套餐修改仅用于后续办理、现有会员保留快照；新套餐可明确选择不限归还日期；套装核实前按单册条码借还。逐套餐数值、套餐变更差额、成功办理后的撤销/换书及核实后的整套规则仍需决定。卡号暂保守精确唯一并保留历史占用，不自动重用；如要重用需明确规则。未决只阻止相关限制或办理启用，资料、搜索与展示继续。

历史权益核对允许管理员填写历史依据后关联已停用的保留套餐版本；后续新办理只选当前启用版本。新服务期开始日计第 1 天，原实际日期不重算。

逐册状态、文本/未分配/结构化库位及统一入库/成功新码贴标已启用，生效规则见[图书和借还](docs/specs/catalog-circulation.md)。旧占用不自动迁册。收费、扣次及套餐数值限制仍按后续阶段启用。

B32有限范围盘点及角色边界已启用，生效规则见同一领域规范；更大范围/多人/离线协作另批。B33-A已上线展示名、年龄原标签、装帧、来源采用/手工维护/筛选；真实来源值仍未自动采用，须由管理员逐书预览确认。精确年龄数值范围仍另行确认。

2026-10-17用户确认B35保留原登记分类、另设可多选主题分类及年龄独立；现已按此上线。管理员逐书手工预览确认身份、维度和别名，SOURCE仍拒绝，不自动合并；生效规则见图书规范。该批完成后曾暂停，2026-10-18已按用户要求恢复。

2026-10-18用户确认B34先建立套装书目与成员书目关系，具体哪几册组成一份实物套装以后核实；同一书目有明确资料证明分别收录时允许关联多个套装，每条关系填写依据。仍按单册借还，不启用整套收费、扣次或限额。本批B36不依赖此关系。

## 证据与恢复边界

会员阶段私有证据：`logs/member-business-stage2/`；B36为`logs/catalog-stage3/publication-manager/`、`publication-release/`及档案链接的交付目录；当前B34由上表定位。盘点及此前证据保持只读。第一批原记录在 `logs/member-business-batch1/`、`logs/member-business-batch1-null-contract/` 及服务器同名 `migrations/` 私有目录。

第一批真实修正已经完成，后续不得重跑整批导入或用旧备份覆盖新业务。首次结果保留；最终代码更新只升级应用。收尾直接 Compose 必须设置 `APP_VERSION`，最后启动后再次核对镜像和字节。

浏览器验收接入用户已登录的 Codex 内置浏览器，已通过第一批不限、空值、统计与 CSV 下载检查；不要再要求用户到 Edge 重复登录。

会员、查询、入库、逐册动作及结构化库位原验收证据保留。盘点`18ecdb6`已通过两次最新完整V10新备隔离恢复升级、最后启动字节及独立只读复核；WORK为`/opt/library-management-system/migrations/catalog-stage3-stocktake`，成功证据与started保留，不重跑旧入口。Codex浏览器`/stocktakes`，线上验收无业务写入。新候选不得复用旧备/旧产物，不自动回旧V10写代码。

## 更新规则

任务切换、阻塞解除或完成交付时更新本文件。较长验收进入原阶段或历史档案，已确认规则更新唯一 owner。
