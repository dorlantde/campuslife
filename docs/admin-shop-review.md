# 平台管理员初始化与商家审核验收

本轮新增四个 HTTP 操作：GET /api/v1/admin/shops、GET /api/v1/admin/shops/{shopId}、POST /api/v1/admin/shops/{shopId}/approve、POST /api/v1/admin/shops/{shopId}/reject。受控创建管理员是独立本机命令，不是公开 HTTP 接口。实现规则见 [ADR-0011](decisions/0011-admin-shop-review.md)。

## 1. 本机创建业务管理员

先停止正在运行的应用。在 VS Code 终端、项目目录执行一次：

```powershell
.\scripts\init-admin.ps1
```

依次填写新的**平台管理员**用户名、昵称、密码和确认密码。账号规范与普通账号相同：用户名 4～32 位字母/数字/下划线，昵称 1～40 码点；密码至少 8 码点、UTF-8 最多 72 字节，不修改密码内容。随后若提示数据库密码，输入已有 **MySQL campuslife 专用账号密码**，不是 root 密码，也不是刚设置的业务管理员密码。

成功会提示 `Created ACTIVE ADMIN business account` 并退出，不会占用 8080 或自动运行平台。初始化会确保数据库迁移已执行；V3 只增加审核记录表，不改写 V1/V2。该工具不需要 JWT 密钥。

默认连接本机 127.0.0.1:3306 / campuslife，使用 MySQL campuslife 账号；独立开发库可通过 DatabasePort、DatabaseName、DatabaseUser、DatabaseHost 参数显式指定，规则与 database.ps1 相同。

用户名已存在时返回 USERNAME_EXISTS 并失败退出，保留已有账号、密码和角色；换一个明确选择的新用户名即可，不要直接修改 account.role。初始化是显式管理操作，没有内置管理员名或默认密码。密码只存在本机输入及进程环境中，不会提交到源码。

## 2. Swagger 审核闭环

1. 用原 VS Code 的 CampusLife (MySQL) 配置启动平台，打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。
2. 如还没有商家申请，先按 [商家入驻指南](merchant-onboarding.md) 创建一个；保留商家用户名和密码。
3. 在 POST /auth/login 用刚创建的 ADMIN 业务账号登录。将实际 data.accessToken 填入 Authorize，替换此前可能填写的学生或商家 token。
4. 打开 **08 管理员 → GET /admin/shops**，approvalStatus 填 PENDING，page=1、size=20；复制待审 shopId。
5. GET /admin/shops/{shopId} 获取详情，复制 data.version。这里是**店铺版本**，不是 GET /me 返回的账号版本。
6. POST /admin/shops/{shopId}/approve 使用刚读取的字符串版本：

```json
{"expectedVersion":"0"}
```

预期 200 / OK，approvalStatus=APPROVED、memberStatus=ACTIVE、businessEnabled=false、version="1"。版本示例必须按实际返回替换；审核通过获得经营资格，仍保持停业，服务与营业配置在后续任务开发。

7. 再创建另一份待审申请，使用 reject：

```json
{"expectedVersion":"0","reason":"请补充准确的店铺位置"}
```

预期 REJECTED、成员 PENDING、营业 false、版本 + 1。原因去首尾空白后 1～300 Unicode 码点。

8. 用相应商家账号重新登录并更新 Authorize，GET /merchant/shop 查看最新结论和拒绝原因。现有商家 token 也按数据库最新状态读取，不必为了审核结论更换 JWT。被拒后修改重提现已实现，按 [重提验收](merchant-resubmission.md) 继续。

## 3. 验收边界

| 操作 | 预期 |
|---|---|
| 学生或商家访问 /admin/** | 403 / ROLE_FORBIDDEN |
| 无 token、已退出/过期 token、停用账号 | 按认证规则返回 401 或 403 |
| 用旧版本重复审核或两个管理员同时审核同一版本 | 只有一个成功，其余 409 / CONFIG_VERSION_CONFLICT |
| 用当前版本再次审核已批准或已拒绝店铺 | 409 / SHOP_STATE_CONFLICT |
| numeric/null/前导零/溢出的 expectedVersion | 400 / VALIDATION_FAILED |
| 拒绝原因空白、超过 300、非字符串或未知 JSON 字段 | 400，状态和审核记录不变 |
| 非法分页/状态、无效店铺 ID | 400；合法但不存在的 ID 为 404 |

shop_review 在成功事务中保存历史结果和被审核版本；本轮未新增历史查询 HTTP 接口。当前只实现本表中的四个管理员接口，账号停用、评价管理等仍为规划。

可重新导入 [运行时 OpenAPI](openapi/campuslife.openapi.json)，或直接使用运行中的 /v3/api-docs。完整规划共 62 个操作，当前 26 已实现、36 待开发；重提、经营资料/营业开关、营业时间及服务均已交付，服务见 [服务验收](merchant-service-catalog.md)。

## 4. 验证记录

以下为审核任务交付时的记录；最新接口数量与验证见 [服务指南](merchant-service-catalog.md)。

2026-10-19 验证覆盖共 187 个测试，包含新增 AdminShopReviewMysqlIT 的 23 个审核/初始化用例。首次全量执行时业务断言通过，但 3 个用例在清理阶段先删管理员而触发 shop.reviewed_by 外键拒绝；清理改为先删除所有测试店铺后，再删账号。随后定向重跑新增 23 个用例及普通 42 个用例，Maven verify 成功；本轮其余 122 个既有集成用例此前已通过，没有无故重复执行。最新各类报告合计 187、0 failures / errors / skipped。

验证包含真实 MySQL 上的并发批准/拒绝、审计外键失败后的三表回滚、审核历史唯一约束、旧版本与版本上限、锁内角色/会话复查、写入前再次检测会话到期、Unicode 原因和分页边界。独立 JVM 实际运行管理员入口，确认不需要 JWT、不启动 HTTP、重复用户名不覆盖密码、不会将已有学生升级为管理员。

Windows PowerShell 5.1 下以合成的本机输入实际执行 init-admin.ps1 → database.ps1 → dev.ps1 → Maven → AdminBootstrap，V1/V2/V3 在独立临时库迁移成功，创建测试 ADMIN 后退出，并恢复三个管理员环境变量。初次路由检查未传夹具端口和库名，曾连接默认端口失败且未执行写入；补充显式数据库参数后，已在临时端口/campuslife_it 复测成功。没有使用用户真实密码或修改现有业务数据，也未代为创建用户正式库的管理员。

mysql 与 web-only 模式 JAR 检查均已通过，运行时 14 个操作与规划 62 个操作同步，官方 OpenAPI 3.0 schema、19 个请求示例及模型引用校验通过。完整规划仍有 48 个待开发：1 个重提规则已确认，47 个保留讨论稿。没有代用户操作 Swagger 或 Apifox。

报告：target/surefire-reports、target/failsafe-reports；日志：.cache/admin-mysql-check.log（首次全回归与清理错误）、.cache/admin-targeted-check.log（定向复测与 mysql JAR）、.cache/admin-cli-check.log（最终命令行路由）。临时实例与测试进程结束后清理，本机 MySQL80 服务保留运行。
