# 站内通知（M03.2-B）

M03.3最新补充：真实进程中断/重启与死信目标故障已验证，[最新报告](reports/messaging-faults-2026-10-30.md)区分实际窗口、178秒死信恢复及剩余边界；下文“留M03.3”为原阶段记录。


沿用[ADR-0053](decisions/0053-reliable-order-messaging.md)规则。75操作/V21/31表，[报告](reports/notifications-2026-10-30.md)区分实测与后续故障演练，前端暂停。

先运行 `./scripts/messaging.ps1 -Action Start`，再停止旧后端、用原VS Code MySQL配置重启，Flyway执行V21；显式本机Start后发布和消费一起开启，仓库默认关闭。

## Swagger/Apifox

1. 学生创建并支付虚构预约，商家首次接单；或打印首次进入待取件。等待后台处理后，学生Authorize，GET /notifications。
2. read=false查询未读；PATCH /notifications/{notificationId}/read不填请求体，重复保留首次readAt。
3. 他人不可读取该通知，标该ID返回404；商家/管理员不能调用学生接口。
4. 通知是当时店名、订单号和发生时间的历史快照；即使后来退款也保留，实际履约以当前订单状态与权限为准。

默认page=1、size=20，最多100；read仅小写true/false。按落库时间/id倒序，落库起90天展示，等于边界排除；GET不标已读/删除。凭据和历史记录暂不物理清理。没有代理人工点击验收。

## 消费与恢复

固定JSON不超过16KiB，验证消息身份、类型/版本及原outbox正文/hash。凭据与通知同RC事务，提交返回后ACK，重复只一次效果。数据库/事务暂态异常最多3次、事务外等待1/2秒；永久错误或用尽次数进入死信。

`./scripts/test-messaging.ps1`使用自有Rabbit容器/卷、独立MySQL/PDF目录验证通知及原发布链。普通MySQL脚本关闭消息网络。真实杀进程、死信目标失效及更细ACK窗口留M03.3。

本机维护入口不启动HTTP，不允许任意JSON：

```powershell
./scripts/messages.ps1 -Action Inspect -MessageId '<日志中的32位messageId>'
./scripts/messages.ps1 -Action Replay -MessageId '<日志中的32位messageId>' -Reason '已核实并修复故障'
```

本机填写campuslife数据库账号密码，不发送到聊天。可用进程DB_HOST/PORT/NAME/USERNAME，默认127.0.0.1:3306/campuslife及campuslife账号；环境在结束恢复。核心维护Java入口已在独立JAR进程验证，PowerShell包装仅做语法检查，未代输入正式密码执行。

Inspect只输出ID/状态/次数。Replay须原因，活动租约未到期拒绝，已消费不改；其他按原ID/正文回READY，记录次数、最后原因/时间，重置本轮尝试次数。并发消费仍靠唯一凭据防重复。不能盲目重放全部SENT。

新增message_consumption、notification及outbox重放字段，不改变订单/资金/券/文件期限。SENT只代表发布确认，通知落库才是消费完成。没有短信/邮件/WebSocket、前端消息页或Lua异步领券；开发Broker保留上轮停止状态，测试资源结束清理。
