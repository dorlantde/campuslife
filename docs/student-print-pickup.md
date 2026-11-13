# 学生确认打印取件验收：Swagger / Apifox

2026-10-26 后续评价已按[ADR-0042](decisions/0042-student-order-review.md)接入，见[学生评价指南](student-order-review.md)。合法完成单可本人一单一评，详情reviewed反映真实记录（含隐藏），不改订单版本/状态事件/资源；旧阶段固定false或学生评价未开放由此取代，公开评价/统计后续。

2026-10-26 后续管理员PRINT异常已按[ADR-0041](decisions/0041-admin-print-exception.md)接入，见[点击指南](admin-print-exception.md)。已付款未完成打印可由管理员基于原因/版本核实全额退款关闭，开始/待取件时间保留、进度ABORTED；已完成不退款，ADMIN仍无内容权限。旧阶段“管理员PRINT未开放”的描述由本次取代。

按[ADR-0040](decisions/0040-student-print-pickup.md)及[确认方案](student-print-pickup-design.md)实现。沿原VS Code **CampusLife (MySQL)** 配置重启；无新增数据库迁移、账号或启动凭据。运行56个操作，完整规划62/已实现56/待开发6。

## 1. 准备待取件打印单

打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，或在Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，基础地址使用实际本机端口。完整规划快照含待开发能力，不能代替运行快照验收。

按[打印履约指南](merchant-print-fulfillment.md)完成学生上传、报价、创建、模拟支付，商家接单、start、ready-for-pickup。记录真实orderId及printing.fileId；应为IN_SERVICE / PAID / READY_FOR_PICKUP，readyAt已有值，completedAt与pickedUpAt仍null。

## 2. 由学生确认取件

使用**该订单学生**的登录响应data.accessToken填Authorize，只填token本身。在“05 学生订单”调用新增POST `/api/v1/orders/{orderId}/pickup`，填写真实orderId，**无请求体**，不发空对象、JSON null、客户端时间或金额。

预期200：

- status=COMPLETED，paymentStatus仍PAID，printing.progress=PICKED_UP。
- completedAt与printing.pickedUpAt为相同UTC时刻，原readyAt/startedAt/acceptedAt/paidAt不改。
- 订单版本+1，一次PICKUP事件：IN_SERVICE→COMPLETED，actorType=STUDENT、actorId为本人、reason=null。
- payment和冻结金额/规格保留，refund=null。无新资金，不受模拟资金开关限制。

允许商家标记待取件后立即确认，不需要等待；商家暂停/停业、账号或OWNER停用、配置下架不阻止正常学生完成已取旧单。学生自身账号、角色和会话仍必须有效。

## 3. 核对历史与重复操作

学生GET本人详情及GET `/orders?orderType=PRINT&status=COMPLETED`，应看到完成单及PICKED_UP摘要；订单事件通常依次CREATE、PAYMENT、ACCEPT、START、READY_FOR_PICKUP、PICKUP，序号字符串"0"～"5"。

重复pickup应200，取件/完成时间、版本、事件数量不变；稍后重试也不延长文件保留期。切换到有效本店商家，查看本店详情、列表和事件；对合法完成单再次start或ready-for-pickup也应200当前完成详情，不回退阶段。商家complete仍仅预约，PRINT返回404。

未标待取件就pickup为409；已完成不再允许普通cancel。商家账号不能调用学生pickup代确认，其他学生访问该单为404。

## 4. 文件保留和下载

使用学生账号GET `/files/{fileId}`查看expiresAt。如果此文件只有该单，完成后设为完成时间+7天；如果还有未结束的关联订单，仍为null，等最后一单结束后按全部订单最晚终结时间+7天保留。正常学生本人在有效期内仍可下载；此后文件清理不会阻止重复pickup或合法商家旧动作重试。

切换商家调用原content下载：如果此单是本店最后一笔合格任务，完成后新请求404。仅仅文件还保留7天并不授权商家；若本店还有另一笔合法已接单、打印中或待取件关联则仍可下载。只有其他店任务不能授予本店访问权。

已有下载在授权后取件，不中断本次传输；等待文件互斥后才重新授权的请求会按最新快照判断。用户无需修改数据库或系统时间来验证这些边界，自动测试负责锁等待、时间、回滚与并发。

