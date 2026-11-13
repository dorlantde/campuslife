# 商家已接单预约无法履约取消：已确认方案

- 日期：2026-10-22（Asia/Shanghai）。
- 状态：用户已明确“采用并实现”；依据 [ADR-0025](decisions/0025-merchant-booking-cancellation.md)，实际验证见[取消验收](merchant-booking-cancellation.md)。
- 依据：[业务基线](decisions/0001-v1-baseline.md)、[状态与事务](v1-state-transitions.md)、[接口契约](v1-api-contract.md)、[支付 ADR-0023](decisions/0023-booking-mock-payment.md)、[商家确认 ADR-0024](decisions/0024-merchant-booking-confirmation.md)。商家接单后、服务开始前无法履约须全额模拟退款沿用已确认基线；本次定稿精确时间、权限与重复操作细则。

## 1. 推荐范围和理由

本轮只新增 **POST /api/v1/merchant/orders/{orderId}/cancel**，处理本店已接单BOOKING的无法履约取消，返回现有OrderDetail。学生取消、待接单拒单继续使用各自入口，不改变既有行为。

新增一个操作后，运行版40→41，完整规划仍62，待开发22→21。复用order模块、现有退款关闭事务和V9/V10共19张业务表，不新增迁移、XML、依赖或外部基础设施。商家订单列表/事件查询、开始/完成、管理员异常和打印取消仍分别推进。

这一入口补齐商家已经接单但临时不能提供服务的责任处理，尤其是学生已进入不足1小时、不能自行取消的窗口；商家不能用学生取消接口代替自身无法履约记录。

## 2. 允许条件与精确时间

首次取消必须同时满足：

1. 当前账号ACTIVE / MERCHANT、会话有效，为订单所属店铺的ACTIVE OWNER。
2. 本店BOOKING，主单ACCEPTED / PAID，占用ACTIVE，存在与冻结payableAmount一致的成功支付且未退款。
3. **取得店铺/日期/订单相关业务锁后的服务器 now < 预约 startAt**。
4. 模拟资金开关已启用，允许产生新退款。

将基线“服务开始前”具体定为**预约开始时刻前**，而不只判断商家是否点击开始按钮。即使订单仍为ACCEPTED，等于或超过startAt也拒绝该普通取消入口，返回409 CANCEL_WINDOW_CLOSED，不自动关单/退款或认定未到店。这是本次已确认的精确边界，可避免漏点开始后继续走普通取消。

商家不受学生提前1小时限制：不足1小时但仍早于startAt时，可以说明无法履约并全额退款；学生原1小时窗口保持不变。已接单不再使用原acceptanceDeadline，超过付款后30分钟也不重新当作待接单超时。

IN_SERVICE、COMPLETED等非法状态409 ORDER_STATE_CONFLICT，不提供履约中任意退款。到预约开始后尚未标记开始的异常，以及已开始后的异常，将由后续履约/管理员任务明确处理；本轮不新增替代入口，因此暂时仍保留原状态与占用。

## 3. 权限与请求

沿用ADR-0024处理既有责任的权限：正常MERCHANT及ACTIVE OWNER可取消本店合法旧单，**无需店铺当前仍APPROVED**。暂停、手动停业、分类停用、服务下架或改价不阻止其在允许窗口内退款；不重新报价或读取新服务时长。

账号或OWNER已停用则禁止操作，不开放其他商家、普通学生或管理员代用该接口。已接单单不受原待接单扫描退款，停用后的存量异常仍由后续管理员流程解决，不声称本轮已提供该能力。

路径ID为规范正BIGINT十进制字符串。请求仅含reason：

```json
{"reason":"理发师临时无法到岗，无法按约提供服务"}
```

原因去首尾空白后1～300个Unicode码点；缺失、null、空白、错误类型、超长和未知字段400 VALIDATION_FAILED。沿用拒单的严格原因校验，不接收金额、退款比例、任意状态、expectedVersion或额外Idempotency-Key。

| 情况 | 结果 |
|---|---|
| 未登录/会话失效 | 沿用401 AUTH_REQUIRED / TOKEN_INVALID / SESSION_EXPIRED / SESSION_REVOKED |
| 学生或管理员调用 | 403 ROLE_FORBIDDEN |
| 账号停用 | 403 ACCOUNT_DISABLED |
| 本人OWNER未激活/停用 | 403 ROLE_FORBIDDEN |
| 不存在、跨店或非BOOKING | 404 RESOURCE_NOT_FOUND |
| 允许状态，但now>=startAt | 409 CANCEL_WINDOW_CLOSED，无写入 |
| 待支付、待接单、已开始/完成或其他原因关闭 | 409 ORDER_STATE_CONFLICT，无写入 |
| 需要新退款但模拟资金关闭 | 503 MOCK_PAYMENT_DISABLED，无写入 |

先验证身份及归属，锁后重新读取状态；格式校验与HTTP请求体解析沿用现有约定。首次操作先判状态与取消窗口，再要求允许新退款，避免把无效状态或已过窗口误报为资金开关问题。

