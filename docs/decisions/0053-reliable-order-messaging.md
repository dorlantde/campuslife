# ADR-0053 订单通知与可靠消息

M03.2-B更新：V21及本人两接口已验证，当前75操作/31表；[通知指南](../notifications.md)/[报告](../reports/notifications-2026-10-30.md)。下文A阶段边界为历史记录，真实ACK/死信目标故障留M03.3。


日期：2026-10-30。状态：用户明确采用[方案](../reliable-messaging-design.md)，授权从M03.2-A实现。已确认两种学生通知、90天展示/本人已读、outbox与订单同事务、默认关闭传输、RabbitMQ及有限重试/死信、A/B拆分。Redis特有协议另列M08.3-A，原第10节细则仍是候选。

## 决定与本批范围

BOOKING首次商家ACCEPT和PRINT首次READY_FOR_PICKUP在原RC事务内追加outbox；其余动作不生成，合法重试不生成，历史事件不补发。SQL失败回滚原动作，提交前会话失败一并回滚。关闭网络传输仍必须保存待发事实。Broker故障仅使后台发布失败，不能把订单事务变成跨网络事务。

A批增加V20 message_outbox一表，73操作/29表。没有消费凭据、notification表、消费者或新HTTP；B批再按实际增加。当前SENT仅代表持久消息被Broker确认且无路由退回，不表示学生已收到通知。

## 实际实现

- Boot 4.1.1管理Spring AMQP 4.1.1和RabbitMQ Java client 5.30.0；使用ObjectMapper显式序列化固定JSON并发送原始UTF-8字节，避免默认Java序列化或任意类名转换。16KiB上限、SHA-256、32位十六进制UUID messageId，重发不改正文/ID。
- RabbitMQ 4.3.6 management镜像固定digest `sha256:54cb013d76cd1fd6c0a0708f34062ad61c452bb6f016b434c6c33f1a765d2b58`；主/死信quorum队列，主队列at-least-once死信、reject-publish及delivery-limit=5显式参数。当前单节点，只验证重启恢复，不证明多节点HA/磁盘丢失安全。
- 短RC认领FOR UPDATE SKIP LOCKED，30秒租约，单条在途。网络IO在事务外，mandatory＋returns＋correlated confirms。一次发布关联使用leaseToken，只有ACK且无return才标SENT；回写按messageId/leaseToken/PUBLISHING条件。迟到结果不能覆盖新认领。
- 默认每秒调度，单实例tryLock防重入；发送失败指数退避1～60秒，认领最多20次后DEAD，次数跨重启保留。第20次进程退出亦不会无限重认领；租约到期可终结DEAD。关闭时停止新认领并最多等待在途8秒，未回写的租约可恢复。
- 客户端连接2秒、握手/RPC/通道等待3秒、confirm等待3秒；NIO写队列64/入队等待2秒、缓存通道4，避免只给future设超时却无限排队。不同阶段时间不可简单当成一个3秒端到端SLA。2026-10-31 M08.4-A修复：通知／券消费者共4个长期通道使共享池耗尽，RabbitTemplate显式使用现有工厂的独立发布连接，RabbitAdmin复用此模板使声明回调也走发布池；发布池仍有界且保留confirm/return。不靠扩大消费者共享上限处理，真实通道占满对照及JVM恢复见[报告](../reports/coupon-faults-2026-10-31.md)。

## 数据与锁的取舍

outbox保存独立的不可变审计快照，orderId/recipientId为逻辑引用，不加反向账号/订单FK锁：追加位置已在原业务锁序末尾，不因收件人FK检查反向取得学生账号锁；恢复不依赖订单当前状态。身份来源于已锁定的合法订单，不允许HTTP写任意消息。业务唯一(eventType,aggregateId,eventSeq,recipientId)、消息PK及状态/金额无关CHECK由数据库兜底。小规模阶段不物理清除成功/失败消息，不从order_event临时推导投递状态。

outbox写入、JSON/hash和索引增加成本；无吞吐提升结论。所有权校验的容器脚本只操作本次GUID容器/卷；开发Broker与测试Broker完全分开。普通MySQL回归明确关闭传输，不受本机启用文件影响，专用联测才可启用。

## 故障与阶段边界

真实Broker已覆盖确认、无绑定return、限长nack、停机后的业务提交/恢复、Broker重启持久消息及“确认后未回写”同ID重发；实际进程杀死、消费事务与ACK窗口、死信目标失效/恢复和消费者去重在B/M03.3继续验证，不冒充本批通过。确认超时通过不完成future的单元注入验证有界等待；不将其写为已做真实网络确认丢包。

A暂不提供公共重放HTTP或执行任意JSON入口；DEAD不自动回READY，保留事实供B批受控Inspect/Replay。开发默认停止，用户按[指南](../reliable-messaging.md)显式Start；通知列表/已读在B批才可验收。
