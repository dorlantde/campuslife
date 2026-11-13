# M06.1-C 私有管理面与即时诊断

按[ADR-0057](decisions/0057-single-instance-deployment.md)增加Boot Actuator/Micrometer。它用于回答“应用是否存活、数据库是否可用、消息为何积压、HTTP/JVM/连接池当前怎样”，不改变订单、优惠券、资金或文件协议。没有新增业务接口、表或Prometheus/Grafana；仍通过原Swagger/Apifox验收75个业务操作，管理路径不计入业务OpenAPI。

## 显式启用

普通本机运行默认`management.server.port=-1`，没有管理HTTP监听。部署也必须在**新实例Init**时显式选择`-Observe`，不会为既有实例自动生成运维身份或转换模式：

```powershell
.\scripts\deploy-base.ps1 -Action Build
.\scripts\deploy-base.ps1 -Action Init -Mode components -Port 18080 -Docs -Observe
```

将实际输出的实例目录填入变量，再启动：

```powershell
$instance = '这里填写Init实际输出的实例目录'
.\scripts\deploy-base.ps1 -Action Start -Instance $instance
.\scripts\deploy-base.ps1 -Action Observe -Instance $instance -Operation Health
.\scripts\deploy-base.ps1 -Action Observe -Instance $instance -Operation Metrics
.\scripts\deploy-base.ps1 -Action Observe -Instance $instance -Operation Metrics -Metric jvm.memory.used
.\scripts\deploy-base.ps1 -Action Observe -Instance $instance -Operation Snapshot
```

`-Observe`可用于base、components或showcase；showcase仍需先按[存储指南](storage-tls-deployment.md)显式StorageInit。模拟资金仍需新建实例时另加`-DemoMoney`，base不允许；活动仍默认SYNC。命令只读取本实例私有身份，不需要在聊天中发送密码或token，也无需手写HTTP请求。普通业务管理员仍需显式Admin初始化。

## 访问边界

管理端口固定为容器内`127.0.0.1:8081`，业务仍8080；不映射宿主、不经Nginx、同网络容器也不能访问。独立`campuslife_ops` Basic凭据由本机随机生成，存放实例秘密文件，应用通过只读configtree加载；与数据库账号、业务ADMIN/JWT均无关。诊断封装将curl配置经标准输入传递，不把密码/Authorization写入命令参数、镜像、报告或环境全集。

独立最高优先级SecurityFilterChain按实际本地端口匹配，仅授权GET：

| 路径 | 含义 |
|---|---|
| `/actuator/health` | 健康摘要，不含组件/异常/连接信息 |
| `/actuator/health/liveness` | 进程应用可存活，不依赖MySQL/Redis/MQ/S3 |
| `/actuator/health/readiness` | 应用接受请求状态＋MySQL连接可用性；不是所有业务能力就绪承诺 |
| `/actuator/metrics`、`/actuator/metrics/{name}` | 当前JVM/HTTP/连接池等Micrometer即时计量 |

匿名、错误密码、业务Bearer凭据不获得运维权限；运维身份不能访问管理端口上的业务路径，也不能拿来登录业务账号。业务端口显式拒绝`/actuator/**`，避免绕过私有管理面。POST/HEAD等其它方法、发现页和未列路径均拒绝；env/configprops/heapdump/logfile/shutdown/loggers等不暴露。JMX关闭，无会话cookie认证。启用但缺少生成凭据、管理端口与业务相同、使用非回环绑定或未明确启用却填写端口均启动失败。

健康检查由Spring内置DB/应用可用性贡献器提供；关闭可选依赖的默认聚合健康探测，防止Redis/MQ/S3断连被误判为必须重启进程。容器原健康检查仍表示原HTTP存活；readiness是独立诊断信号，既不自动摘流量，也不自动重启。普通web-only仅为诊断测试配置，不作为MySQL业务就绪证据。

## 读懂快照

Snapshot按需采集容器状态、liveness/readiness、数据库聚合和Broker队列ready/unacked，不导出姓名、账号、订单ID、payload、文件路径或内容。数据库使用一个短RR只读快照，固定聚合outbox状态/最老积压年龄、领取结果、活动模式/门闩及文件后端/生命周期；每条SELECT最多1500ms，不持续轮询或增加指标表。查询失败只标记数据库不可用，不把SQL错误或秘密回显给操作者。

- 数据库故障时：readiness为503，liveness和JVM metrics仍可用；业务health继续只表示HTTP存活。
- Broker故障时：数据库/readiness可以正常，queues显示不可用；outbox READY/PUBLISHING数和积压年龄提供原因线索。恢复后要核对SENT、消费凭据和实际通知，SENT本身不证明通知成功。
- 异步领券问题：快照可看到模式/门闩的聚合分布；指定活动的代次、截止和暂停/恢复仍使用原Coupon Inspect维护入口，不能根据202或队列为空宣称已获券。
- 文件问题：快照只展示后端/状态聚合；原文件权限、定位、日志及受控上传/下载验证仍是判断依据，不能用health代替S3新写与完整回读。

Metrics为进程内即时数据，重启会重置，没有长期时序保存或告警。HTTP按路由模板/方法/状态聚合，不加入账号、订单、token或查询值标签。Nginx原脱敏日志保留入口request_id、应用生成的trace_id、状态和耗时；外部提供的trace不可信，完整查询串、Authorization及请求体不记录。不得据这些即时指标声称容量或性能提升。

## 复现与剩余范围

隔离验收入口：

```powershell
.\scripts\test-deploy-observability.ps1
```

它创建自有components测试实例，真实停启MySQL和RabbitMQ，以原打印订单触发通知；结束仅清理本轮容器、卷、网络和秘密，保留脱敏证据，不关闭Docker Desktop/WSL、不访问开发库或用户上传目录。相关单测、部署结果及首轮失败记录见[验收报告](reports/private-observability-2026-11-03.md)。

本项止于最小观测；后续[D容器组合与指南](linux-deployment.md)已交付，用户暂无Linux环境、真实宿主条件项未执行；E全栈备份恢复和M06.2固定负载仍须独立执行。原M04冷恢复新写超时原因尚未定位，本轮数据库/Broker运行期恢复不能视为修复；前端继续暂停。

后续进度：E隔离冷备/新卷恢复已[验证](full-stack-recovery.md)。用户现已补充有阿里云Linux服务器，当前尚未核对或部署；早期“暂无环境”是当时验收回答。下一项先只读核对云端条件，M06.2固定负载仍待做，前端暂停。
