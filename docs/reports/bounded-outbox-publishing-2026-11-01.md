# M08.5-B 有界发布实现与正确性验收

2026-11-01。结论：按用户已确认的[ADR-0055](../decisions/0055-bounded-outbox-publishing.md)完成B。**182项不同相关检查最终通过：54项单元、128项集成，最终无失败/错误/跳过。** 本批新增42项（28项组件边界、11项原生消息集成、3项优惠券整链）。[机器汇总](bounded-outbox-publishing-2026-11-01.json)／[使用与复现](../bounded-outbox-publishing.md)。

本批验证实现和正确性，没有执行C的真进程强杀或旧新性能对照；≤5秒/降低≥70%的目标尚未验证，不根据测试完成速度声称性能提升。

## 实现与设计理由

生产修改仅`OutboxPublisher.java`和`application.properties`。共享1秒回调只提交任务，组件私有单工作线程、SynchronousQueue与AbortPolicy，不使用通用Executor Bean或CallerRunsPolicy，不在进程里排待发消息队列。

新增`campuslife.messaging.publisher-batch-size`，环境变量`MESSAGING_PUBLISHER_BATCH_SIZE`，默认32、允许1～32，非法值启动失败。每轮最多32次claim调用，1秒单调继续预算在每次认领前检查；预算不能截断正在处理的一条。上一条ACK且当前租约回写成功后才取下一条，避免预认领整批消耗后续消息的30秒租约。

原同步`publishOne()`仍只处理一条，true包含非ACK及回写失败等“已尝试”结果。后台使用内部NONE/ACKED/ENDED判断，不能直接循环这个boolean。首个非ACK、租约回写失败或SQL/未知结果即结束本轮，未知提交不猜测清空租约或补写SENT。原短RC claim/finish、事务外IO、租约/退避/20次、消息身份、mandatory/return/confirm与消费幂等均复用，没有批量SQL或业务事务重写。

同步单次与后台共用发送互斥；当前一条执行不等于数据库只有一行PUBLISHING。关闭前已进入认领的尝试允许收尾，关闭后不开始下一次。stop幂等，锁等待和私有线程等待共享真实8秒截止，超时尽力中断自有worker，旧token拒绝及租约提供恢复；不承诺整个应用8秒退出或强制取消IO。

## 实际验证

| 层次 | 已执行结果 |
|---|---|
| 配额与时间 | 1/2/32边界、后续tick继续、空扫描结束、1秒等于/超过及nanoTime回绕；Ticker仅用于单元边界，关闭预算仍真实等待 |
| 失败与未知结果 | RETURN/NACK/TIMEOUT/连接失败/SHUTDOWN后停止；ACK但finish=false停止；claim/send/finish异常不继续或猜测回收；原单次方法返回语义保持 |
| 生命周期 | 100次重复tick无任务积压、不在调用线程做IO；自动调度/消息关闭及缺依赖；共享8秒等待与重复stop不延长；关闭前已进入claim可完成，后续不再认领 |
| 配置 | 默认可装配，0/-1/33/非数值/小数拒绝；测试配置有独立profile，避免混入真实应用类路径 |
| 原生MySQL/Broker | 40行多轮排空、单轮不超过32；两个通知类型和券路由；失败一行退避其余READY，实际ACK后另一个原claim取得新token，旧回写不能覆盖；finish SQL后异常真实回滚，原行保留PUBLISHING且其他行未预占 |
| 调度隔离 | 实际单线程TaskScheduler中阻塞发布IO；只有一行PUBLISHING、另外两行READY，预约/打印的真实到期scan仍关闭订单。随后释放IO并继续发送，attempts均1 |
| 优惠券整链 | 20笔真实服务层受理→Lua→outbox→新worker→Broker→消费→Redis结算；两发布器实例独立认领20条、无重复券或凭据；实际ACK后受控抛异常保留租约，推进测试Clock30秒接管同ID重投后只发行一张 |
| 相关回归 | 既有HTTP入口41、用券订单19、通知读写及消费、outbox业务原子性、SKIP LOCKED、30秒旧token、20次DEAD、路由return/nack、Broker停启/重发、MySQL约束和75操作OpenAPI |

