# M08.4-A 优惠券真实进程故障验收

## 用途与边界

本批沿用ADR-0054检验进程消失后的恢复，不新增业务规则、HTTP操作、迁移或组件。前端继续暂停。SYNC与ASYNC的性能对照另见[M08.4-B口径](coupon-measurement-design.md)，尚未执行，不依据202响应快慢作收益结论。

本轮发现并修复消息配置问题：两类消费者各2个长期通道，占满原共享连接的4个通道上限；同时启用发布调度时，发布器无法取得通道。RabbitTemplate改用现有连接工厂提供的独立发布连接，RabbitAdmin复用此模板，避免首次建立发布连接／重连时声明回调仍争用消费池。保留发布确认、mandatory return及原有界参数，不增加Broker。`OutboxRabbitIT.publisherConnectionWorksWhenAllFourConsumerChannelsAreOccupied`持有4个真实通道，以旧配置作为失败对照，再验证新配置能声明拓扑并将同一消息确认ACK。

`CouponFaultsIT`从真实公开POST进入，使用独立MySQL、Redis、RabbitMQ以及带测试类路径的子JVM。在指定操作边界写入本轮标记后等待，由父测试通过自己持有的Process对象强制结束；重启的是新JVM，使用真实系统时钟及现有恢复调度。故障钩子仅存在于`src/test`，正式JAR没有故障入口。标记和汇总只记录模式、业务ID、PID、时间，不记录密码／JWT／请求头。

## 可复现入口

在项目根目录的PowerShell运行（需要本机Docker引擎可用，Java21和MySQL测试工具沿用现有工程配置）：

```powershell
.\scripts\test-coupon-redis.ps1 -Phase Messaging -Cases 'CouponFaultsIT' -UnitCases 'CouponRedisRepairJobTest,CouponIssueListenerTest'
```

复用现有隔离脚本创建专用ACL/AOF/noeviction Redis、RabbitMQ及临时原生MySQL；测试先核对容器test标签与本轮随机身份，不能针对开发服务运行。会话和签名只使用脚本生成的临时环境，日志不输出凭据。脚本清理自己创建的容器和卷，保留忽略目录中的日志和结果。失败时按脚本提示核对精确路径与自有进程后处理，禁止全局清空Docker资源或用户文件目录。

预期该类执行8项，另有两个单元测试类共8项。XML分别位于`target/failsafe-reports`和`target/surefire-reports`。运行汇总在`target/coupon-faults-evidence.json`；子JVM日志与停点标记在其中folder指定的`target/coupon-faults-*`目录。每次构建可能覆盖XML，需在下一轮前复制本轮相关结果。历史已执行结果见[报告](reports/coupon-faults-2026-10-31.md)，不把本节预期当作执行证据。

## 八个故障窗口

| 停点 | 杀进程前检查 | 重启后必须成立 |
|---|---|---|
| AFTER_RESERVE | Lua已HELD，SQL请求和outbox均不存在 | 等真实30秒孤儿期限，数据库永久FAILED仲裁后归还；旧键不复活，新键可领取 |
| BEFORE_COMMIT | outbox已在事务内写入但未提交，其他连接看不到请求／outbox，Redis仍HELD | MySQL回滚；同上仲裁回收，不因调用异常直接返库存 |
| AFTER_ADMISSION | PENDING和outbox已提交，HTTP仍未返回 | 新JVM发布／消费同一消息，一张券；同键200重放，原截止不变 |
| BEFORE_ISSUE_COMMIT | 发行和消费凭据已在事务内写入但未提交，外部均不可见 | 连接死亡回滚，Broker重投，新事务只提交一次券／计数／凭据 |
| BEFORE_ACK | 发券和凭据已提交，basicAck尚未发送 | 重投后识别原凭据，不重复发券或扣库存 |
| BEFORE_CHECKPOINT | Redis已SPENT，SQL结算checkpoint尚未落库 | 新JVM条件结算／checkpoint，不二次扣减；本例先调用真实发行事务准备终态，再由真实MQ验证重复消费 |
| AFTER_SEED | 重建已回填但尚未激活，SQL仍REBUILDING/resume=OPEN | 等真实30秒租约，新JVM以新代次接管，恢复OPEN意图；原PENDING和5分钟截止保留 |
| AFTER_ACTIVATE | Redis已激活但SQL重建未完成 | SQL门闩仍拒绝新受理；新代次重建接管后才开放，原待处理请求正常发行 |

每项最终核对数据库累计发行=券数=1、唯一消费凭据、outbox数量、SENT／单次发布尝试、无死信、队列ready/unacked排空、Redis(total/free/held/spent)与数据库事实可解释。这里Broker始终健康，单次尝试断言用来发现初始化饥饿，不表示生产环境禁止合法重试。孤儿场景另有一条永久FAILED及一条新键成功请求。消费停点在杀进程前还通过Broker管理API确认未ACK消息，恢复后核对排空，不能只看到SQL终态就宣称重投已处理。测试结束强制收回自己持有的全部子进程，再清理临时业务数据。

## 恢复的操作边界

重建接管两个案例特意以RECOVER子进程仅启动恢复调度，暂不启动发布器和消费者；SQL恢复OPEN后才通过真实Broker继续处理。理由是现有消费者使用有界重试，REBUILDING期间消费可能进入死信，不能把“有死信留存”声称为自动重放。该选择验证受控恢复顺序，不证明所有组件同时自动启动都能无需人工干预完成。

其他案例以NORMAL子进程同时启动恢复、发布和消费。测试不修改生产默认开关、既有活动或5分钟业务期限，不把测试等待上限当作业务期限。

本批证明应用JVM强制结束／重启窗口。Redis进程中断、AOF恢复、OOM和运行身份变化已有[M08.3-C证据](reports/coupon-redis-recovery-2026-10-31.md)；消息确认和死信目标故障已有[M03.3证据](reports/messaging-faults-2026-10-30.md)，这些不是本批重新执行的项目。没有宿主断电、磁盘损坏、多节点HA或性能收益结论。
