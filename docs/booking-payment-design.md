# 预约模拟支付与待接单退款：已确认方案

- 日期：2026-10-22（Asia/Shanghai）。
- 状态：用户已明确“采用并实现”；实现依据为 [ADR-0023](decisions/0023-booking-mock-payment.md)，验收见[支付指南](booking-payments.md)。
- 依据：[业务基线](decisions/0001-v1-baseline.md)、[待支付预约 ADR-0022](decisions/0022-booking-orders.md)、[接口契约](v1-api-contract.md)、[状态与事务](v1-state-transitions.md)、[数据库设计](v1-database-design.md)。已确认的 15 分钟支付、支付后 30 分钟接单、待确认可取消和全额模拟退款沿用，不重复定稿。

## 1. 推荐范围和理由

先实现“支付 → 待接单 → 学生取消或接单超时退款”闭环，再单独实现商家接单/拒单。支付引入成功资金事实，取消和超时必须同步维护退款与占用，不能只把主订单改成已付款。

| 接口/能力 | 本轮范围 |
|---|---|
| POST /api/v1/orders/{orderId}/payments/mock | 新增，本人正常 STUDENT，首次及合法重复请求均 200 |
| 已有 POST /api/v1/orders/{orderId}/cancel | 增加待接单已支付预约的全额模拟退款，保留未支付取消 |
| 已有 GET /api/v1/orders/{orderId} | 返回真实 payment/refund 事实和固定接单截止时间 |
| 超时扫描与创建锁内清理 | 扩展到已到接单期限的 PENDING_ACCEPTANCE 预约，全额模拟退款并释放 |

新增一个操作，运行版 36 → 37，完整规划仍 62，剩余 25 个待开发。V10 建立 payment_record、refund_record 两张实际使用表，业务表 17 → 19 张。沿用 order 模块，不引入支付 SDK、Redis、MQ 或外部资金服务。

本轮不开放商家接单/拒单、开始/完成、已接单取消、商家订单列表/详情、管理员异常退款或打印支付。待接单的这笔预约可以由本人取消或在 30 分钟后超时退款，不会假称已经获得商家确认。下一项再完成商家确认入口及其存量权限细节。

## 2. 首次模拟支付

请求仅包含路径 orderId 与 Bearer 身份，不接收金额、支付渠道、回调地址或其他业务请求体；ID/请求格式非法为 400。商家/管理员访问为 403，他人订单或不存在统一 404。

首次支付须同时满足：

1. 本人学生账号、当前会话仍有效；订单为 BOOKING + PENDING_PAYMENT + NOT_PAID，占用 HELD。
2. 在统一锁协议内检查服务端 now，**now < paymentDeadline** 才可支付；等于截止时间已经到期。
3. 店铺仍 APPROVED、OWNER 有效、经营账号 ACTIVE/MERCHANT。失去上述经营资格时为 409 SHOP_UNAVAILABLE，不生成支付事实，未支付单仍可取消或超时关闭。不新增暂停店铺/停用账号接口。
4. 手动停业、服务下架、改名称/价格/时长不阻止合法原订单付款，仍使用冻结的原金额/时长。付款不重新报价，也不重新要求距预约开始至少两小时；原订单已在创建时合法暂占。
5. 新增模拟资金流水的开关已显式启用。

锁内发现未支付已到期：先提交 PAYMENT_TIMEOUT 关闭/释放，再返回 409 PAYMENT_DEADLINE_PASSED，不写支付记录。等待锁不延长付款窗口；以取得相关业务锁后的服务端判定时刻作为 paidAt 和截止比较依据，不能使用等待前的时间。通过该时刻判断后不在写入中反复移动支付时刻；支付与超时按锁内状态判定收敛。提交前仍复查会话，身份失效整笔回滚，不留下支付事实。

成功原子完成：

- 插入唯一成功支付事实，amount=原 payableAmount，channel=MOCK、status=SUCCEEDED；不修改原报价快照。
- 主订单改为 PENDING_ACCEPTANCE / PAID，设置 paidAt 和 **acceptanceDeadline=paidAt+30 分钟**。
- 占用 HELD → ACTIVE，订单 version +1，写同序号 PAYMENT 事件。支付时间使用服务端 UTC 毫秒时间。

