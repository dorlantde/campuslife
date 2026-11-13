# RabbitMQ与outbox可靠发布（M03.2-A）

M08.5当前补充：按[ADR-0055](decisions/0055-bounded-outbox-publishing.md)实现私有单工作线程，每秒唤醒、每轮最多32次逐条claim及1秒继续预算，失败结束，总8秒关闭等待；[指南](bounded-outbox-publishing.md)/[B的182项报告](reports/bounded-outbox-publishing-2026-11-01.md)。[C真实故障与旧新对照](reports/outbox-publisher-comparison-2026-11-01.md)已通过：11个JVM窗口，三档完整p95中位0.58～1.15秒、较旧异步降93.88%～96.95%。默认开关、原租约/确认/消费协议保持，不外推容量；下文A的每秒单条发送为历史版本，不应套用为当前吞吐。

M08.4-A配置修复：通知和领券两类消费者同时开启会占用4个长期通道，原共享连接的4通道有界上限使发布器无法取得通道。outbox RabbitTemplate现使用连接工厂的独立发布连接，RabbitAdmin复用该模板进行初始及重连声明；仍启用correlated confirm及mandatory return，不改路由、重试、凭据或业务协议。[故障指南](coupon-faults.md)与[报告](reports/coupon-faults-2026-10-31.md)记录复现和回归；旧配置只在隔离负向对照中复现。

M08.3-B当前补充：75操作/V22/32表，原通知消费已实现，见[通知指南](notifications.md)。新增独立coupon.issue路由、campuslife.coupon.issue.v1及campuslife.coupon.issue.dead.v1两队列；ACK仍不等于业务成功，券命令结果由本人领取查询读取。公开异步受理尚关闭，不能靠切换消息开关开始异步领券；见[底座指南](async-coupon-foundation.md)。下文A阶段的“无消费者”为历史记录。

M03.3最新补充：真实进程中断/重启与死信目标故障已验证，[最新报告](reports/messaging-faults-2026-10-30.md)区分实际窗口、178秒死信恢复及剩余边界；下文“留M03.3”为原阶段记录。


规则见[ADR-0053](decisions/0053-reliable-order-messaging.md)，范围与证据见[报告](reports/outbox-publishing-2026-10-30.md)。当前73操作/V20/29表；仅发布链，尚无学生通知列表/消费者，前端继续暂停。

## 本机启动与停止

本机Docker Desktop Linux引擎需已运行。以下命令本轮已在本机验证：

```powershell
.\scripts\messaging.ps1 -Action Start
.\scripts\messaging.ps1 -Action Status
.\scripts\messaging.ps1 -Action Stop
```

Start首次创建项目专用回环端口、持久卷、vhost和非guest账号；镜像固定4.3.6及digest。之后Start复用同一已校验容器/卷，不覆盖已有队列。Status显示本容器状态和端口；Stop保留消息及卷并关闭本机传输启用配置。脚本不会停止MySQL、Redis或其他容器，也不使用prune。

生成的凭据只留Git忽略的`.local/rabbitmq/manifest.json`及环境文件；本机管理页登录时自行查看该文件的username/password。不要把文件、密码或token发到聊天/提交Git。该账号只用于消息Broker，与业务学生/商家/管理员、MySQL账号不同。

Start在`.local/messaging.properties`显式开启本机传输；Stop关闭。两种操作后都需停止旧后端，再用原VS Code CampusLife (MySQL)配置启动；Flyway执行V20。数据库/JWT配置保持原方式，代理没有读取或修改现有密码。当前本机Broker已测试后停止、传输关闭，需要使用时再Start。

仓库默认MESSAGING_ENABLED=false。关闭时不创建Rabbit连接，两个新业务动作仍保存READY，稍后启用再发布。普通构建和scripts/test-mysql.ps1默认不连Broker；scripts/test-messaging.ps1才使用单独随机环境启用。

## Swagger/Apifox点击观察

1. 按Start输出的管理页地址在本机打开RabbitMQ管理界面，用本机专用账号登录。确认自己的vhost，查看Queues与连接状态，不要反复Get/requeue同一消息。
2. 原Swagger/Apifox中学生下预约并模拟支付，商家首次accept；另一路打印首次ready-for-pickup。登录切换沿原指南，消息ID不是订单ID或token。
3. 传输开启时观察`campuslife.notification.v1`的Ready数量与后端`Outbox message=... attempt=... result=ACK leaseUpdated=true`日志。本批没有消费者，队列留存是预期，**ACK/SENT不代表已生成学生通知**。
4. 同一合法商家动作重试应保留原订单时间/版本，不追加消息。原订单事件只有一次业务事件；不为发送再增加订单事件或资金。
5. Stop并在关闭传输配置下重启后端，再做一次合法动作，订单可成功；再次Start/重启后积压可发送。若保持应用传输开启而直接停Broker，失败会退避留待重试，20次后DEAD须人工排查；不要把队列清空或所有SENT重置为READY来解决。

本轮没有代理代用业务账号人工点击验收。自动测试另用自有账号、独立MySQL和PDF目录；本机原有业务数据不用于故障注入。B批才增加本人通知GET/PATCH与消费去重；A阶段看不到通知页面不属于接口缺失。

## 复现自动验收

```powershell
.\scripts\test-messaging.ps1
```

该命令本轮实际执行通过：随机标签容器/独立持久卷、随机回环端口/凭据，真实MySQL/AMQP；结束只清理精确匹配身份及端口的自有资源，不清空开发卷。测试包括停机/重启，必须使用专用脚本，不能把测试连接指向开发或外部Broker。

状态日志仅输出messageId、attempt、分类及租约回写结果，不输出凭据或完整payload。RETURNED表示无目标队列、NACK表示Broker拒绝、TIMEOUT表示确认未知、CONNECTION_FAILED表示连接/发送异常；都不能当SENT。超时可能已收到，恢复始终同ID发送；B批消费唯一键负责抑制重复业务效果。

本轮确认丢失窗口用“真实确认后跳过DB回写”模拟应用退出验证，非真实杀进程；确认等待超时由单元注入，真实网络丢包及消费ACK窗口留M03.3。主/死信quorum和at-least-once参数已核对，死信目标不可用的转移安全在B/M03.3再验证。单节点/原始执行耗时不等于生产HA或容量结论。
