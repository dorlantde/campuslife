# M06.1-D.6f 正式包维护预检分阶段诊断

后续[D.6g维护初始化预算隔离](maintenance-initialization-budget-2026-11-05.md)已采用/实现并定向验证；云端正式制品尚未更新，下一项D.6h构建/静态核验。下文保留本次诊断事实，不追认为原实机验收通过。

**结论：已捕获具体的首次预检握手超时；第二次成功，整个Resume完成。D.6e的最终拒绝没有复现，不能判定问题已修复。** 本轮诊断结束，没有增加超时或重试、修改生产类、重建JAR/镜像或重跑验收。[机器记录](cloud-preflight-trace-2026-11-05.json)/[下一步待采用提案](../maintenance-initialization-budget-design.md)。

## 单次执行与证据

单次外层264.072秒，UTC 2026-11-06 04:19:36～04:23:53，即纽约2026-11-05 23:19～23:23。仍为新ECS/compact4g七服务/相同8秒资源采样；正式JAR `4dfd3a9f...`、镜像 `8d654a79...` 保持。仅上传[观察类](../../scripts/diagnostics/CouponPreflightTrace.java)和[驱动](../../scripts/diagnostics/coupon_preflight_trace_case.py)，共23,842字节，核验后用原156依赖编译。

挂载目录经检查只含diagnostic包的测试class，生产维护/恢复类均来自app.jar。额外观察main沿用原CouponMaintenanceBootstrap配置创建上下文并调用原Resume Bean；它不是逐字执行原Bootstrap.main。观察会影响类加载与时序，不能把成功追认为原入口稳定。

使用零发行、零处理中请求的虚构技术活动，SQL设置测试审核资格与ASYNC/PAUSED元数据，等待SQL/Redis就绪。该夹具只用于连接诊断，不计正常商家审核、受理或交易验收，也没有原卷重启。

| 调用阶段 | 本次实际结果 |
|---|---|
| 上下文及配置 | 生产类来自原JAR；密码与挂载密钥一致，账号/主机/端口匹配预期 |
| 预检第1次runId | 2696ms失败，线程未中断；RedisConnectionFailureException → RedisConnectionException → RedisCommandTimeoutException |
| 根异常定位 | `io.lettuce.core.protocol.RedisHandshakeHandler.lambda$channelRegistered$0` |
| 连接事件 | 第一次Connected后断开，没有Activated；第二次Connected后出现Activated |
| 预检第2次runId | 768ms成功，线程未中断；激活后INFO完成 |
| 原恢复直接runId | 51ms成功，随后原Lua恢复完成 |
| SQL直接读快照 | 前为ASYNC/PAUSED gen3/v5，后为ASYNC/OPEN gen4/v7；前后均无遗留票据/租约，发行/持有券/处理中均0/0/0 |

第1次在获取原生连接时抛出，尚未派发业务INFO；第2次激活后才观察到INFO。根异常明确属于连接初始化握手定时器，不是本次INFO业务命令执行超时。握手内部具体哪个HELLO/AUTH/元数据步骤耗时未被分离，不作单条握手命令归因。

2696/768ms均为完整客户端调用观察时间，不是纯握手/服务器执行耗时。打印事件由异步事件流交付，不能相减当网络包时间。INFO和后续EVALSHA/EVAL监听也有开销；其中NOSCRIPT随后自动EVAL成功属于原脚本加载回退，不是新的业务失败。观察器只输出命令类型、类型链和受控栈帧，不输出参数/message/地址或凭据。

## 已确认的配置耦合

核对本机缓存的Lettuce 7.5.2.RELEASE和Spring Data Redis 4.1.1字节，与云端运行包内依赖逐项一致；使用javap保存本版本真实代码路径：

1. Spring的commandTimeout进入RedisURI.withTimeout，当前500ms。
2. ConnectionBuilder将RedisURI.timeout传给RedisHandshakeHandler的initializeTimeout。
3. RedisHandshakeHandler在channelRegistered启动定时器，channelActive才调用连接初始化器；到期任务创建RedisCommandTimeoutException。该方法与本次根异常帧一致。

因此当前500ms并不只约束已连接后的命令，也约束冷连接初始化窗口。它提供了比“Redis连不上”更具体的修复方向。**具体冷初始化为何耗时、D.6e最终拒绝是否同因仍未证明**；CPU调度、类加载等不能仅凭样本定因。两次预检过程中未改变配置，也未清除中断标志。

Redis ACL日志前后均无拒绝记录，rejected_connections均0；两次均校验到同一runId。服务器total_connections_received 56→84包含健康检查和诊断查询，不能当Java重试次数；total_error_replies 6→8也不能直接当认证失败。没有输出或保存原ACL客户端/命令参数。

## 边界、清理与后续

TCP300ms、URI/命令500ms、原最多两次预检、30秒租约、CLI90秒保持。观察main到操作结束约56.005秒，JVM uptime约57.942秒；启动耗时不等于连接耗时。原驱动diagnosisCaptured字段只标记整体调用失败，所以本次为false；原始轨迹仍完整保存首次失败，不改写该回执。

独立复核：新462项有效输入（461原项＋既有部署工具权限补丁）、七份既有辅助工具、两份诊断源码及新旧JAR/镜像均保持。containers/volumes/test networks/生成secrets均为空，旧17/新9个历史夹具目录留证，无运行中测试。本机Docker未启动，业务Java/DDL/pom/前端、生产配置和预算未改。24次资源采样仅说明诊断条件，不作最终资源验收或容量结论。

证据位于 `.cache/cloud-preflight-trace/case-df9d93c96b81`，库字节码核对记录位于其父目录，摘要见机器报告。实际目标机命令：

```sh
python3 -u /home/campusdeploy/.cache/campuslife-preflight-trace/case-df9d93c96b81/coupon_preflight_trace_case.py --release /home/campusdeploy/campuslife/releases/5382d9dd9ca2 --output-dir /home/campusdeploy/campuslife/releases/5382d9dd9ca2/.cache/diagnostics/case-df9d93c96b81
```

已按本轮最多一次约定停止，不重复运行碰运气。下一项建议采用[D.6g初始化预算隔离提案](../maintenance-initialization-budget-design.md)后，先验证维护专用5秒初始化窗口与普通命令500ms是否能同时成立；本轮不实施该新预算。D.6e/整项D.6仍未通过，D.7/压测及前端继续等待。
