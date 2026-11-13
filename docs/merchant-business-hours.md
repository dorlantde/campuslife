# 每周营业时间：Swagger / Apifox 验收

规则见 [ADR-0014](decisions/0014-merchant-business-hours.md)。新增 PUT /api/v1/merchant/shop/business-hours，位于“06 商家经营配置”。本轮 V4 新增 shop_business_hour，原有账号、店铺、审核记录保留。

2026-10-21 补充：按 [ADR-0018](decisions/0018-merchant-schedule.md)，营业时间实际变更必须覆盖今天及之后的全部已保存排班，包括停用人员；否则 409 SCHEDULE_CONFLICT。请先调整/清空冲突排班，再修改营业时间；具体点击流程见[排班指南](merchant-schedule.md)。预约存量保护仍待订单模块接入。

## 点击流程

1. 使用原 VS Code 配置重启项目，Flyway 自动执行尚未应用的 V4。无需手动建表或修改既有迁移。打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。
2. 准备已批准的商家，步骤见 [申请](merchant-onboarding.md)与[审核](admin-shop-review.md)。用商家账号登录，将 data.accessToken 填入 Authorize。
3. GET /api/v1/merchant/shop，复制实际 data.version。新增 businessHours 字段，未配置时为 []；不要使用 GET /me 的账号版本。
4. 在“06 商家经营配置”展开 PUT，每次提交**完整一周**计划。示例版本改成实际值：

```json
{
  "expectedVersion": "1",
  "intervals": [
    {"weekday": 1, "startTime": "09:00", "endTime": "12:00"},
    {"weekday": 1, "startTime": "14:00", "endTime": "18:00"},
    {"weekday": 2, "startTime": "09:00", "endTime": "18:00"}
  ]
}
```

预期 200 / OK，businessHours 按星期/开始升序，店铺版本增加一。此例只配置周一、周二，其他日休息；原来其他日的时段会被删除。businessEnabled 不变，不会自动开启营业。

5. GET 本人店铺确认版本和时段。用最新版本提交同一份计划，即使调换输入顺序，版本也不再增加。
6. 将周二拆成 09:00～12:00 和 12:00～18:00 两段再保存，应合并为 09:00～18:00；与原配置等价，因此版本不变。
7. 使用旧版本保存，即使内容相同也应为 409 / CONFIG_VERSION_CONFLICT。先重新 GET，再决定是否修改。
8. 需要整周休息时传当前版本和 intervals=[]，预期时段清空且版本加一。再次提交空数组不再加版本，手动开关保持原值。

经营 PATCH 也共用同一个店铺版本：先修改店名/开关后，不能继续用之前的版本保存营业时间。经营资料操作见 [经营指南](merchant-operation.md)。管理员列表、详情也返回实际 businessHours。

## 时间与权限边界

| 场景 | 预期 |
|---|---|
| 每周重复，星期整数 1～7，严格 HH:mm | 接受，时区固定 Asia/Shanghai |
| 10:01～11:02 | 接受，营业时间不强制 15 分钟对齐 |
| 跨午夜、起止相同、重叠或完全重复 | 400 / VALIDATION_FAILED |
| 相邻但不重叠的时段 | 排序后合并 |
| 9:00、24:00、09:00:00、带空白/时区 | 400 |
| weekday="1"、1.0、true、0 或 8 | 400，必须为整数 1～7 |
| 原始每天超过 4 段或整周超过 28 段 | 400，合并前检查 |
| intervals 未传/null/非数组，条目缺字段或未知字段 | 400 |
| 版本不符，或版本上限时实际修改 | 409 / CONFIG_VERSION_CONFLICT |
| 学生/管理员、无效成员或未通过审核的商家 | 403，与经营 PATCH 相同 |
| 无会话/已退出/过期、账号停用、无本人店铺 | 分别沿用 401 / 403 / 404 |

不接受 shopId 或账号归属字段。未列出的日期休息；空数组是清空整周，不是忽略修改。新店没有默认营业时段。

本轮不新增 openNow 字段，也不提供预约或下单能力。后续营业判断使用左闭右开区间（09:00 包含、12:00 结束不包含），并检查资格和开关；接单还需服务、排班等条件。节假日与跨午夜尚未支持。

Apifox 可重新导入 [运行快照](openapi/campuslife.openapi.json)，或同步本机 /v3/api-docs。当前 32 个操作已实现，完整规划仍为 62 个（30 个待开发）；服务见 [服务验收](merchant-service-catalog.md)，下方保留营业时间任务交付时记录。

## 验证记录

2026-10-19：新增 BusinessHoursMysqlIT 共 58 个用例，最新普通/Web 与 MySQL 集成报告合计 335，0 failures / errors / skipped。既有 277 个用例已在本轮全回归通过；新增用例在修正测试辅助函数后，连同普通 42 个定向重跑（共 100 个），Maven verify 成功。OpenApiMysqlIT 的 3 个用例也已复测通过。

两次失败均在同一个约束测试：最初只接受外键错误 1452，而本机因约束细节隐藏返回 1216；兼容 1216/1452 与 1217/1451 后，测试辅助函数又把 int[] 当作单个对象作比较。已改为检查允许编号数组包含实际编号，并补充 SQLState / CHECK 错误编号验证；没有为通过测试放宽应用校验或修改业务规则。

真实 MySQL 覆盖整周替换、排序与相邻合并、重叠/重复、原始数量限制、清空、严格 JSON 类型和时间、权限/归属/会话、版本上限、与经营 PATCH 并发、两个周计划不能混合、删除/插入中途失败及最后版本更新失败回滚。管理员分页确认只批量读一次时段；本人读取和管理员分页在并发修改中保持版本/时段同一快照。等待行锁后用当前时段比较；测试时钟模拟插入后会话到期，确认事务回滚。

V1～V4 在独立临时库迁移成功，mysql 模式 JAR 启动、健康、UTF-8、trace 和权限边界检查通过。实际 OpenAPI 导出 17 个操作，完整规划 62 个（17 已实现、45 待开发）、9 分组、71 个 schemas；官方 OpenAPI 3.0 schema、16 个请求示例及全部模型/参数引用校验通过。未代用户操作 Swagger 或 Apifox。

验证仅用独立临时 MySQL 和合成账号，不使用用户真实密码或已有业务数据。临时实例与目录已清理，本机 MySQL80 保持运行。报告位于 target/surefire-reports、target/failsafe-reports；日志为 .cache/business-hours-mysql.log、.cache/business-hours-targeted.log、.cache/business-hours-final.log。尚未在用户现有库代跑 V4，也没有排班/预约/订单的存量保护验收。
