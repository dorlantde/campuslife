# 打印模拟支付与待接单退款：已确认方案

- 日期：2026-10-24；校园业务时区Asia/Shanghai，响应时间UTC。
- 状态：用户明确“采用并实现”；决策见[ADR-0035](decisions/0035-print-mock-payment.md)，操作与实际验证见[打印支付指南](print-payments.md)。
- 依据：[业务基线](decisions/0001-v1-baseline.md)、[预约支付ADR-0023](decisions/0023-booking-mock-payment.md)、[打印订单ADR-0034](decisions/0034-print-orders.md)、[接口契约](v1-api-contract.md)。已确认的15分钟支付、付款后30分钟接单、待接单可取消全额模拟退款和全部文件引用保护沿用，不重复定稿。

## 1. 本轮范围及完成目标

形成“打印创建→模拟支付→待接单→学生取消或接单超时全额退款”的闭环。商家确认和内容访问后续单独实现；本轮付款不代表商家已经接单。

| 既有入口或任务 | 推荐扩展 |
|---|---|
| POST /api/v1/orders/{orderId}/payments/mock | 按订单冻结类型分派，新增PRINT付款；BOOKING原行为保持 |
| POST /api/v1/orders/{orderId}/cancel | 增加PRINT待接单全额模拟退款，保留未付取消 |
| GET /api/v1/orders/{orderId} | PRINT展示真实payment/refund及固定接单截止时间 |
| 本人订单列表/事件、创建重放 | 如实展示付款/退款后的当前状态及原事件，不自行触发退款 |
| PrintExpiryJob | 未支付、已支付待接单各独立100条，启动及每30秒扫描，每单独立事务 |

复用V10 payment_record/refund_record、V12 print_detail和原订单主表，不新增迁移、表、依赖或支付基础设施。运行操作仍54，完整规划仍62/54已实现/8待开发：扩展已有路径的订单类型能力，不虚增接口数量。

本轮不开放商家PRINT列表/详情/事件、接单/拒单、下载文件、打印中/待取件/确认取件、管理员打印异常或评价。商家和管理员原预约入口继续拒绝PRINT，商家列表PRINT暂空页；学生打印接单后不直接取消的基线不变。

## 2. 首次付款资格与冻结金额

请求仅路径orderId及Bearer身份，无业务请求体，不接收客户端金额、渠道、支付链接或回调地址。ID为规范正BIGINT字符串；非法ID/业务请求体400，未登录401，非正常学生403，他人/不存在订单404。付款无需新的Idempotency-Key，以订单锁及唯一支付事实防止重复付款。

首次PRINT付款推荐同时满足：

1. 当前正常STUDENT及有效会话，本人PRINT + PENDING_PAYMENT / NOT_PAID / QUEUED。
2. 取得全部必要业务锁后取统一毫秒时刻now，严格now < paymentDeadline；等于已经到期。锁等待不延长窗口，paidAt固定为此判定时刻，提交前仍复查会话。
3. 店铺仍具APPROVED、ACTIVE OWNER及ACTIVE/MERCHANT经营账号的公开资格，分类PRINT；否则409 SHOP_UNAVAILABLE，无资金写入，仍可取消或超时关闭。
4. **下单后的手动停业、营业结束、服务/规格停用或改价，不阻止合法旧单付款。** 使用冻结payableAmount，不重新报价、不校验当前价目版本，也不重新要求当前营业。创建已校验营业，本轮延用预约的短期付款承诺，避免15分钟内悄悄改变成交价格。
5. 关联文件元数据仍为本人AVAILABLE且expiresAt=NULL，说明未终结引用仍受保护；异常组合503 FILE_STORAGE_UNAVAILABLE并回滚，不擅自修复生命周期。**付款不重新解析或打开PDF、不取得实例文件互斥。** 创建已经验证真实对象，正常清理不会删除未终结引用；采用元数据复核可保持资金事务短小。外部删除、损坏等磁盘异常仍由下载或后续履约内容读取发现，本轮不承诺付款时已经再次检验磁盘。
6. 显式开启现有模拟资金开关，所有新资金写入受同一开关保护。

