# M03.3 消息故障与并发验收

本专项只使用脚本自有RabbitMQ容器/卷、临时MySQL与虚构订单。运行前Docker Desktop Linux引擎可用；不拿用户当前后端/数据库做断电或杀进程实验。当前业务规则见[ADR-0053](decisions/0053-reliable-order-messaging.md)，结果见[报告](reports/messaging-faults-2026-10-30.md)。

## 复现

```powershell
./scripts/test-messaging.ps1 -Cases MessagingFaultsIT -UnitCases 'OutboxPublisherTest,NotificationListenerTest'
```

测试自建子JVM，使用正常生产配置/事务/监听器，在仅测试类路径的BeanPostProcessor上记录并暂停选定操作。父测试核对阶段标记，再只强制终止自己创建的Process；恢复后检查数据库凭据/通知及队列。没有在生产代码加入暂停接口或调试开关，测试类不进可执行JAR。

- BEFORE_COMMIT：真实通知SQL已执行但事务未提交，另一个连接看见0条凭据/通知；杀进程，重新启动消费者JVM，最终1/1，队列耗尽。
- BEFORE_ACK：消费事务已提交，Channel.basicAck尚未发送，看到1/1及未确认投递；杀进程并重启消费者JVM，真实重投不会产生第二条。
- AFTER_CONFIRM：真实publisher ACK已经返回，outbox尚未回写SENT；杀发布子JVM，保留PUBLISHING。另一应用实例在测试Clock推进到持久租约到期后接管，同ID重发、消费一次。这里是跨实例接管，不冒称等待真实30秒或重启原发布PID。
- 删除自有死信目标队列，等待消费者已经reject；目标持续不存在后恢复绑定。Broker默认内部重试可能数分钟，原消息最终到目标，错误payload不生成消费凭据。

## 死信等待与背压

RabbitMQ官方[at-least-once说明](https://www.rabbitmq.com/blog/2022/03/29/at-least-once-dead-lettering)提到默认重试可达3分钟；[4.3说明](https://www.rabbitmq.com/docs/4.3/quorum-queues)指出源队列会保留待确认死信，缺路由等情况周期性重试。测试保留默认Broker配置，观测上限240秒，不能用60秒未到达就宣称消息丢失，也不能立即恢复目标掩盖故障。

等待时无需人工确认或操作。目标长时间失效会累积源队列资源；本项目不能据此称“永不丢失/无限容量”。原发布链的reject-publish/nack与20次重试上限仍有效；死信不是业务成功，也不自动重新发回主队列。

## 并发与耗时口径

现有发布器单条在途，tryLock防止同实例重叠，MySQL租约/token协调跨实例。监听并发2、prefetch10，沿用框架线程，不增加装饰性的通用线程池。专项另验证发送被阻塞时停止新认领，退出等待最多8秒；这项使用可控阻塞测试，不能当真实网络所有故障都8秒恢复的证明。

保留8次串行发布至通知可见的原始毫秒样本，包含启动监听器及150毫秒轮询开销；不计算“生产QPS提升”，不与M01/M02基准混比。所有恢复耗时标明是否含JVM冷启动/轮询，不能作SLA。

## 验证边界

实际子JVM终止不等于对网络逐个丢包，未做随机ACK丢包代理、多节点失去多数副本、磁盘永久损坏或大规模容量压测。单节点持久卷重启证据、真实进程故障证据和单元超时注入分别列出。测试结束清理自有进程/容器/卷/数据库，诊断日志留target下；开发Broker状态不改，前端继续暂停。
