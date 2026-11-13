# M06.1-D.6c 维护票据前连接预检实现与定向验证

后续D.6d已[构建并静态核验正式云端新制品](cloud-preflight-build-2026-11-05.md)，新release为5382d9dd9ca2，尚未启动其业务实例；D.6整体仍未通过。下文保留D.6c本轮实现与临时源码类路径验证事实。

**结论：修复已实现，本机35项相关检查及云端源码类路径定向验证通过；云端新JAR/镜像尚未构建验收，D.6整体仍未通过。** 用户“进行下一项”采用了[预检方案](../coupon-maintenance-preflight-design.md)。没有启动第四轮完整交易链、扩大预算、发布公网或恢复前端。[机器记录](maintenance-preflight-2026-11-05.json)。

## 实现及边界

只修改两份生产源码：[维护入口](../../src/main/java/com/campuslife/coupon/service/CouponMaintenance.java)、[恢复服务](../../src/main/java/com/campuslife/coupon/service/CouponRedisRecovery.java)。ASYNC Resume和ASYNC→SYNC维护在取票据前调用原Store做只读INFO；先检查原版本/阶段/依赖，再连接，成功后仍由原写事务在锁下重新校验版本、模式和阶段。

仅首次 `RedisConnectionFailureException` 的原因链含 `RedisCommandTimeoutException` 时额外尝试一次，原因链最多检查16层。两次连接失败即固定安全码 `COUPON_REDIS_PREFLIGHT_FAILED`；不携带原异常message/cause或凭据。认证类连接失败不重试；直接命令超时、RUN_ID校验失败和SQL错误不匹配该条件，原样失败。中断前后都停止，保留中断标志，不继续申请票据。

原取票据后的恢复/续约/fencing及异常接管不变。Inspect、Pause、SYNC Resume、模式赋值、公开领券和后台恢复未接入维护预检。没有新增表、接口、依赖、连接池或线程；socket connect 300 ms、command 500 ms、重建租约30秒、业务截止5分钟及原维护CLI90秒均保持。初始化可能跨多个步骤，两个命令配置不等于总计1秒硬上限。

## 本机真实MySQL与故障检查

新增[故障测试](../../src/test/java/com/campuslife/coupon/service/CouponMaintenancePreflightTest.java)和[真实MySQL测试](../../src/test/java/com/campuslife/CouponMaintenancePreflightMysqlIT.java)。Redis故障注入与真实Redis链分别验收，不用mock声称完成真实网络恢复。

| 最终有效运行 | 用例数 | 结果 |
|---|---:|---|
| CouponMaintenancePreflightTest | 11 | 首次超时再成功、两次失败、非匹配不重试、三类中断边界及安全错误码 |
| CouponMaintenancePreflightMysqlIT | 11 | 两个分支失败不写门闩/库存；另一线程在预检期间真实提交版本变化后拒绝票据；票据后失败保留原恢复意图；其它维护动作不连接 |
| CouponMaintenanceBootstrapCheck | 1 | 原显式非Web维护入口装配 |
| MysqlIntegrationIT | 12 | 原迁移/约束基础检查 |

合计35项，0失败/错误/跳过。MySQL测试使用本机独立临时实例及campuslife_it，结束清理，未连接或停止用户MySQL服务。预检期间另一事务按店铺→门闩顺序取得锁并在5秒观察内提交，写动作重新校验后返回CONTROL_VERSION_CHANGED；这项锁/事务证据来自真实MySQL。

有效Maven verify耗时31.401秒，打包后mysql profile的真实JAR启动/健康/UTF-8/访问边界检查通过；不是clean构建性能测量。最初受限运行因JDK attach管道access denied受阻，无完成用例结果，结束自有进程后以允许管道/回环访问的方式重跑；失败dump留证，不累计为通过。

实际可复现入口（本机已有MySQL工具，不填写用户密码）：

```powershell
$env:PATH='D:\Program Files\MySQL\MySQL Server 8.0\bin;'+$env:PATH
$env:MAVEN_ARGS='-Dtest=CouponMaintenancePreflightTest -Dit.test=CouponMaintenancePreflightMysqlIT,CouponMaintenanceBootstrapCheck,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 60
```