PaymentResult 沿用草案字段：orderId、orderStatus、paymentStatus、paymentNo、amount、paidAt、acceptanceDeadline。不返回外部支付链接、账户余额或虚构第三方回调。

## 3. 重复支付和待接单取消

付款不新增一套创建幂等键，按订单锁及 payment_record.order_id 唯一约束保证一笔成功事实。所有重试仍先验证身份及归属。

| 情况 | 确认结果 |
|---|---|
| 已有成功支付且未退款，重复支付 | 200，返回原支付事实和当前订单状态；不更新 paidAt/接单期限/版本，不增加事件 |
| 重复付款时已超过接单期限，但退款关闭尚未提交 | 仍只返回已有支付事实，不视为新付款或延长期限；取消/扫描按原期限处理 |
| 已退款关闭后再次支付 | 409 ORDER_STATE_CONFLICT，不重新扣款或创建第二笔支付 |
| 未支付 USER_CANCEL 关闭后支付 | 409 ORDER_STATE_CONFLICT |
| PAYMENT_TIMEOUT 关闭后支付 | 409 PAYMENT_DEADLINE_PASSED |
| PENDING_ACCEPTANCE 且 now < acceptanceDeadline，本人取消 | 全额模拟退款，CLOSED / REFUNDED / RELEASED，closeReason=USER_CANCEL，200 |
| 本人取消时 now >= acceptanceDeadline | 先提交全额模拟退款及 ACCEPTANCE_TIMEOUT 关闭/释放，再返回 409 ACCEPTANCE_DEADLINE_PASSED |
| 已因 USER_CANCEL 退款关闭，再取消 | 200，返回同一关闭/退款事实，不新增流水/事件，不覆盖时间 |
| 已因 ACCEPTANCE_TIMEOUT 退款关闭，再取消 | 409 ACCEPTANCE_DEADLINE_PASSED，不改成 USER_CANCEL |
| ACCEPTED 或后续状态取消 | 本轮仍 409 ORDER_STATE_CONFLICT；已接单取消随商家确认任务定稿实现 |

退款金额严格等于成功支付金额，一次全额；保留原成功支付记录，另建成功退款记录，不把原支付事实改写成失败。退款、主状态/支付状态、占用释放和关闭事件同事务提交，version 只增加一次。V1 不引入 REFUNDING、不支持部分退款，没有公开“任意退款”接口。

OrderDetail.payment 为 paymentNo/amount/status/paidAt；refund 为 refundNo/amount/status/refundedAt。尚未发生的对象为 null；退款后 payment 仍保留原成功事实，主订单 paymentStatus 为 REFUNDED。

## 4. 模拟资金开关与超时恢复

使用 `campuslife.mock-payment.enabled=${MOCK_PAYMENT_ENABLED:false}`，默认关闭。实现时在本机 VS Code 开发启动项中显式设置 MOCK_PAYMENT_ENABLED=true，指南说明如何开/关；默认配置不自动生成模拟资金流水。

开关约束所有新增模拟支付及退款，包含学生取消、后台超时、未来商家操作和创建预约中的到期清理，不能只保护支付按钮。

- 正常待支付单首次付款、待接单单需要退款关闭时，开关关闭均为 503 MOCK_PAYMENT_DISABLED，无资金/状态/事件写入。
- 已经存在的合法支付/退款事实可以幂等返回，不因开关关闭重新制造或篡改事实；普通详情读取始终可用。
- 不需要资金操作的未支付取消、PAYMENT_TIMEOUT 关闭继续工作，不受此开关影响。
- 开关在启动配置中读取，不提供 HTTP 动态切换。带着未终结已支付单关闭开关时，待接单到期不会伪造退款/释放，仍保持保守占用；恢复启用后从数据库原截止时间补处理，不延长期限。后台记录可定位订单的提示，不静默把已付款单改成未付款关闭。

