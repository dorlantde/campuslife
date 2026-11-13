# M08.3-B：异步领券数据库底座验收

2026-10-31当前状态：B/C/D已验证，75操作/V23/32表，公开受理与本机模式维护见[公开受理/维护指南](async-coupon-admission.md)。下文V22及未开放说明是B批历史边界。

2026-10-31更新：C组件已按[指南](coupon-redis-recovery.md)/[报告](reports/coupon-redis-recovery-2026-10-31.md)交付，当前V23/32表；下文V22与“下一批C”为B交付时的阶段事实。公开新异步受理仍关闭，当前下一项D。

本批依据[ADR-0054](decisions/0054-async-coupon-claims.md)。**默认仍同步领取，未提供ASYNC启用/新受理入口；Redis Lua、孤儿补偿和重建在C批。** 75个运行操作，V22，32张应用表（不含Flyway历史表），前端暂停。

## 已实现的事务边界

V22扩展领取请求PENDING/SUCCEEDED/FAILED、全局原键、进行中/成功唯一约束，新增每活动一个技术门闩；旧成功回填SYNC，原ID/键/券/完成时间保持。新商家活动与SYNC/OPEN门闩同事务创建。

同步新请求必须经过OPEN/SYNC门闩，商家变更先锁gate X；原键结果优先于当前活动资格重放。消费路径按学生账号→店铺S→gate S→活动X→请求/券/消费凭据加锁，不使用原HTTP会话；库存、券、终态、凭据一并提交，之后ACK。停止/领取窗口结束不撤销持久受理；当前身份/经营资格、原5分钟截止及最终库存仍约束发行。正常业务失败记录FAILED，不重试发行。

消息使用独立COUPON_ISSUE_REQUESTED / coupon.issue / campuslife.coupon.issue.v1及其dead队列，复用原发布确认、租约和有界重试。通知队列不接券消息。Redis不在线不影响B批发券/超时事务；此处并不代表Redis预留已经结算。

每秒最多扫描100条已到deadline的PENDING，每单独立RC事务；配置campuslife.coupon.expiry-enabled（环境COUPON_EXPIRY_ENABLED，默认true）控制自动调度，测试关闭自动调度并直接调用。REBUILDING跳过，PAUSED允许旧请求处理，暂停不延期；缺门闩报错，不静默当SYNC。

## Swagger / Apifox人工兼容验收

按原VS Code MySQL配置启动，Flyway自动执行V22；不需要手动改旧迁移或填写root账号。沿[同步领取指南](coupon-claims.md)准备虚构商家和学生：

1. 商家Authorize，创建活动并发布；原六个商家活动接口照常使用。
2. 学生Authorize，POST /api/v1/coupon-claims填写campaignId及合法Idempotency-Key，首次201、同键同活动200、换活动409；新键再领同活动409。
3. GET /api/v1/coupon-claims/{claimId}：status=SUCCEEDED，原coupon/完成时间保持，新增acceptedAt/deadline/failureCode为null（SYNC没有异步受理事实）。
4. 停止活动后用原键仍读成功，另一学生新领取冲突；GET /api/v1/me/coupons及原两类订单用券按既有指南执行。

PENDING、超时、重复投递和模式围栏用下列隔离组件检查验证；本批没有用于手工造PENDING的HTTP，不要直接改项目库mode或伪造领取/资金记录。202仅可能是已有PENDING原键重放，B不会首次公开受理ASYNC。

## 可复现的隔离检查

这些命令由项目根运行，脚本只访问自有临时MySQL/RabbitMQ，不访问用户现有服务数据。第二条需要Docker Desktop与已拉取的固定RabbitMQ镜像（沿原[messaging指南](reliable-messaging.md)）。

```powershell
# 必须单独运行：先在新临时库形成V21事实，再迁移V22。
$env:MAVEN_ARGS='-Dit.test=CouponAsyncUpgradeCheck -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false'
./scripts/test-mysql.ps1 -StartupTimeoutSeconds 90
Remove-Item Env:MAVEN_ARGS

# 新底座及真实RabbitMQ，B批不依赖Redis。
./scripts/test-messaging.ps1 -Cases 'CouponAsyncMysqlIT,CouponAsyncRabbitIT,NotificationRabbitIT,NotificationMysqlIT,OutboxMysqlIT,OutboxRabbitIT,CouponOrdersMysqlIT,MysqlIntegrationIT,OpenApiMysqlIT' -UnitCases 'CouponIssueListenerTest,RabbitOutboxTransportTest,OutboxPublisherTest,NotificationListenerTest'
```

实际命令/分轮结果以[本轮报告](reports/async-coupon-foundation-2026-10-30.md)为准；上面的专项只覆盖B与相关回归，不代表全部V1或全协议故障验收。项目库迁移、浏览器人工、Redis/M08.4容量测量不应冒充本轮已执行。

## 下一批停止条件

下一项M08.3-C接专用Redis、条件结算/孤儿FAILED墓碑与重建协调。C仍保持默认SYNC、禁止公开启用ASYNC；只有恢复与围栏验证后，D才接通Lua→持久受理→202及活动模式维护。
