# M03.1 可靠消息与通知方案（已确认）

日期：2026-10-30。**用户已明确采用本方案并授权从M03.2-A实现**；正式[ADR-0053](decisions/0053-reliable-order-messaging.md)记录已确认范围，实际结果见[发布链指南](reliable-messaging.md)。A/B拆分及M08.3-A独立定稿已确认，第10节具体Redis细则仍是候选，不因本方案通过自动批准。本篇保留原推荐作为设计依据，当前状态以路线图/验收报告为准。

## 1. 为什么引入MQ及本轮范围

真实场景：学生希望得知预约已获商家确认、打印任务已可取件。这些站内通知允许短暂延迟，却不能让RabbitMQ停机导致原订单失败，也不能重复显示同一次动作。订单的资金、资源占用、优惠券状态及文件保护继续原MySQL同步事务。

推荐只做两种通知：BOOKING_ACCEPTED、PRINT_READY_FOR_PICKUP。均发给订单归属学生；不先做商家通知、短信/邮件、WebSocket、退款通知或营销推送。已有order_event是审计，不承担投递状态或确认职责。

核对的真实插入点：

| 通知 | 现有源码位置 | 同事务产生，合法重试不重复产生 |
|---|---|---|
| BOOKING_ACCEPTED | BookingOrderTransactions.merchantDecision首次ACCEPT分支，orders.accept及events.append之后、事务提交之前 | 仅BOOKING；原合法重放分支直接返回 |
| PRINT_READY_FOR_PICKUP | PrintOrderTransactions.merchantFulfill中ready=true首次推进，events.append之后、事务提交之前 | 原waiting/completed重试提前返回；START不生成 |

两者不增加订单事件序号或版本；沿原业务动作的eventSeq。新outbox写失败则订单事务回滚，避免已提交动作没有待发事实；Broker网络故障发生在提交后的发布阶段，不回滚订单。此举新增一次数据库写入及索引成本，必须验证，不能宣称零影响。

## 2. 通知规则与两个候选接口

- 收件人固定studentId，保存订单号、店名等当时快照，不复制登录名、联系方式、文件名/内容、备注或管理员核实原因。固定模板表达“在某时刻确认/进入待取件”；提示以订单当前状态为准。延迟消息不根据当前状态丢弃历史通知，也不让已取消订单看起来仍可取件。
- 消费是系统任务，不依赖原HTTP会话持续有效。目标账号停用/店铺暂停也可持久保存通知；只有当前正常STUDENT本人能读取/标已读，恢复后可查看保留范围。通知不授予其他订单或文件权限。
- 推荐GET /notifications：page默认1、size默认20至100，read可选true/false，createdAt/id倒序，count/items/当前身份同一短RR；返回前核对会话期限。仅本人，返回id/type/orderId/orderNo/shopName/occurredAt/createdAt/readAt及服务端固定标题/正文，不展示消息内部载荷。
- 推荐PATCH /notifications/{notificationId}/read，无业务请求体：首次写服务器毫秒readAt，重复200保留原时间；他人/不存在/超出展示期统一404。事务账号X→会话X→通知X，提交前会话复核，不锁订单或店铺。
- 通知从首次落库createdAt起保留展示90天，等于90天不展示；GET不删除/不标已读。小规模阶段不自动物理删除notification、outbox或去重凭据；后续清理必须保留去重墓碑及失败恢复来源，单独定稿。业务occurredAt与落库createdAt区分，不用消息迟到时间覆盖动作时间。
- 不补发接入前历史事件。M03.2起两个动作无论传输开关是否开启都同事务写outbox；开关仅控制后台发布/消费，默认关闭，恢复后发送积压。这与M02关闭缓存仍维护版本的原则类似，但不共用缓存实现。

未来实现后预计75操作，建议分批V20/V21共新增3表至31表。**这是候选交付计数，当前不改运行OpenAPI或业务契约。** 前端消息页仍暂停；Swagger/Apifox可以验收全部新入口。

## 3. 选型与本地运行边界

推荐Spring Boot管理版本的spring-boot-starter-amqp，使用Spring AMQP/RabbitTemplate及监听容器，先核对有效依赖版本与Jackson3 JSON转换，不手工混搭客户端版本。推荐RabbitMQ 4.3.6 management官方镜像作为本轮候选：官方发布页显示4.3系列仍在社区支持期，4.4刚发布；实施准备阶段验证镜像可获取并固定digest，再写真实可用命令。版本/镜像变动需说明，不能写成已安装。

本机使用项目自有隔离容器、卷、vhost及账号，AMQP和管理端口只绑定127.0.0.1；凭据留Git忽略配置，使用非guest专用账号。当前只证明单节点重启恢复，不证明磁盘损坏或多节点高可用。Docker/现有容器运行时是否可用在M03.2-A核实，本轮不启动/下载。默认关闭时普通测试、启动不依赖Broker。

