# 商家打印接单后无法履约取消：已确认方案

- 日期：2026-10-25；校园时区Asia/Shanghai，响应时间UTC。
- 状态：用户明确“采用并实现”；已按[ADR-0037](decisions/0037-merchant-print-cancellation.md)落实，操作与实际验证见[指南](merchant-print-cancellation.md)。
- 依据：[V1业务基线](decisions/0001-v1-baseline.md)、[预约商家取消ADR-0025](decisions/0025-merchant-booking-cancellation.md)、[文件引用ADR-0034](decisions/0034-print-orders.md)、[打印支付ADR-0035](decisions/0035-print-mock-payment.md)、[商家打印确认ADR-0036](decisions/0036-merchant-print-confirmation.md)。

## 1. 推荐任务与完成标准

扩展既有POST `/api/v1/merchant/orders/{orderId}/cancel`，让本店商家对已经接单、尚未开始打印且无法履约的PRINT说明原因，全额模拟退款并关闭。这样本阶段ACCEPTED打印单获得明确的退款终结路径；原BOOKING取消时间窗口保持ADR-0025规则。

复用BookingCancellation(reason)、OrderDetail、现有退款与关闭原因MERCHANT_UNAVAILABLE。不新增路径、表、迁移、依赖、模块或客户端幂等键。运行仍54个操作，完整62/54/8，V1～V12、21张业务表。打印写事务仍放既有PrintOrderService/PrintOrderTransactions，按冻结订单类型分派，不进入预约日期锁事务。

本轮只做接单后普通取消；商家内容下载、开始打印、待取件、学生确认取件、管理员PRINT异常及评价仍留到后续。学生PRINT接单后仍不能直接取消。

## 2. 权限和取消边界

正常MERCHANT、有效会话及本店ACTIVE OWNER才可操作，店铺归属由服务器决定。无所属店铺、跨店或不存在404；本店成员失效403。本人账号停用或会话无效按现有401/403规则拒绝。处理旧单不要求店铺仍APPROVED或正在营业：暂停、停业、营业时间结束、服务/价目停用或改价、目标学生停用均不阻止正常商家承担退款责任。

首次取消推荐同时满足：

1. PRINT，主状态ACCEPTED、付款PAID，打印进度QUEUED，acceptedAt非空；startedAt、readyAt、pickedUpAt均为空。
2. 存在与本单冻结金额/paidAt一致的唯一成功支付，尚无退款事实。
3. 文件元数据仍属订单学生、AVAILABLE、expiresAt=NULL，沿用既有未终结引用保护不变量。元数据异常503 FILE_STORAGE_UNAVAILABLE，整笔回滚；不重新打开或解析PDF，磁盘对象丢失不阻止正常元数据下退款。
4. 订单版本可递增；所有新退款仍须开启原模拟资金开关。

PRINT没有预约startAt或预计取件承诺，因此不增加预约时间、一小时限制或自接单起的最长取消时限。即使已经超过原支付/30分钟接单截止，只要仍是上述已接单未开始组合，就允许取消；原接单期限只约束待接单状态。

未来开始打印后，进度和主状态应一并变更，普通取消必须拒绝；不能只因没有预约时间就允许履约中退款。本轮只做边界保护，不据此开放PRINTING或管理员异常功能。

## 3. 请求、结果与重复操作

请求沿用严格JSON，仅reason必填字符串，strip后1～300个Unicode码点；null、数字、空白、超长或未知字段400 VALIDATION_FAILED。orderId沿用规范正BIGINT字符串。示例：

```json
{"reason":"接单后设备故障，无法完成本次打印"}
```

成功200 OrderDetail，同一事务提交：

- 唯一全额成功退款，金额为原冻结实付金额；保留原成功payment。
- 主订单CLOSED/REFUNDED，closeReason=MERCHANT_UNAVAILABLE、closeNote=归一化原因、closedAt=锁后统一毫秒时刻、version+1；保留acceptedAt、paidAt和所有冻结规格/金额。
- 打印QUEUED→ABORTED，不伪造开始、待取件或取件时间。
- 一条MERCHANT_UNAVAILABLE事件，fromStatus=ACCEPTED、toStatus=CLOSED、actorType=MERCHANT、actorId=实际操作者，reason为同一归一化文本；退款reason_note保存同一原因。不额外合成REFUND事件。通常CREATE/PAYMENT/ACCEPT/关闭的eventSeq为0/1/2/3。
- 按全部关联订单重算文件保护期限，不在取消时删除磁盘。

