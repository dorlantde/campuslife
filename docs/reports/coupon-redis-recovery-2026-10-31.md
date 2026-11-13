# M08.3-C：Redis券预留与恢复验收报告

交付日期：2026-10-31（纽约时区；工作跨10月30～31日）。结果：**191项不同相关JUnit检查分轮最终通过**，最终失败/错误/跳过均0；75操作/V23/32张应用表。不是全部V1全量、HTTP异步上线、QPS或HA结论。明细见[机器可读记录](coupon-redis-recovery-2026-10-31.json)。

## 实际交付

依[ADR-0054](../decisions/0054-async-coupon-claims.md)实现独立ACL/AOF everysec/noeviction Redis、显式独立Lettuce连接，Lua有身份预留/条件结算、MySQL孤儿FAILED墓碑仲裁、代次影子重建及持久租约接管。B的发券/超时/outbox/消费凭据继续复用，不重做另一套发行事务。

V23只增加gate.resume_phase及约束；重建恢复原OPEN/PAUSED，旧B REBUILDING行的未知原阶段保守回填PAUSED。V1～V22 SHA-256不变，前端未改。没有新增依赖或HTTP路径，公开新异步受理仍关闭、默认SYNC、仓库Redis/消息默认关闭。

较长重建由私有单工作线程承接，AtomicBoolean＋SynchronousQueue不重叠、不排队；不向Spring暴露Executor/TaskScheduler Bean，保留原MVC异步和调度自动配置。调度入口快速返回；关停中断并最多等待2秒，重建协作中断、数据库仍受租约围栏保护。该等待预算不是整个应用退出SLA。

## 检查分布与关键证据

| 检查类 | 去重数量 |
|---|---:|
| CouponAsyncMysqlIT | 37 |
| CouponAsyncRabbitIT | 4 |
| CouponOrdersMysqlIT | 19 |
| CouponRedisIT | 48 |
| CouponRedisIsolationIT | 1 |
| CouponRedisMessagingIT | 3 |
| CouponRedisUpgradeCheck | 1 |
| MysqlIntegrationIT | 12 |
| NotificationRabbitIT | 4 |
| OpenApiMysqlIT | 3 |
| OutboxMysqlIT | 10 |
| RatingCacheIntegration | 19 |
| CouponIssueListenerTest | 5 |
| CouponRedisRepairJobTest | 3 |
| RatingCacheTest | 18 |
| RabbitOutboxTransportTest | 4 |

以(class,testcase name)合并最后结果，重复执行不累计。两次BeforeAll设置失败没有执行目标场景，记录为阶段失败而不伪造新增用例。原XML在Git忽略.cache/m083c-evidence/round*，对外记录只提取名称与结果，不包含系统属性或凭据。

- **48项真实Redis/MySQL专项**：12学生争3份预留恰3个HELD；同学生/键/异键不重复；30秒前1ms/等值/后1ms；补偿先于迟到Reserve写CLOSED但不加库存；PENDING与FAILED唯一键仲裁/四轮真实竞争；不同活动全局键冲突只释放本孤儿；停用学生仍可清理。
- 成功消费后HELD→SPENT不返库存，失败只释放一次且不删新rid指针；丢预留回包、checkpoint写后异常、缺成功rid有/无重建证明、旧代次checkpoint及迟到Lua，不产生重复扣/返。原同步成功及券过期仍永久计spent，不重写旧持有事实。
- 205条PENDING分成200＋5构建，完整HSCAN多页枚举205个身份；按MySQL issued/PENDING恢复，真实过量PENDING按原acceptedAt/id确定保留，其余FAILED；失效/到期不占保留量，原deadline不延期。
- 协调者seed后/Redis激活后注入中断，DB保持REBUILDING；30秒租约等值拒绝续租，接管升新代次，旧激活/DB开放失败；Redis先OPEN不授权测试数据库受理。线程中断也保留可接管状态。协调者故障为注入异常/中断，不冒充真实JVM kill。
- Redis真实stop/start与强制kill/start，AOF保留观察、run_id变化强制重建；不假定kill前最新预留零丢失，恢复以SQL事实为准。实际见过管理连接PING就绪而应用连接仍未重连，入口保持关闭，经连接恢复/租约接管继续。
- WRONGTYPE、哨兵/计数/记录损坏、当前control/epoch异常TTL均拒绝并重建；仅已关闭旧代次设技术清理TTL。CONFIG maxmemory=1的真实OOM不产生不可定位扣减，DB保持关闭，恢复容量/租约后重建；未清空开发数据或猜测INCR。
- **3项真实Redis/MySQL/RabbitMQ链**：测试夹具模拟未来D持久受理，与outbox同事务，然后真实发布/消费/结算；重复消息、先超时后晚投递、重建后旧accepted_generation命令均只有一次发行/终态。夹具不是生产HTTP入口。
- 原发券Rabbit4、通知Rabbit4、订单券19、outbox SQL10及相关发布单元回归通过。发行/终态/消费凭据仍数据库原子提交，ACK后置，Redis结算事务外。
- **双Redis1项＋原评分缓存19集成/18单元**：同应用两工厂/模板、不同端口/真实run_id，两个实例互不读取对方探针；券ACL拒绝越命名空间，原缓存命中/版本/回源/竞争检查通过。
- **3项JUC单元**：长恢复不阻塞调度入口、不排队重叠；关闭中断自有线程并拒绝后续；关闭选择配置不启动工作。默认调度线程的选择依据见[Spring Boot官方说明](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html)。
- **V22→V23真实升级1项**：原业务活动及gate已有字段逐项不变，resume_phase回填/空值约束正确，重复迁移0；关闭组件时无券Redis工厂/Store Bean。多个mysql可执行包健康/UTF-8/访问边界启动检查通过。