拓扑：持久direct exchange campuslife.events.v1、notification主队列及死信exchange/queue；主/死信队列均quorum。业务消息持久化，不设消息TTL或用TTL驱动业务超时。主队列显式delivery-limit=5，死信使用at-least-once策略及其必需的overflow=reject-publish配置；部署时验证策略已实际生效，不能只创建一个名为DLQ的队列就称消息不会丢。队列类型/参数不一致应报错，禁止自动删队列重建。

预取10、消费者并发2；单个消息一次数据库事务。不上来创建通用消息SDK或跨服务框架；notification按模块内分层，messaging仅负责outbox发布及当前必要的投递能力。

## 4. 三张表与消息身份

| 候选表 | 核心字段/约束 | 职责 |
|---|---|---|
| message_outbox | messageId唯一UUID；eventType/schemaVersion=1；aggregateType/orderId/eventSeq；recipientId；固定JSON payload及SHA-256；status/attempts/nextAttemptAt/leaseToken/leaseUntil/createdAt/publishedAt/lastErrorCode；唯一(eventType,orderId,eventSeq,recipientId)；待发扫描索引 | 与订单原事务一起保存不可变内容；单独记录发布状态，SENT仅表示Broker确认并已路由，不等于通知已落库 |
| message_consumption | PK(consumerName,messageId)，payloadHash、processedAt；不可变DONE凭据 | 通知与消费去重在同一事务提交；不把未提交PROCESSING当成功，不拿redelivered标记当唯一去重依据 |
| notification | id、sourceMessageId、recipientId、type、orderId/orderNo/shopName快照、occurredAt/createdAt/readAt；唯一(sourceMessageId,recipientId)及本人分页索引 | 一次动作只显示一次；不反向更新订单或资金 |

消息格式：messageId、eventType、schemaVersion、aggregateType/id、eventSeq、recipientId、occurredAt、payload；AMQP messageId与正文一致，contentType=application/json，UTF-8，最多16KiB。traceId只用于诊断，不用作幂等键，不携带JWT/密码。不可使用Java序列化或依据消息中的任意类名实例化对象。

数据库是消息身份和快照来源。消费者校验白名单类型、版本、字段、正文hash与该messageId的不可变outbox事实；同ID不同内容按永久错误隔离，不能覆盖原消费凭据。先以通知单消费者实现；优惠券后续用不同consumerName、独立队列与业务键，不能让通知队列竞争领取消息。

## 5. 可靠发布与进程恢复

1. 订单RC事务内追加outbox，在原资源/订单/券操作之后，不反向锁收件人账号。提交前会话复核失败时outbox一并回滚。提交后回调至多用于唤醒，不作为唯一发送来源。
2. 单个发布任务每轮至多取1条到期READY或租约已过期PUBLISHING；短RC事务用FOR UPDATE SKIP LOCKED认领，写随机leaseToken及30秒leaseUntil后提交。
3. 在事务外发送并等publisher confirm，mandatory=true且启用returns；确认等待上限3秒、连接/请求超时有界。只有本次确认成功且无return才尝试标SENT。return、nack、超时/断连均不能标成功。关联结果按本次publish attempt区分；发送异常不是HTTP订单失败。
4. 回写必须WHERE messageId＋leaseToken＋PUBLISHING匹配。过期实例迟到回调不能覆盖新租约，亦不能覆盖别的发送结果。若确认已到但本机回写前崩溃，租约到期按同messageId重发，这是允许的重复窗口。
5. 默认每秒调度、单任务单条在途；失败指数退避1/2/4/.../60秒，每条最多20次自动尝试后DEAD，保留payload、错误码及时间，人工修复后可按同ID恢复READY。重启不清零次数、不丢租约。连接长时间中断不会无限忙循环。
6. 使用框架现有调度与监听并发；单实例任务防重入，数据库租约支持实例之间认领。先不自建线程池；JUC验证覆盖任务重入、退出/在途等待有界和共享状态保护。吞吐不足先测再调并发，不制造线程池装饰简历。

Broker停机时outbox逐步积压，原订单仍能提交；outbox自身SQL失败必须导致原动作失败。监控READY/PUBLISHING/DEAD数量及最老等待、确认延迟、return/nack/超时、重复消费次数，不能只看队列长度或HTTP成功率。

## 6. 消费、重试和死信

监听包装层与事务服务分开：每次调用事务服务重新开启短RC事务，消费凭据插入→notification插入→提交完成之后才basicAck(multiple=false)。不得在@Transactional方法返回前ACK，亦不允许吞掉提交异常后ACK。

