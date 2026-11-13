# M06.1-B1 双Redis与RabbitMQ部署验收

日期：2026-11-02，America/New_York。状态：**B1已交付并收口**，依据[ADR-0057](../decisions/0057-single-instance-deployment.md)。[使用与Swagger/维护命令](../component-deployment.md)、[机器记录](component-deployment-2026-11-02.json)。本批是六服务中间模式，文件仍LOCAL；S3 TLS、观测、真实Linux宿主及全栈备份恢复未完成。

## 改了什么、为何这样做

新`components.yaml`叠加在A的三服务基础配置上，显式增加评分Redis、券Redis和RabbitMQ。统一Init/Start/Status/Stop入口按清单校验3/6服务、应用开关、真实私有端口和模式标签；只Nginx回环可达宿主。新实例默认SYNC；资金仍默认关闭，只有Init明确指定components及DemoMoney才开模拟资金。既有卷不能通过改manifest静默降成base，当前没有原地转换或真实数据迁移入口。

评分Redis原连接工厂没有用户名/密码注入，本轮给`RatingCacheProperties`、`RatingRedisConfiguration`和`application.properties`增加可选凭据与脱敏打印。保留四参构造和空凭据回环行为，显式标注绑定用的规范构造器；没有改评分缓存的版本键、TTL、RR或故障回源逻辑。

应用只挂DB/JWT及三个运行密码；Redis各有独立运维身份，Broker有独立vhost/应用/运维身份，均不把运维密码或cookie挂给业务应用。评分Redis仅评分前缀及GET/SET/DEL/连接操作；券Redis保留原Lua读写/脚本/INFO，拒绝全库/配置/ACL/关闭。默认Redis用户关闭，Rabbit不建guest；应用仅有原六个具名交换机/队列资源权限。

新增两个容器维护分支直接启动原CouponMaintenanceBootstrap/MessageMaintenance，不增加HTTP接口或新的业务维护协议。前者继续configtree；后者原来使用System.getenv，包装器只在该子进程从文件导入DB密码，凭据不放Docker参数/配置环境或日志。原因由本机交互输入并按变量名转发；实际维护锁序、控制版本、重建、租约及重放逻辑保持。

生产修改限3个配置文件/类；其余改动为4个既有部署/脚本文件及新增配置、测试、文档。原541个源码/脚本/部署/前端/pom文件中534个保持SHA，7个预期修改均逐项登记。**没有改订单/优惠券/Lua/outbox/消费者实现、历史迁移、Maven依赖或前端**；75业务操作/V24/32应用表，148个运行依赖逐项SHA不变。JAR因配置变化更新，不能继续称与A相同，准确SHA及新image ID见机器记录。

## 版本与持久策略