锁后发现未付到期，优先原子提交PAYMENT_TIMEOUT / CLOSED / NOT_PAID / ABORTED、关闭事件及文件保留期，再由事务外返回409 PAYMENT_DEADLINE_PASSED。该关闭不新增资金，不因模拟开关关闭而被阻止。关闭行为沿ADR-0034，不能因异常返回而回滚。

首次成功同事务完成：

- 插入唯一payment_record，amount=冻结payableAmount、channel=MOCK、status=SUCCEEDED；long保持最高5000000000分，不经过int或浮点。
- 主订单PENDING_PAYMENT / NOT_PAID → PENDING_ACCEPTANCE / PAID，paidAt=now、acceptanceDeadline=now+30分钟，version+1。
- 打印progress继续QUEUED，不伪造PRINTING或商家确认；文件继续NULL保护，不因付款递增文件版本或重新计24小时。
- 追加一次PAYMENT事件，eventSeq与新版本一致、STUDENT及本人ID。

返回200 PaymentResult，复用orderId/orderStatus/paymentStatus/paymentNo/amount/paidAt/acceptanceDeadline。尚未发生的退款对象为null，订单名称、规格、页数、价格均保持冻结快照。

## 3. 重试、取消与全额退款

所有重试仍检查当前身份及订单归属。已有成功事实的重放绕过新付款的营业资格、文件元数据资格和模拟开关检查，不访问磁盘、不延长任何期限。

| 锁后情况 | 推荐结果 |
|---|---|
| 已有成功支付且未退款的有效订单 | 重复付款200，返回原paymentNo/amount/paidAt/acceptanceDeadline及当前主状态，不增加版本/事件 |
| 待接单已过期但退款尚未提交 | 付款重放仍200，仅返回既有事实；不自动退款或重新开始30分钟 |
| 已全额退款关闭后付款 | 409 ORDER_STATE_CONFLICT，不重新付款 |
| 未付USER_CANCEL关闭后付款 | 409 ORDER_STATE_CONFLICT |
| PAYMENT_TIMEOUT关闭后付款 | 409 PAYMENT_DEADLINE_PASSED |
| PENDING_ACCEPTANCE / PAID且now < acceptanceDeadline，本人取消 | 全额退款，CLOSED / REFUNDED / ABORTED，USER_CANCEL，200 |
| 本人取消时now >= acceptanceDeadline | 先提交全额退款及ACCEPTANCE_TIMEOUT关闭，再409 ACCEPTANCE_DEADLINE_PASSED |
| 已USER_CANCEL关闭，再次取消 | 200返回原关闭/退款事实，不重写时间、版本、事件或文件期限 |
| 已ACCEPTANCE_TIMEOUT关闭，再次取消 | 409 ACCEPTANCE_DEADLINE_PASSED，不改为USER_CANCEL |
| ACCEPTED或后续状态 | 409 ORDER_STATE_CONFLICT，本轮不开放PRINT学生接单后取消 |

退款金额严格等于该订单唯一成功支付金额，校验payment.orderId及amount与冻结应付金额一致。原成功支付记录保留，另插入一笔成功全额refund_record；不增加部分退款、REFUNDING、余额或第三方回调。

退款记录、主状态/支付状态、closedAt/closeReason、QUEUED→ABORTED、版本、关闭事件及文件保留期同事务提交。关闭动作仍为USER_CANCEL或ACCEPTANCE_TIMEOUT，事件包含原因，操作者分别STUDENT本人或SYSTEM/null；不额外合成一条导致序号失配的REFUND事件。

待接单退款不要求店铺仍公开、营业或服务上架，不重新读取PDF磁盘，因此正常学生在店铺暂停后仍可取消旧单；系统也能处理学生停用、经营账号/OWNER停用的到期旧单。文件元数据/关联事实损坏属于不变量异常，整笔回滚并记录标识，不单独改为已退款却留半笔状态。

## 4. 文件保护与恢复

沿ADR-0034：任一引用PRINT订单非CLOSED/COMPLETED，文件expiresAt保持NULL；本单退款终结后，在同一文件行X下普通读统计全部关联订单。仍有其他未终结单则NULL；全部终结则最晚实际closedAt/completedAt+7天。退款不意味着立即删除文件，也不从paidAt或acceptanceDeadline开始计7天。

同一文件跨店铺、多笔未付/已付引用，全部纳入同一算法。重复退款/扫描/创建重放不续期；文件版本仅在期限实际变化时递增，版本上限导致必要更新失败则退款和关闭全部回滚，不能留下半笔资金事实。