同ID重复先比对hash/业务类型；消费凭据已提交且内容一致则直接ACK，notification唯一键第二层兜底。并发插入冲突要精确处理对应唯一约束；其它约束/数据库异常不能误当成功。事务失败没有DONE凭据，允许重新执行。

可恢复数据库竞争/短暂故障采用最多3次本地尝试（包括首次），间隔1秒、2秒，等待在数据库事务外；不每次失败nack(requeue=true)形成热循环。用尽后reject/nack(requeue=false)进死信。未知类型、schema/hash/字段错误不重试，直接死信。消费进程在提交后ACK前退出会重复投递；提交前退出会回滚。连接/容器重启造成的重复由Broker显式delivery-limit及业务凭据共同约束，不能把本地三次次数当作跨重启总次数。

死信是待排查事实，不自动发回主队列。管理端只看消息ID、类型、时间、错误分类；修复后本机受控Inspect/Replay操作从原outbox加载不可变消息、记录reason与重放次数，并使用原messageId重新发送。具体脚本在实现时给出实际验证过的用法，不新增公开“重发任意JSON”HTTP入口。已DONE重复重放不会再次展示。SENT但无消费凭据也不等于丢失，先查主队列/死信/消费者；不得盲目把全部SENT改READY。

不假定DLQ永远可用；验证目标队列不可用时主quorum保留待转移消息、目标恢复可转移。outbox保留提供额外人工恢复来源，但不将其说成Broker单节点磁盘丢失也自动完全恢复。没有跨系统恰好一次承诺。

## 7. 乱序、权限及故障边界

通知是不可变历史，不据消费时订单状态生成新的业务动作。先待取件、后管理员退款的消息迟到时仍说明“曾进入待取件”，查看当前订单重新走原权限/状态。列表按通知落库时间/id排序，展示原发生时间；不保证跨队列全局有序。

操作者会话到期导致原事务回滚时无待发事实；消息消费本身不需要原会话。学生当前停用不能读取；旧店铺/商家停用不隐藏本人的保留通知。消费者不锁目标账号再取订单资源，不读磁盘，不生成支付/退款，不改变券或文件期限。

## 8. 验证与完成标准

| 检查 | 通过标准与证据 |
|---|---|
| 事务原子性 | 两个业务动作后注入outbox写失败：订单版本/事件/资源全部回滚；提交前会话过期亦无消息；原合法重试不新增outbox |
| 发布 | Broker停机、无绑定return、nack/确认丢失、发布确认后回写前崩溃；原ID恢复发送，状态无误标SENT，迟到租约回调不能覆盖新认领 |
| 消费幂等 | 同ID并发10次：1凭据/1通知；同ID异内容进死信；通知写失败则凭据与通知都不提交 |
| ACK窗口 | 分别在提交前、提交后ACK前杀死测试消费者/进程，重启后最终仅1条通知 |
| 重试/死信 | 可恢复故障按有界退避，永久错误直接隔离；重启计数/租约仍有效；目标DLQ不可用及恢复转移实测；人工同ID重放不重复 |
| 权限/历史 | 跨用户404、停用/角色/会话保护、重复已读时间不改、90天边界、乱序/退款后迟到历史文案、当前订单权限独立 |
| 回归与成本 | 真实MySQL＋自有RabbitMQ隔离环境，保留业务提交/发布/落库三段时间、消息SQL及积压恢复数据；原两类订单、用券/文件回归，不能只用mock或web-only |
| 可操作交付 | Swagger/Apifox列表及标已读、固定镜像/本地启停和失败Inspect/Replay指南；脚本只清理自身GUID数据库/文件/容器/卷，不碰现有服务 |

全部对应事实未通过前不写M03已完成。单节点测试不作为多节点可用性证据；本轮方案没有性能数据。

## 9. M03.2拆分，避免一次大实现

- **M03.2-A：隔离环境与outbox发布。** 固定镜像/digest、默认关闭的传输配置、outbox迁移及两个订单写点、认领/确认/恢复，先证明Broker停机不影响业务、SQL失败原子回滚、同ID重发。仅落本批表，建议V20一表至29表；暂无新HTTP，仍73操作。
- **M03.2-B：站内通知消费与本人接口。** 消费凭据/notification两表及2个接口，建议V21至31表、75操作；消费事务/ACK、去重、权限/已读及点击指南。阶段计数以实际迁移为准，不提前建立空表。
- **M03.3：故障与并发证据。** 真实进程/连接中断、死信目标失效与恢复、重放、有界退出及延迟/成本记录；不重复跑无关性能大实验，不以文档代替真实组件验证。

## 10. 优惠券后续复用及必须独立落定的协议

2026-10-30 M08.3协议已按[ADR-0054](decisions/0054-async-coupon-claims.md)确认：采用5分钟、30秒孤儿仲裁、专用Redis/门闩及B/C/D拆分。B/C/D已交付；下列2分钟为未采用的历史候选，具体规则以新协议为准，C已用真实组件验证，公开异步受理D已按[指南](async-coupon-admission.md)接通，M08.4性能/真进程窗口仍待验收。

