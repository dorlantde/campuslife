# 管理员打印异常验收：Swagger / Apifox

2026-10-26 后续评价已按[ADR-0042](decisions/0042-student-order-review.md)接入，见[学生评价指南](student-order-review.md)。合法完成单可本人一单一评，详情reviewed反映真实记录（含隐藏），不改订单版本/状态事件/资源；旧阶段固定false或学生评价未开放由此取代，公开评价/统计后续。

按[ADR-0041](decisions/0041-admin-print-exception.md)及[确认方案](admin-print-exception-design.md)扩展原管理员两个入口。重启原VS Code **CampusLife (MySQL)** 配置；没有新增迁移或默认管理员，原本机启动凭据不用修改。运行仍56，完整规划62/已实现56/待开发6。

## 1. 准备打印单和管理员

打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，或在Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，基础地址使用实际本机端口。浏览器运行文档及运行快照只展示已经实现能力。

按[打印支付](print-payments.md)、[商家确认](merchant-print-confirmation.md)及[开始/待取件](merchant-print-fulfillment.md)分别准备已付待接单、已接单、打印中或待取件订单，记录实际orderId及printing.fileId。这是四种独立验收场景，不要对同一已关闭订单继续尝试下一阶段。

用之前显式初始化的ADMIN业务账号登录，把data.accessToken填Authorize，只填token本身。尚无管理员时沿[管理员初始化指南](admin-shop-review.md)准备，不使用MySQL数据库账号登录，也没有公开管理员注册或默认密码。

首次退款须已显式开启模拟资金。合法重复不新增资金，开关关闭时仍可返回原事实；不要为验收修改数据库或发送客户端金额。

## 2. 管理员读取并核实

在“08 管理员”调用GET `/api/v1/admin/orders/{orderId}`，应返回PRINT冻结明细、实际进度和已有支付事实。保存返回的**version字符串**；订单可以来自任意店铺。目标学生/商家/OWNER停用、店铺暂停、停业或配置变化不妨碍核实。

然后调用原POST `/api/v1/admin/orders/{orderId}/close-exception`：

```json
{
  "reason": "已向双方核实，打印任务无法继续，按V1全额模拟退款",
  "expectedVersion": "填写刚读取的实际版本字符串"
}
```

将示例expectedVersion替换为真实数字字符串，例如实际返回"4"就填"4"。不要增添amount、状态、时间或管理员ID。reason去首尾空白后1～300个Unicode码点，未知字段/错误类型/缺失均拒绝。

## 3. 核对结果和历史

预期200，status=CLOSED、paymentStatus=REFUNDED、printing.progress=ABORTED，closeReason=ADMIN_EXCEPTION、closeNote为归一化原因，closedAt有值，版本+1。refund为一笔成功全额退款，金额等于原冻结payment.amount；保留原支付及已发生的acceptedAt/startedAt/printing.readyAt。completedAt与printing.pickedUpAt仍null。

分别切换学生和商家账号查看本人/本店详情、列表及事件。仅新增一条ADMIN_EXCEPTION事件，fromStatus为核实前的PENDING_ACCEPTANCE、ACCEPTED或IN_SERVICE，toStatus=CLOSED，actorType=ADMIN、actorId为实际管理员，reason为核实原因。不会另造取件、完成或一条独立REFUND事件。

切回同一管理员，保持原reason与**首次提交的expectedVersion**再次提交，应200原关闭结果，时间、版本、事件、退款数量和文件保留期不变。不要把重试版本改成关闭后的新版本。另一管理员、改原因或改版本重试为409。

若首次返回409 CONFIG_VERSION_CONFLICT，说明读取后订单已变化，重新GET并核实实际情况后再决定是否提交。不要自动把版本替换成新值：若学生已确认取件完成，本入口不能退款；若已被扫描/其他取消关闭，本入口不能改写关闭原因。

## 4. 文件与内容权限

学生正常账号可GET本人文件元数据。此文件仍有未结束关联订单，expiresAt保持null；全部关联结束后，按全部订单最晚终结时间保留7天。同一管理员重复核实不续期。历史PDF清理后，管理员详情与合法退款重放仍可读取原业务事实。

关闭单不再授予商家PDF下载权限；若是本店最后一个合格关联，新content请求404；本店还有另一合法已接单、打印中或待取件任务仍可下载。只有其他店关联或7天保留本身不授权。管理员调用content仍403，本次未授予内容权限。

已获准传输允许完成，之后请求重新判断；退款不等待下载。实际磁盘PDF丢失或不可读不阻止核实退款，接口只核对受保护元数据。文件元数据或必要保留期写入失败则整笔回滚，不能出现部分成功。