沿用 30 秒扫描、每单独立事务。分两组各最多 100 个到期候选：未支付组和待接单组，每轮最多 200 个；已支付退款被关闭或失败不能饿死原未支付释放任务。启动首扫，按截止时间/ID 排序；扫描只定位 ID，锁后复核状态/期限，重复/多实例调用只能成功退款关闭一次。

候选查询仍为只读 RR，未提交关闭的 HELD/ACTIVE 继续占用。创建新预约在日期锁内清理重叠到期单：未支付沿原规则，待接单单在开关允许时全额退款/关闭，再校验占用；不允许退款时保留原占用，返回 BOOKING_CONFLICT。清理失败或后续新建失败整笔回滚，后台仍可独立重试。

## 5. 数据库和事务

V10 沿用已有草案的最小成功事实表：

- payment_record：id、order_id、payment_no、amount、channel=MOCK、status=SUCCEEDED、paid_at。order_id 和 payment_no 分别唯一，金额为正，订单外键。
- refund_record：id、order_id、payment_id、refund_no、amount、reason、reason_note、status=SUCCEEDED、refunded_at。order_id、payment_id、refund_no 分别唯一，订单/支付外键，金额为正。

金额一致性、订单/支付/退款关联和状态组合由同一事务校验；数据库唯一键不能代替合法迁移。没有“失败支付也保存成功记录”的做法，不提前新增通用支付尝试框架。

写事务沿用 RC：HTTP 本人账号 → 会话 → 店铺 S → 日期保护行 → 订单 → 支付/退款/事件；SYSTEM 不锁登录身份。定位数据允许先读，锁后必须重新核对；不复用等锁前的主/扩展快照。经营资格只在受店铺锁保护的当前读取中判断，不持店铺锁反向锁经营账号。配置路径继续店铺 X → 日期行 → 后续资源。

创建锁内处理多个到期订单按订单 ID 升序。不得从超时扫描先拿订单写锁再反向拿日期锁。关闭并提交后才向 HTTP 返回期限 409，资金相关错误在任何半笔写入后均完整回滚；死锁/锁超时沿用 503 SERVICE_TEMPORARILY_UNAVAILABLE，不自动重复生成资金事实。

## 6. 完成标准

1. 本人首次支付、反复/并发支付只有一条资金事实；金额和原报价快照一致，改价不影响旧单。支付后候选仍排除 ACTIVE。
2. 支付与未支付取消/超时竞争：取消/到期先成功则未付款关闭；付款先成功则已付款待接单；学生取消随后锁到已付款待接单状态则全额退款关闭。各种结果均须资金事实、主状态和占用一致。
3. 待接单取消与超时、创建锁内清理、重复扫描竞争，仅一次全额退款、一次关闭事件；释放后新预约可以成功。
4. 注入 Clock 验证两个截止时间的前/等于/后及锁等待跨界；重复付款不重置 30 分钟期限，期限错误须证明已经提交关闭。
5. 全链路开关验证：默认关闭不生成资金；已有事实仍可读；关闭时已付到期保持占用，恢复后处理原截止时间；未支付释放不被影响。
6. 权限/归属、暂停或经营资格变化、手动关店/服务下架、非法请求体、真实 MySQL 唯一约束和故障回滚分别验证。支付/退款/事件写入中途失败，不残留半笔资金事实。
7. 同步 Swagger、实际运行 OpenAPI、完整规划和点击指南；用虚构数据在独立 MySQL 验证，记录执行与未执行项目，不以 mock 证明锁正确性。

人工流程：开启本机模拟开关 → 学生创建并付款 → 详情为 PENDING_ACCEPTANCE/PAID/ACTIVE，有支付流水及固定接单期限 → 重复支付核对同一流水 → 本人取消后 REFUNDED/RELEASED 且仅一笔退款 → 另一笔等待 30 分钟及扫描后自动全额退款。商家确认入口后续单独交付，精确时间与并发不要求人工改系统时间。

本方案已获用户确认。实际构建、迁移与测试结果以[支付验收记录](booking-payments.md)为准。
