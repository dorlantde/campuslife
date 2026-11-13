# M08.5-B 有界outbox发布使用与验收

2026-11-01，按用户已确认的[ADR-0055](decisions/0055-bounded-outbox-publishing.md)交付发布器实现与正确性回归。[B报告](reports/bounded-outbox-publishing-2026-11-01.md)给出实现记录；后续[C真实故障及旧新性能对照](reports/outbox-publisher-comparison-2026-11-01.md)也已完成，三档完整p95中位0.58～1.15秒、较旧异步降93.88%～96.95%，≤5秒目标通过，保留成本及适用范围。以下B用例继续可用于正确性复现。

## 当前行为

共享调度仍每秒唤醒一次，但只提交任务；网络/SQL工作由组件私有单线程处理。该线程没有待执行任务队列，每轮最多32次claim调用，在1秒单调继续预算内逐条处理。已经开始的一条可以超过1秒收尾，不能把它理解为网络超时或业务SLA。

每条继续使用短RC认领、事务外发送、短RC条件回写。只有ACK且当前leaseToken回写成功才继续下一条；遇空扫描、忙、非ACK、SQL/未知结果或旧租约回写失败结束本轮。不会预占32条租约。已有历史未决PUBLISHING可能保留，单线程只约束当前执行中的尝试，不等于数据库里PUBLISHING总数始终≤1。

消息关闭时不查询或发送；仅自动调度关闭时，原`publishOne()`仍可同步尝试一条。该方法true表示已尝试，不表示SENT或消费成功，后台没有用这个boolean直接循环。无新增HTTP操作，现有Swagger/Apifox路径不变。

关闭幂等，停止新轮次及新尝试；关闭前已进入的claim可能在关闭请求后提交，允许当前尝试收尾。锁等待与线程等待共用8秒截止，超时尽力中断自有线程。该预算不强制取消IO、不延长业务期限，也不是应用总退出时间。仍使用原30秒租约、旧token拒绝、1～60秒退避、最多20次及消费幂等。

## 配置与回退

```properties
campuslife.messaging.publisher-batch-size=${MESSAGING_PUBLISHER_BATCH_SIZE:32}
```

允许整数1～32，非法值启动失败；默认消息与券Redis仍关闭，活动仍默认SYNC。需要启用开发消息时沿用原[消息指南](reliable-messaging.md)，正常重启后使用新发布器；没有修改本机.vscode或.local配置。

将批次设为1可以降低每轮配额，但仍使用私有线程，不能把它当作原二进制或旧性能基线。完整回退须先停止新进程，使用保留旧制品；在途事实按原租约恢复，不能清空outbox或重置attempts。没有新迁移、依赖、消息字段、路由或消费者并发变更，仍75操作/V23/32表。

## 已执行的自动验收入口

性能/故障操作不适合通过新增HTTP调试开关暴露。以下命令在项目根目录PowerShell运行，均使用随机专用依赖与临时数据库，不访问开发数据；Docker Linux引擎须已运行。

```powershell
.\scripts\test-messaging.ps1 -Cases 'OutboxBatchIT,OutboxMysqlIT,OutboxRabbitIT,NotificationMysqlIT,NotificationRabbitIT,CouponAsyncRabbitIT,MysqlIntegrationIT,OpenApiMysqlIT' -UnitCases 'OutboxBatchTest,OutboxPublisherTest,RabbitOutboxTransportTest,NotificationListenerTest'

.\scripts\test-coupon-redis.ps1 -Phase Messaging -Cases 'CouponOutboxBatchIT,CouponAdmissionIT,CouponRedisMessagingIT,CouponOrdersMysqlIT' -UnitCases 'CouponRedisRepairJobTest,CouponIssueListenerTest'
```

第一批包含批次/时间/失败/配置/关闭单元检查，真实MySQL/RabbitMQ下的有界发布、两种通知与券路由、租约、回写回滚及实际订单到期处理；第二批覆盖新发布器→真实领券消费→Redis结算、两个实例、受控确认结果丢失后重投，以及原HTTP/用券订单回归。

调度隔离案例在同一个单线程TaskScheduler上调度发布回调与真实预约/打印`scan()`，用可控阻塞保持发布IO在途，核对一个PUBLISHING、其余READY且订单关闭。业务Clock推进到原15分钟期限，没有真的等待15分钟，也没有把显式scan称为验证了原30秒注解周期。确认结果丢失是在实际ACK后注入异常，不声称做了真实网络丢包。

XML位于`target/surefire-reports`和`target/failsafe-reports`，下一轮前保存所需证据。测试脚本成功后回收自身MySQL、容器与卷；失败时保留诊断目录并核对精确路径/所有权后清理，禁止全局prune或删除整个.cache。

## C的旧版资产与已交付对照

旧版在任何代码编辑/构建前封存于`.cache/m085-baseline-2026-11-01`：`classes`完整生产类路径、`campuslife-before.jar`、pom、376个类/资源及115项依赖SHA256、修改前源码指纹。B结束也要校验该目录未变。**C完成前勿清理此目录**，它不包含开发密码或JWT，不依赖未提交工作区的Git HEAD。

C已使用同版无mock驱动分别加载完整旧/新生产类路径，旧目录替换当前生产类目录，不能简单前置后保留新目录造成Bean混装；两版本串行并先排空。旧新各24轮，版本顺序和局限已归档；选择器及实际命令见[C复现指南](outbox-publisher-comparison.md)。本机旧基线继续保留，Git忽略目录不会随空仓库拉取自动获得。

`benchmark-coupons.ps1`默认运行当前代码；显式`-ComparePublisher`才进行封存旧版/新版对照，包含指纹及类路径校验。必须另选归档目录并标注版本，不能覆盖或冒充M08.4-B旧版复现。C一次完整固定参数对照已达到目标，本项结束，不自动扩大线程/组件调参。