| 返回 | 含义 |
|---|---|
| 400 VALIDATION_FAILED | 非规范orderId或发送禁止的body，重复操作也拒绝 |
| 401/403 | 登录会话、账号或角色无效 |
| 404 RESOURCE_NOT_FOUND | 他人、BOOKING或不存在 |
| 409 ORDER_STATE_CONFLICT | 未待取件、已关闭、错配完成或订单版本无法递增 |
| 409 PRINT_TIME_INVALID | 首次取件时刻早于readyAt，无写入 |
| 503 FILE_STORAGE_UNAVAILABLE | 首次文件保护异常或必要期限写入失败，整笔回滚 |
| 503 SERVICE_TEMPORARILY_UNAVAILABLE | 数据库竞争已回滚，可重试同一请求 |

## 自动验证记录

2026-10-25：本轮去重定向 **790个用例通过**（普通/Web172、真实MySQL/HTTP618，其中新增学生取件79），零失败/错误/跳过。V1～V12迁移及mysql模式JAR健康、UTF-8、trace和鉴权检查通过；运行56、完整62/56/6。未重跑全部历史业务IT。

首轮782个通过（普通/Web172、MySQL/HTTP610，含学生取件71）；补充8个取件用例和摘要文档说明后，最终定向95个通过（Web13、取件79、OpenAPI3）。重复执行的用例不重复累加；上述790使用各套件本轮最后通过报告。

实际命令均使用独立临时MySQL及自有文件根：

```powershell
$env:MAVEN_ARGS='-Dit.test=StudentPrintPickupMysqlIT,MerchantPrintFulfillmentMysqlIT,MerchantPrintDownloadMysqlIT,MerchantPrintCancellationMysqlIT,MerchantPrintConfirmationMysqlIT,PrintOrdersMysqlIT,PrintPaymentsMysqlIT,MerchantBookingFulfillmentMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90

$env:MAVEN_ARGS='-Dtest=WebFoundationTest -Dit.test=StudentPrintPickupMysqlIT,OpenApiMysqlIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

日志：`.cache/student-pickup-mysql.log`及`.cache/student-pickup-mysql-final.log`。

| MySQL/HTTP套件 | 本轮最后通过数 |
|---|---:|
| StudentPrintPickupMysqlIT | 79 |
| MerchantBookingFulfillmentMysqlIT | 54 |
| MerchantPrintCancellationMysqlIT | 83 |
| MerchantPrintConfirmationMysqlIT | 87 |
| MerchantPrintDownloadMysqlIT | 72 |
| MerchantPrintFulfillmentMysqlIT | 79 |
| MysqlIntegrationIT | 11 |
| OpenApiMysqlIT | 3 |
| PrintOrdersMysqlIT | 79 |
| PrintPaymentsMysqlIT | 71 |

新增取件用例从实际上传/创建/付款/接单/开始/待取件进入完成，核对状态、相同完成/取件时间、版本、唯一PICKUP事件、学生/商家详情与分页及完整事件。覆盖当前角色/账号/会话/归属、无body/规范ID、商家与OWNER停用、暂停/配置/资金开关变化，未待取件/关闭/错配完成、阶段时间有序及now±1ms边界；匹配支付事实、文件保护及订单/文件版本上限验证通过。

主订单、明细、事件、文件期限写入及最终会话失效逐点回滚；同文件多单/跨店保留、其他本店有效任务继续授权而他店不能授权、重复不续期、7天到期清理后的取件及商家start/ready重放均通过。真实并发验证双取件、取件与start/ready/取消，同文件跨店取件及不同调用账号的跨店退款/取件；店铺/文件/订单行真实锁等待后确定动作时刻和返回状态。持商家账号锁和实例文件互斥时学生取件仍完成，且不调用磁盘/实例锁。

下载等待互斥后在取件完成的新RR快照拒绝，已经通过授权并打开流的下载在取件后完整完成，后续新请求404。既有打印开始/待取件、受控下载、接拒单/取消、支付/超时/引用保护、原预约履约及数据库约束回归通过。不是用mock代替真实MySQL锁验证；故障注入仅用于验证回滚，未代用户操作Swagger/Apifox客户端。

成功临时实例和测试文件根已由脚本关闭清理；没有访问用户正式MySQL数据/持久上传目录，也没有改本机VS Code凭据。两份OpenAPI、枚举/引用和唯一分组同步检查；文件根仍为单实例本地存储。管理员PRINT异常退款、评价及通知仍待后续。
