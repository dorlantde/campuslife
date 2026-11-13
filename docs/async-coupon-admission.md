# M08.3-D：公开异步领券与本机模式维护

2026-11-05 D.6g补充：显式coupon-maintenance进程初始化/URI及同步等待预算改为5秒，已连接命令固定expiry仍500ms，普通HTTP/后台原500ms、TCP300ms、预检最多两次、lease30秒和CLI90秒保持。[隔离与相关回归](reports/maintenance-initialization-budget-2026-11-05.md)已通过；云端正式制品仍旧版，尚待构建/验收，不能据此直接处理已有活动或宣称D.6通过。

2026-11-05维护实现补充：ASYNC Resume及ASYNC→SYNC维护已接入[票据前连接预检](coupon-maintenance-preflight-design.md)，失败安全码为 `COUPON_REDIS_PREFLIGHT_FAILED`；仅匹配连接超时时最多额外尝试一次，原版本/租约与HTTP规则保持。[定向验证](reports/maintenance-preflight-2026-11-05.md)已完成，正式云端新制品仍待核验。下文为原M08.3-D交付记录。

日期：2026-10-31。按[ADR-0054](decisions/0054-async-coupon-claims.md)接通原有入口，无新HTTP路径、迁移或业务表，仍75操作/V23/32表。前端暂停。默认活动SYNC、消息与券Redis关闭；本轮只在自有临时环境启用并验证，不修改现有开发活动的模式。验证证据见[报告](reports/async-coupon-admission-2026-10-31.md)。

## 入口的承诺

同一个`POST /api/v1/coupon-claims`按数据库门闩选择路径。SYNC首次201/SUCCEEDED，原键重放200；ASYNC首次202/PENDING，只有领取请求与发行outbox同事务提交后才返回。202含`Location: /api/v1/coupon-claims/{claimId}`及`Idempotency-Replayed: false`，只承诺持久受理。原PENDING重放202/true，终态重放200/true；Location也随重放返回。同步响应同样提供结果地址。

三阶段为：短SQL身份/原键/资格预检 → **SQL事务外**Lua预留 → 短RC事务复核并写PENDING/outbox。受理锁序为学生账号X→会话X→店铺S→gate S→活动当前读→请求/outbox，不主动取活动库存X锁。外键仍可能取得父行锁，受理与消费都有数据库成本；本轮不宣称吞吐提升。

固定锁后毫秒时间，deadline=min(acceptedAt+5分钟,useEndAt)。公开资格、PUBLISHED及领取窗口、当前学生/会话、代次和Redis运行标识均复核。原键跨活动409；同活动每人最多一个进行中、终身成功一次。结果GET只读，不自动超时/补偿。公开remainingQuantity仍是MySQL未发行量，不能作为Redis可受理额度。

已持久原键先于依赖可用性与活动资格读取：Redis/消息关闭、活动停止、店铺暂停也能重放，但当前学生身份与会话仍必须有效。首次ASYNC要求本应用显式启用券Redis和消息组件；Broker短时离线由outbox保留事实，仍受原5分钟期限约束。配置启用不证明Broker在线或积压能及时处理。

Lua未知回包、SQL回滚或提交结果不确定时先查持久原键；可确定原事实则重放，否则返回安全的409/503，**不直接返库存**。保留原Idempotency-Key重试。C的30秒孤儿仲裁持久写FAILED后才按原rid条件释放；FAILED永久占用原键，新键只在仍合格时可重试。先被孤儿墓碑仲裁失败的请求不会复活。

## 指定活动启用与关闭

维护仅限本机显式脚本，使用数据库账号权限，和平台业务ADMIN不是同一个概念。入口是独立`CouponMaintenanceBootstrap`进程，不启动HTTP，也不启动发布/消费/恢复定时任务。普通应用不会自动执行维护。不要手写UPDATE切模式，或让旧二进制绕过gate。

启用前停止旧版本实例，完成全部实例升级；启动本机自有券Redis和RabbitMQ，随后重启正常后端让配置生效。服务启停沿用已验证的[券Redis指南](coupon-redis-recovery.md)与[消息指南](reliable-messaging.md)。只启动依赖不改变任何活动的默认SYNC。脚本中的数据库密码仍在本机输入，不在聊天或命令参数里填写。

下面`123`仅代表你自己创建并发布的虚构活动；每一步先Inspect，把其**最新controlVersion**代入下一命令，不能使用活动的业务version，也不要直接照抄示例版本0/1/2。

```powershell
.\scripts\coupon-mode.ps1 -Action Inspect -CampaignId 123
.\scripts\coupon-mode.ps1 -Action Pause -CampaignId 123 -ExpectedControlVersion 0
.\scripts\coupon-mode.ps1 -Action Inspect -CampaignId 123
.\scripts\coupon-mode.ps1 -Action Async -CampaignId 123 -ExpectedControlVersion 1 -AllInstancesUpgraded
.\scripts\coupon-mode.ps1 -Action Inspect -CampaignId 123
.\scripts\coupon-mode.ps1 -Action Resume -CampaignId 123 -ExpectedControlVersion 2
```

`Async`只在PAUSED、无PENDING且发行数=真实持有券行数时改变模式，继续保持PAUSED。`-AllInstancesUpgraded`是操作者对部署状态的明确声明；程序无法远程证明所有旧进程已退出。`Resume`为ASYNC持久创建新代次的REBUILDING票据及OPEN恢复意图，构建并核对Redis后才开放SQL门闩。中断留下关闭门闩，30秒协调租约到期后由C接管，保留这次显式OPEN意图；不会延长5分钟业务期限。

