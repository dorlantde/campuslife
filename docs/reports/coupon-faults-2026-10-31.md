# M08.4-A 优惠券真实进程故障验收报告

日期：2026-10-31。结论：本批范围完成；最终轮110项相关检查通过（84项集成、26项单元，失败／错误／跳过均0），含8个真实JVM崩溃窗口及1项新增通道占满回归。三轮按类名＋用例名去重仍为110项，不将重复执行累加为覆盖数。[机器可读汇总](coupon-faults-2026-10-31.json)／[复现指南](../coupon-faults.md)。

## 改动与发现

新增测试专用`CouponCrashChild`、`CouponFaultsIT`，在实际操作边界暂停后，通过父测试持有的Process对象强制结束自有JVM，再启动独立新进程恢复。使用真实系统时钟，未推进30秒孤儿／重建租约时间，未延长5分钟处理截止。没有测试故障HTTP入口，测试类未进入可执行JAR。

本轮修复一个消息连接配置缺陷：通知与领券各2个消费者占满原共享连接4个通道上限，发布器拿不到通道。仅切换发布连接还不充分，RabbitAdmin声明回调仍可能争用消费池。最终在同一个生产配置文件中完成两点：RabbitTemplate显式使用现有工厂的独立发布连接；RabbitAdmin复用此模板进行初始／重连声明。confirm、mandatory return、通道等待及原有界参数继续保留，未增加Broker、修改路由或业务协议。

真实通道回归持有全部4个消费通道：将模板临时设回旧模式，确认为CONNECTION_FAILED；恢复新模式后，拓扑声明与同一消息发布均成功ACK。负向对照只在隔离测试中使用。两类消费者与发布器同时开启的重启案例，最终均要求SENT且只尝试一次，避免用后续重试掩盖初始化问题。

生产改动仅`RabbitMessagingConfiguration.java`。源文件快照核对：23个迁移、32个脚本、45个前端源文件及pom未改；无新操作／表／依赖，仍75操作、V23、32张应用表。默认SYNC、消息和券Redis关闭，既有开发活动未变，前端暂停。

## 真实崩溃结果

每项均实际强制结束一个自有JVM并启动新JVM；最终数据库发行计数＝券数＝1、消费凭据唯一、outbox唯一且SENT，Redis库存与数据库事实一致，队列排空且无意外死信。原请求身份和截止未变。

| 窗口 | 验证结果 | 本轮杀进程至核对完成 |
|---|---|---|
| AFTER_RESERVE | 无SQL受理事实；真实30秒后永久FAILED仲裁、回收一次；旧键重放失败，新键成功 | 32.066秒 |
| BEFORE_COMMIT | 事务外观察不到请求／outbox；进程死亡回滚，随后孤儿仲裁 | 32.196秒 |
| AFTER_ADMISSION | PENDING/outbox已提交而HTTP未返回；新进程继续发行，同键返回原ID | 6.380秒 |
| BEFORE_ISSUE_COMMIT | 券／累计数／消费凭据未提交；Broker重投后仅提交一次 | 10.168秒 |
| BEFORE_ACK | 发券已提交但ACK未发送，Broker确认未ACK；重启重投后排空，无第二张券 | 10.090秒 |
| BEFORE_CHECKPOINT | Redis已SPENT、SQL checkpoint未写；新进程结算／确认及消息处理不重复扣减 | 6.230秒 |
| AFTER_SEED | SQL仍REBUILDING，等真实租约后新代次接管，恢复OPEN意图与原PENDING | 31.211秒 |
| AFTER_ACTIVATE | Redis已激活但SQL未完成；新受理仍503，接管完成后继续处理旧请求 | 33.069秒 |

以上是单次本机样本，包含JVM启动、调度和Broker指标采样，不是RTO保证或性能基线。详细PID、代次及时长见JSON。checkpoint案例先用真实发行事务准备终态，再由重启后的真实消息链验证结算；其余发行使用真实公开POST和RabbitMQ路径。孤儿案例另保留一条永久FAILED记录。

两项重建接管以RECOVER进程暂关发布／消费，仅先恢复门闩，OPEN后再继续真实Broker处理。消费者有界重试可能将重建中的消息送入死信，本批不把这种情况称为自动重放，也不声称任意组件启动顺序都能无人工干预恢复。

## 执行记录与回归

环境为Windows单机、Java21、Boot4.1.1、隔离原生MySQL8.0、Redis8.2.4与RabbitMQ4.3.6；固定镜像沿用现有脚本。测试凭据均由隔离脚本生成，未读取开发数据库密码或JWT文件。

| 轮次 | 检查数 | 失败 | 解释 |
|---|---:|---:|---|
| 1 | 16 | 3 | 两个孤儿恢复和已提交响应丢失案例发现共享通道耗尽；其余5个故障窗口及8项单元通过 |
| 2 | 110 | 3 | 发布连接隔离后8个崩溃案例通过，但日志仍有首次声明约35秒等待；通道占满正向检查失败，两个路由故障因延迟声明重建了被移除资源而未保持注入状态 |
| 3 | 110 | 0 | 声明也复用发布模板；增强SENT／单次尝试及声明检查后全批通过，JAR启动验证通过 |

最终集成84项：公开入口41、真实JVM故障8、券Redis与消息3、异步发行消息4、原生MySQL12、通知消息4、OpenAPI3、outbox真实Broker9。单元26项：恢复调度3、领券监听5、通知监听4、outbox调度10、传输结果4。包含原路由缺失、交换机缺失、quorum NACK、Broker停机／重启、确认后租约重发、消费幂等等回归；负向场景的预期错误日志不等于检查失败。

完整最终轮已实际执行：

```powershell
.\scripts\test-coupon-redis.ps1 -Phase Messaging -Cases 'CouponFaultsIT,CouponAdmissionIT,CouponRedisMessagingIT,CouponAsyncRabbitIT,OutboxRabbitIT,NotificationRabbitIT,OpenApiMysqlIT,MysqlIntegrationIT' -UnitCases 'CouponRedisRepairJobTest,CouponIssueListenerTest,RabbitOutboxTransportTest,OutboxPublisherTest,NotificationListenerTest'
```

本机原始XML／停点汇总已按轮归档到`.cache/m084a-evidence/round1`、`round2`、`round3`，运行日志为`.cache/m084a-round*.log`；最终子JVM日志位于`target/coupon-faults-18083773945501463788`。这些忽略目录不是随仓库发布的材料；上方JSON保留必要的脱敏汇总。没有重跑整个V1测试集，不把既有历史报告计入本轮110项。

可执行JAR通过mysql模式启动、健康、UTF-8及访问边界smoke；检查JAR不含故障测试类。最终脚本成功清理临时MySQL、Redis／RabbitMQ容器和卷；额外核对遗留子JVM、隔离MySQL进程、测试容器及卷均0。前两轮失败数据库目录在另存证据、核对进程退出后精确删除。原开发券Redis仍停止，持久卷保留。

## 下一批与剩余边界

下一项为M08.4-B，[同条件测量口径](../coupon-measurement-design.md)已整理：同数据／负载、交替配对，分别记录201最终持券与202仅受理的延迟、完整成功耗时、实际发行量、积压及数据库／组件成本。现有每轮单条outbox发布可能构成瓶颈，须测量后解释，不在本批改调度算法。

性能对照尚未执行，没有性能提升、容量或多节点HA结论；没有宿主断电、磁盘损坏实验。独立Redis进程故障等已有C报告，消息死信目标故障已有M03.3报告，不能将它们混作本批新执行结果。当前默认和前端暂停边界继续保持。