使用现有campuslife.mock-payment.enabled（默认false，本机开发已显式开启），不改动本机VS Code密钥或凭据。关闭时：

- 新付款、新退款均503 MOCK_PAYMENT_DISABLED，无新增资金/状态/进度/事件/期限写入。
- 原支付/退款重放和只读详情/历史继续可用。
- 无资金的未付款取消/超时继续工作。
- 已付待接单到期保留原PAID状态及文件NULL保护，不假称退款；记录订单提示。重新开启后按数据库原截止时间补处理，实际关闭后才重算7天。

PrintExpiryJob在原未付100条之外增加待接单100条，各按截止时间/ID定位，每单重新取锁判断、独立提交；已付组开关关闭或某单失败不能阻止未付组关闭。BOOKING两组配额不变，不在打印创建内清理其他旧单，也不从GET触发资金操作。

## 5. 事务与实现边界

RC写事务沿打印已有协议：HTTP学生账号X→会话X→店铺S→文件X→目标订单/打印明细→资金/事件；SYSTEM店铺S→文件X→目标订单/明细→资金/事件，不反向锁学生或经营账号。先定位读取只提供锁入口，主状态/进度/支付事实必须在锁后复核。经营资格沿既有公开当前读判断，不为此反向取经营账号锁。

文件行X在任何打印状态变更前取得，支付、取消、系统退款与清理/新建按同一入口串行。重算其他关联订单仅RC普通读，不跨店铺锁其他订单。支付/退款流程全部是数据库操作，不等待实例文件互斥、不执行磁盘IO；创建与下载原来的“事务外获取互斥”规则不变。

打印写逻辑留在PrintOrderService/PrintOrderTransactions，学生共用支付入口按冻结类型分派；复用MoneyMapper/MockPaymentPolicy、已有支付/退款VO及条件更新。学生列表仍一次RR快照count/page，详情及创建重放读取成功资金事实，商家和管理员预约写入口保留BOOKING防护，不误入预约日期锁或打印操作。

数据库唯一键保证每单至多一笔成功支付/全额退款，不能代替状态与金额校验。中途SQL失败全部回滚，死锁/锁超时503 SERVICE_TEMPORARILY_UNAVAILABLE，保留原订单重试，不从失败SQL中间继续。提交后的期限409与数据库失败明确区分。

## 6. 验收范围

以下是确认的范围，实际已执行项目与结果见[验收记录](print-payments.md#自动验证记录)；Swagger/Apifox提供人工步骤，不声称已代用户点击。

1. Swagger/Apifox：学生上传→报价→营业时创建→15分钟内付款→详情/列表/事件为待接单已付QUEUED；重复付款原事实；本人取消后真实退款对象及ABORTED。
2. 独立真实MySQL/HTTP验证角色/归属、严格请求格式、原金额（含50亿分）、经营资格、停业/服务下架/改价后旧单付款、文件保护元数据不变量及原BOOKING回归。
3. Clock与真实锁等待覆盖付款15分钟、接单30分钟的前/等于/后；断言期限错误返回之前已经提交关闭/退款，重试不重置时间或事件。
4. 真实并发付款/取消/扫描、待接单取消/系统扫描、跨店同文件退款与新建竞争：唯一资金事实、主状态/打印进度/文件期限一致；一单退款后其他未终结引用仍受保护。
5. 在支付插入、状态更新、事件、退款和文件期限写入处注入失败，所有相关事实回滚；文件版本上限不能导致半笔资金或提前删除。
6. 默认关闭/显式开启、关闭时已付到期保留及恢复、100+100独立批次、新JAR启动恢复，测试自动扫描显式关闭、直接调用同一实际方法；测试存储根独立，不访问用户持久文件。
7. 执行受影响的预约支付/取消、打印创建/历史/事件、文件引用/清理/下载及OpenAPI回归；同步注解、两份导入文件、ADR和点击指南。不新增虚假的第55个操作，不把模拟资金称为真实支付。

本次已确认的打印细则：短期旧单付款资格、付款仅校验受保护元数据而不再次打开磁盘、合法重放优先及待接单退款/开关/批次与文件期限的组合行为。商家打印接单及文件访问下轮另行定稿。
