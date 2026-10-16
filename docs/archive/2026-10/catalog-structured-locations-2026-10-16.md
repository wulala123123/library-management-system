# 结构化库位上线验收

本页记录2026-10-16当次结果；现行规则见[图书和借还](../../specs/catalog-circulation.md)，当前任务由[PLAN](../../../PLAN.md)维护。

## 候选与范围

已验收生产代码为`ff7c5b67166859116e43c2dd7e2b71d14949eca1`，以`bedaf62`的V9完整库升级到V10。保留此前文档、技能、会员、来源登记、查询、入库和逐册动作成果。

本批实现稳定库位ID与实体册关联、三种有效位置查询/筛选、结构化MOVE、V1/V2独立动作协议，以及书架/层/库位维护的版本、依据、审计和引用保护。同址可放多册并跨书目，不按历史容量禁选；旧书目占用保留待核实，不自动分配册。

REGISTERED保留原文本但以地址为有效位置；显式TEXT/UNASSIGNED才清地址指针。停用、启用和报损保持位置，恢复仍到停用并明确文本或未分配，再独立移位。V8及V1原持久请求、摘要和回执不扩字段；原成功回执优先重放。

## 实现与验证

- 后端33个文件，108项不同定向用例通过；MySQL覆盖非空V8/V9保值、跨升级预览确认、原回执、来源异常迁出、事务回滚及真实双连接竞争。册页固定4次SELECT、地址页2次、旧册数组3次。
- 前端32个文件，首轮72项、经理返工20项定向测试及类型构建通过。返工保留已有书架省略字段的原NULL，并允许不可用地址用于查询筛选；MOVE仍拒绝不可用目标。
- 固定JAR本机HTTP初始化和后续状态检查通过，核对6份V1及7份V2回执；实际context/history/address/结构事件样例精确检查通过。
- [完整Verify 38846639017](https://github.com/wulala123123/library-management-system/actions/runs/38846639017)全部成功：后端336项、前端179项，无失败或跳过；包含导入事务、依赖审计和容器重建、更新、启动失败恢复、备份及隔离恢复。
- 首候选`31bb8b6`的[38845930076](https://github.com/wulala123123/library-management-system/actions/runs/38845930076)因新测试限定本机端口失败；仅修测试URL护栏。`f161586`的[38846348243](https://github.com/wulala123123/library-management-system/actions/runs/38846348243)后端全过，旧导入测试字母排序把V10提前，修三处数字排序后完整重验。业务实现和已部署迁移未因这两项修正改变。

发布产物`12023592735`；外层SHA256为`51bf1cfc09d051b2ec1c77a2f6aee1c61410c14ada56ea30c2e384e5c4bba71f`，内层为`dcc52d7057569eacc4709f850f56ecefc36241ce1cd9c731f644039642606178`。包内V1–V10与源码一致，V1–V9保留原字节。

## 两次新备与上线

专用流程在`/opt/library-management-system/migrations/catalog-stage3-structured-locations`执行，两次当时最新全备分别为：

- `member-business-stage2-catalog-stage3-structured-locations-initial-20261016T035054011991Z`
- `member-business-stage2-catalog-stage3-structured-locations-final-20261016T035758504008Z`

两轮全新隔离恢复均先核对完整V9结构/全部旧值，再升级V10；全部41张旧表的旧列、主键、NULL/空串、JSON、摘要、非零版本及来源均保留。独立审阅的干净结构为41→43表，只增加位置相关列/表及必要约束，唯一旧约束增量为允许动作schema1/2。未重导或重建业务库。

最终备份后保持旧前后端停止，第二轮隔离通过后启动候选；最后实际APP_VERSION、镜像/JAR、全部静态/HTTP字节、GET、uploads/env及无关服务均通过。独立verify成功，隔离资源清理完成，没有待续发布进程。

浏览器沿用户已登录的Codex页面只读核对有效位置筛选、地址选择空态、旧绑定/关联册分栏、单册详情/来源与MOVE三分支；控制台错误0。未提交真实馆藏动作或地址维护。验收后新位置事件、结构事件、V2请求和位置指针均为0；这仅是当时观察，不作后续业务巡检条件。

## 证据与后续

私有经理证据为`logs/catalog-stage3/structured-locations-manager/manager-final-acceptance.json`，包含候选、CI、产物、契约、两次备份、独立核对及浏览器结果。后端、前端、发布准备和盘点设计由PLAN及该目录定位，原失败诊断保留。

B36的结构化位置子范围完成，出版信息及简介补录仍未实施；B32盘点设计v2.1已验收，规则和确认权限已获用户确认，接续实施。盘点不直接覆盖库存，相关写者及借阅行锁竞争仍须在实现后验证。B33–B35保留。
