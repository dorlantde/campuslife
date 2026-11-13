# M08.3-D：公开受理与受控切换验收报告

日期：2026-10-31（America/New_York）。**147项不同相关JUnit检查分轮最终通过**，最后结果均通过、无跳过；仍75操作/V23/32张应用表。计数以(class,testcase name)合并最后执行结果，重复运行不累计，不把旧target目录残留XML计入本轮。明细见[机器可读记录](async-coupon-admission-2026-10-31.json)。

## 交付结果

原POST /coupon-claims接入SQL预检→事务外Lua→短RC持久受理，PENDING与唯一发行outbox同事务提交后才202；新增Location，原键PENDING202/终态200，SYNC首次201保持。GET仍只读本人事实，目录剩余量仍是MySQL未发行数。没有新增HTTP路径、依赖、迁移或表，V1～V23全部迁移及56个前端源文件SHA-256未变。

本机独立维护入口Inspect/Pause/Async/Sync/Resume不启动HTTP、消息消费者或调度循环。controlVersion阻止旧操作者覆盖；跨模式必须先暂停、排空PENDING并核对真实发行数。ASYNC Resume持久新代次及OPEN恢复意图后沿C构建；切SYNC先新代次PAUSED Redis，再使用自身票据对应的固定版本条件切换，最后显式Resume。未知/失败保持关闭，不读到新版本就替别的维护操作继续。

默认SYNC、仓库消息/券Redis关闭；没有替用户变更现有开发活动，也没有恢复前端。所有新ASYNC启用均使用本轮自有临时库/Redis/Broker。设计与点击操作见[指南](../async-coupon-admission.md)，规则继续[ADR-0054](../decisions/0054-async-coupon-claims.md)。

## 检查分布

| 类 | 去重检查数 | 作用 |
|---|---:|---|
| CouponAdmissionIT | 41 | 真实HTTP/预留/受理、身份/窗口/竞争/未知结果、维护切换与子JVM、三组件最终发行 |
| CouponAdmissionDisabledMysqlIT | 3 | 两个依赖关闭时默认同步、拒绝新ASYNC、原异步事实可重放 |
| CouponMaintenanceBootstrapCheck | 1 | 真实非Web上下文、不需要JWT、不启消费者与恢复任务 |
| CouponClaimsMysqlIT | 8 | 原同步HTTP、权限、幂等、锁等待及目录兼容 |
| CouponOrdersMysqlIT | 19 | 两类订单用券及释放/退款兼容 |
| CouponRedisIT | 48 | 原C真实Lua/仲裁/重建/租约/Redis故障回归 |
| MysqlIntegrationIT | 12 | 迁移、约束及数据库连接 |
| OpenApiMysqlIT | 3 | 真实运行快照、权限/契约、交互请求 |
| CouponAsyncRabbitIT | 4 | 原专用券路由、消费与有界重试/终态 |
| CouponIssueListenerTest | 5 | ACK/异常分流 |
| CouponRedisRepairJobTest | 3 | 调度隔离/无积压/关停 |

总计147。原始XML保留Git忽略目录`.cache/m083d-evidence/round*`，其中round1曾复制到历史文件；统计严格限定各轮实际选择的类。公开JSON只提取名称/结果/自有夹具身份，不包含XML系统属性、密码或token。

## 关键证据

- 真实HTTP首次202的Location能读取本人PENDING；原键202/true且受理时刻/截止不变，消费后原键200/SUCCEEDED。跨学生404、跨活动同键409。默认SYNC首次201/重放200不调用Redis。
- 9名学生真实HTTP并发争3张券恰好3次202、6次409，最终发行3、Redis spent=3；同学生4个相同键均返回同一持久请求，异键仅一个受理；请求/outbox各一条，不将202当成功发行。
- SQL受理复核发生在Lua之后：Pause、代次重建、活动Stop、退出、停用、角色改变、会话到界均不能越过校验。受理先提交则停止/原会话退出不撤销其合法发行权。处理deadline按useEnd提前截断，GET不会自动终结到期PENDING。
- outbox写后异常使PENDING/outbox全回滚，没有202；Redis仍HELD，30秒后先写永久FAILED墓碑再释放。丢Lua回包先503，同键重试找到原rid不多扣；模拟持久提交后响应未知时查原键返回原PENDING，不重复outbox。SQL提交和随后读取均不确定时503，不猜测返库存。
- 同全局键在两个活动预检查/预留后竞争，只先提交者受理，另一活动预留由永久键绑定证明仲裁释放；本次成功活动不被错误释放。孤儿墓碑在Lua之后先提交时，HTTP返回其FAILED原事实，不重建PENDING。
- 不允许在外层SQL事务内调用整个三阶段入口；Redis本身的事务外守卫继续生效。会话在outbox写后到期时，最终校验使整个受理事务回滚。
- 默认/旧控制版本不能启ASYNC；Pause不阻止旧PENDING发行，但有PENDING不能换SYNC。新代次关闭Redis后切回SYNC，旧Lua不能再预留，重新ASYNC从SQL恢复永久成功标记。
- 三轮Resume与Sync并发竞争同一controlVersion均只有一个维护意图成功，最终只有一种模式可开放；旧操作者不采用较新的Resume版本。SQL发行数与真实券数不一致时拒绝切换。
- Resume构建中注入失败留下REBUILDING/OPEN意图，30秒协调租约后C接管，保持原意图及业务截止；没有因维护异常自动退回SYNC。
- 一条真实链路：HTTP202→Redis HELD→SQL请求/outbox→真实RabbitMQ确认→真实消费者提交→本人GET SUCCEEDED→Redis SPENT。原C48项包含真实Redis stop/kill/OOM、迟到与重建，提取公共complete(ticket)后回归通过。
- 独立维护main通过实际子JVM启动并Inspect自有活动，exit=0，输出SYNC/OPEN，不启动Tomcat；另一个真实非Web上下文核对无JWT服务、消费者和恢复Job。PowerShell包装脚本语法解析通过，未用现有开发库实际执行切换脚本。
- 最终MySQL可执行JAR健康/UTF-8/trace/访问边界启动通过；运行OpenAPI重新导出为75操作，202的Location/Idempotency-Replayed头已记录；完整导入文件同步生成。