| 锁后情况 | 推荐行为 |
|---|---|
| 首次合法已接单未开始PRINT | 全额退款关闭，200 |
| 已MERCHANT_UNAVAILABLE/REFUNDED/ABORTED且原因相同，当前操作者仍有权限 | 200原事实，不重写版本/关闭时间/退款/事件/文件期限；资金开关关闭也可重放 |
| 已商家取消，但原因不同 | 409 ORDER_STATE_CONFLICT，不覆盖原因 |
| 未付款、待接单、其他原因关闭、已开始或已完成 | 409 ORDER_STATE_CONFLICT，无新资金或状态写入 |
| 待接单已到期限而调用本取消入口 | 仍409 ORDER_STATE_CONFLICT，不代替reject/学生取消/扫描处理超时 |
| 首次合法取消但新模拟资金关闭 | 503 MOCK_PAYMENT_DISABLED，原状态及文件保护保持 |
| 订单版本上限 | 409 ORDER_STATE_CONFLICT，无写入 |
| 必要文件期限变更失败/版本上限 | 503 FILE_STORAGE_UNAVAILABLE，连同退款与状态整笔回滚 |
| 数据库死锁/锁等待失败 | 503 SERVICE_TEMPORARILY_UNAVAILABLE，保留原orderId/原因重试 |

读取、列表和事件通过已有入口展示真实关闭/退款。学生的创建幂等重放返回原单当前详情，不恢复被取消订单；之后付款、接单、学生取消等动作不能覆盖原商家责任。

## 4. 原子性、锁和文件保护

沿ADR-0036商家RC协议：商家账号X→会话X→本店店铺S→OWNER当前资格→文件X→主订单X→打印明细X→资金/事件；必要锁取得后读取now，提交前再复核会话。不反向锁目标学生账号，不持有数据库锁等待实例文件互斥，不在事务中访问磁盘。

定位普通读不能作为最终状态，等待后以锁后订单/打印明细判断。结果和重放使用锁后视图，保持已有MyBatis定位缓存修复。复用退款逻辑时须把事件fromStatus从固定PENDING_ACCEPTANCE改为实际锁后原状态；预约、打印待接单退款的事件语义不能被一并改错。

退款、关闭、进度、版本、事件及必要文件期限写入同事务。仍有任意同文件未终结订单（跨店也算），expiresAt继续NULL；最后一单终结后为全部订单最晚实际终结时间+7天，仅期限实际变化递增文件版本。其他引用在文件X保护下用RC普通读聚合，不跨店锁其他订单。

与同单取消/重复接单、学生取消、扫描、同文件新建和其他订单终结协调。商家取消先提交后接单409；合法接单重放先返回后，取消仍可继续提交，跨请求不承诺结果永远不变。系统扫描不把ACCEPTED当待接单退款；学生接单后取消409。取消与新引用先后提交的任何合法顺序都不能留下未终结引用却可清理的文件，也不能重复退款。

## 5. 验收范围（实际执行记录见指南）

1. Swagger/Apifox：学生上传/报价/创建/付款→商家接单→本店cancel填原因→学生与商家详情/列表/事件核对退款和MERCHANT_UNAVAILABLE；本人文件核对最后终结+7天。
2. 真实MySQL/HTTP检查身份/会话/成员/归属、暂停/停业/学生停用、严格reason/Unicode、未付款/待接单/已接单/关闭边界；对合成已开始/待取件/完成组合只验证拒绝，不声称对应履约已实现。
3. 已超过原30分钟仍合法取消（会话须仍有效），固定锁后closedAt、真实锁等待后状态复核；同原因重试、异原因冲突、资金关闭及恢复、最高冻结金额全额退款。
4. 在退款、主状态、进度、事件、期限及最终会话复核处注入失败，确认所有事实回滚；订单/文件版本边界和文件保护元数据异常。
5. 真实并发双取消/同异原因、取消与重复接单/学生取消/扫描，跨店同文件末笔终结、取消与新引用竞争；持有实例文件互斥或目标学生账号锁时仍可完成元数据取消，排除反向锁或磁盘IO。
6. 回归原BOOKING取消与窗口、PRINT接拒单/付款/学生取消、文件保护及历史事件；同步注解、两份OpenAPI、ADR和点击指南。测试只用独立临时MySQL和测试文件根。

本提案定稿的是PRINT“尚未开始”的精确状态组合、无预约时间限制、旧单退款权限与重复操作结果；已有资金开关、锁顺序及文件保护继续沿已确认规则执行。本次授权范围已落实；实际执行与未执行的验证以指南记录为准。