| 组件 | 本批实际版本/选择 | 边界 |
|---|---|---|
| 两Redis | `redis:8.2.9@sha256:66b7f4c2f3c6ef1b298bbd7b13c806bc6f71e61f1136a4f58710daf2cb053b21`；linux/amd64清单`sha256:a1b87e9c2b89fb4523c6777f42ad288826b539ad309b17e692a69706ddcfe999` | 旧8.2.4之后已有同线安全修复，故本批新部署采用8.2.9并实际验证；未升级旧实例，不沿用旧性能数字。[官方8.2发布说明](https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/release-notes/redisce/redisos-8.2-release-notes/) |
| RabbitMQ | 保持`rabbitmq:4.3.6-management@sha256:54cb013d76cd1fd6c0a0708f34062ad61c452bb6f016b434c6c33f1a765d2b58` | 核对仍在[官方社区支持期](https://www.rabbitmq.com/release-information)，不为部署改成4.4或重做原拓扑 |
| Java/MySQL/Nginx | 沿A固定Temurin21.0.13+9、MySQL8.4.13、Nginx1.30.6及digest | 无新的数据库版本/中间件产品；Windows运行入口、应用在Linux容器内，非独立Linux宿主验收 |

评分缓存64MiB/allkeys-lru、无AOF/RDB、临时数据目录；券Redis128MiB/noeviction、AOF/everysec、独立卷。Rabbit持久整个`/var/lib/rabbitmq`，固定节点名和cookie，cookie不匹配旧卷即失败；定义文件带盐密码哈希，初次导入不建默认用户/vhost，后续不覆盖已有定义，依据[官方导入语义](https://www.rabbitmq.com/docs/definitions)。两个Redis实例保持分离，缓存故障不能“降级”到券Redis。

## 已执行检查

| 类别 | 最终不同检查数 | 证据 |
|---|---:|---|
| 原RatingCacheTest＋新增RatingRedisConfigurationTest | 18＋2＝20项JUnit | 原缓存命中/编码/回源逻辑；可选ACL绑定与连接工厂应用、脱敏、旧构造/默认关闭。最终0失败/错误/跳过 |
| 六服务部署 | 34组 | `.cache/deploy-base/0e596fd6fd994b2eb6965186bf410867/components-acceptance.json` |
| 默认base小回归 | 7组 | `.cache/deploy-base/9b6fb16f26b54c2e925322462093f754/base-regression.json` |
| 实际运行OpenAPI | 1次只读核对 | HTTP200、75业务操作；`.cache/m061b1-openapi.json` |

部署“检查组”与JUnit用例分开计数，不按重复轮次或每个内部断言累计，也不是覆盖率/容量指标。

六服务现场检查具体覆盖：

1. 三个新组件实际就绪、无宿主私有端口；24迁移/32表且无默认ADMIN。应用不获root/Redis运维/Broker运维/cookie文件。两个Redis匿名、跨前缀写与FLUSH均拒绝，实际策略一LRU一noeviction/AOF；Rabbit非guest、独立vhost、原四个quorum主/死信队列。
2. 真实公开详情填充评分缓存，MySQL performance_schema聚合语句计数证明后续命中不执行评分聚合；停止评分Redis后响应不变且实际聚合执行增加，重启后重新填充。没有用“Redis能PING”代替业务缓存验证。
3. 商家通过原HTTP接口发布活动，初始仍SYNC；独立CLI按Pause→Async→Resume打开指定活动。停止券Redis后503且不落同步券；重启后SQL门闩与新runId完成原代次恢复，模式仍ASYNC。
4. 停止Broker后首次领取仍202/PENDING，SQL请求与发行outbox已持久、尚无实际券；同键重放保留ID/期限。普通JDBC消息维护入口能通过文件秘密Inspect。Broker恢复后消费成功，同键终态重放200、仅一张券；另5名学生争抢最后1张，只有1个202赢家，MySQL发行/实际券均精确2，不超发。
5. 该实际券用于打印报价20→15分、下单和显式模拟支付；商家接单/开始/待取件后原MQ消费者生成一次学生通知。已消费通知通过旧维护入口重放不重复。学生取件与评价推进真实评分版本，新缓存键返回5分/1条，订单支付/用券事实各1条。
6. 暂停活动后停止/重启六服务，四个卷保留；新Redis运行身份触发重建并保留PAUSED，库存/券事实不变，原JWT会话、已落库通知、LOCAL文件与存储标识仍可读。旧控制版本不能重开；原协议切回SYNC并Resume，事实仍保留，新默认SYNC活动首次领取201。
7. 修改清单企图把已有components卷变base被模式标签阻止；服务日志不含本轮密码/token。默认base回归验证仍3服务、组件和资金全关、只生成原3个秘密、新JAR无需Redis/Broker可启动，旧清单缺少mode字段仍兼容，普通Stop仍保留2卷。

这些是本批路径的实际运行证据；仅验证打印通知，不冒充重跑全部预约通知/所有旧崩溃窗口。暂停/重启是生命周期验收，不是完整环境备份恢复；本批没有制造Broker非空队列的备份还原或断电持久性实验。

## 失败、清理与限制

- 首轮单元检查原18项通过，新2项绑定失败：多构造记录需要明确标记规范构造器，修正ConstructorBinding后20项全部通过。原失败摘要保留`.cache/m061b1-failures/rating-acl-first-binding.txt`。
- 首轮六服务`d0199d99b4134adfbcbf67f6f161f3da`已通过组件/ACL/拓扑检查，但验收函数`http`遮蔽Python模块，尚未进入业务注册就失败；修正测试模块别名，不改业务行为。其失败记录及日志保留；完成轮全34组通过，不累计首轮重复项。
- PowerShell桥接做语法检查；上述新模式和维护功能由同一Python核心及真实独立JVM执行。没有代用户在开发实例上执行任何切换或输入私有密码。

每轮只清理身份标签匹配的自有测试容器/卷/网络；临时CLI自动退出，生成的13个组件秘密/ACL/定义文件及3个base秘密均删除。最后Docker按本任务test标签复核全部为0。镜像/构建缓存、脱敏结果和失败日志保留；没有prune、退出Docker Desktop或关闭WSL。没有读取/改写开发数据库凭据、真实上传根、原MySQL/Redis/MQ数据或VS Code配置。

依然是单节点可信私有网络；组件管理面不发布，AMQP/Redis未新增TLS，不称端到端零信任。AOF每秒策略不等于零丢失承诺，MySQL仍是券恢复事实源，Rabbit单节点不等于HA；维护进程本机权限等同其项目SQL/组件身份。性能、HA、断电窗口和全栈恢复不从这些功能检查推导。

下一项仅 **M06.1-B2：私有S3原生TLS/CA整合**。沿已采用方案验证正确/错误CA和主机名、原桶/前缀权限、20MiB、LOCAL共存及新写/重启；不放宽跨容器明文规则，不同时实施C/D/E，前端继续暂停。
