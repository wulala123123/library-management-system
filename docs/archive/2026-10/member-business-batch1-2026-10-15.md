# 会员整合第一批验收

适用范围：B00–B02 及 B10–B14；B03 仅已确认规则登记，不代表收费或会员拦截已经启用。当前恢复位置见 [PLAN](../../../PLAN.md)，后续规范见[领域入口](../../specs/README.md)。

## 候选与发布

| 项目 | 结果 |
| --- | --- |
| 本批前基线 | `9ebf52609415b5d07289c61d5bbbc8d464c0586e` |
| 首次实现 | `e70b33ff104ec37ee65c777376993616d7820ccd`；[完整 Verify 38721357111](https://github.com/wulala123123/library-management-system/actions/runs/38721357111) 通过 |
| 最终应用提交 | `f98539403c1a6d18afcc217de254938ecdbfed2a`；[完整 Verify 38724073975](https://github.com/wulala123123/library-management-system/actions/runs/38724073975) 全部通过 |
| 最终分发产物 | `verified-distribution`，artifact `11968080287` |
| 产物外层 SHA256 | `aab4fea625e43a58eaf8f6a8c4f3b65862fe46c6dc48ba47d44c59868b3763d8` |
| 发布 ZIP SHA256 | `549fb9ceebda9e66db9540e1b703c10752d14a80020a14dcf5c96c47ab3119c7` |
| 生产目标 | `https://library.webuihub.com`，运行镜像标签明确绑定最终版本目录 |

已有文档整理、历史证据和项目经理技能接入成果保留并随候选提交。Flyway V1–V4 未修改，真实会员、宝宝、财务和凭据未进入 Git、CI 或发布包。

## 验收结果

- B00：完整数据库、上传、配置及版本备份通过 SHA256 校验；全新隔离项目恢复，全表内容哈希与上传字节一致，生产数据未变。
- B01–B02：来源清单、批次哈希、会员档案和业务字段关联逐项核对；原套餐设置与实际权益保留。字段分类见[来源字段](phase-1-data-adaptation/member-business-alignment/source-fields.md)。
- B10–B11：只修正来源明确不限、预期旧值匹配的 `total_uses`。正式执行前只读预览、校验备份、停应用；锁定事务提交并核对所有无关记录及字段。重复同预览没有变化；首次结果保留，后续代码更新未重复修正。
- B12–B13：首页、库存、统计和 CSV 的十项指标与数据库独立查询一致；种数、册数、账号、会员及有效期待核实分别显示。实体册登记差异如实提供，权限通过验收。
- B14：缺失卡号、库位、出版日期、页数和简介按未设置、待补充显示，已知零值及前导零保留。次数接口明确输出 NULL，缺失字段不冒充不限。
- 最终后置核对：健康、十项指标、CSV、不限字段、运行 JAR、全部前端 JS/CSS/HTML 字节、其他服务和配置均通过；只读验证未改变业务数据。
- Codex 内置浏览器：已登录首页、库存及原会员权益展示通过；通过可见按钮下载基础 CSV，十项字段、单位、日期和口径通过核对。没有在生产创建合成会员或图书。

本地证据包含后端统计 7 项、空值契约 7 项、前端 34 项及修正工具 13 项定向测试，分别绑定文件哈希。完整 CI 另验证 MySQL 8.0.44、事务与权限、容器更新回退和恢复；两次真实备份隔离演练均通过并清理专属资源。

## 发现与返工

1. 修正 v1 使用 DML 应用账号，无法创建临时保护表且不具备完整触发器可见性。经理退回后，v2 改用既有维护账号并验证权限，应用账号权限未扩大。
2. 首次后置发现全局 NON_NULL 省略 NULL 次数键。原隔离断言用 `get()` 无法区分缺失和 NULL，验收不足。修复仅对该属性明确输出 NULL；实际应用配置下先复现、再通过键存在断言。二次完整 CI 与独立真实快照演练通过后只升级代码。
3. 生产收尾命令曾缺少 `APP_VERSION`，启动旧 `local` 标签。已显式固定到最终 CI 版本，再次核对镜像标签和实际资源字节通过，数据未变。[部署规范](../../operations/deployment.md)要求最后一次启动后复核运行版本。

## 私有证据入口

本地：`logs/member-business-batch1/`、`logs/member-business-batch1-null-contract/`、`logs/b10-b11-unlimited-tests/`。服务器安装根目录下 `migrations/member-business-batch1/` 保存基线、首次修正和恢复依据，`migrations/member-business-batch1-null-contract/` 保存加强后二次隔离及最终线上核对。备份路径只保存在私有记录；不重写原文件，不公开真实数量。

后续进入[阶段 2](phase-1-data-adaptation/member-business-alignment/stages/02-membership.md)的会员资料、搜索和权限。套餐版本、收费、扣次及借阅限制按各自阶段验收后启用。
