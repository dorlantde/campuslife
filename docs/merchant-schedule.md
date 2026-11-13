# 理发师按日排班验收（Swagger UI / Apifox）

2026-10-21：按 [ADR-0018](decisions/0018-merchant-schedule.md) 实现查询、按日完整替换及营业时间保护。重启原有 mysql 配置会自动执行 V8，新增两表，保留账号、店铺及原有配置，不重新建库。

## 准备

1. 停止旧进程，在 VS Code 选择 CampusLife (MySQL) 正常启动，等待启动成功；打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。
2. 使用本人已获批准的理发商家登录，从响应 data.accessToken 复制 token，填入 Authorize。账号和 OWNER 均须 ACTIVE，店铺须 APPROVED / HAIRCUT。
3. 在“06 商家经营配置”找到两个 schedule 接口。若页面仍只有旧接口，先确认已停止旧应用并重新运行，再刷新。Apifox 可更新导入 [运行快照](openapi/campuslife.openapi.json)，或[完整规划](openapi/campuslife.v1-design.openapi.json)；本轮增加两个已实现操作。
4. GET /merchant/shop 取得店铺版本和营业时间；若尚未配置，按[营业时间指南](merchant-business-hours.md)保存整周。以下用周一至周日 09:00～12:00、14:00～18:00 作为测试计划；整周保存会替换原计划，请先记录已有值。
5. 按[理发师指南](merchant-barbers.md)新增或选择本店人员，记录实际 barberId。不要求先启用人员、营业开关或绑定服务。

所有示例使用虚构数据，日期必须替换为 Asia/Shanghai 今天至第 6 天的实际日期；版本必须从本人查询取得，不能长期照抄。

## 查询和保存

GET /api/v1/merchant/barbers/{barberId}/schedule：点 Try it out，填写 barberId 和 date（例如 2026-10-22），点 Execute。日期必须严格 YYYY-MM-DD，支持合法历史/未来日期；未配置返回 intervals=[] 和当前 barberVersion，不会创建数据库记录。

PUT 同一路径，填写真实日期及刚查到的人员版本：

```json
{
  "date": "2026-10-22",
  "expectedBarberVersion": "0",
  "intervals": [
    {"startTime": "14:00", "endTime": "17:00"},
    {"startTime": "10:00", "endTime": "12:00"},
    {"startTime": "09:00", "endTime": "10:00"}
  ]
}
```

预期 200 / OK，返回 date、barberVersion，以及按序合并后的 09:00～12:00、14:00～17:00。实际变化仅递增人员版本一次，店铺版本不变；人员姓名、启停和绑定不变。再次 GET 应与保存结果一致。接口是工作计划，不会创建预约或订单。

重复提交刚才的旧版本，预期 409 CONFIG_VERSION_CONFLICT；换成最新版本，将时段排序改成两段等价计划再保存，预期成功且人员版本不增加。修改姓名、启停、服务绑定或另一日排班，也会使旧人员版本失效。

## 边界与清空

每次错误后可再 GET，确认计划和版本不变。

| 操作 | 预期 |
|---|---|
| PUT 昨天或今天后第 7 天 | 400 VALIDATION_FAILED |
| GET 昨天 | 200，可查看历史计划 |
| 09:10、9:00、09:00:00、24:00、跨午夜 | 400；起止须为严格 HH:mm 且 15 分钟对齐 |
| 重复、重叠，或原始 5 段 | 400；最多 4 段，相邻可合并 |
| 营业分段为 09～12、14～18，却排 11:30～14:30 | 400，不能跨午休 |
| 营业为 09:10～17:40，排 09:15～17:30 | 200，营业时间不被自动取整 |
| 休息日保存非空计划 | 400；空数组可保存 |
| intervals 省略/null、非法字段或字段类型 | 400，不能当成清空 |
| 非本人理发师 | 404 RESOURCE_NOT_FOUND |

清空时使用该人员最新版本，保持真实 date，设置 intervals=[]。成功后该日为空，人员版本增加一次；其他日期不变。清空保留日期保护行，但这不表示当天仍可预约。对从未配置的日期保存 [] 属于无变化，不增加版本或建立保护行。

## 营业时间保护

1. 为今天或未来 6 天内的人员保存 09:00～12:00。人员停用也参与保护。
2. GET /merchant/shop 取得最新店铺版本；PUT /merchant/shop/business-hours 用整周目标计划把对应星期缩为 10:00～11:00，或清空整周。
3. 预期 409 SCHEDULE_CONFLICT，营业时间、排班及版本全部不变。店铺旧版本冲突则仍为 CONFIG_VERSION_CONFLICT，应先刷新。
4. 先用排班 PUT 调整/清空所有冲突人员和日期，再用最新店铺版本修改营业时间，预期成功。今天已经过去的上午也按整天保护，历史日期不限制修改。

不能直接在库里删时段绕过约束。本轮没有预约表；以后有效预约也需要独立保护，并不因当前排班验收通过而自动完成。

## 验证记录

2026-10-21 实际执行：Java 21 + 独立临时 MySQL 8.0，全回归普通/Web 42 + MySQL 集成 654，共 **696 个用例全部通过**，零失败、错误或跳过；新增 BarberScheduleMysqlIT 共 **91 个**。V1～V8 迁移及 mysql 模式可执行 JAR 的 HTTP 就绪、健康、UTF-8、trace 与访问边界检查通过。

新增验证覆盖日期窗口与严格格式、4 段和 15 分钟边界、相邻合并/重叠拒绝、午休和分钟营业覆盖、清空/日期隔离、UTC 跨日前后往返、共享版本/上限、权限与跨店隔离。实际 SQL 后故障及会话过期/跨午夜均回滚；真实 MySQL 验证 shop、日期保护行及首次 INSERT 外键对人员行的等待、共享版本并发、读取 RR 一致性、店铺入口阻塞独立事务和营业时间/排班两种保存顺序。营业时间保护覆盖今天的已过时段、停用人员、未来日期，忽略历史日期。

首轮定向执行发现一处测试仅接受 1452、实际 MySQL 返回等价外键错误 1216；修正为接受对应外键错误码，并补充锁等待/快照验证后，上述完整回归通过。没有以 mock 替代数据库锁或 SQL 回滚验证。日志为 .cache/schedule-mysql-first.log、.cache/schedule-mysql-full.log；报告位于 target/surefire-reports、target/failsafe-reports。

OpenApiMysqlIT 从真实运行服务导出 28 个操作；完整规划 62 个（28 已实现、34 待开发）、9 分组、77 个 schemas。生成器一致性、官方 OpenAPI 3.0 schema、10 个请求示例与模型/参数引用、运行快照与真实导出一致性，以及指南 JSON/本地链接检查通过。未代用户执行浏览器/Apifox 点击验收，也未在现有项目库执行 V8；本轮不声称预约或订单已实现。
