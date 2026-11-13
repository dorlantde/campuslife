# 商家申请与本人店铺验收

当前实现：GET /api/v1/shop-categories、POST /api/v1/merchant-applications、GET /api/v1/merchant/shop。管理员初始化与审批见 [审核验收](admin-shop-review.md)，被拒后修改重提已实现，见 [重提验收](merchant-resubmission.md)，资料与营业开关现已实现，见 [经营验收](merchant-operation.md)，其余经营接口待开发；完整规则已确认，见 [ADR-0010](decisions/0010-merchant-onboarding.md)。

## 浏览器操作

1. 停止旧应用，通过 VS Code 的 CampusLife (MySQL) 配置启动。Flyway 自动执行 V2，建立三张商家表并初始化理发、打印分类；不需要重建数据库，不修改已执行的 V1。
2. 打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，在 **03 商家入驻** 中执行 GET /shop-categories。复制理发分类返回的字符串 id，不假定不同数据库的 ID 相同。
3. 执行 POST /merchant-applications，categoryId 替换为上一步的 ID：

```json
{
  "username": "demo_merchant_01",
  "password": "DemoPass_123!",
  "nickname": "模拟店主",
  "categoryId": "1",
  "shopName": "星海模拟理发店",
  "locationText": "虚构校区服务中心 101",
  "description": "仅用于学习验收的虚构店铺"
}
```

4. 预期 201，data 包含 accountId、shopId 和 PENDING；此时尚未自动登录。用户名已用返回 409 / USERNAME_EXISTS，可换一个新测试用户名；不要重复执行建库脚本。
5. 在 **01 账号认证** 的 POST /auth/login 使用刚才的商家用户名和密码。复制实际响应中的 data.accessToken，在 Authorize 只填写 token。
6. 执行 GET /merchant/shop，预期 200。id 与申请返回的 shopId 相同，approvalStatus=PENDING、memberStatus=PENDING、businessEnabled=false、version="0"、approvalReason=null。
7. 执行 GET /me，role 应为 MERCHANT。待审商家可以改昵称、改密码、退出；不能以“已登录”为由获得经营资格。经营入口尚未开发；审核由 ADMIN 在管理员入口完成，不能通过直接修改数据库把它们当成已实现。

## 错误与边界

| 操作 | 预期 |
|---|---|
| 使用不存在、停用或非字符串分类 ID | 400 / VALIDATION_FAILED，账号和店铺均不留下 |
| 名称空白、超过 80 码点，位置超过 200 或介绍超过 1000 | 400；中文和 emoji 按 Unicode 码点计数 |
| 申请中添加 role、approvalStatus、businessEnabled 等字段 | 400，服务端不接受自行提权 |
| 使用现有学生用户名申请商家 | 409；不转换或覆盖学生账号 |
| 未登录读取本人店铺 | 401 / AUTH_REQUIRED |
| 学生或管理员访问本人商家店铺入口 | 403 / ROLE_FORBIDDEN |
| 商家 A 试图通过查询参数指定 B 的 shopId | 仍只返回 A 的店铺，参数不能改变身份归属 |
| 当前会话退出或账号停用后访问 | 401 / SESSION_REVOKED 或 403 / ACCOUNT_DISABLED |

Apifox 重新导入 [已实现接口 JSON](openapi/campuslife.openapi.json) 或运行中的 /v3/api-docs。完整规划仍使用 [V1 规划 JSON](openapi/campuslife.v1-design.openapi.json)，其中已确认但未开发的接口单独标注。

## 实现说明与验证记录

分类为基础字典，不是演示账号。申请事务包含账号、店铺、成员三次插入；密码哈希放在事务外。数据库共享分类锁避免提交中途分类被停用，用户名唯一索引防并发重复；接口不做“先查用户名再当作不会冲突”的假设。

2026-10-19 实际完成：

- Java 21 Maven verify 成功：普通单元/Web 42 个，真实 MySQL 集成 122 个，合计 164 个；0 failures / errors / skipped。其中 MerchantOnboardingMysqlIT 新增 24 个，覆盖三表原子写入、跨角色用户名冲突、并发重复、分类锁、真实外键失败后的整体回滚、数据库约束、Unicode 边界、身份归属、停用和会话撤销。
- V1 + V2 在独立 MySQL 8.0.45 上迁移成功，重复 migrate 为零新增，原 V1 文件未修改；没有读取用户密码或访问现有服务数据。
- 最后的 Maven 回归全部通过后，原冒烟脚本因依赖重定向日志出现就绪误判；改为随机回环端口的 HTTP 就绪探测，随后单独复核 mysql 和 web-only JAR，健康、UTF-8、trace 与访问边界均通过。没有因此重复运行已通过的全部业务测试。
- 初次受限终端的 Web 测试曾被回环权限阻止，允许本地网络后实际执行通过；新增测试中的非法 Unicode HTTP 编码、MyBatis 代理测试调用和 MySQL CHECK 异常断言在修正后通过。没有放宽业务校验、移除锁或跳过失败用例。
- OpenApiMysqlIT 导出的 10 个运行时操作与完整 62 个规划操作已同步；生成器校验通过，官方 OpenAPI 3.0 JSON Schema、21 个请求示例和模型引用校验通过。52 个待开发操作中 5 个规则已确认、47 个仍为讨论稿。

报告位于 target/surefire-reports、target/failsafe-reports；本轮构建日志位于 .cache/merchant-mysql-check.log，JAR 单独复核日志为 .cache/merchant-jar-check.log 与 .cache/merchant-web-jar-check.log。测试进程已停止，临时 MySQL 目录清理完成，原 MySQL80 服务保持运行。未代用户操作 Swagger / Apifox，用户本机迁移与点击验收仍需重启应用后完成。
