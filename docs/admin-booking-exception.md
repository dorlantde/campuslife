# 管理员预约异常验收：Swagger / Apifox

2026-10-26 后续管理员PRINT异常已按[ADR-0041](decisions/0041-admin-print-exception.md)接入，见[点击指南](admin-print-exception.md)。已付款未完成打印可由管理员基于原因/版本核实全额退款关闭，开始/待取件时间保留、进度ABORTED；已完成不退款，ADMIN仍无内容权限。旧阶段“管理员PRINT未开放”的描述由本次取代。

规则按[ADR-0027](decisions/0027-admin-booking-exception.md)确认，完整细则见[确认方案](admin-booking-exception-design.md)。本轮新增管理员预约详情、核实退款关闭两个接口；运行45个操作，完整62个（45已实现/17待开发），无需新增迁移。

## 准备

停止旧应用，使用原VS Code **CampusLife (MySQL)** 配置重启，打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，地址设为`http://127.0.0.1:8080`；[完整规划](openapi/campuslife.v1-design.openapi.json)还包含待开发操作。

使用已有ADMIN业务账号。尚未初始化时按[管理员审核指南](admin-shop-review.md)在本机运行受控初始化脚本，不要使用MySQL账号campuslife/root登录业务接口；不提供默认管理员密码。

按[创建预约](booking-orders.md)、[模拟付款](booking-payments.md)、[商家接单](merchant-booking-confirmation.md)准备虚构BOOKING，记录学生、商家及订单ID。当前没有订单列表接口，直接使用创建响应中的ID。本机模拟资金须开启，原开发启动项已显式开启，普通配置默认关闭。

## 无需等待的未开始退款流程

1. 使用ADMIN账号调用登录接口，将响应`data.accessToken`复制到Authorize；只填token本身。
2. 在“08 管理员”展开 **GET /api/v1/admin/orders/{orderId}**，填预约ID后Execute。已付款待接单通常version="1"，已接单通常version="2"；以实际响应为准。
3. 展开 **POST /api/v1/admin/orders/{orderId}/close-exception**，填同一ID及刚读到的版本，例如：

```json
{
  "reason": "已向双方核实，商家无法按约履行，按V1规则全额模拟退款",
  "expectedVersion": "2"
}
```

4. 应200：status=CLOSED、paymentStatus=REFUNDED、closeReason=ADMIN_EXCEPTION，closeNote为核实原因；refund.amount与payableAmount一致，payment保留。未开始的booking.occupancyStatus=RELEASED；版本加1，closedAt有值。
5. 原样再次执行，应200返回同一结果，退款号/金额/时间/版本不变。不要把expectedVersion改成关闭后的版本；此处用于重试原操作，必须保留原请求版本。
6. 改原因、改版本或换另一ADMIN重复关闭，应409 ORDER_STATE_CONFLICT，不覆盖原核实结论。换学生/商家token调用管理员接口，应403 ROLE_FORBIDDEN；学生查本人或商家查本店详情仍能看到同一关闭结果。

本入口允许管理员核实后处理旧单，无学生提前1小时或商家开始前限制；不要求人工等到预约结束再操作。它不自动认定任何一方过失，也不会自动收取缺席费用。

## 服务开始后的异常

另建订单付款、接单，等待实际预约开始后，按[履约指南](merchant-booking-fulfillment.md)由商家点击start。此处遵守原创建至少提前2小时规则，没有绕过时间的调试接口。

切换ADMIN后重新GET详情，确认IN_SERVICE、记录最新version；提交实际核实原因并关闭。应全额退款且主单CLOSED/REFUNDED，但占用为CONSUMED，startedAt与原startAt/endAt保持。即使原预约仍有剩余时间，也不再售卖原区间。关闭后商家start/complete均409，不能恢复服务中或补记完成。

已COMPLETED订单不能通过此接口退款；未到店到endAt仍未开始的ACCEPTED订单，可以核实后退款并RELEASED。精确边界、长预约剩余区间和并发保护由受控Clock自动测试验证；不要求用户手工修改系统时间。