调度隔离通过在共享调度器上显式安排真实`scan()`验证；Clock推进至原15分钟期限，不是实际等待15分钟或原30秒注解周期。确认丢失是实际ACK后注入异常，不是已做真实网络丢包。两发布器是在同进程独立组件实例，证明该场景的DB认领/幂等，不是多节点HA验收。新增整链从服务层受理进入，HTTP沿原41项回归，未把两者混称同一个新HTTP旅程。

## 轮次、问题与最终结果

| 轮次 | 检查数 | 失败 | 处理 |
|---|---:|---:|---|
| 受限终端首次unit | 未产出完整报告 | 不计通过 | 停在Mockito代理加载、诊断也无法连接；仅结束已核对PID/Java路径/启动时间的自有Maven、fork与attacher，允许本机通信后重跑 |
| unit1 | 42 | 6 | 重设Mock时提前调用旧Answer污染计数；默认配置测试依赖装配不完整。修正夹具，检查轮次自然结束后再断言，确保负值配置因实际参数错误失败 |
| unit2 | 42 | 1 | 等待辅助方法在上一轮已结束时多提交了一轮空扫描。先判断完成再提交，保留“空扫描计入配额”的实现规则 |
| messaging | 108 | 0 | 46单元＋62集成，JAR smoke通过 |
| coupon | 74 | 0 | 8单元＋66集成，JAR smoke通过 |

按类名/用例名合并最新结果为182项，未把266次重复执行当作不同覆盖。最终消息批：OutboxBatchTest28、原发布器10、传输4、通知监听4；OutboxBatchIT11、原outbox MySQL10/Broker9、通知MySQL9/Broker4、券消息4、MySQL12、OpenAPI3。券批：恢复调度3、券监听5；新增券整链3、原入口41、用券19、Redis/MQ3。

原始XML按轮保留在`.cache/m085b-evidence/unit1`、`unit2`、`messaging`、`coupon`，日志为`.cache/m085b-unit-restricted.log`、`m085b-unit-elevated.log`、`m085b-unit2.log`、`m085b-messaging.log`、`m085b-coupon.log`。忽略目录不随仓库发布，汇总保留脱敏数量及范围；复现命令见使用指南。没有重跑整个V1，也不将历史110项真进程报告算作本轮新验收。

## 旧版封存与工程边界

任何编辑/编译前封存`.cache/m085-baseline-2026-11-01`，含完整旧生产classes/资源376个、可执行包、pom、115项生产依赖SHA256及修改前源码指纹。旧jar SHA256：`6F406141A158C54B158403FF63A5F85686BF255B3D7EB2AD610079162FE73246`。B结束逐文件/制品复核未变，新包115项依赖指纹一致，C可据此使用旧二进制，不依赖未提交Git HEAD或用batch-size=1冒充旧线程模型。

原491个实现文件中，仅2个生产文件与旧OutboxPublisherTest构造调用变化，另新增3个测试文件；POM、23个迁移、34个既有脚本和45个前端源文件不变。仍75操作/V23/32表，默认SYNC与消息/券Redis关闭，未改本机.vscode/.local、活动或业务规则。正式JAR检查不含新增测试/故障入口。

两批临时MySQL、RabbitMQ/Redis容器与卷已清理，额外核对测试JVM、隔离MySQL进程、测试容器/卷均0。旧基线及开发持久卷保留，没有全局清空。Java21下可执行JAR的mysql启动、健康、UTF-8及访问边界均通过。

## 下一项

M08.5-C执行已确认的真进程故障与一次旧新各24轮同口径对照；实现旧/新类路径选择并防Bean混装，保留版本顺序、异常、成本和未完成数据，不能只以本次快速排空示例替代测量。目标仍是各档ASYNC完整p95中位≤5秒且较旧版下降≥70%，不要求胜过SYNC，不是业务SLA；未达标也保留结论并收口，不扩线程/组件反复调参。前端继续暂停，原工程目标保留。
