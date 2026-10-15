# 当前工作

本文件只保存活动计划的执行游标。总体路线由 [PROJECT](PROJECT.md)维护，完整任务状态由对应阶段维护。

## 执行游标

| 项目 | 当前值 |
| --- | --- |
| 活动计划 | [会员业务整合](docs/plans/member-business-alignment/README.md) |
| 下一工作项 | 阶段 3 B30–B32 手工与采购统一实体入库实现及验收 |
| 工作项状态 | 首次Verify239项中1项因旧测试按引用比较二进制数组失败；已定向复现并修正快照helper，4项补验通过，准备新提交完整重验；发布工具39项离线通过；生产仍为 `88dc996`，V8尚未上线 |
| 阶段定义 | [阶段 3](docs/plans/member-business-alignment/stages/03-catalog.md)，[阶段 0 剩余规则](docs/plans/member-business-alignment/stages/00-baseline.md) |
| 工作分支 | `codex/member-business-batch1`；文档整理与技能接入已保留并提交 |
| 应用代码基线 | 已验收生产 `88dc996`，V1–V7；新入库候选 `f93c2dbeafead9152118d1d5660079c16d72d847` 含 V8，已推工作分支及main，尚未发布 |
| 完整 Verify | 新候选 [38774595681](https://github.com/wulala123123/library-management-system/actions/runs/38774595681) 运行中；旧线上 [38752876666](https://github.com/wulala123123/library-management-system/actions/runs/38752876666) 保留 |
| 最近维护结果 | [逐册查询验收及上线](docs/archive/2026-10/catalog-query-2026-10-15.md)；[套餐及来源登记](docs/archive/2026-10/member-entitlements-2026-10-15.md)；[第一批](docs/archive/2026-10/member-business-batch1-2026-10-15.md)保留 |
| 恢复依据 | `git status --short`、当前阶段勾选、下述有效执行者与私有证据 |

## 下一步

1. B22 七户登记已完成，保留首次预览、V7 新备及隔离复验证据，禁止重跑整批导入或会员完整保存。
2. 实现与补验已验收，保存 `logs/catalog-stage3/intake-manager-acceptance.json`；提交目标并运行完整 Verify，使用其独立产物。
3. 已审 V8/精确DDL契约，绑定CI产物后执行两次最新全备隔离升级及冻结回退策略发布；逐套餐数值未核实不启用收费或限制。

## 有效执行者

经理为当前任务。用户授权当前任务内委派和满足门槛后的生产更新，不创建独立长期会话或自动化。经理模型与强度工具不可核实；子代理显式配置按技能默认 `gpt-6.1-sol` / `xhigh`。

| 有效任务 | 执行者 | 唯一可写边界 | 交付与下一动作 |
| --- | --- | --- | --- |
| 阶段 3 入库后端 / v1，验收通过 | `/root/mapping_correction` | backend/ 内 V8、统一 Intake 模块、Book/Purchase/Normalize 旧旁路和必要测试 | 原90项及补验4项通过，候选 `2e85d580`；干净DDL经理已审；`handoff-supplement.md`，target/33079已释放，无运行进程 |
| 阶段 3 入库前端 / v1，定向验收通过 | `/root/statistics_backend` | frontend/ 入库 API、book/purchase 预览确认/回执/贴标及必要依赖/测试，查询 placeholder 小修 | `acceptance.json` 与 `source-manifest.json`（清单摘要 `7f0224ec`）；日期/可空数值返工15项及build通过，其他37项和合成浏览器证据复用；经理已审，实际集成及完整CI待验证；无运行服务 |
| 阶段 3 入库发布准备 / v1，验收通过 | `/root/intake_release_prepare` | 仅 `logs/catalog-stage3/intake-release/`；无网络、服务器或数据库操作 | 39项离线通过，工具 `15e962fe` 无损区分空串与nil；已审DDL契约 `1c4cbe5d`；最终CI产物待绑定，实际恢复/发布未执行 |

B22 登记与查询实现已交付验收；查询发布工具 v2 已验收，实际发布由经理执行。原前端代理已中止。后续不重复派发已完成任务。经理独占公共文档及 `scripts/container_smoke.py` 的新入库验收适配；后端 owner 同时处理精确条码参数的最小局部过滤修正及安全回归。入库设计已验收；同一可写范围仅一执行者。会员资料与来源登记已上线，收费、扣次及套餐拦截继续留待后续阶段。

## 已确认与未决

已确认规则维护在[会员规范](docs/specs/membership.md)、[财务规范](docs/specs/finance-reporting.md)：单次与累计上限分开、成功按订单扣一次、到期无宽限禁新借续借、续费起算、退押金条件、管理员核定会费退款及馆员资料权限。

已确认套餐修改仅用于后续办理、现有会员保留快照；新套餐可明确选择不限归还日期；套装核实前按单册条码借还。逐套餐数值、套餐变更差额、成功办理后的撤销/换书及核实后的整套规则仍需决定。卡号暂保守精确唯一并保留历史占用，不自动重用；如要重用需明确规则。未决只阻止相关限制或办理启用，资料、搜索与展示继续。

历史权益核对允许管理员填写历史依据后关联已停用的保留套餐版本；后续新办理只选当前启用版本。新服务期开始日计第 1 天，原实际日期不重算。

逐册状态规则已确认：临时停用计总库存但不可借；正式报损扣总库存，恢复需独立登记审计，已借册先归还。实现前仍按现行口径查询，不提前启用状态写入。新增册优先使用已贴条码，无条码时生成独立馆内码，预览确认后贴标；入库能力实现验证后启用。

## 证据与恢复边界

新阶段私有证据：`logs/member-business-stage2/`。第一批原记录在 `logs/member-business-batch1/`、`logs/member-business-batch1-null-contract/` 及服务器同名 `migrations/` 私有目录。

第一批真实修正已经完成，后续不得重跑整批导入或用旧备份覆盖新业务。首次结果保留；最终代码更新只升级应用。收尾直接 Compose 必须设置 `APP_VERSION`，最后启动后再次核对镜像和字节。

浏览器验收接入用户已登录的 Codex 内置浏览器，已通过第一批不限、空值、统计与 CSV 下载检查；不要再要求用户到 Edge 重复登录。

会员完整范围已完成 `231201c` 发布，V6/V7 保留全部旧业务列；两次恢复升级、最后启动字节及独立只读 API 核对通过。Codex 浏览器验收通过且未保存资料，当前保留 `/members`。新证据在 `logs/member-business-stage2-complete/`。后续查询 `88dc996` 亦完成两次最新全备、全 V7 行/结构保留及字节/只读验收，依据 `logs/catalog-stage3/query-manager-v2/`；当前浏览器 `/book-copies`。新入库不得复用旧备份或旧产物。

## 更新规则

任务切换、阻塞解除或完成交付时更新本文件。较长验收进入原阶段或历史档案，已确认规则更新唯一 owner。
