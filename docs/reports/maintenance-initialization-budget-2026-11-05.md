# M06.1-D.6g 维护连接初始化预算隔离

后续D.6h已[构建并静态核验新正式云端制品](cloud-initialization-build-2026-11-06.md)：新release1cf0cb53a24a，649条目内容与本机验证包相同，尚未运行业务；下一项D.6i剩余定向实机验收，D.6仍未通过。下文保留本轮事实。

**结论：用户推进下一项后采用方案，隔离机制验证、配置接入及相关回归完成。云端正式制品尚未更新，D.6仍未通过。** [机器记录](maintenance-initialization-budget-2026-11-05.json)/[已采用方案](../maintenance-initialization-budget-design.md)。

## 改动和取舍

仅修改一份生产源码[CouponRedisConfiguration](../../src/main/java/com/campuslife/coupon/config/CouponRedisConfiguration.java)：显式 `coupon-maintenance` profile使用5秒的URI/连接初始化预算；固定命令expiry仍500毫秒。普通HTTP与后台仍使用原500毫秒初始化配置，TCP300毫秒、最多两次预检、30秒重建租约和CLI90秒整体约束均保持。不改变订单、库存、数据库锁、版本复核、失败码、重试分类或开关默认值。

这是对[D.6f所发现配置耦合](cloud-preflight-trace-2026-11-05.md)的最小调整。5秒是显式维护进程的冷启动余量，不是已验证的最佳值或RTO。Spring同步等待配置也变为5秒，不能说所有内部等待仍500毫秒；本轮实际验证的是连接激活后固定命令expiry仍能更早结束普通INFO。没有为HTTP或后台放宽预算，也没有增加组件、连接池或线程。

## 真实Redis隔离验证

使用新ECS上的原固定Redis8.2.9镜像、原JAR中相同依赖，运行[独立观察程序](../../scripts/diagnostics/CouponRedisBudgetProbe.java)与[驱动](../../scripts/diagnostics/coupon_redis_budget_case.py)。只创建一个临时Redis及逐个短命JVM；网络internal，无公网/宿主端口、持久卷、SQL或业务交易。候选程序独立构造配置，**不是更新后的正式应用包或原维护main验收**。

最终完整轮 `case-c5aa58f50dd0` 外层47.428秒，两份上传输入13,961字节，五项全部通过：

| 检查 | 最终轮观察 |
|---|---|
| 原500ms初始化＋代理延迟首次服务端响应1200ms | 初始化握手超时；完整调用1814ms |
| 候选5秒初始化＋相同1200ms延迟 | 第一次连接成功；完整调用2885ms |
| 连接成功后CLIENT PAUSE 2500 ALL，再执行INFO | QueryTimeoutException→RedisCommandTimeoutException，541ms失败；未等到5秒 |
| 错误密码 | 1861ms拒绝，异常链为连接失败→RedisCommandExecutionException |
| 目标拒绝连接 | 1538ms拒绝，异常链末端ConnectException |

耗时包括客户端处理、类加载和调度，不是纯网络/握手耗时，也不承诺每次541毫秒。[Redis CLIENT PAUSE官方说明](https://redis.io/docs/latest/commands/CLIENT-PAUSE/)用于确定暂停注入方式；测试并不解析或打印凭据及命令参数。

前两轮31.069/14.845秒未通过，保留原输入和回执。第一轮在命令检查的内部栈帧断言失败，第二轮补充观测确认实际528ms已超时，但同一断言仍失败。最终改为核对实际配置、公开异常类型及300～1800ms的有界观察窗口，不依赖异常必须保留某个内部调度类栈帧；该测试断言修正不涉及生产配置。原轨迹字段commandExpiryFailure仅表示观察到该内部栈帧，false不表示命令没有超时；实际失败类型及541ms由独立字段记录。失败轮不计入最终五项，也不拼接成完整轮通过。

## 实际配置及事务回归

新[配置测试](../../src/test/java/com/campuslife/coupon/config/CouponRedisConfigurationTest.java)通过Spring真实装配覆盖普通mysql、无关profile、显式维护profile，核对初始化预算、固定expiry、TCP、拒绝离线命令、队列上限和延迟连接。该测试不连接Redis；上面的独立真实Redis验证补足机制边界。

同一次本机隔离MySQL验证共26项全部通过，无跳过：配置3项＋原预检故障11项＋真实MySQL门闩/并发版本等11项＋独立维护Bootstrap装配1项。MySQL测试中的Redis是注入替身，不能替代真实Redis验证。Maven verify成功1:08，随后新JAR的mysql启动、健康、UTF-8、trace与访问边界smoke通过。

实际执行命令：

```powershell
$env:MAVEN_ARGS='-Dtest=CouponRedisConfigurationTest,CouponMaintenancePreflightTest -Dit.test=CouponMaintenancePreflightMysqlIT,CouponMaintenanceBootstrapCheck'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

最初单元测试编译因ObjectMapper的registerBean重载歧义失败，已改用零参数Supplier；失败日志留存，首次编译不计通过。隔离探针上传前的SocketOptions导入歧义也已修正，未改依赖。本轮不同相关检查共31项（5真实Redis＋14单元＋12真实MySQL），JAR smoke另记。

本机新JAR SHA256 `67b6c80b5900d3f439db3751f778190a195524333d5338df445bc37a0d99d420`，72,026,099字节。与D.6d正式包比较649条目集合保持，仅配置外部类和Enabled内部类两个class内容变化，156依赖全部保持；无DDL/pom/前端/业务接口改动，仍75操作/V24/32表。

## 清理、限度和下一项

独立云端复核462项有效源输入（461原项＋既有deploy_base权限补丁）、七份辅助工具、新旧JAR/镜像保持；本轮没有上传新的生产配置。containers、volumes、测试网络、探针网络和生成秘密文件均为空，旧17/新9个历史业务夹具目录不变。本机临时MySQL已停止，未启动本机Docker。原始回执、各轮冻结源码、审计及本机回归证据位于 `.cache/redis-budget`，机器报告记录摘要。

**尚不能称云端问题已解决。** 未重新构建云端正式制品，未在更新后的正式包上运行原main的Resume、原卷重启或新领券；没有重跑第四轮整链。下一项D.6h建议仅白名单新release构建/静态制品核验，之后再执行明确范围的剩余实机验收，失败不自动继续加预算。

若正式入口仍失败，保留PAUSED/围栏及SQL事实，按新的异常证据限定下一步；无法完成异步维护验收就如实标记限制。项目已有默认SYNC闭环可用于展示，但已有ASYNC活动仍须按原Pause/核对/Sync协议处理，不能因失败直接UPDATE或自动切回SYNC。其他已验收模块证据保持，D.7/综合测量和前端继续等待。
