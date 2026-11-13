# 被拒后修改资料并重新提交：Swagger / Apifox 验收

入口为 POST /api/v1/merchant/shop/resubmit，位于 Swagger 的“03 商家入驻”。规则见 [ADR-0012](decisions/0012-merchant-resubmission.md)。普通启动方式保持不变，本轮不需要填写新的配置或执行新的数据库迁移。

## 点击流程

1. 正常启动项目，打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。已有商家申请可直接复用；否则按 [申请指南](merchant-onboarding.md) 创建虚构商家。管理员按 [审核指南](admin-shop-review.md) 初始化一次即可，不必重复创建。
2. 用管理员登录，把 data.accessToken 填入 Authorize。在“08 管理员”查询待审申请及详情，复制实际 shopId 和店铺 version，使用 reject 拒绝并填写原因。
3. 用对应商家登录，替换 Authorize。GET /api/v1/merchant/shop，应看到 REJECTED、拒绝原因及新的 version。复制此处的**店铺版本**；它不同于 GET /me 的账号版本。
4. GET /api/v1/shop-categories 取得启用分类的实际 ID。在重提接口点击 Try it out，填写完整资料：

```json
{
  "expectedVersion": "1",
  "categoryId": "1",
  "shopName": "星海模拟理发店",
  "locationText": "虚构校区服务中心 102",
  "description": "已补充准确位置，仅用于学习验收"
}
```

示例 ID、版本须换成实际查询结果，保留双引号。此接口不接收 shopId 或账号信息，服务端始终修改当前登录商家的店铺。description 未填、null 或空白会清空旧介绍；名称、分类、位置必须完整填写。

5. 预期 200 / OK：id 与原店铺相同，approvalStatus=PENDING、approvalReason=null、memberStatus=PENDING、businessEnabled=false，版本由 "1" 变为 "2"。原用户名、密码和 token 仍可使用；不自动生成新账号或店铺。
6. 原请求直接再发送一次，应为 409 / CONFIG_VERSION_CONFLICT；换成当前版本再次提交，应为 409 / SHOP_STATE_CONFLICT，因为店铺已经待审。
7. 再用管理员登录并替换 Authorize，重新 GET 店铺详情，用最新版本 approve。预期 APPROVED、成员 ACTIVE、版本再加一、仍停业。也可以再次拒绝，让商家继续修改重提。

## 边界与错误

| 场景 | 预期 |
|---|---|
| 无登录、退出或会话过期 | 401，店铺不变 |
| 学生或管理员调用商家重提入口 | 403 / ROLE_FORBIDDEN |
| 账号被停用 | 403 / ACCOUNT_DISABLED |
| 当前商家没有归属店铺 | 404 / RESOURCE_NOT_FOUND |
| 版本过期，或版本已达 BIGINT 上限 | 409 / CONFIG_VERSION_CONFLICT |
| 当前版本下 PENDING / APPROVED / SUSPENDED，或 OWNER 非 PENDING | 409 / SHOP_STATE_CONFLICT |
| 分类不存在或已停用 | 400 / VALIDATION_FAILED |
| 版本/分类为数字、null、前导零或溢出；未知字段 | 400 / VALIDATION_FAILED |
| 名称/位置空白、长度超限、无效 Unicode | 400 / VALIDATION_FAILED |

名称、位置、介绍去首尾空白后分别最多 80、200、1000 Unicode 码点；前两项至少 1。省略介绍等于清空，不是保留旧值。待审不能修改或撤回；批准后的资料修改现已通过经营 PATCH 实现，见 [经营验收](merchant-operation.md)。

当前店铺的上一轮拒绝信息会清空，但 shop_review 中的历史记录保留。暂未新增历史查询 HTTP 接口，自动化验证直接检查独立临时库。

Apifox 重新导入 [运行时接口快照](openapi/campuslife.openapi.json)，或同步本机 /v3/api-docs 即可看到新增接口。完整规划仍为 62 个操作，当前 26 已实现、36 待开发，其余业务规则继续按讨论稿推进。以下保留重提任务交付时的验证记录，最新验证见 [服务指南](merchant-service-catalog.md)。

## 验证记录

2026-10-19：本轮新增 MerchantResubmissionMysqlIT 的 40 个用例；当前普通单元/Web 与 MySQL 集成报告合计 227，0 failures / errors / skipped。

首次全回归（当时新增 38 个用例）发现三项失败：数字店名被 Jackson 转为字符串，以及两条非法代理字符请求在 HTTP 发送前被客户端替换成问号。重提文本改用 JsonNode 严格检查字符串类型，测试把孤立代理字符转义后发送，并补充数字介绍/布尔位置边界。随后定向复测重提 40、首次申请 24、OpenAPI 3，加上普通 42，共 109 个均通过，Maven verify 成功；其余 118 个既有集成用例已在首次全回归通过。本轮没有因文档更新再重复运行全套。

真实 MySQL 验证完整“拒绝 → 重提 → 拒绝 → 重提 → 批准”，核对账号/店铺/成员身份与历次审核记录；两会话同时重提仅一次成功；旧管理员审核与重提并发不能覆盖资料；分类共享锁阻止提交前并发停用；实际 SQL 更新后注入异常能整体回滚；锁内复查角色/账号/会话，等待后会话到期不能写入。包含 Unicode 长度、空值、未知字段、非法状态和版本上限。

mysql 模式可执行 JAR 启动与健康/权限检查通过。实际 /v3/api-docs 导出快照为 15 个操作；完整规划 62 个操作（15 已实现、47 待开发）、71 个 schemas，官方 OpenAPI 3.0 schema、18 个请求示例和全部模型/参数引用检查通过。未代用户操作 Swagger 或 Apifox。

普通 Web 测试最初在受限终端中无法建立回环连接，允许本地网络后正常通过，无需修改应用。自动化均使用独立临时 MySQL，无用户真实密码、无现有业务数据；临时进程和目录已清理，本机 MySQL80 保持运行。报告在 target/surefire-reports 和 target/failsafe-reports；日志为 .cache/resubmit-mysql.log（首次回归）与 .cache/resubmit-targeted.log（修正后复测及 JAR 检查）。
