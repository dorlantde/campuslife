# M03.2-A outbox与RabbitMQ发布验收

日期：2026-10-30（America/New_York）。**状态：发布链已验证，通知消费未实现。** 73操作/V20/29张应用表。[ADR-0053](../decisions/0053-reliable-order-messaging.md)、[本机及点击指南](../reliable-messaging.md)、[结构化证据](outbox-publishing-2026-10-30.json)。

## 改了什么

首次预约ACCEPT和打印READY_FOR_PICKUP在原RC事务末尾写固定消息快照；关闭传输亦写READY，写失败/最终会话失败原动作回滚。后台单条短租约认领，事务外发送、mandatory/return/correlated confirm，按token回写；20次上限、指数退避和租约过期恢复。新增V20 message_outbox，原V1～V19校验和不变。

Boot管理Spring AMQP 4.1.1/Rabbit Java client 5.30.0。RabbitMQ 4.3.6官方镜像固定digest，主/死信quorum队列与安全策略参数实际核对。NIO写队列、通道/连接/确认等待有界；不新增无业务用途线程池。运行默认关闭，普通MySQL脚本不会读本机启用配置连Broker，专用脚本才能启用。

## 实际结果

**403项不同相关检查分轮最终通过，零失败/错误/跳过。** 203普通/Web＋182相关MySQL＋18额外outbox/真实Rabbit检查；不重复累计多次运行的相同测试，也不是全项目全套回归。

| 类 | 项数 | 失败/错误/跳过 |
|---|---:|---|
| OutboxPublisherTest | 9 | 0/0/0 |
| RabbitOutboxTransportTest | 2 | 0/0/0 |
| OutboxMysqlIT | 10 | 0/0/0 |
| OutboxRabbitIT | 8 | 0/0/0 |
| MysqlIntegrationIT | 12 | 0/0/0 |
| OpenApiMysqlIT | 3 | 0/0/0 |
| FilePolicyTest | 38 | 0/0/0 |
| RatingCacheTest | 18 | 0/0/0 |
| BookingSlotPolicyTest | 22 | 0/0/0 |
| BookingOrderPolicyTest | 22 | 0/0/0 |
| CouponOrderHashTest | 2 | 0/0/0 |
| PrintOrderPolicyTest | 30 | 0/0/0 |
| PrintQuotePolicyTest | 18 | 0/0/0 |
| RegistrationPolicyTest | 12 | 0/0/0 |
| TokenServiceTest | 17 | 0/0/0 |
| WebFoundationTest | 13 | 0/0/0 |
| MerchantBookingConfirmationMysqlIT | 59 | 0/0/0 |
| MerchantPrintFulfillmentMysqlIT | 79 | 0/0/0 |
| BookingJourneyMysqlIT | 4 | 0/0/0 |
| PrintJourneyMysqlIT | 6 | 0/0/0 |
| CouponOrdersMysqlIT | 19 | 0/0/0 |

10项outbox MySQL验证两类首次/重放、写失败与最终会话到期全回滚、租约恢复原ID/正文、迟到token拒绝、跨重启保留的20次预算、最后租约崩溃终结及SKIP LOCKED；8项真实Rabbit验证成功确认/持久UTF-8、无绑定return、缺exchange、quorum限长nack、停机后业务仍提交/恢复、Broker重启消息保留并同ID再发、禁止事务内网络IO、拓扑参数。单元额外验证确认超时等待、return优先于ACK、任务并发防重入及有界退避/关闭。

确认后未回写的窗口是“获得真实ACK后跳过DB finish，再重启Broker/推进测试时钟认领”的故障注入，非真实杀应用进程。确认TIMEOUT用未完成future单元注入，非真实网络丢包。最终增强非空lease CHECK后，普通MySQL回归新增直接非法状态写入验证并通过；JSON分别保留专项运行迁移指纹及最终源码指纹，明确分轮证据。

专项脚本scripts/test-messaging.ps1实际通过43检查与MySQL JAR smoke；随后一次相关回归203＋182全部通过，并再次通过传输关闭时mysql-profile JAR健康/UTF-8/访问边界。OpenAPI仍73操作；运行快照与设计导入同步，无通知候选冒充已实现。

本机scripts/messaging.ps1 Start/Status/Stop/Status实际通过。开发Broker目前停止、卷保留、传输启用文件为false；只有用户显式Start才开启，并需重启后端。凭据Git忽略且未在报告显示。测试另用自有随机容器/卷、独立MySQL/PDF目录，标签及回环端口校验后清理；失败回归留下的本轮MySQL目录另按验证后的精确GUID路径清理。未读取用户DB/JWT/VS Code凭据，未代启正式库迁移。

## 修正与局限

第一次容器启动因PowerShell字符串/数组拼接使环境键未分行而未就绪，修复后自有资源清理。首轮真实Broker测试中两处断言错误：接收属性需用receivedDeliveryMode，quorum限长可能先短暂超额后才nack；更新为检查真实拒绝最终发生，所有错误结果仍不得SENT。没有放宽生产确认标准。

本轮不做吞吐/容量收益结论；outbox序列化、hash及索引写入有成本。没有消费者/通知HTTP/前端、消费ACK事务窗口、死信目标失效后转移、真实应用杀进程、Lua异步领券、多节点HA、公开部署或人工浏览器业务验收。DEAD暂留待人工排查，B批增加受控Inspect/Replay，不自动重放所有SENT。

下一项：**M03.2-B站内通知消费与本人接口**，按已确认规则实现消费去重、提交后ACK、本人列表/已读及受控恢复；之后M03.3补全真实故障证据。Redis受理/补偿/重建仍待M08.3-A独立协议。
