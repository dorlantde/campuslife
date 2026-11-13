# M06.1-D.6i 新正式包剩余定向实机验收

**结论：同一次执行的32项定向断言和5组配置检查全部通过，D.6维护缺陷整改及剩余实机断言收口。** 新正式包的首次Resume、原卷重启后的Resume及两次真实领券成功。没有新增一轮完整打印交易通过，也不更改此前三轮整链失败事实。健康探针和SSH观察单独保留，不据此承诺持续稳定、容量、HA或RTO。[机器记录](cloud-final-remaining-2026-11-06.json)/[入口与范围](../cloud-final-remaining-acceptance.md)。

## 单次运行与真实链路

本轮唯一case为 `case-f3b1fc89a03e`，外层841.056秒，原900秒监督未改。按纽约时间2026-11-06 02:19～02:33运行；原维护CLI90秒、初始化5秒、固定命令expiry500ms/TCP300ms、两次预检及租约30秒均保持。耗时包含服务启动、五个独立维护JVM及清理，不作为连接延迟或性能收益。

使用[D.6h正式包](cloud-initialization-build-2026-11-06.md)：release `/home/campusdeploy/campuslife/releases/1cf0cb53a24a`、JAR `6311c16a...`、镜像ID `76569040...`。通过原app-entrypoint和CouponMaintenanceBootstrap.main，没有额外main、业务class覆盖或测试SQL播种。正常注册学生、显式创建虚构ADMIN、商家申请审核及发布库存2张活动，模拟资金关闭。

| 原维护动作 | 实际结果 | 完整CLI耗时 |
|---|---|---:|
| 初次Pause | SYNC/PAUSED，gen1/v1 | 63.200秒 |
| Async | ASYNC/PAUSED，gen1/v2 | 59.469秒 |
| 初次Resume | ASYNC/OPEN，gen4/v7 | 71.344秒 |
| 发行1张后Pause | ASYNC/PAUSED，gen4/v8，issued/actual均1 | 62.372秒 |
| 原卷重启后Resume | ASYNC/OPEN，gen7/v13，issued/actual仍1 | 76.285秒 |

这些是整次独立进程耗时；没有采集预检每次尝试，不推断其使用第几次成功，也不称五秒为整个维护耗时上限。三次SQL/Redis暂停代次就绪等待分别31.891/28.904/1.474秒，沿原等待机制，没有改代次或版本。

两次POST均返回持久202并最终SUCCEEDED；重启前保留原五卷和24份凭据指纹，重启后旧会话、原幂等键、发行事实、LOCAL/S3身份保持。第二次发行后MySQL发行量/持有券/消费凭据均2，Redis库存total/free/held/spent为2/0/0/2，无重复或超发。没有创建订单、资金或业务文件，未运行打印、模拟付款、取件或评价。

同轮还通过七服务就绪、V24/32业务表/75操作、无默认ADMIN、管理面拒绝匿名和学生JWT、应用不挂载八类运维/root秘密、私有观测及JVM指标；最终资源/配置保护和日志扫描钩子均执行。13类生成凭据、本轮密码及4个token未出现在服务日志。断言以机器记录逐项列出，不累计历史通过项。

## 资源结果与保留观察

73次资源采样无采样错误；常驻合计采样最大约1.121 GiB，含有机会捕获的CLI最大约1.298 GiB。实际cgroup共享CPU0/1、原内存限额及禁容器Swap生效，采样记录无OOM/oom_kill/容器Swap或自动重启计数；MySQL参数与原提交持久性保持。host oom_kill为0→0，宿主Swap采样最大约4.023 MiB。缺少memory.peak时保留null，采样不保证捕获所有短命客户端或瞬时峰值，不能推出服务器最大并发容量。

额外只读观察必须保留：

- RabbitMQ在计划重启前被观察为unhealthy，失败连续计数40，最近五次探针退出-1且有超时标志；重启后恢复healthy，但后续又记录连续10/17次失败及超时。实际两次消息消费与最终管理面查询通过，**不等于探针波动已修复**，具体调度/资源或其它原因未定位。本轮没有改探针或超时。
- 原SSH执行通道报 `Connection reset`，本机原runner-result.json为0字节，均保留。只读复核确认原case进程及原Python监督仍存活，elapsed653秒，未重跑；最终从同一远端stage取回exit0回执，保存为runner-result-recovered.json。不能把通道断开当业务失败，也没有证据将它归因为Redis、RabbitMQ或OOM。

本轮可关闭已指定维护缺陷和剩余断言，不以此删除这些工程观察或承诺健康状态始终不波动。

## 输入、审计与清理

仅[测试入口](../../scripts/test_deploy_remaining.py)更新制品固定值、任务标识和失败时的受控SQL证据；七份辅助工具保持，八份输入136216字节。六份辅助文件加入新release scripts，已有deploy_base逐项核对，主入口在私有stage；原463项构建输入及JAR/镜像保持。业务Java、配置、依赖、DDL、前端均未改。

独立SSH审计确认本次八份输入、新release463项源及JAR/镜像一致；前两release源码/已记录补丁、七份辅助工具和原JAR/镜像保持。最终containers、volumes、测试/探针网络、生成secrets与探针秘密目录均空。旧17/9个夹具目录保持，新release4个目录仅为三种配置夹具和本轮运行留证，无运行中测试或长期实例；本机Docker未启动。

证据在 `.cache/cloud-final-remaining/case-f3b1fc89a03e`，机器报告记录输入、原空回执/错误、同case恢复回执、监督存活证明、健康观测与独立审计摘要。实际目标机命令：

```sh
python3 -u /home/campusdeploy/.cache/campuslife-final-remaining/case-f3b1fc89a03e/test_deploy_remaining.py --release /home/campusdeploy/campuslife/releases/1cf0cb53a24a --output-dir /home/campusdeploy/campuslife/releases/1cf0cb53a24a/.cache/acceptance/case-f3b1fc89a03e
```

本轮只执行一次，不要求再次运行该历史命令。旧三轮整链及D.6e失败记录保持；本轮属于修复后正式包的缺陷回归和剩余边界验收，不拼接为一轮新整链通过。

下一项建议 **M06.1-D.7：云端观测与私有演示交接方案定稿**，基于已有证据评估健康探针行为、断线后回执取得方式及是否保留私有实例，明确范围和完成标准后再决定实施。该项尚未开始，不默认修探针、运行长期实例或开放公网；M06.2综合测量与M07随后，前端仍暂停。
