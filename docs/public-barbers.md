# 公开理发师查询：Swagger / Apifox 验收

2026-10-21，按 [ADR-0020](decisions/0020-public-barbers.md) 实现一个匿名 GET /api/v1/shops/{shopId}/barbers。运行版现为 36 个操作，完整规划 62 个（26 个待开发）；无新表或迁移。

## 准备人员与服务

1. 用原 VS Code 的 CampusLife (MySQL) 配置重启，打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。
2. 按[公开商家目录指南](public-shop-catalog.md)准备一间已批准、经营账号和 OWNER 有效的虚构理发店，记录实际 shopId。
3. 商家登录，在“06 商家经营配置”按[服务指南](merchant-service-catalog.md)创建并上架两项理发服务 A、B，另准备一项下架服务 C。记录各自实际字符串 ID。
4. 按[人员指南](merchant-barbers.md)新增甲、乙两人：甲绑定 A/B/C，乙绑定 A。新增人员默认停用，需要再 PATCH enabled=true。另保留一名停用人员或启用空绑定人员用于对照。
5. 姓名/启停、绑定和排班共用人员版本；写操作的 expectedVersion 应从最新商家查询取得，不使用服务或店铺版本。不必配置排班、营业时间或先打开营业开关。

## 匿名查询与筛选

在 Authorize 点击 Logout 并关闭弹窗，清除旧 token。在“03 商家入驻”找到“查询公开店铺理发师”，点击 Try it out：

1. shopId 填实际理发店 ID，serviceId 留空不发送，执行后为 200 / OK。甲、乙可见，停用/空绑定人员不可见。甲的 serviceIds 只含 A/B，不含下架 C；乙只含 A。
2. serviceId 填 B 的实际 ID，应只返回甲，但甲的 serviceIds 仍含 A/B；筛选不会把字段缩成只剩 B。
3. serviceId 填 A，应返回甲、乙。若某项已上架服务没有启用人员绑定，返回 []。
4. 人员按创建时间/ID 倒序，服务 ID 按数值升序；同名人员不会合并。响应仅包含 id、displayName、serviceIds，没有人员版本、启停或排班。

data 的结构示例（ID 只是虚构示例）：

```json
[
  {"id": "302", "displayName": "模拟理发师乙", "serviceIds": ["201"]},
  {"id": "301", "displayName": "模拟理发师甲", "serviceIds": ["201", "202"]}
]
```

Swagger 不勾选/不发送可选 serviceId 表示省略；主动发送 ?serviceId= 是非法空值，返回 400。Apifox 更新导入[运行快照](openapi/campuslife.openapi.json)或[完整规划](openapi/campuslife.v1-design.openapi.json)，公开接口选择“无需认证”，可关闭 serviceId 参数开关来省略。服务地址为 http://127.0.0.1:8080。

## 修改后的展示与边界

需要写配置时重新商家登录并 Authorize；改完再清除授权，按公开接口复查。

| 操作 | 预期 |
|---|---|
| 下架服务 B | 甲的公开 serviceIds 不再含 B；以 B 筛选为 404 |
| 停用甲 | 甲从公开人员数组消失 |
| 清空乙绑定，或只保留下架服务 | 乙从公开数组消失 |
| 关闭营业开关、未配置营业时间或没有排班 | 合格人员仍可浏览，不表示有空位 |
| 可见打印店不带 serviceId 查询 | 200，[] |
| 筛选不存在、跨店、下架或非理发服务 | 404 RESOURCE_NOT_FOUND，不区分原因 |
| 查询待审/被拒/暂停等不可见店铺 | 404 RESOURCE_NOT_FOUND |
| shopId/serviceId 为 0、01、负数、小数、空白或超出 BIGINT | 400 VALIDATION_FAILED |
| 带失效 token 查询 | 401；清除 token 后可匿名浏览 |

该接口是人员目录，没有日期参数，不筛选休息日或可预约区间，不预留服务、价格或资源。公开数据发生变化后，新查询反映最新已提交状态；已经开始的查询可返回当时一致快照。

## 自动化验证记录

2026-10-21，Java 21 + 独立临时 MySQL 8.0：定向执行新增 PublicBarbersMysqlIT **33 个**、相关公开目录 **61 个**、OpenAPI **3 个**及普通/Web **42 个**，本轮共 **139 个用例全部通过**，零失败、错误或跳过。没有重跑全部旧业务，不能将历史报告合计当作本轮全回归。

覆盖统一公开资格、匿名及三种有效角色、失效/撤销/过期/停用账号凭证、精确路由与其他方法保护；启停/空绑定/仅下架绑定、全部公开 ID、同名人员和排序、合法服务无人员、非法/空/跨店/下架/非理发筛选、PRINT 空结果及未配置营业时间/排班仍可展示。真实 SQL 人为建立跨店/错误类型关系后验证不泄露服务 ID，也不修改存量配置。8 名人员各 50 个服务的联表读取验证完整排序、单次关系查询及无逐人服务校验，不作为性能压测成绩。

真实 MySQL 事务在读取店铺资格后或目标服务后，并发停用账号/人员、下架服务、解绑并修改姓名，验证本次返回一致旧快照，下一请求反映已提交变化；不是仅用 mock 验证隔离。查询不递增版本或写配置，不声称实现预约占用保护。

实际使用 scripts/test-mysql.ps1 -StartupTimeoutSeconds 90，MAVEN_ARGS 选择 PublicBarbersMysqlIT,PublicShopCatalogMysqlIT,OpenApiMysqlIT；Maven verify 和 mysql 模式 JAR 的 HTTP 就绪、健康、UTF-8、trace 与访问边界全部通过。临时库应用 V1～V8，没有新增迁移或依赖。日志为 .cache/public-barbers-mysql.log，选定报告位于 target/surefire-reports、target/failsafe-reports。

OpenAPI 从真实服务导出 33 个操作，完整规划 62 个（32 已实现、30 待开发）、9 分组、81 个 schemas。生成器一致性、官方 OpenAPI 3.0 schema、10 个请求示例、模型/参数引用和公开字段检查通过。使用合成数据，未访问现有项目库或用户密码；临时实例和目录均已清理，MySQL80 保持 Running。尚未代用户执行浏览器或 Apifox 点击验收；该轮尚未实现候选时段。后续配置候选已交付，见[候选验收](booking-slots.md)；真实占用、下单、评价及报价仍未实现。
