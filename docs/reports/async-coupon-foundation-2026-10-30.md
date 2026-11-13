# M08.3-B：异步领券数据库底座验收报告

日期：2026-10-30。结果：**212项不同相关JUnit检查分轮最终通过**，失败/错误/跳过最终均0；75操作/V22/32张应用表。不是完整V1全量、覆盖率、QPS或整个异步协议验收。明细见[机器可读记录](async-coupon-foundation-2026-10-30.json)。

## 交付与边界

[ADR-0054](../decisions/0054-async-coupon-claims.md)按用户确认实现B。V22新增活动技术门闩并回填SYNC/OPEN，扩展请求状态/预留身份/原处理期限与生成列唯一约束；同步领取/商家配置共用锁围栏。增加独立券命令/路由/队列、发券消费事务及超时扫描，原键与本人查询能读取真实PENDING/FAILED。

库存＋券＋请求终态＋消费凭据同事务，提交后ACK；停止前已受理可继续发行，配置版本冻结在受理时；deadline等值失败，业务失败永久终态。当前资格失效/售罄持久失败，旧HTTP会话退出不取消处理资格。REBUILDING暂停普通发行/扫描、不延期，PAUSED可处理旧请求。默认SYNC、消息传输默认关闭，**没有公开ASYNC新受理或启用入口**；PENDING只由自有测试夹具构造，不提供跳过Lua的临时HTTP。

Redis Lua/专用实例、孤儿墓碑仲裁、结算和重建尚未实现；不宣称预留已补回、跨系统原子或性能提升。前端未推进，未使用或修改用户数据库/JWT凭据、既有服务数据或开发Broker卷。

## 证据与检查分布

| 检查类 | 去重数量 |
|---|---:|
| CouponAsyncMysqlIT | 37 |
| CouponAsyncRabbitIT | 4 |
| CouponAsyncUpgradeCheck | 1 |
| CouponCampaignMysqlIT | 74 |
| CouponClaimsMysqlIT | 8 |
| CouponOrdersMysqlIT | 19 |
| MysqlIntegrationIT | 12 |
| NotificationMysqlIT | 9 |
| NotificationRabbitIT | 4 |
| OpenApiMysqlIT | 3 |
| OutboxMysqlIT | 10 |
| OutboxRabbitIT | 8 |
| CouponIssueListenerTest | 5 |
| NotificationListenerTest | 4 |
| OutboxPublisherTest | 10 |
| RabbitOutboxTransportTest | 4 |

分轮证据在Git忽略的.cache/m083b-evidence/round*，按class＋testcase name取最后结果；重复执行不累计。新增底座37项真实MySQL、4项真实券RabbitMQ、1项真实V21升级、5项监听单元；其他为相关旧链回归及发布路由检查。

- 9个学生争3张券并重复投递：恰好3成功、6售罄失败、9份消费凭据，无超发；同请求并发只一次效果。
- 截止前1ms/等值/后1ms、使用截止更早、等待活动锁后再读Clock、扫描与消费竞争、倒退Clock无写；GET到期PENDING不代跑扫描。
- 当前学生停用/角色变化、商家停用/成员停用/店铺暂停均终态失败；原会话退出/活动停止及领取窗口结束后合法已受理仍成功；使用受理版本，不因停止版覆盖。
- 凭据写后注入异常使发行数/券/结果/凭据全回滚；原凭据hash冲突、消息内容/身份/预留快照不匹配无发行。
- gate S可同时持有并阻塞gate X停止；缺门闩不绕过；不同键PENDING/成功唯一，失败可新键、原全局键跨活动仍唯一；非法NULL/期限/状态形状被MySQL拒绝。
- 专用真实AMQP路由，消费后重启/同ID重发仍一券/一凭据；等待重复请求进入处理并停止容器完成本次消费后核对。券消息不生成通知，篡改进入券专用死信，原通知/outbox/订单券回归通过。
- 全新临时库先停在V21写入合法成功领取，真实迁移V22，原ID/学生/活动/键/券/完成时间和持有券全行保持；默认门闩/来源回填、重复迁移零新增。V1～V21文件SHA-256不变。
- HTTP已持久PENDING原键202、失败终态200，读取不发券/关单；原同步201/200保持。OpenAPI75操作，明确5个可空字段；coupon局部内联可空对象避免引用生成器忽略nullable，不放宽其他持有券对象。运行及规划快照同步导出。

## 实际执行与中间问题

1. 首轮：123项集成检查＋16项单元，新增26项里一项测试会话创建时刻晚于模拟退出，违反原会话约束；修正测试夹具显式created_at，未放宽生产约束。其他旧74活动/8领取等结果归档。
2. 自有RabbitMQ联测：95项集成＋23项单元通过，覆盖原通知/发布链/订单券；随后增加门闩/SQL形状/扫描/HTTP检查，并加强重复消息完成观察。
3. V21独立升级：1项通过。中间一次最终联测在增量编译阶段缺类失败，未计通过；不据随后跳过编译成功作结论。验证生成目录D:/campuslife/target在工程内后完整clean：266生产文件、84测试文件重新编译，134项集成＋9项单元通过，可执行包启动通过。增量缺类的具体触发根因未确定，未据此改依赖版本。
4. 加强后的券真实MQ：4集成＋9单元通过，测试Broker容器/卷及MySQL清理完成。
5. 契约复核发现普通nullable注解（及allOf注解尝试）被生成器丢弃coupon的可空引用，定向检查如实失败；在既有OpenAPI定制器局部内联原对象形状后，3项契约检查和mysql可执行包启动最终通过。该修复只影响描述，不改变发行事务。

实际核心复现命令见[指南](../async-coupon-foundation.md)。本轮还实际执行：

```powershell
# 完整重建；此前已保存证据并校验target绝对路径。
$env:MAVEN_ARGS='clean -Dit.test=CouponAsyncMysqlIT,CouponCampaignMysqlIT,CouponClaimsMysqlIT,MysqlIntegrationIT,OpenApiMysqlIT -Dtest=CouponIssueListenerTest,RabbitOutboxTransportTest'
./scripts/test-mysql.ps1 -StartupTimeoutSeconds 90
# 加强重复处理后的真实券链。
./scripts/test-messaging.ps1 -Cases 'CouponAsyncRabbitIT' -UnitCases 'CouponIssueListenerTest,RabbitOutboxTransportTest'
# 可空契约定向复验。
$env:MAVEN_ARGS='-Dit.test=OpenApiMysqlIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false'
./scripts/test-mysql.ps1 -StartupTimeoutSeconds 90
```

## 环境与剩余工作

使用本机Java21/MySQL8.0及原项目固定RabbitMQ4.3.6镜像/digest。Broker均为随机身份/回环端口、独立vhost及自有卷；联测成功记录960bdd5e86784affaf4f657f1dadebbc、d0cda748dd5944a5bf010abcbe883ecf保留在.cache/rabbit-it。失败测试目录在保存诊断/分轮证据并核实进程已退出、路径位于项目临时根后精确清理，不使用全局prune。

没有在正式项目库执行迁移或浏览器人工验收；用户按原VS Code配置重启后由Flyway执行V22，点击方法见指南。下一项M08.3-C，继续保持默认SYNC和公开ASYNC关闭，C/D及M08.4故障/性能验收不能由本报告替代。