## 4. 原子结果与重复请求

成功首次操作返回200，原子提交：

- 一笔金额等于原成功支付及冻结payableAmount的成功全额模拟退款；原成功支付保持不变。
- 主单CLOSED / REFUNDED，closeReason=MERCHANT_UNAVAILABLE，closeNote为归一化原因，closedAt为锁后判定的UTC毫秒时刻。
- 预约占用ACTIVE→RELEASED，候选在提交后才重新开放原时段。
- 订单version+1，仅写一条MERCHANT_UNAVAILABLE关闭事件，fromStatus=ACCEPTED、actorType=MERCHANT、actorId为实际账号，事件reason保存原因。
- 退款reason存MERCHANT_UNAVAILABLE、reason_note保存原因；保留原acceptedAt及支付/接单时间、报价和人员快照。不递增店铺/服务/人员配置版本。

重复请求不新增资金或覆盖原责任说明：

| 已有结果 | 本次结果 |
|---|---|
| CLOSED / REFUNDED，MERCHANT_UNAVAILABLE且归一化原因相同 | 200原当前详情；退款号、金额、时间、版本及事件不变 |
| 同原因重试时已到startAt，或模拟资金已关闭 | 身份及本店权限仍有效则200原结果，不重算首次窗口、不新增资金 |
| MERCHANT_UNAVAILABLE关闭，但改成不同原因 | 409 ORDER_STATE_CONFLICT，不覆盖历史原因 |
| USER_CANCEL / MERCHANT_REJECT / ACCEPTANCE_TIMEOUT等其他原因关闭 | 409 ORDER_STATE_CONFLICT，不修改关闭原因、不二次退款 |

关闭后的学生取消、商家接单或拒单不应把MERCHANT_UNAVAILABLE改成其他原因。已有对应接口均会返回状态冲突，保持现有规则。

## 5. 锁、并发和失败

写事务继续RC：当前商家账号→会话→店铺S→本店OWNER复核→日期保护行→订单/预约当前读→退款/关闭/释放/事件。读取定位允许在等待锁前进行，但不能使用等待前的订单快照或Mapper缓存判断最终状态；不反向锁学生账号，提交前复查当前商家会话。

用同一日期与订单锁串行处理学生取消、商家取消及未来开始服务；使用当前状态和服务器版本条件更新，沿用订单/支付唯一退款约束。无需引入客户端版本或另一套幂等记录。

- 商家取消与学生取消竞争：先提交者决定关闭原因；失败方状态冲突，最终仅一笔退款、一条关闭事件。
- 多端同原因商家取消竞争：首次200，后续重放200；不同原因竞争只保留首个成功原因，其余409。
- 原待接单扫描不关闭ACCEPTED；新建重叠预约只有在退款释放已提交后才能成功，否则BOOKING_CONFLICT，不能自行清理未到期的已接单单。
- 未来开始接口须遵守同一锁协议：已关闭不能开始，已开始不能走本次普通取消。当前只用合成IN_SERVICE状态验证拒绝，不声称开始接口已实现。
- 退款、状态、释放或事件任一步失败整笔回滚；提交前会话过期同样回滚。真实死锁/锁超时503 SERVICE_TEMPORARILY_UNAVAILABLE，重试仍重新验证资格、状态和时间。
- 窗口已过不执行补偿关闭，因此CANCEL_WINDOW_CLOSED为无写入错误；区别于现有待接单期限“退款关闭提交后再409”的处理。

## 6. 完成标准和验收

1. Swagger / Apifox：学生创建付款→商家接单→商家填写原因取消→双方详情均为MERCHANT_UNAVAILABLE / REFUNDED / RELEASED，支付保留、退款全额。
2. 验证同原因重放、改原因冲突、重放跨开始时间/资金开关变化，退款和事件不重复、不覆盖原时间。
3. Clock覆盖startAt前/等于/后，真实MySQL日期锁等待跨界后按新时刻判断；不足1小时商家可取消，而学生仍不可自行取消。
4. 覆盖角色/本店/类型、停用账号与OWNER、暂停店铺和手动停业/下架的区别，以及锁等待后的资格复核。
5. 独立MySQL验证多端同/异原因、学生取消竞争和重叠新建；真实唯一约束、锁超时、退款/关闭/释放/事件及会话过期回滚。
6. 模拟资金默认关闭；新退款503无副作用，已有成功事实可合法重放；原待接单扫描及已接单保护不退化。
7. 同步Swagger注解、运行OpenAPI、完整规划、决策及人工点击指南；按改动风险运行相关回归，不访问用户现有库，不以mock代替数据库锁验证。

实现上述一个接口及配套验证。后续建议推进开始/完成履约，之后补充管理员异常；本次不把整个订单模块一次性开发。

本方案已获用户确认。当前实现及实际构建、独立MySQL与接口文档检查记录见[取消验收](merchant-booking-cancellation.md)。
