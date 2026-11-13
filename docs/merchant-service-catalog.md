# 商家服务配置：Swagger / Apifox 验收

已确认规则见 [ADR-0015](decisions/0015-merchant-service-catalog.md)。三个接口位于“06 商家经营配置”，V5 新增 service_item。理发师、打印规格价目和订单尚未实现。

## 点击流程

1. 使用原 VS Code 配置重启，Flyway 自动应用尚未执行的 V5；不要手改旧迁移或重新建库。打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。
2. 准备已批准、店主有效的理发商家，步骤见 [入驻](merchant-onboarding.md)和[审核](admin-shop-review.md)。登录后把实际 data.accessToken 填入 Authorize。店铺停业、没有营业时间也可以配置服务。
3. POST /api/v1/merchant/services 创建服务：

```json
{
  "serviceType": "HAIRCUT",
  "name": "模拟标准理发",
  "description": "学习验收使用的虚构服务",
  "priceAmount": 3000,
  "durationMinutes": 30
}
```

预期 201 / OK，返回 id、version="0"、enabled=false。3000 是 3000 分，即 30 元。新增默认下架；若显式 enabled=true 则上架，但不自动开启店铺或创建排班。

4. GET /merchant/services，复制该服务的 id 和 version。可使用 enabled=false、serviceType=HAIRCUT 筛选；不填筛选会包含本店所有上架/下架服务。返回数组，按创建时间/ID 倒序，无其他店铺数据。
5. PATCH /merchant/services/{serviceId}，使用实际服务 ID 和**服务版本**，不是 GET /merchant/shop 的店铺版本：

```json
{
  "expectedVersion": "0",
  "priceAmount": 3500,
  "durationMinutes": 45,
  "enabled": true
}
```

预期 200，服务版本变为 "1"，名称和介绍保持不变，价格为 35 元，时长为 45 分钟。店铺版本、营业开关和审核状态不变，不必重新登录。

6. 原请求再发送一次，应为 409 / CONFIG_VERSION_CONFLICT。重新查询服务后决定是否继续修改；不要自动换新版本覆盖。
7. 用最新版本只传 enabled=false 下架，或 description="" 清空介绍。省略字段保持原值；只传当前 expectedVersion 或传相同内容，不增加版本，但旧版本仍冲突。下架后仍能在本店查询中看到，没有删除接口。

## 打印店验证

使用已批准的 PRINT 分类商家，登录并替换 Authorize，创建：

```json
{
  "serviceType": "PRINT",
  "name": "模拟 PDF 打印",
  "priceAmount": 20,
  "durationMinutes": null,
  "enabled": true
}
```

预期时长 null、上架 true，介绍默认空字符串。20 分仅参考起价，不是某份文件的最终价格；规格价目和报价仍待后续开发。PRINT 缺失 durationMinutes 或传 0/30 应为 400，PATCH 中可省略以保留 null，或显式传 null。

## 边界与错误

| 场景 | 预期 |
|---|---|
| 金额整数分 1～100000，理发时长 15～240 且为 15 倍数 | 接受；金额不是元 |
| 数字字符串、小数形式、超限金额/时长 | 400 / VALIDATION_FAILED |
| 理发店创建 PRINT 或打印店创建 HAIRCUT | 400，与店铺分类不匹配 |
| 修改 serviceType / shopId / role / version，或未知字段 | 400 |
| 可变字段 null，或 enabled="true" / 1 | 400；只有 PRINT 时长允许 null |
| 学生、管理员、无效 OWNER | 403 / ROLE_FORBIDDEN |
| PENDING / REJECTED / SUSPENDED 店铺 | 403 / SHOP_NOT_APPROVED |
| 停用账号、无效/退出/过期会话 | 按已有认证规则返回 403 或 401 |
| 不存在或其他店铺服务 | 404 / RESOURCE_NOT_FOUND，不返回对方内容 |
| 服务旧版本，或版本上限下实际修改 | 409 / CONFIG_VERSION_CONFLICT |

名称/介绍去首尾空白后分别为 1～80、0～500 Unicode 码点；拒绝非法 Unicode。同名允许，POST 重复发送会创建不同 ID，不自动去重。日常服务维护直接生效，不重新审核；下架不删除，不自动操作存量订单，当前未实现订单功能。

Apifox 可重新导入 [运行快照](openapi/campuslife.openapi.json)，或同步本机 /v3/api-docs。交付后运行文档有 32 个操作，完整规划仍为 62 个（30 个待开发）。

打印规格价目已实现，具体配置及最新执行记录见 [价目验收](merchant-print-prices.md)。下方保留服务任务交付时记录。

## 验证记录

2026-10-20，Java 21 + 独立 MySQL 8.0：新增 ServiceCatalogMysqlIT 共 78 个用例通过，覆盖理发/打印正常流程、分类绑定与字典停用边界、严格金额和时长、默认值/null、Unicode、字段白名单、账号/成员/店铺/会话权限、跨店隔离、独立服务版本、无变更与版本上限、并发修改、实际 SQL 后故障回滚、锁后复查及数据库 CHECK/外键。

首次全回归执行普通 42、集成 371 个用例，其中旧入驻测试仍断言服务接口未开发并返回 404，因新接口对空请求返回 400 而失败；新增服务用例全部通过。已将该历史断言更新为使用合法服务请求、验证待审核商家返回 403 / SHOP_NOT_APPROVED。随后定向重跑入驻 24 + 服务 78 个集成用例及普通 42 个用例，Maven verify 成功。最新各类报告合计 413、0 failures / errors / skipped，其余已通过用例未重复运行。

V1～V5 在临时库迁移成功，mysql 模式可执行 JAR 启动、健康、UTF-8、trace 与访问边界检查通过。实际 OpenApiMysqlIT 导出 20 个操作；完整规划 62 个（20 已实现、42 待开发）、9 分组、73 个 schemas，生成器一致性、官方 OpenAPI 3.0 schema、14 个待开发请求示例及全部模型/参数引用校验通过。未代用户操作 Swagger 或 Apifox。

测试使用合成账号和临时库，不访问现有业务数据或真实密码；临时实例及失败留存目录均已清理，MySQL80 保持 Running。报告位于 target/surefire-reports、target/failsafe-reports；日志为 .cache/service-catalog-mysql.log、.cache/service-catalog-recheck.log。尚未代用户在现有库执行 V5，也没有报价、预约或订单存量保护的验收。
