# 批次4：后端公共逻辑整理

状态：本地已验收，未上线。目标、基线和共用护栏见[第二阶段总计划](README.md)，当前执行位置见[根目录PLAN](../../../../PLAN.md)。

## 范围

先处理CSV转义和公式防护等明确重复逻辑，再按引用与语义依据整理重复校验和投影。已发现候选为[借阅查询CSV](../../../../backend/src/main/java/com/library/borrowquery/BorrowInquiryCsv.java)、[报告CSV](../../../../backend/src/main/java/com/library/managementreport/ManagementReportCsv.java)和[列表CSV](../../../../backend/src/main/java/com/library/managementexport/ManagementCsv.java)。

## 任务

- [x] 对照各CSV原输出，列清BOM、换行、转义、公式防护、NULL、空串、零值及字符串编号规则。
- [x] 提取语义相同的底层处理，保持不同导出的空值策略、元数据和既有格式约定。
- [x] 核对其他明确重复的校验或投影，仅处理本轮有依据且收益清楚的候选。
- [x] 按职责整理必要方法，核对调用关系、权限、事务、锁顺序及持久回执边界。
- [x] 运行对应输出和边界检查，记录实际合并内容及保留差异。

## 验证与完成条件

- 后端编译与受影响的CSV/接口检查通过，覆盖特殊字符、公式前缀、原空值和字符串保真。
- 若实际涉及数据库事务或并发，按[验证规范](../../../development/testing.md)补充合成隔离检查；仅CSV底层整理不机械重跑全部历史业务。
- 不改变已部署迁移、业务事务、锁顺序、幂等、审计或既有响应；不为缩短代码重写安全过滤与业务状态机。
- 合并内容有明确收益，原行为与必要检查保持，即结束本批；复杂服务的大范围重写不自动纳入。

## 交付记录

2026-10-22：新增公共CsvWriter，三个领域render、BOM、UTF-8、列序、元数据及调用者类型转换原文保持，只委托相同的行追加、引号与公式防护。借阅/报告NULL仍为引号空串，列表NULL仍为裸`\N`；字符串`\N`、空串、已知零、编号前导零和尾空格保持。负Number不加公式单引号，原String类型继续防护，不重推金额或JSON类型。

- 三类导出的有记录及空记录共六份合成完整文件（3291/565、1651/920、1104/573字节）新旧逐字节一致。
- Java17编译通过；CsvWriterTest 20、BorrowInquiryControllerTest 32、ManagementReportControllerTest 21、ManagementExportControllerTest 26，共99项H2检查全部通过，0失败/错误/跳过。
- main三CSV及工具共126→121行，7522→6923字节（净减5行/599字节），新增57行边界测试。收益为安全规则仅维护一份；未为删行扩大服务校验或投影合并。
- 18份Flyway哈希不变；POM、控制器、服务、权限、事务、SQL、锁顺序和回执保持。未新建数据库、未运行MySQL或生产入口。
- 最终`-DskipTests package`通过；JAR为51,050,275字节、SHA256 `0276023c16771c2157c78fbdce2377830657ea7dff398496d27e916b684e7d8b`，其相关class与测试候选一致；测试由前述99项提供，打包跳过不冒充测试通过。
- 私有证据在`logs/engineering-cleanup/batch4/`的`delivery-v2.md`、`v2-source-manifest.json`、`v2-verification.json`、`render-comparison.json`及旧/新输出；所有本批进程结束，提交由本批Git记录定位。无本批剩余项，正式发布仍须目标完整Verify。