反向切回SYNC：先Pause，Inspect确认pending=0（旧请求仍可正常消费或按原期限失败），再Sync，最后Resume。PENDING非零时直接拒绝切换，不提前强制失败已受理请求。

```powershell
.\scripts\coupon-mode.ps1 -Action Inspect -CampaignId 123
# 把 v 替换为刚查到的controlVersion；每条变更后再次Inspect取得新的v
.\scripts\coupon-mode.ps1 -Action Pause -CampaignId 123 -ExpectedControlVersion v
.\scripts\coupon-mode.ps1 -Action Sync -CampaignId 123 -ExpectedControlVersion v
.\scripts\coupon-mode.ps1 -Action Resume -CampaignId 123 -ExpectedControlVersion v
```

`v`是占位符，不是可直接执行的参数。Sync操作先用新代次重建关闭的Redis control/epoch，再条件改变数据库模式，保持PAUSED。Redis不可用时切回也会拒绝，不把故障降级当维护成功。全部成功后才可关闭专有依赖；关闭前还应确认没有其他ASYNC活动。历史已领券/订单用券不返发行库存，重新ASYNC时从MySQL重建终身成功标记。

所有变更带controlVersion，拒绝旧操作者覆盖较新的暂停、重建或恢复。Sync最终回写固定使用自己票据对应版本，不读取并采用别人的新版本。REBUILDING期间人工Pause/Resume拒绝；Inspect后等待当前协调者完成或租约接管，不直接解除围栏。维护失败后先Inspect，不能假定失败意味着所有步骤都未提交。

| 提示 | 处理方式 |
|---|---|
| CONTROL_VERSION_CHANGED | 重新Inspect核对实际状态与本次目的，使用新版本操作 |
| PAUSE_FIRST / PENDING_MUST_DRAIN | 先暂停新领取，再等既有请求终结；GET不会代跑清理 |
| ISSUED_COUNT_MISMATCH | 保持暂停，核对真实发行事实；不要手工加库存绕过 |
| MESSAGING_MUST_BE_ENABLED / COUPON_REDIS_MUST_BE_ENABLED | 在正常应用与维护进程显式配置专用依赖后再重试 |
| REBUILDING_WAIT_FOR_LEASE / 协调失败 | 保持关闭；确认依赖恢复，再由原恢复任务按租约接管 |

## Swagger / Apifox点击验收

1. 默认SYNC的点击流程沿用[同步领取](coupon-claims.md)。商家创建并发布活动，领取窗口应覆盖当前时间；这里只用虚构测试账号和活动。
2. 指定活动经上面的维护流程变为ASYNC/OPEN后，在`http://127.0.0.1:8080/swagger-ui/index.html`或导入[运行契约](openapi/campuslife.openapi.json)。用正常学生登录，data.accessToken填Authorize。
3. POST /coupon-claims填写Idempotency-Key，如`async-demo-1`，正文`{"campaignId":"实际活动ID"}`。预期202，data.status=PENDING，coupon/completedAt为null，记录data.id、acceptedAt、deadline及Location。处理较快时随后的GET可能已是SUCCEEDED。
4. GET /coupon-claims/{claimId}观察SUCCEEDED/FAILED；成功后GET /me/coupons可见实际券。用原键再提交：仍PENDING为202，已终态为200，ID/受理时间/期限不变。换活动仍409。
5. Pause后用新学生/新键提交应503，原PENDING仍可查/重放并处理。排空后切回SYNC并Resume，再用新学生首次领取应201；已成功学生不能再领。
6. 对503或连接断开保留原键。不要把202当作得券，也不要因为页面暂时PENDING就用新键重抢。FAILED读取failureCode，原键不会重新发行。

本轮真实HTTP/三组件自动化链路已经覆盖这些服务端行为；浏览器人工点击单独验收，不标作已完成。没有优惠券前端页面。

## 自动复现与边界

```powershell
.\scripts\test-coupon-redis.ps1 -Phase Messaging -Cases 'CouponAdmissionIT,CouponMaintenanceBootstrapCheck,CouponAsyncRabbitIT,OpenApiMysqlIT' -UnitCases 'CouponRedisRepairJobTest,CouponIssueListenerTest'
.\scripts\test-coupon-redis.ps1 -Phase Messaging -Cases 'CouponAdmissionDisabledMysqlIT,CouponClaimsMysqlIT,CouponOrdersMysqlIT,CouponRedisIT,MysqlIntegrationIT' -UnitCases 'CouponRedisRepairJobTest,CouponIssueListenerTest'
```

Runner仅创建自有临时MySQL、专用Redis和RabbitMQ，结束清理自有容器/卷；失败数据库保留诊断。实际执行的组合与轮次以报告为准。独立维护main通过子JVM实测，脚本参数通过PowerShell解析检查；不读取现有开发库凭据来替用户改变真实活动。

事务异常、丢回包、暂停/停止与授权变化用可定位注入复现，不能称为全部真实进程崩溃验收。没有高可用、容量、性能收益或所有宕机窗口结论；真进程窗口及与SYNC同负载对照留到M08.4。普通同步与异步消费/订单仍保留原事务、锁、唯一约束及幂等，不重写V1。