## 实际执行与修复

| 轮次 | 本轮检查执行数（含相关单元） | 结果与处理 |
|---|---:|---|
| round1 | 48 | 新入口33项中15个断言失败、1个错误；原Rabbit4/OpenAPI3/单元8通过。测试令牌整秒期限与人工创建的毫秒会话期限不一致，真实认证正确拒绝；夹具改为与真实登录同样使用IssuedToken期限。事务MANDATORY服务不适合在无事务位置直接配置spy，改在无事务代理的outbox mapper注入写后异常 |
| round2 | 137 | 136通过；唯一失败为“旧键重放不调用Redis”的verify误计入准备原请求时的合法reserve。清除准备阶段调用记录后再观察；服务端HTTP202/200/409、其余D与全部旧业务/恢复目标通过 |
| round3 | 57 | 新入口41、独立bootstrap1、原Rabbit4、OpenAPI3及单元8全部通过；补足三轮维护并发、SQL未知/外层事务守卫和维护子JVM。完整构建及mysql JAR启动通过 |

编译预检查成功不计为真实组件验证。147是各轮不同目标的最终结果合并，不声称一条命令跑了147项或一次全绿覆盖全部历史业务。

本轮执行的三条隔离验收命令如下（均使用`-Phase Messaging`的自有服务）：

```powershell
.\scripts\test-coupon-redis.ps1 -Phase Messaging -Cases 'CouponAdmissionIT,CouponAsyncRabbitIT,OpenApiMysqlIT' -UnitCases 'CouponRedisRepairJobTest,CouponIssueListenerTest'
.\scripts\test-coupon-redis.ps1 -Phase Messaging -Cases 'CouponAdmissionIT,CouponAdmissionDisabledMysqlIT,CouponMaintenanceBootstrapCheck,CouponClaimsMysqlIT,CouponOrdersMysqlIT,CouponRedisIT,MysqlIntegrationIT,OpenApiMysqlIT' -UnitCases 'CouponRedisRepairJobTest,CouponIssueListenerTest'
.\scripts\test-coupon-redis.ps1 -Phase Messaging -Cases 'CouponAdmissionIT,CouponMaintenanceBootstrapCheck,CouponAsyncRabbitIT,OpenApiMysqlIT' -UnitCases 'CouponRedisRepairJobTest,CouponIssueListenerTest'
```

## 环境收尾与未验证范围

Docker引擎起初未运行，启动本机已有Docker Desktop后29.8.0就绪；没有安装/升级组件。三轮自有券Redis夹具身份为`039ff3e0e55045d9ab738caf94d255c8`、`100c9e2b423c45618af42cb91cc1a5de`、`d70abc2c63cd475fb1812969dccefdeb`；Rabbit身份见JSON。自有临时容器/卷均已清理，最后一轮completed.json核对成功；Docker标签复核没有遗留test资源。

两份失败MySQL数据目录`2f6eb3cd2de94c13a7a6224b4238ac3e`、`3383cd2dd0ab46d38a5977c68899b246`保留XML诊断后，核对绝对路径属于D:/campuslife/.cache/mysql-it且无对应mysqld进程，再精确删除；成功库由原runner清理。不访问开发库数据/持久文件目录。开发券Redis仍exited，自有AOF卷保留，默认选择配置未改变。

没有执行全V1回归、真实浏览器人工点击或全部JVM宕机窗口；未知回包/业务暂停/SQL写后异常使用明确注入。没有SYNC与ASYNC同负载吞吐/最终成功延迟对照、容量或多节点HA结论。数据库仍有受理/outbox/发行写入及外键锁成本，不能据Lua预留或202响应断言性能提升。下一项M08.4先固定真实故障矩阵和测量口径，再用公开入口执行对照；前端继续暂停。
