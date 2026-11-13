# 商家无法履约取消验收：Swagger / Apifox

2026-10-25 后续扩展：[商家打印接单后取消](merchant-print-cancellation.md)已按ADR-0037开放，原“PRINT取消404/接单后无终结路径”的阶段说明已替代。学生接单后仍不能直接取消；商家内容读取及打印正常履约继续后续开发。

规则按 [ADR-0025](decisions/0025-merchant-booking-cancellation.md) 确认，细则见[已确认方案](merchant-booking-cancellation-design.md)。取消阶段新增一个商家接口，当前运行43个操作，完整62个（43已实现/19待开发）；无新增迁移，继续使用V9/V10。

## 准备与取消

停止旧应用，使用原VS Code **CampusLife (MySQL)** 配置重启。打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)；本机沿用已显式开启的模拟资金配置，数据库凭据不变。

Apifox可重新导入[运行快照](openapi/campuslife.openapi.json)，服务地址 `http://127.0.0.1:8080`。查看所有规划操作使用[完整规划](openapi/campuslife.v1-design.openapi.json)。本轮没有替用户上传或导入客户端。

1. 按[预约](booking-orders.md)、[付款](booking-payments.md)、[商家确认](merchant-booking-confirmation.md)指南，创建一笔未来的虚构理发预约，付款并由本店商家接单，保存订单ID。确认状态ACCEPTED / PAID / ACTIVE、version="2"。
2. 使用本店商家登录响应的 `data.accessToken` 填入Authorize，不加Bearer前缀。在“07 商家订单履约”打开 **POST /api/v1/merchant/orders/{orderId}/cancel**，填入订单ID及原因：

```json
{"reason":"理发师临时无法到岗，无法按约提供服务"}
```

3. 预约开始前执行应200：CLOSED / REFUNDED，closeReason=MERCHANT_UNAVAILABLE，closeNote为填写原因，booking.occupancyStatus=RELEASED，version="3"。
4. 核对原payment保留，refund.amount等于payableAmount及原payment.amount；acceptedAt、预约时段和原报价保持不变。切换为该学生token查本人详情，结果应一致。再次查候选，原时段恢复。
5. 切回商家token，用同原因重复取消（允许首尾空白差异），应返回同一退款号、时间、原因及版本；把原因改为另一内容重试，应409 ORDER_STATE_CONFLICT，原记录不变。
6. 对这笔已取消单再接单、拒单，或由本人学生取消，均应409 ORDER_STATE_CONFLICT，不改变责任原因或重复退款。新的同时间预约可正常创建。

不需要商家列表，直接填写学生创建得到的测试订单ID。原因必填，去首尾空白后1～300个Unicode码点；null、空白、数字、超长和额外金额/状态等字段均400。

## 时间、权限与开关

| 情况 | 预期 |
|---|---|
| 已接单，早于预约开始，包括不足1小时 | 商家可说明原因全额退款取消 |
| 等于/超过预约开始，仍ACCEPTED | 409 CANCEL_WINDOW_CLOSED，不退款、不关单、不释放 |
| 已过原接单30分钟期限，但已接单且仍未到预约开始 | 不重新按待接单超时处理，可商家取消 |
| 待支付、待接单、IN_SERVICE/COMPLETED或其他原因已关闭 | 409 ORDER_STATE_CONFLICT |
| 学生或管理员调用商家取消 | 403 ROLE_FORBIDDEN |
| 跨店、不存在或非BOOKING | 404 RESOURCE_NOT_FOUND |
| 账号/OWNER停用 | 403；停用后不能重放原取消结果 |
| 店铺暂停、手动停业或服务下架/改价 | 正常账号及有效店主仍可取消合法旧单，退款使用原金额 |
| 新退款所需模拟资金未启用 | 503 MOCK_PAYMENT_DISABLED，原状态及占用保留 |
| 同原因已成功取消，重试时已到开始时刻或资金关闭 | 权限仍有效则200原结果，不重复资金与事件 |

学生原取消规则不变：已接单距开始不足1小时不能自行取消，商家不能把自身无法履约记成USER_CANCEL。到预约开始后本入口不自动改成管理员异常处理；开始/完成已按ADR-0026实现；管理员异常入口仍待后续开发，不能普通取消的异常仍保留原状态与占用。

开关由启动配置读取，改后需重启。无需人工修改系统时间测试开始时刻；精确前/等于/后和锁等待跨界由自动测试覆盖。

## 自动验证记录

2026-10-22 定向Maven verify **235个用例全部通过**，零失败/错误/跳过：普通/Web86、新增商家取消47、商家确认59、支付/退款40、文档3。按本次共享商家锁入口和退款变更范围执行，未重跑全部旧业务。

新增47个真实MySQL/HTTP用例覆盖：成功全额退款和原支付/接单快照保留、责任原因及事件操作者；同原因跨窗口/资金关闭重放与改原因拒绝；开始前/等于/后及真实日期锁等待跨界；学生不足1小时不能取消但商家可以、原30分钟截止不误用；角色/账号/OWNER/本店/类型和锁等待后资格复核，暂停/停业/下架不重算旧金额；严格原因格式与300个Unicode码点；非法状态和其他关闭原因不被改写；资金关闭无写入、窗口错误不被资金开关掩盖；多端同/异原因及学生取消竞争、锁后重新读取学生已关闭结果；退款未提交时公开候选保守占用，新创建等待提交释放后成功；退款/关单/释放/事件各阶段故障及写后会话过期整笔回滚；版本上限、真实唯一约束及InnoDB锁超时503后安全重试。

独立临时MySQL的V1～V10迁移、mysql可执行JAR真实HTTP启动/健康/中文/鉴权边界检查通过；本轮无新迁移。测试脚本已停止并清理自有实例及目录，未访问用户现有项目库或密码。IN_SERVICE/COMPLETED等后续状态为合成数据，只验证取消拒绝，不声称已实现开始/完成。

运行OpenAPI41个操作，完整62个（41已实现/21待开发）、9分组、88个schemas。实际导出、生成器一致性、官方OpenAPI3.0 schema、7个请求示例及参数/模型引用和本地文档链接校验通过。人工Swagger/Apifox点击未代用户执行，按上文复现。

后续预约开始/完成已按ADR-0026实现，见[履约验收](merchant-booking-fulfillment.md)。开始后普通取消仍409；服务中异常和未到店处理待管理员入口。当前43个运行操作，本页235个验证为取消阶段记录。