## 实际轮次、中间问题与修复

1. Docker Linux引擎起初未运行，启动本机已有Docker Desktop（未安装/升级），29.8.0就绪后继续。夹具曾使用已移除的Lettuce setDefaultTimeout，编译阶段改用URI timeout；未把编译成功当真实Redis通过。
2. round1：B37＋MySQL12＋OpenAPI3及9相关单元通过；新增C套件BeforeAll的Windows Go-template引号被进程参数解析，目标场景未运行。改用JSON标签解析，保留精确身份守卫。
3. round2：42项中41通过，kill后应用连接尚未恢复导致暂不可用。保留生产快速失败/DB REBUILDING，验收补齐连接恢复及原租约到界后的接管路径。round3增补TTL、多批次和迟到修复检查后46项全通过。
4. round4：真实三组件及旧链40项＋9相关单元通过。round5双实例隔离1项及缓存18单元通过，但旧缓存集成夹具仍断言V21/31表而实际V23/32表，BeforeAll失败。只更新当前守卫、保留V15升级分支；round6双实例＋缓存19集成/18单元全部通过。
5. 发现共享单线程调度可能被大重建占用，改为私有单工作线程，不替换公共自动配置。round7最终48项C专项＋3项工作线程检查通过；round8真实升级1项及mysql可执行包启动通过。
6. 本机Start/Status/Stop实际执行，专用Redis版本8.2.4/PONG验证；修正停止容器没有live docker port输出的Status分支，最终Status正常显示exited与“配置端口、未监听”。自有AOF卷保留，选择配置已关闭。

## 复现与环境清理

命令及Swagger兼容点击方法见[指南](../coupon-redis-recovery.md)。本轮实际使用：

```powershell
./scripts/test-coupon-redis.ps1 -Phase Core -Cases 'CouponRedisIT,CouponAsyncMysqlIT,MysqlIntegrationIT,OpenApiMysqlIT'
./scripts/test-coupon-redis.ps1 -Phase Core -Cases 'CouponRedisIT' -UnitCases 'CouponRedisRepairJobTest'
./scripts/test-coupon-redis.ps1 -Phase Messaging -Cases 'CouponRedisMessagingIT,CouponAsyncRabbitIT,NotificationRabbitIT,CouponOrdersMysqlIT,OutboxMysqlIT'
./scripts/test-coupon-redis.ps1 -Phase Cache
$env:MAVEN_ARGS='-Dit.test=CouponRedisUpgradeCheck -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false'
./scripts/test-mysql.ps1 -StartupTimeoutSeconds 90
```

消息轮次运行时的UnitCases默认仍为原监听/路由9项；最终脚本默认改为3项恢复工作线程测试，-UnitCases可显式选择。普通隔离MySQL脚本默认关闭未带专用自有标识的两类Redis，避免继承.local开发选择配置；专用联合runner保留自己创建的端点。未在正式项目库运行迁移或使用其凭据。

代表性完成记录：券Redis核心c40bd26dc7db4301ae469508fa97785f、最终943eea5c0818432bb9312a6877039ce7；三组件券Redis8eee1ef5bab34ea4b31d0d9771c54e6e/Rabbit6fdcd2e680ea4b7293ddd7fbb63ab341；双Redis券d622358bf10b4ecdbe94434689e7978e/评分fde67fd67fa3429fa48ca0be48f0b782。均为随机身份、回环端口、临时自有库/容器/卷，按精确身份清理。失败库目录在保留证据、验证进程退出及绝对路径后单独清理，不使用prune。

Redis持久性边界依[官方AOF说明](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)；脚本执行范围依[官方Lua说明](https://redis.io/docs/latest/develop/programmability/eval-intro/)。MySQL是最终发行真相，Redis/SQL没有共同事务。

## 限制与下一项

公开异步新受理/202首次返回/Location/受控模式切换留M08.3-D；当前仅组件实链与旧接口兼容，不是学生浏览器完整异步领券验收。M08.4再做同负载同步对照、延迟/失败比例/热锁/积压和真实JVM崩溃窗口，不宣称性能提升或多节点HA。前端继续暂停。

本机开发券Redis已停止、AOF卷保留，仓库及本机选择配置关闭；用户按原VS Code MySQL配置重启应用，由Flyway应用V23。没有自动更改业务活动mode。
