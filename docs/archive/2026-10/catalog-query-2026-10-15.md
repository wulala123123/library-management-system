# 书目与实体册查询验收

本页保存 2026 年 10 月 15 日结果。当前状态见 [PLAN](../../../PLAN.md)，业务含义见[图书和借还规范](../../specs/catalog-circulation.md)。

## 版本与实现

- 提交 `88dc9961acc6639b49591aa0b43d4cf2cfd094c4`，完整 [Verify 38752876666](https://github.com/wulala123123/library-management-system/actions/runs/38752876666) 成功，产物 `11982183030`。
- 发布 ZIP SHA256：`d13f76f8ecc52274e3c8840be89ea5efa1cbdb7bebe681fb07e85efe143761f7`。
- 新增两层管理查询、精确馆内条码、状态/位置筛选和必要在借关联；旧 `/books/{id}/copies` 数组契约保留。
- 仅 ADMIN/LIBRARIAN 可访问；读者和匿名拒绝。空册位置保留未分配，原元数据、原状态、NULL、零与不限期分别处理。
- 查询不改变库存、不补造历史册；登记差额只作核对线索，数量一致不能代替逐条关联验收。

## 验证及发布

后端 H2 11、真实 MySQL 1、前端 5 项通过，类型构建和合成浏览器通过。MySQL 验证完整 UTF-8 字节精确匹配，覆盖前导零、大小写、尾空格和非 ASCII；重复 GET 的全业务表快照保持。

首次隔离尝试在合成角色清理时因排序规则冲突失败，已清理，生产未变，也未部署。原错误现场保留；发布工具 v2 修正精确角色比较，保留主错误与清理错误的独立私有诊断。v2 的 36 项离线检查及 6 个真实 MySQL 场景通过，使用全新执行目录、项目和备份重试。

v2 两次最新全备恢复均通过，包含此前完成的七户宝宝和来源审计。V7 全表、全部旧列旧行、索引及约束保留，V1–V7 迁移文件原字节一致，无新迁移。隔离权限样例检查后清理精确所属资源。

正式更新后显式 `APP_VERSION` 最后启动，运行 JAR、全部静态文件及 HTTP 字节与 CI 产物一致；独立只读复核通过。上传、配置及无关服务保留，未回灌旧数据库、未写生产样例。生产没有可用馆员账号，馆员权限由 CI 与隔离样例补证。

Codex 内置浏览器复用原登录，确认导航、书目差额、在借筛选、实际单册在借摘要、空位置和真实条码精确单结果；控制台无错误，未写业务资料。

## 证据和后续

私有证据：`logs/catalog-stage3/query-backend/`、`query-frontend/`、`query-manager-acceptance.json`，以及 `query-manager-v2/` 的 `trial-complete.json`、`final-complete.json`、`deployment-complete.json`、`online-verification.json`、`manager-final-acceptance.json`。服务端对应受保护目录为 `migrations/catalog-stage3-query-v2/`；v1 失败现场继续保留。

本批只完成 B30/B31 的查询子范围。统一手工/采购入库、逐册状态与库位写操作尚未上线，按[阶段 3](phase-1-data-adaptation/member-business-alignment/stages/03-catalog.md)继续推进；套餐收费及借阅限制仍未启用。
