# M08.3-C：券预留、结算与Redis恢复

2026-10-31当前状态：D公开三阶段受理与受控切换已交付，见[公开受理/维护指南](async-coupon-admission.md)；本篇保留C组件交付时的范围，不将C的191项检查改计为D证据。

依据[ADR-0054](decisions/0054-async-coupon-claims.md)及[完整协议](async-coupon-claim-design.md)。本批交付独立Redis与恢复组件，**公开异步受理仍关闭，默认活动仍SYNC**。没有新增HTTP路径；75操作/V23/32张应用表，前端暂停。D才接通公开Lua→持久受理→202与受控模式切换。

## 为什么独立运行

评分缓存可以丢失并回源，使用原allkeys-lru/无持久化实例；券预留使用独立8.2.4固定digest、ACL凭据、AOF everysec、noeviction和自有卷。两套Lettuce资源/连接工厂/模板显式限定，不能把预留放进评分缓存。AOF仍可能丢失近期写入，最终库存、成功限领与终态由MySQL决定；不宣称跨系统原子或每个202成功。

仓库默认campuslife.coupon-redis.enabled=false。启用必须明确namespace、非default账号及至少32字符密码；配置类不在toString输出凭据。应用账号只访问自己的coupon命名空间，测试管理账号另用隔离凭据执行故障注入。测试不会使用开发Redis、项目库、用户PDF目录或开发Broker卷。

## 本机启停与人工兼容验收

Docker Desktop Linux引擎需已运行。项目自有环境仅控制清单中身份/标签/回环端口/卷都匹配的资源，不使用prune：

```powershell
./scripts/coupon-redis.ps1 -Action Start
./scripts/coupon-redis.ps1 -Action Status
./scripts/coupon-redis.ps1 -Action Stop
```

Start生成仅Git忽略.local下保存的专用凭据和本机选择配置；不读取数据库密码/JWT配置，不修改业务活动mode。Stop保留AOF卷并关闭本机选择配置。每次改变后按原VS Code MySQL配置重启；Flyway执行V23，不编辑V1～V22。

V23仅新增技术resume_phase：重建前OPEN/PAUSED必须持久保存，重建后恢复原阶段。若升级时已有B阶段的REBUILDING技术行，其未知原阶段保守回填PAUSED；业务活动/券/请求事实不改。

Swagger/Apifox沿[同步领取](coupon-claims.md)及[订单用券](order-coupons.md)流程：首次201、原键200、本人结果/券及旧订单保持。启用Redis组件不会把SYNC活动变为异步。**不要在项目库手工改mode或伪造PENDING来演示；C没有新异步受理接口。** Redis故障不会让ASYNC自动回落SYNC，旧MySQL发行/超时仍可独立决定结果，恢复结算后补。

## 核心实现与边界

- 活动hash-tag下control＋代次state hash；当前控制/状态不可有TTL。脚本先验证类型、身份、代次及free+held+spent=total等条件；正常Reserve/Settle最后一次HSET提交相关计数和身份，不先扣库存后另写标记。
- 相同学生/键返回原rid，其他键不会再占一份；已成功永久CLAIMED。ID/代次以十进制字符串比较，避免Lua double误判BIGINT。
- 数据库终态提交后在事务外结算；只有自己的HELD可一次返还/转SPENT。失败缺rid也保存CLOSED但不INCR；学生指针只有仍属于该rid才清除。缺成功rid必须有重建的对应券证明，不凭猜测扣/增计数。
- Lua预留30秒未入库可由回收器按学生账号→店铺S→gate S→请求写永久FAILED墓碑，和真实受理争同一唯一键。PENDING先提交则不能释放；FAILED先提交，晚受理不能重开。全局键已绑定其他身份时仅结算本孤儿，不改那条事实。
- 重建先短事务关闭DB gate、升代次并持久30秒协调租约；正常受理/发行/扫描/结算checkpoint暂停，原业务deadline不延长。逐条清理过期/失效/过量请求，按MySQL issued和PENDING重建，不能只用total-issued开库存。
- 影子空间每批最多200条、批号＋摘要去重；核对全部已发/保留映射后激活Redis，再条件开放DB gate。Redis先OPEN的短窗口不授权数据库新受理。接管再次升代次，迟到激活/续租/DB开放必须匹配token/代次；旧读不能使已经更新的代次重复重建。
- 正常网络IO强制拒绝在SQL事务内执行；结算成功后的SQL checkpoint须复核当前代次/运行标识和终态。丢回包/写checkpoint失败可重试，不重复补回或发行。
- 已关闭的旧代次仅在新control围栏建立后设1小时技术清理期，当前代次/墓碑不短TTL。重建过程中OOM/断线保持DB关闭，需连接/容量恢复后重试；AOF不替代重建。异常类型control只在DB已关闭且持有新票据时重置这一个已知派生键，失败仍关闭。

恢复监控由每秒轻量调度触发私有单工作线程，AtomicBoolean＋SynchronousQueue避免重叠/积压，不占用订单扫描/outbox共用定时线程；停止时中断并最多等待2秒，重建按批/按行协作检查中断，DB门闩保持可接管。这不是整个应用的退出时延承诺。恢复监控每轮gate及终态分别用100条keyset页轮转；单条失败不阻止本轮后续项。孤儿用HSCAN COUNT 100提示分批枚举（COUNT不是严格响应条数上限），Lua业务操作不扫描全活动。调度开关campuslife.coupon-redis.scheduling-enabled默认true；隔离测试关闭自动调度并手动调用。不承诺恢复时间或处理容量。

## 隔离复现

```powershell
# 真实Redis/MySQL：包括Redis stop/kill、OOM、租约和多批次。
./scripts/test-coupon-redis.ps1 -Phase Core -Cases 'CouponRedisIT,CouponAsyncMysqlIT,MysqlIntegrationIT,OpenApiMysqlIT'
# 再加真实RabbitMQ：预留/持久夹具/outbox/消费/结算及旧链兼容。
./scripts/test-coupon-redis.ps1 -Phase Messaging -Cases 'CouponRedisMessagingIT,CouponAsyncRabbitIT,NotificationRabbitIT,CouponOrdersMysqlIT,OutboxMysqlIT'
# 两套Redis同时存在并使用，验证原评分缓存和明确隔离。
./scripts/test-coupon-redis.ps1 -Phase Cache
# 独立新库先V22再V23，关闭券Redis组件，检验存量与默认关闭。
$env:MAVEN_ARGS='-Dit.test=CouponRedisUpgradeCheck -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false'
./scripts/test-mysql.ps1 -StartupTimeoutSeconds 90
Remove-Item Env:MAVEN_ARGS
```

各项按需创建随机身份/回环端口的自有实例，完成后精确删除测试容器/卷/临时库，保留脱敏报告；不能把环境变量改成开发或外部服务。执行结果与中间问题以[本轮报告](reports/coupon-redis-recovery-2026-10-31.md)为准。测试使用临时PENDING夹具；不把它当作D的公开领取验收。

M08.4后续才做同步/异步同条件延迟、失败比例、SQL/热锁/积压及真实JVM中断测量。C的Redis进程实际stop/kill与协调者注入异常不等于多节点HA或所有崩溃窗口已验收。
