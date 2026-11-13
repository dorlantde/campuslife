# M03.2-B 通知消费与本人接口验收

日期：2026-10-30。当前75操作/V21/31表。[指南](../notifications.md)、[已确认决策](../decisions/0053-reliable-order-messaging.md)、[结构化证据](notifications-2026-10-30.json)。

## 交付

固定消息大小/身份/类型/版本与原outbox正文/hash核对；消费键串行化，凭据和通知同RC事务提交，提交返回后单条ACK。数据库/事务暂态异常最多3次，事务外等待1/2秒；永久错误/用尽次数进死信，不无限重入队。并发/重放只一次通知效果。

正常学生本人GET分页和PATCH首次已读，90天展示、历史快照、当前角色/状态/会话保护、跨用户404，已读事务最后会话失败全回滚。目标停用不阻止系统保存通知；旧单/券/资金/文件期限不改。V21两表及重放记录字段，V1～V20校验和不变，POM无新依赖。

显式本机无HTTP维护入口支持Inspect及原ID/正文Replay，活动UTC租约拒绝、已消费不再重放，保存最后原因/时间及累计次数；不接受任意JSON。没有公开恢复HTTP，前端仍暂停。

## 实际验证

**253项不同相关检查分轮最终通过，零失败/错误/跳过。** 最终207项普通/Web＋46项MySQL/真实RabbitMQ组件检查，不是一次全项目全套回归，不累计重复执行。

| 类 | 项数 | 失败/错误/跳过 |
|---|---:|---|
| FilePolicyTest | 38 | 0/0/0 |
| RatingCacheTest | 18 | 0/0/0 |
| BookingSlotPolicyTest | 22 | 0/0/0 |
| NotificationListenerTest | 4 | 0/0/0 |
| OutboxPublisherTest | 9 | 0/0/0 |
| RabbitOutboxTransportTest | 2 | 0/0/0 |
| BookingOrderPolicyTest | 22 | 0/0/0 |
| CouponOrderHashTest | 2 | 0/0/0 |
| PrintOrderPolicyTest | 30 | 0/0/0 |
| PrintQuotePolicyTest | 18 | 0/0/0 |
| RegistrationPolicyTest | 12 | 0/0/0 |
| TokenServiceTest | 17 | 0/0/0 |
| WebFoundationTest | 13 | 0/0/0 |
| MysqlIntegrationIT | 12 | 0/0/0 |
| NotificationMysqlIT | 9 | 0/0/0 |
| NotificationRabbitIT | 4 | 0/0/0 |
| OpenApiMysqlIT | 3 | 0/0/0 |
| OutboxMysqlIT | 10 | 0/0/0 |
| OutboxRabbitIT | 8 | 0/0/0 |

9项通知MySQL覆盖两类读写/重放/跨账号/参数及认证、10并发同ID一凭据一通知、写失败回滚凭据并可恢复、篡改拒绝、90天边界、最终会话失效回滚已读、独立JAR维护Inspect/Replay/已消费及活动UTC租约拒绝。4项真实Rabbit验证两种业务通知到达HTTP、重复投递/已提交后重投只有一次效果、篡改进入真实死信而无凭据。4项监听单元验证成功后ACK、永久错误不重试、暂态数据库及事务建立异常有限调用和拒绝。

原outbox的10项MySQL、8项真实Broker以及数据库/OpenAPI检查同时回归。消息脚本使用自有随机标签容器/持久卷、独立MySQL/文件根，结束验证所有权后清理；未访问原MySQL服务或用户上传目录。普通MySQL测试关闭Broker，MySQL模式JAR健康/UTF-8/访问边界通过。第一次外层启动空参数传递错误未运行测试，改用包装脚本后通过；不归咎业务。

最终补齐事务异常分类及维护入口LocalDateTime UTC读取后，只重跑相关4单元＋9MySQL及JAR，不重复所有组件测试。Java维护进程实际运行，PowerShell包装仅语法检查、未代输入用户正式密码。运行/设计OpenAPI均75 implemented / 0 planned。

## 边界与下一项

本轮未代用户人工点击，未做前端、短信/邮件/WebSocket、Lua异步领券、性能/高可用结论。真实杀应用、确认/ACK丢包和死信目标失效恢复仍属于M03.3；已提交后重投是受控逻辑窗口验证，不称真实杀进程通过。小规模阶段保留物理历史及去重凭据，未实现自动清理。

下一项M03.3故障与并发控制验收，补全进程/ACK/死信目标中断的实测证据。之后M08.3-A独立定稿Redis受理/预留/补偿，前端继续暂停。
