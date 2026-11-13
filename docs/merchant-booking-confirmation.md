# 商家预约确认验收：Swagger / Apifox

2026-10-24 后续扩展：[商家打印确认](merchant-print-confirmation.md)已开放本店PRINT列表/详情/事件及接拒单；本页旧阶段中的“商家PRINT未开放/空页”已由ADR-0036替代。打印内容访问、接单后取消及打印后续履约仍待开发。

规则按 [ADR-0024](decisions/0024-merchant-booking-confirmation.md) 确认，完整细则见[确认方案](merchant-booking-confirmation-design.md)。确认阶段新增本店预约详情、接单、拒单三个接口；后续无法履约取消已按ADR-0025实现，当前运行43个操作，见[取消指南](merchant-booking-cancellation.md)。复用V9/V10，不新增迁移。

## 准备与切换身份

停止旧应用，以原有VS Code **CampusLife (MySQL)** 配置重启，打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。本机模拟资金开关沿用上一任务的显式开启配置，数据库凭据不变。

Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，或使用[完整规划](openapi/campuslife.v1-design.openapi.json)查看62个操作（43已实现/19待开发）。实际服务地址使用 `http://127.0.0.1:8080`。本轮没有替用户上传文件或操作Apifox客户端。

先按[预约指南](booking-orders.md)准备已审核理发店、服务、人员、营业时间及排班，再按[支付指南](booking-payments.md)由学生创建并付款，保存订单 `data.id`。建议预约明天、至少提前两小时，便于接单后继续验收取消。

学生和商家各自登录，分别保存 `data.accessToken`。每次切换操作者时，在Authorize清除旧token并填入当前身份token本身，不加Bearer前缀。商家本轮还没有订单列表，用学生创建的虚构测试订单ID查询即可。

## 接单与学生取消

1. 使用本店商家token，在“07 商家订单履约”调用 **GET /merchant/orders/{orderId}**，确认PENDING_ACCEPTANCE / PAID / ACTIVE，核对原金额、人员、时间和支付流水。GET不自动退款。
2. 在付款后30分钟截止前，调用 **POST /merchant/orders/{orderId}/accept**，填写订单ID，**不填写请求体**。应200，状态ACCEPTED，paymentStatus=PAID，占用ACTIVE，acceptedAt为服务器时间，version由"1"变"2"。
3. 重复接单应返回同一acceptedAt、版本及截止时间，不生成新支付或退款；候选仍排除原占用区间。
4. 切换为下单学生token，调用已有 **POST /orders/{orderId}/cancel**。若距开始至少1小时，应200、CLOSED / REFUNDED / RELEASED，USER_CANCEL、version="3"；原payment保留，refund.amount等于冻结金额。
5. 再取消应返回原关闭/退款事实，版本不变；商家再次接单应409 ORDER_STATE_CONFLICT。重新查候选，原时段恢复。

距开始不足1小时且仍ACCEPTED时，学生取消应409 CANCEL_WINDOW_CLOSED，无退款、无状态变更；等于1小时允许。精确时间由自动测试覆盖，不要求人工改系统时间。IN_SERVICE/COMPLETED不能直接学生取消，开始/完成已按ADR-0026实现，见[履约指南](merchant-booking-fulfillment.md)。

## 拒单与退款

1. 学生另建并付款一笔预约，保存新的ID，切换本店商家token。
2. 期限内调用 **POST /merchant/orders/{orderId}/reject**，请求示例：

```json
{"reason":"人员临时无法提供服务"}
```

3. 应200、CLOSED / REFUNDED / RELEASED，closeReason=MERCHANT_REJECT，closeNote为填写原因，版本由"1"变"2"；支付保留且新增一笔全额退款。
4. 同原因（允许首尾空白差异）再拒单应200、同一退款和时间；改成另一原因重试应409 ORDER_STATE_CONFLICT，不覆盖原因。学生取消或商家接单均不能改变这笔拒单结果。

