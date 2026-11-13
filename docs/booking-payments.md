# 预约模拟支付与退款验收

规则已按 [ADR-0023](decisions/0023-booking-mock-payment.md) 确认；金额为整数分，全部为虚构交易。

## 启动准备

本机 VS Code 的 CampusLife (MySQL) 开发启动项已显式设置 `MOCK_PAYMENT_ENABLED=true`。停止旧应用后使用该启动项运行，Flyway 自动执行 V10，建立支付/退款两张表。现有数据库账号及密码保持原配置。

应用默认关闭模拟资金。通过其他方式运行时，须在该进程的启动环境显式设置 `MOCK_PAYMENT_ENABLED=true`；不要把模拟资金默认开启写入公共部署配置。修改开关后需重启。未开启时新支付或需要新退款的取消返回503 `MOCK_PAYMENT_DISABLED`，普通读取、已有事实重放及未支付关闭不受影响。

## Swagger / Apifox 点击流程

打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。Apifox 可重新导入[运行快照](openapi/campuslife.openapi.json)，服务地址设为 `http://127.0.0.1:8080`。当前完整规划有62个操作，其中43个已实现、19个待开发。

1. 按[预约指南](booking-orders.md)准备虚构商家、营业时间、已启用服务和人员及有效排班。学生登录，复制 `data.accessToken` 到 Authorize。
2. 查询公开候选时段，使用当前服务版本、金额、UTC 开始时间创建预约。首次201，保存响应 `data.id`，确认 PENDING_PAYMENT / NOT_PAID / HELD。
3. 在“05 学生订单”打开 **POST /api/v1/orders/{orderId}/payments/mock**，填入上一步 ID，**不填写请求体**，点击 Execute。15分钟内付款应200，响应 PENDING_ACCEPTANCE / PAID、固定 paymentNo 和 amount，acceptanceDeadline 为 paidAt 后30分钟。
4. 再点击付款，返回同一支付流水及截止时间。查看本人订单详情，应有 payment，refund=null，booking.occupancyStatus=ACTIVE，version="1"；该时段仍被占用。
5. 在接单截止前调用 **POST /orders/{orderId}/cancel**。应200、CLOSED / REFUNDED、closeReason=USER_CANCEL、RELEASED，version="2"；payment 保留，refund.amount 等于原支付金额。
6. 再次取消，应返回同一退款事实，版本及时间不变；再次付款应409 ORDER_STATE_CONFLICT。重新查询候选，原时段恢复。
7. 另建一笔付款后不取消，等待接单截止和下一轮扫描（通常最多30秒）。详情应 ACCEPTANCE_TIMEOUT / REFUNDED / RELEASED。到期但扫描未提交前，GET 如实返回旧状态，不替后台执行退款；此时主动取消会先提交退款，再409 ACCEPTANCE_DEADLINE_PASSED。

商家详情/接单/拒单已按ADR-0024实现，见[商家确认验收](merchant-booking-confirmation.md)。本页30分钟自动退款仅适用于仍未接单的订单；已接单不被原待确认扫描退款，学生距开始至少1小时仍可取消。无需通过修改系统时间验收精确边界。

## 常见结果

| 条件 | 结果 |
|---|---|
| 支付截止前首次付款 | 200，原快照金额，一笔成功支付 |
| 等于/超过支付截止 | 先提交未付款关闭及释放，再409 PAYMENT_DEADLINE_PASSED |
| 接单截止前本人取消 | 200，一笔全额退款 |
| 等于/超过接单截止后取消 | 先提交退款/关闭/释放，再409 ACCEPTANCE_DEADLINE_PASSED |
| 他人或不存在订单 | 404 RESOURCE_NOT_FOUND |
| 商家/管理员调用学生支付 | 403 ROLE_FORBIDDEN |
| 首次付款时店铺经营资格无效 | 409 SHOP_UNAVAILABLE；未支付单仍可取消 |
| 传入金额、JSON null、{} 等请求体 | 400 VALIDATION_FAILED |
| 开关关闭，需要新支付/退款 | 503 MOCK_PAYMENT_DISABLED，无资金或状态写入 |

关闭开关期间，已付款到期单不会伪造退款或释放，仍保守占用；后台日志含订单ID供定位。恢复开关后按原截止时间处理，不重新计30分钟。重复已有成功付款或 USER_CANCEL 退款关闭可直接返回原事实。新预约遇到这种不可退款的重叠单返回409 BOOKING_CONFLICT。

## 自动验证记录

2026-10-22 最终定向验证 **272个用例全部通过**，零失败/错误/跳过：普通/Web86、预约65、支付/退款40、模拟开关显式配置1、候选66、迁移11、文档3。未重新运行所有旧业务测试。独立临时 MySQL V1～V10迁移及 mysql 可执行JAR真实启动检查通过，未访问用户现有项目库或密码。

覆盖默认关闭、新资金全路径受限、已有事实重放、恢复按原期限补处理；冻结报价与经营资格；身份/角色/归属及非法请求体；两个截止前/等于/后与真实日期锁等待跨界；锁后付款时间不随写入移动、提交前会话过期回滚；并发支付仅一笔事实，支付/取消/未付超时竞争，退款/取消/扫描/新预约竞争；支付和退款各阶段故障整体回滚、失败后扫描重试，创建失败回滚到期退款；真实唯一/CHECK约束、InnoDB锁超时503、版本上限保护，以及详情RR快照和等待后重新读取已退款状态。

恢复验证通过重新构建扫描器并读取已有数据库截止时间执行，未执行携带存量订单的完整JVM进程重启；JAR启动检查独立完成。商家ACCEPTED状态测试为合成数据库状态，用于验证取消边界和付款事实重放，不代表已实现商家接单。

首轮发现并修正MoneyMapper查询/写入退款同名导致的MyBatis statement冲突，以及测试夹具使用错误成员状态值；修正后198个定向用例通过，再补充读取一致性/锁竞争/配置验证后最终272个通过。本轮自有临时MySQL实例及目录已停止并清理。

实际运行OpenAPI37个操作，完整规划62个（37已实现/25待开发）、9分组、86个schemas。生成器一致性、官方OpenAPI3.0 schema、9个请求示例及参数/模型/本地链接校验通过。人工浏览器/Apifox点击由用户按上文复现，尚未代用户操作客户端；项目库V10是否执行以用户重启后的Flyway日志为准。
