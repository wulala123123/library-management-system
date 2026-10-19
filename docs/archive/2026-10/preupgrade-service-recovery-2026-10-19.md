# 未升级生产的 V15 服务恢复

历史记录：2026 年 10 月 19 日。本页仅记录原服务恢复；后续候选发布和当前版本见 [PLAN](../../../PLAN.md)，现行操作见[部署与恢复](../../operations/deployment.md)。

## 范围与结论

B40-A 原候选 `046b6fa035a99405d5a2999665ad516dd328fdb7` 的第一次新备隔离演练，在 V16 的 reviews 第 2266 项返回 `409/CATALOG_BUSY`。原失败记录保持。生产从未安装该候选或执行 V16 迁移，前后端按失败流程停止，MySQL 保持运行。

本次完成严格保值核对后，恢复同一组原 V15 前后端容器。原生产提交仍为 `b80bef7001d8d3ee30ee0dbcf891897e9b61b783`，对应 [Verify 39141666413](https://github.com/wulala123123/library-management-system/actions/runs/39141666413)。三个服务健康，公网原页面及引用资源与原验收包逐字一致；原失败演练仍为失败。

## 执行边界

1. 核实原本地 wrapper 和远端 runner 已终态、原操作锁可独占，原隔离资源已验空。
2. 原 current、容器 ID、镜像、配置、挂载和网络身份保持；没有最终停写、第二新备、候选安装、切换或生产迁移标记。
3. 当前 70 张旧表全部原列值、19 类元数据、全部原始 next-ID 与原初备的完整捕获相符；备份本身、环境、上传文件及其他服务保持。
4. 经理验收窄政策、16 项离线检查及 10 项准确封版检查，再执行现场完整只读 check；实际 check 通过后另绑定一次性批准。
5. 使用准确旧 APP_VERSION，仅执行原前后端的 `up --no-deps --no-build --no-recreate`，随后复核同一启动时点的镜像、JAR、全部静态和 HTTP 字节，以及完整数据、环境、上传和其他服务保持。

此次未重建容器、安装候选、执行迁移或回灌备份，原失败和已消费入口保持只读。后续若进入最终停写或生产已升级，该窄恢复条件不再适用。

## 验收依据

- 私有总回执：`logs/member-business-stage4/manager/preupgrade-v15-final-acceptance.json`。
- 原现场与终态：`manager/preupgrade-inventory-f156ce6d5b5e4b35835c160ea56ae388/`。
- 已审封版：`b40-preupgrade-recovery-review/sealed-inputs-v1/`；工具仅绑定准确来源 pins，并等价明确检查文件名正则。
- 启动前只读回执和一次性批准：`manager/preupgrade-v15-readonly-acceptance.json`、`manager/preupgrade-v15-recovery-approval.json`。
- 启动后完整回执：`manager/preupgrade-collect-23913e7a051040b4ab614e26dfa3abe6/recovery-complete.json`。
- 独立公网 GET：`manager/preupgrade-v15-public-get-acceptance.json`；浏览器原套装目录正常、管理员登录状态保留，未办理业务。

上述相对证据目录均位于私有 `logs/member-business-stage4/`。原始捕获、环境、备份和任何真实资料不随本公开档案进入 Git 或发布包。