原因必填字符串，去首尾空白后1～300个Unicode码点；未知字段、null、空白、错误类型或超长均400。已付款待接单订单到截止时刻，接单/拒单会先提交ACCEPTANCE_TIMEOUT全额退款/关闭/释放，再409 ACCEPTANCE_DEADLINE_PASSED；不会把到期改为商家拒单。未付款、已接单或其他原因已关闭时不能首次拒单。

## 权限与模拟资金

| 情况 | 结果 |
|---|---|
| 学生或管理员调用商家接口 | 403 ROLE_FORBIDDEN |
| 其他店铺或不存在的订单 | 404 RESOURCE_NOT_FOUND |
| 本店OWNER未激活/停用 | 403 ROLE_FORBIDDEN |
| 账号停用 | 403 ACCOUNT_DISABLED |
| 店铺失去APPROVED资格 | 接单403 SHOP_NOT_APPROVED；正常账号及ACTIVE OWNER仍可详情/拒单退款 |
| 手动停业、服务下架或改价 | 可处理合法旧单，使用原快照，不重新报价 |
| 接单带{}、JSON null、版本等请求体 | 400 VALIDATION_FAILED |
| 关闭模拟资金，合法未到期已付单接单 | 可接单，不生成新流水 |
| 关闭模拟资金，拒单/已接单取消/到期处理需新退款 | 503 MOCK_PAYMENT_DISABLED，整笔无写入 |
| 关闭模拟资金，同原因已拒单或USER_CANCEL重放 | 返回原成功事实，不重复生成退款 |

模拟资金开关只在进程启动时读取，修改后需重启。关闭期间未处理已付到期单仍占用；恢复后已有扫描按原截止补处理。商家接单成功后不再适用待确认超时退款，不自动认定完成或缺席；无法履约取消已按ADR-0025实现，见[取消验收](merchant-booking-cancellation.md)；开始/完成见[履约验收](merchant-booking-fulfillment.md)。

## 自动验证记录

2026-10-22 最终定向 **330个用例全部通过**，零失败/错误/跳过：普通/Web86、新增商家确认59、预约65、支付/退款40、候选66、迁移11、文档3。首轮250个通过后，补充资格锁后复核、已接单保护及接单/扫描精确竞争，再按上述范围复测；未重跑全部旧业务。

新增59个真实MySQL/HTTP用例覆盖：账号/角色/本店与类型/OWNER资格，暂停仍读与拒单、禁止接单，锁等待后复核审批及成员；手动停业/下架/改价/分类停用下的原快照；接单截止前/等于/后及学生1小时窗口前/等于/后，真实日期行等待跨界；接单与退款重放、严格请求体/Unicode原因和版本上限；多端并发接单、接单/学生取消、拒单/取消/扫描、截止接单/拒单/扫描/新预约竞争；已接单不被原截止扫描或创建清理退款，截止前接单在扫描等待后仍保留固定acceptedAt；接单/退款/关单/释放/事件故障整笔回滚、实际写后会话过期回滚；真实InnoDB锁超时503及原操作可重试、详情RR在并发退款下保持同一快照。

V1～V10迁移与mysql可执行JAR真实HTTP启动/健康/中文/身份边界检查通过；无新迁移。本轮独立测试实例及目录由脚本停止并清理，未访问用户现有数据库或密码。后续IN_SERVICE/COMPLETED仅用合成状态检验重复接单及禁止取消，未声称开始/完成已实现。

实际运行OpenAPI40个操作、完整62个（40已实现/22待开发）、9分组、87个schemas；生成器一致性、官方OpenAPI3.0 schema、8个请求示例及参数/模型引用和本地文档链接校验通过。人工Swagger/Apifox验收尚未代用户执行，按上文复现即可。

后续预约开始/完成已按ADR-0026实现，当前43个运行操作，步骤见[履约验收](merchant-booking-fulfillment.md)。本页原330个验证记录为确认阶段历史记录，不代表最新一次全回归。
