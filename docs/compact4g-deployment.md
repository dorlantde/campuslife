# 2核4 GiB演示部署配置

实机后续状态：[D.6报告](reports/cloud-host-acceptance-2026-11-05.md)仅部分通过，最后优惠券Resume失败；测试资源已清理，未保留演示实例。下面D.3本地预验证的通过结论保持历史范围，不能当作云端整轮已通过。

本配置沿用同一业务JAR、数据库和七服务，适配代金券允许购买的2核4 GiB测试机。用户于2026-11-04明确采用本项；任务编号M06.1-D.3，因购买资源约束先于尚未接通的D.2云端核对执行。它是显式的新实例资源配置，不是业务分支或性能扩容方案。默认standard及已有实例保留。

## 资源预算与取舍

| 常驻服务 | 容器内存上限 |
|---|---:|
| Java应用 | 896 MiB，堆最大448 MiB，初始128 MiB |
| MySQL | 768 MiB，InnoDB缓冲池128 MiB，最多40连接 |
| RabbitMQ | 640 MiB，内存告警阈值384 MiB |
| SeaweedFS | 384 MiB |
| 券Redis | 192 MiB，原128 MiB数据上限/AOF/noeviction保持 |
| 评分Redis | 128 MiB，原64 MiB数据上限/LRU保持 |
| Nginx | 64 MiB |
| **合计** | **3072 MiB（3 GiB）** |

全部容器固定使用相同逻辑CPU 0、1；应用JVM报告2个处理器，Rabbit运行2个调度器。容器memory-swap总限额等于memory，因而不使用Swap。4 GiB主机余下约1 GiB需要供内核、Docker及其它宿主进程使用，不能把3 GiB常驻上限称为完整主机内存保证。

Java连接池最大5、最小空闲1，Tomcat线程最大32/最小2；MySQL缩小表缓存和临时表内存预算，保留Performance Schema及原事务持久性。Rabbit的384 MiB是发布流控阈值，非进程硬上限；不能通过关闭内存告警应付积压。仍保留双Redis隔离、Lua、AOF、消息确认/幂等、私有S3 TLS、文件限制和模拟资金显式开关。

ADMIN初始化及优惠券维护会临时额外启动Java客户端，继承同一896 MiB容器上限，**不包含在七个常驻容器3 GiB之内**。只在无演示流量时逐个运行；采样可能漏掉短暂客户端，不能承诺维护与业务峰值同时发生也安全。初始创建及ASYNC切换后再进行演示，不并行启动多个维护命令。

参数依据：[Docker内存与Swap语义](https://docs.docker.com/engine/containers/resource_constraints/)、[MySQL内存分配](https://dev.mysql.com/doc/refman/8.4/en/memory-use.html)、[Rabbit内存流控](https://www.rabbitmq.com/docs/memory)。数值是本项目轻载演示选择，不是官方推荐的统一最低规格。

## 新建实例

Windows本机已有构建制品时，无需为选择资源配置重新打业务包。Linux新主机仍先按[Linux指南](linux-deployment.md)准备JDK21/Maven/Python3/OpenSSL/keytool/Docker/Compose，**在业务服务未启动时先完成Build**。本轮不改变镜像分发方式，不复制Windows开发目录或真实秘密。

```sh
python3 scripts/deploy_base.py build
python3 scripts/deploy_base.py init --mode showcase --resource-profile compact4g --docs --observe
```

Init仍不自动开启模拟资金。若这台新实例只作虚构交易演示，可在Init时显式增加`--demo-money`；不要直接修改已有实例清单。

```powershell
.\scripts\deploy-base.ps1 -Action Init -Mode showcase -ResourceProfile compact4g -Docs -Observe
```

后续命令只需指定Init输出的实例目录，不再传资源参数；StorageInit/Start/Stop/Admin/维护均从清单读取profile。仅showcase支持compact4g，base/components拒绝此组合；已有容器的profile与清单不符时拒绝接管。

```sh
instance='这里填写Init实际输出的目录'
python3 scripts/deploy_base.py storageinit --instance "$instance"
python3 scripts/deploy_base.py start --instance "$instance"
python3 scripts/deploy_base.py status --instance "$instance"
python3 scripts/deploy_base.py observe --instance "$instance" --operation Snapshot
```

入口仍仅映射127.0.0.1，云端后续先通过SSH转发验收。不要为了展示数据库、Broker或管理面而映射公网端口。当前用户原ECS的SSH尚未接通，新机也须单独完成连接、系统、磁盘和资源核对；云端安装/部署不能据本地预验证记为完成。

## 有界预验证

```powershell
.\scripts\test-deploy-compact4g.ps1
```

Linux具备条件后使用同一Python入口：

```sh
python3 scripts/test_deploy_compact4g.py
```

入口复用原七服务交易链：异步领券→20 MiB PDF上传/完整下载→用券打印付款→商家履约→MQ通知→学生取件/评价缓存；整套Stop/Start后再验新领券和新S3写入。另核对三种standard默认配置、非法profile组合、实际cgroup CPU/内存/Swap/OOM、MySQL生效参数和旧实例profile保护。只使用自有测试库、卷、文件和账户，结束清理运行资源及实例秘密。

容器固定同两个逻辑CPU、逐服务硬限额均实际检查，但本机Docker引擎仍有更大宿主内存；本项**不是完整4 GiB虚拟机测试**。cgroup采样包含页缓存、逐容器顺序读取，并非精确同步总峰值；短暂客户端可能漏采。轻载功能通过不代表容量、并发性能或新ECS稳定性，M06.2仍单独执行。

本项不重跑冷备/故障注入，原E工具只验证过standard；compact4g调用该冷备演练会在冻结/备份前明确拒绝，避免把未验证资源协议带入恢复流程。这是隔离test演练工具的范围限制，并非修改业务数据或否定常规备份需求。完整云端保管/恢复方案仍须在实际部署时核对。

最终29组业务/资源及5组配置检查通过，资源与失败记录见[本轮报告](reports/compact4g-deployment-2026-11-04.md)。前端继续暂停，Java/DDL/业务接口保持原范围。