| 返回 | 含义 |
|---|---|
| 400 VALIDATION_FAILED | ID、原因、版本字符串、JSON类型或字段不符合要求 |
| 401/403 | 管理员账号/角色/会话无效，重复也需有效身份 |
| 404 RESOURCE_NOT_FOUND | 不存在、不支持类型或不完整的打印资源 |
| 409 CONFIG_VERSION_CONFLICT | 非关闭订单版本已变化，须重新核实 |
| 409 ORDER_STATE_CONFLICT | 未付、已完成、非法阶段、版本无法递增或关闭重试不匹配 |
| 409 PRINT_TIME_INVALID | 首次打印关闭时钟早于付款或已发生阶段，无写入 |
| 503 MOCK_PAYMENT_DISABLED | 新退款未显式启用，整笔无写入 |
| 503 FILE_STORAGE_UNAVAILABLE | 保护元数据或必要期限写入失败，整笔回滚 |
| 503 SERVICE_TEMPORARILY_UNAVAILABLE | 数据库竞争回滚，可重试原核实请求 |

自动测试负责时间边界、锁等待、故障回滚和竞争；人工验收无需手写PowerShell HTTP请求、修改时钟或数据库状态。原BOOKING异常仍见[预约异常指南](admin-booking-exception.md)。

## 自动验证记录

2026-10-26：本轮各套件最终报告去重 **814个定向用例通过**（普通/Web172、真实MySQL/HTTP642，新增管理员打印异常108）；最终报告零失败/错误/跳过。V1～V12迁移与mysql模式JAR健康、UTF-8、trace及鉴权启动检查通过，运行56、完整62/56/6。未重跑全部历史业务IT。

首轮新增套件出现一次目标账号锁等待超时（其余107项通过）；将测试持有锁限定为两个目标账号的独立主键等值点锁，仍同时持有学生、商家和实例文件互斥，并强化竞争用例必须一个成功/一个冲突。业务代码未因该超时改变。最终定向复验124个通过（Web13、管理员PRINT108、OpenAPI3）；此前其他回归套件通过，重复执行不重复累加。

实际执行，均使用独立临时MySQL及自有文件根：

```powershell
$env:MAVEN_ARGS='-Dit.test=AdminPrintExceptionMysqlIT,AdminBookingExceptionMysqlIT,StudentPrintPickupMysqlIT,MerchantPrintFulfillmentMysqlIT,MerchantPrintCancellationMysqlIT,MerchantPrintDownloadMysqlIT,PrintPaymentsMysqlIT,PrintOrdersMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90

$env:MAVEN_ARGS='-Dtest=WebFoundationTest -Dit.test=AdminPrintExceptionMysqlIT,OpenApiMysqlIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

日志：`.cache/admin-print-mysql.log`保留首次超时诊断，`.cache/admin-print-mysql-final.log`为最终复验通过及JAR启动记录。

| MySQL/HTTP套件 | 本轮最终通过数 |
|---|---:|
| AdminPrintExceptionMysqlIT | 108 |
| AdminBookingExceptionMysqlIT | 57 |
| StudentPrintPickupMysqlIT | 79 |
| MerchantPrintFulfillmentMysqlIT | 79 |
| MerchantPrintCancellationMysqlIT | 83 |
| MerchantPrintDownloadMysqlIT | 72 |
| PrintPaymentsMysqlIT | 71 |
| PrintOrdersMysqlIT | 79 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |

新增用例实际走上传/创建/付款/接单/打印/待取件及管理员核实四个阶段，验证CLOSED/REFUNDED/ABORTED、原阶段时间、冻结金额、唯一退款与实际ADMIN/原因/真实fromStatus事件，以及全部状态只读详情。500页×100份×100000分的实际50亿分订单完整付款/退款不截断；严格JSON/Unicode原因/版本/ID，权限/账号/会话/目标停用、时间边界/旧版本、资金开关和首次保护异常均覆盖。

逐点注入退款、主订单、进度、事件、文件期限及最终会话异常核对整笔回滚。合法原请求重放不改时间/版本/资金/保留期；其他管理员/原因/版本冲突，损坏的退款金额/原因/说明/时间、事件时间或进度不伪装成功。PDF物理缺失仍可退款，7天清理后的历史与原管理员成功重放仍可用。

真实MySQL验证双管理员/同管理员、管理员与开始/待取件/取件/普通退款/超时扫描竞争，以及不同管理员跨店关闭同一文件引用。店铺/文件/订单行锁等待后确定时刻、版本和锁后返回；持两个目标账号主键锁及实例文件互斥时退款仍完成且无磁盘/实例锁调用。多单同店/跨店引用持续保护、最后终结时间+7天和文件版本无需写入的上限边界通过。

下载等待互斥后新RR见关闭则404，已获准并打开流的传输在退款后完整结束，后续新请求404；另一笔本店合格引用继续授权，他店引用不授权，ADMIN内容始终403。既有预约管理员异常、打印取件/履约/取消/下载/支付/创建与数据库约束回归通过；并非用mock代替真实MySQL锁验证。

本轮失败诊断临时实例停止后清理，最终成功实例由脚本停止清理；不访问用户正式MySQL数据或持久上传根，也未改本机VS Code凭据。接口注解、两份OpenAPI、唯一分组、模型引用及文档链接同步检查。Swagger/Apifox点击流程供用户复现，未计入已执行客户端人工操作；管理员订单列表、评价、部分退款和已完成售后后续推进。