## 错误与重复操作速查

| 情况 | 预期 |
|---|---|
| 非管理员 / 管理员停用 | 403 ROLE_FORBIDDEN / ACCOUNT_DISABLED |
| 不带token或无效/过期/撤销会话 | 401 |
| 订单不存在或非BOOKING | 404 RESOURCE_NOT_FOUND |
| reason缺失/null/空白/超长，版本数字/前导零/超范围，未知字段 | 400 VALIDATION_FAILED |
| 管理员读取后商家已开始/完成，仍提交旧版本 | 409 CONFIG_VERSION_CONFLICT；重新读取并核实 |
| 版本匹配但未付款或已完成 | 409 ORDER_STATE_CONFLICT |
| 其他原因关闭，或重试不匹配原管理员/原因/版本 | 409 ORDER_STATE_CONFLICT |
| 需要新退款而模拟资金关闭 | 503 MOCK_PAYMENT_DISABLED，无写入 |
| 原成功核实请求重试，模拟资金已关闭 | 权限有效仍200，不生成新退款 |
| 目标账号/OWNER停用、店铺暂停/停业 | 管理员可查询及处理合法已付旧单 |

待接单已过30分钟但尚未关闭时，GET只显示现状；管理员关闭与扫描先提交者保留关闭原因。扫描已退款关闭后，管理员不能将ACCEPTANCE_TIMEOUT改为ADMIN_EXCEPTION。

管理员、原因及迁移前后状态记录在订单事件；当前接口未提供独立事件列表，不把详情显示的关闭原因等同于完整审计查询。V1不包含投诉上传、审批流或已完成订单售后。

## 自动验证记录

2026-10-22 首轮定向Maven verify **280个用例全部通过**（普通/Web86、管理员异常50、支付退款40、商家取消47、履约54、文档3）。补充7个并发/权限/真实锁场景后，最终定向 **205个用例全部通过**（普通/Web86、管理员异常57、商家确认59、文档3），零失败/错误/跳过。两轮覆盖不同相关回归，不宣称最后单次重跑全部旧业务。

57个管理员真实MySQL/HTTP用例覆盖三种允许状态、全额资金与核实事件、未开始释放/开始后CONSUMED、原始时间/快照保持；合法原请求重放、改原因/管理员/版本冲突、读取后状态变化及版本上限；资金关闭的新退款拒绝与原结果重放；ADMIN/停用/撤销/过期会话、目标停用不阻断、非法ID/字段/Unicode长度与非BOOKING资源。

真实InnoDB验证多端同请求、不同管理员、商家开始/完成、学生取消和到期扫描竞争，确保只有一次关闭退款；日期锁等待后读取最新版本；真实锁超时503及原请求重试。退款/主单/释放/消耗/事件实际SQL执行后注入故障，以及提交前会话过期，均验证整笔回滚。

4小时原预约开始后异常关闭，未来剩余区间仍从公开候选排除且实际创建409，原结束后的相邻预约可创建，排班/营业时间保护及人员调整沿用既有规则。退款提交暂停时，候选读取提交前ACTIVE、创建等待后读取CONSUMED，均不开放原区间；未开始关闭则只在提交后释放并允许合法新建。

最初受限终端运行普通Web测试因禁止回环连接失败；允许本地网络后两轮验证通过，未将环境权限失败作为应用缺陷。独立临时MySQL完成V1～V10迁移；mysql可执行JAR真实HTTP启动、健康、UTF-8中文、鉴权边界检查通过。两轮实例及临时目录由脚本停止并清理，没有访问用户现有服务数据或密码；无新迁移/依赖/XML，本机启动凭据未改。

实际运行OpenAPI45个操作，完整62个（45已实现/17待开发）、9分组、89schemas；生成器一致性、官方OpenAPI3.0 schema、6个请求示例、参数/模型及引用检查通过。尚未代用户进行Swagger/Apifox人工点击验收，按上文复现。