## 云端定向验证

新ECS上单次282.845秒，UTC 08:49:25～08:54:01（纽约03:49～03:54）。只上传两个修复源码和[Java观察类](../../scripts/diagnostics/CouponPreflightVerification.java)/[Python驱动](../../scripts/diagnostics/coupon_preflight_case.py)，共29,942字节，逐文件摘要一致。

在私有暂存目录用原156依赖编译，显式把新类置于类路径首位；观察类校验两份生产类的CodeSource来自该挂载目录。原release的target/classes、JAR及镜像均未覆盖。这是新源码的真实MySQL/Redis链验证，**不是云端新JAR或应用镜像验收**；独立编译类指纹与本机Maven制品分别记录。

使用新的技术已发券夹具（库存3、原发1），不重跑原卷重启或打印/资金全链。技术资格与异步元数据SQL播种不计为业务审核/受理验收。实际通过：

- 冷JVM执行新ASYNC Resume，外部SQL由PAUSED gen3/v5到OPEN gen4/v7，票据/租约清空，原券保留。
- 新学生通过正常HTTP异步领取返回202/PENDING，经真实RabbitMQ/SQL发行及Redis结算到SUCCEEDED；SQL已发/持有券/处理中为2/2/0，Redis total/free/held/spent为3/1/0/2。
- 另一个冷JVM执行Pause与ASYNC→SYNC维护，随后SYNC Resume；最终SYNC/OPEN gen5/v12，计数仍2/2/0，SYNC Resume新增Redis读取为0。

两个冷JVM首次预检分别2833/2922 ms直接成功，没有触发额外重试；后续原恢复的直接runId调用分别16/10 ms，含Spring启动的操作总时间分别38.678/40.971秒。额外重试分支由本机故障注入覆盖，不把本轮首次成功追认为旧超时已永久消失或建立SLA。

观察器仅统计Store的外部runId调用，内部requireRun自调用不经过代理，因此日志redisReads不是总Redis命令数。其Mapper门闩采样未保证当前读，可能受会话缓存影响，不用回调中的阶段标签证明实时票据状态；门闩断言采用独立外部SQL快照及上述真实MySQL故障测试。

## 制品与清理

本机新JAR SHA256为 `2bc27269d1a8241b899e807ab4868bc4d2042f5266027d6f77a97b7af9e905c6`，649个文件条目，与D.5运行内容对照仅两份业务class改变，156个依赖条目内容全保持。369个选定基线文件仅两份生产源码改变，其余367项保持；配置/DDL/pom/前端和维护预算文件未改。

云端仍保留旧JAR `2ad77758b9f8fc2bc070a19bf169ff989b978df32c66e7e04eae01eb96f7bb71` / 旧镜像 `sha256:c27d02862aebbda20b37004bff4a105551fc1e3ff538c613d81945c05efb4512`；原D.5输入459项保持，唯一旧差异仍为mysql-init shell，既有四份测试补丁保持。新源码只在独立暂存目录。

独立审计确认云端containers/volumes/test networks/生成secrets均为空，17个历史夹具目录留证；本机自有MySQL测试进程0，无运行中测试/长期演示实例，本机Docker未启动。原始记录保留于 `.cache/maintenance-preflight/case-8944ebe2414a`，包括源码白名单、XML、受限首轮dump、Maven日志、云端结构化结果和最终审计；机器报告保存摘要，不公开原XML/日志或凭据。

## 下一项与未完成项

建议 **D.6d：修复版本的云端白名单构建及制品核验**。建立独立新release，复用已批准工具/依赖缓存，仅包含核验后的源码和既有部署补丁，构建新JAR/镜像并核对运行内容/依赖/用户/平台；保留旧release作证，不复制整个工作区或传大体积本机JAR。该项尚未开始，不默认启动实例、公网、冷备或压测。

随后才用新云端制品完成范围明确的剩余实机断言及资源钩子；本轮没有重做原五卷重启，没有最终资源容量验收，不能把三轮旧失败和本次源码检查拼成一轮整链通过。D.6整体、D.7和综合压测仍未完成，前端继续暂停。