本稿先定消息公共约束：未来COUPON_ISSUE_REQUESTED按持久claimId作为业务身份、messageId作投递身份，同一claim仅一个发行效果；发行库存条件更新、持有券唯一键、请求终态在同一MySQL消费事务。202只在持久PENDING＋outbox提交后返回。消费者不要求原HTTP会话继续有效；重试/失败均可本人查询。通知的收件人/90天规则不套用到券请求处理期限。

为避免把复杂Redis协议混进第一条MQ实现，推荐在M08.3前增加明确的**M08.3-A异步受理/预留与重建协议定稿**，届时结合真实MQ故障结果确认下列候选；这是对原“所有细节都放M03.1”的拆分，理由是受理状态/Redis代次/补偿属于新增券生命周期，与公共发布确认不同。目前仍是待确认技术候选：

1. 每活动持久gate(mode=OPEN/REBUILDING、generation)，Lua预留含reservationId、student/key、generation及有限回收索引；成功Lua不等于受理成功。MySQL入口锁后复核当前账号/会话/店铺、活动窗口及gate代次，写唯一(student,key)的PENDING/outbox才可202。请求仍写MySQL，不能称数据库零压力。
2. 活动停止前持久受理保留处理资格，不重新判断claimEnd或STOPPED；使用截止、当前账号/店铺安全资格、最终MySQL库存仍约束发行。推荐处理截止=min(acceptedAt＋2分钟,useEndAt)，到界转可查询FAILED；这是新增候选细则，需M08.3-A明确确认，不静默应用。
3. Lua后DB前崩溃：回收器不能仅超时INCR。先在MySQL对相同student/key写或读取持久拒绝/终态墓碑；若PENDING已存在就不能释放。迟到受理必须看到墓碑拒绝，成功发行永不补偿。不同reservationId但相同业务键的竞争也必须按唯一键仲裁。
4. 消费、超时与失败补偿竞争先由MySQL对request条件更新决定唯一终态；Redis只按同reservationId/generation且未已结算标记条件释放一次。FAILED不允许晚消费发行，SUCCEEDED永不返预留。通知消费成功凭据可复用投递幂等思路，但不能代替券业务唯一约束。
5. Redis丢数据时先以gate X锁切REBUILDING并升代次，等正在持gate S的受理/消费提交后再冻结发行/终态写入；网络IO放事务外。旧代次迟到入口拒绝，新Redis代次重建从MySQLissued及所有有效PENDING扣除，不能只算total-issued。重建的PENDING映射保留claim/reservation身份，核对初始化哨兵及库存后再条件开放gate；失败保持关闭。消费者暂停而非把重建误当业务失败。新/旧代次结算目标及恢复者租约须具体字段/并发矩阵证明。
6. 故障时不自动切回同步入口与异步共同抢库存。模式切换须停受理、排空/终结在途、对账后再开；普通下单/付款/退款仍走M08.2同步事务。超时墓碑、代次、处理截止、受理停止锁序与表迁移必须在M08.3-A形成正式ADR后才写Lua/异步代码。

这使M03可完成真实可展示的MQ链路，后续领券复用已验证的发布/投递机制；不把未解决的跨Redis/MySQL窗口包装成“有outbox就原子”。

## 11. 官方核验依据与确认记录

RabbitMQ官方明确publisher confirm与consumer acknowledgement互相独立；确认不证明消费者业务提交，连接故障可能要求重发并导致重复：[确认文档](https://www.rabbitmq.com/docs/confirms)、[可靠性](https://www.rabbitmq.com/docs/reliability)。持久/单节点不等于多节点HA，quorum及死信策略也有前置条件：[quorum](https://www.rabbitmq.com/docs/quorum-queues)、[DLX安全](https://www.rabbitmq.com/docs/dlx)。Spring的confirm/return和异常策略应显式配置，不能依赖默认重入队：[RabbitTemplate](https://docs.spring.io/spring-amqp/reference/amqp/template.html)、[错误与恢复](https://docs.spring.io/spring-amqp/reference/amqp/resilience-recovering-from-errors-and-broker-failures.html)。候选版本来源：[发布与支持信息](https://www.rabbitmq.com/release-information)（本轮读取2026-10-30，实施前再次核实镜像）。

用户已确认：两个学生通知场景；历史/迟到展示、90天展示及已读规则；outbox同订单提交、默认只关闭传输、RabbitMQ隔离单节点/显式确认/有界重试与死信；按A/B小批实现，以及把Redis特有受理协议单列M08.3-A。通过不等于批准第10节每条候选或声称MQ已实现。
