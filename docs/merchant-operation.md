# 商家经营资料与营业开关：Swagger / Apifox 验收

已确认规则见 [ADR-0013](decisions/0013-merchant-operation.md)。新增 PATCH /api/v1/merchant/shop，位于 Swagger 的“06 商家经营配置”。本轮沿用现有数据库、账号和启动配置，无新迁移。

## 点击流程

1. 重启项目，打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。如还没有已批准商家，先按 [入驻指南](merchant-onboarding.md) 申请，再按 [审核指南](admin-shop-review.md) 由业务管理员批准。
2. 用相应商家账号 POST /auth/login，将实际 data.accessToken 填入 Authorize。
3. 在“03 商家入驻”GET /merchant/shop，确认 approvalStatus=APPROVED、memberStatus=ACTIVE、businessEnabled=false，复制实际 data.version。这里是店铺版本，不是 GET /me 的账号版本。
4. 打开“06 商家经营配置”的 PATCH 接口，点击 Try it out，填写修改资料及营业开关。将示例版本改为上一步实际值：

```json
{
  "expectedVersion": "1",
  "name": "星海模拟理发店",
  "locationText": "虚构校区服务中心 102",
  "description": "仅用于学习验收的店铺介绍",
  "businessEnabled": true
}
```

预期 200 / OK，返回修改后的 MerchantShopView，version 增加 1，仍为 APPROVED / ACTIVE，businessEnabled=true。不需要重新审核或重新登录。此处字段是 name，不是首次申请/重提使用的 shopName。

5. 用原版本重复发送，预期 409 / CONFIG_VERSION_CONFLICT。重新 GET 后查看最新资料，再决定是否修改；不要自动用新版本覆盖别人提交的内容。
6. 只关闭开关：保留新的 expectedVersion 和 businessEnabled=false，其余字段删除。预期版本再加 1，店名/位置/介绍保持原值，仍然通过审核。
7. 只传当前 expectedVersion，或传与现有值相同的开关，预期 200，版本不增加。旧版本即使没有可变字段也应为 409。
8. 清空介绍时传 description=""；只修改店名或营业开关时，省略 description 才会保留原介绍。

营业开关表示商家主动允许营业，未来下单还要检查营业时间、服务及具体场景条件。当前尚未提供下单接口，不能凭开关为 true 验收接单能力。

## 输入与权限边界

| 场景 | 预期 |
|---|---|
| 未登录、退出、会话过期 | 401，不修改 |
| 学生或管理员调用经营接口 | 403 / ROLE_FORBIDDEN |
| 业务账号停用 | 403 / ACCOUNT_DISABLED |
| PENDING / REJECTED / SUSPENDED 店铺 | 403 / SHOP_NOT_APPROVED |
| 本人 OWNER 为 PENDING / DISABLED | 403 / ROLE_FORBIDDEN |
| 无本人归属店铺 | 404 / RESOURCE_NOT_FOUND |
| 旧版本，或版本上限时实际修改 | 409 / CONFIG_VERSION_CONFLICT |
| 可变字段 null、错误 JSON 类型、未知字段 | 400 / VALIDATION_FAILED |
| 数字/前导零/空白/溢出的 expectedVersion | 400 / VALIDATION_FAILED |

名称、位置、介绍去首尾空白后分别为 1～80、1～200、0～1000 Unicode 码点。禁止 shopId、accountId、categoryId、campusCode、审批状态等字段；身份及店铺归属由当前会话确定。字符串 "true"、数字 1 不能替代布尔值 true。

不传字段表示保留原值，null 不表示保留或清空。与“完整重提”不同，这个 PATCH 不会因省略介绍清空旧值。多个字段同次变更只增加一次店铺版本；无变更仍检查经营资格。旧审核人/时间和审核历史保留，不代表管理员审核过修改后的展示文本。

Apifox 可重新导入 [运行时快照](openapi/campuslife.openapi.json)，或同步本机 /v3/api-docs。当前运行文档有 32 个操作，完整规划仍为 62 个（26 已实现、36 待开发）；时间配置见 [营业时间验收](merchant-business-hours.md)，服务见 [服务验收](merchant-service-catalog.md)。下方保留本任务交付时验证记录。

## 验证记录

2026-10-19 已执行 scripts/test-mysql.ps1：Java 21 下 Maven verify 成功，普通单元/Web 42 个、真实 MySQL 集成 235 个，共 277 个，0 failures / errors / skipped。新增 MerchantOperationMysqlIT 共 50 个；既有注册、认证/改密、资料、入驻、审核、重提、数据库和文档用例本轮一起回归通过。

新增用例包含完整资料修改与营业开关开启/关闭，严格 JSON 类型和 Unicode 长度，省略/清空、无变更不写 SQL、旧版本、BIGINT 上限，账号/成员/店铺状态和归属检查。两个会话并发修改只允许一次提交；并发无变更均成功且不递增版本。实际执行 UPDATE 后注入异常，确认所有字段与版本回滚，审核字段与历史、账号和成员不变。

并发场景使用真实 MySQL：外部事务持有店铺锁并暂停店铺，等待中的 PATCH 必须按锁后最新状态拒绝；首次归属读取后管理员批准店铺，无变更响应必须返回最新 APPROVED / ACTIVE。写入前会话到期复查使用测试时钟推进模拟，确认不能写入。

mysql 模式可执行 JAR 的启动、健康、UTF-8、trace 与访问边界检查通过。实际运行 OpenAPI 导出共 16 个操作，完整规划 62 个（16 已实现、46 待开发）、9 分组、71 个 schemas；生成器、官方 OpenAPI 3.0 schema、17 个请求示例及全部模型/参数引用校验通过。没有代用户操作 Swagger 或 Apifox。

验证仅使用独立临时 MySQL，不使用真实密码或现有业务数据。脚本正常停止临时实例并清理目录。报告在 target/surefire-reports 和 target/failsafe-reports，完整日志在 .cache/merchant-operation-mysql.log。本轮没有执行已有业务库的迁移或新建正式业务账号；没有订单实现，尚未验证真实接单/存量履约。
