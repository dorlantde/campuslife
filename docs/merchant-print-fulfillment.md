# 打印开始与待取件验收：Swagger / Apifox

2026-10-26 后续管理员PRINT异常已按[ADR-0041](decisions/0041-admin-print-exception.md)接入，见[点击指南](admin-print-exception.md)。已付款未完成打印可由管理员基于原因/版本核实全额退款关闭，开始/待取件时间保留、进度ABORTED；已完成不退款，ADMIN仍无内容权限。旧阶段“管理员PRINT未开放”的描述由本次取代。

2026-10-25 后续取件已开放：[学生取件指南](student-print-pickup.md)及[ADR-0040](decisions/0040-student-print-pickup.md)使待取件打印单可由本人完成。完成后合法start/ready重试200且不改事实，完成单不再授权商家下载；全部引用终结后保留7天。下方旧阶段取件未开放或完成后拒绝重试的说明由本次取代。

按[ADR-0039](decisions/0039-merchant-print-fulfillment.md)及[确认方案](merchant-print-fulfillment-design.md)实现。重启原VS Code **CampusLife (MySQL)** 配置，无新迁移或数据库重建，本轮未改启动凭据。运行55个操作，完整62/已实现55/待开发7。

## 1. 准备已接单任务

打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，或在Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，地址`http://127.0.0.1:8080`。本轮新增ready-for-pickup路径，start沿原路径扩展。

按[商家打印确认](merchant-print-confirmation.md)完成学生上传/报价/创建/付款、商家接单。记录实际orderId、printing.fileId，初始ACCEPTED / PAID / QUEUED、acceptedAt有值、startedAt与readyAt为空。使用本店商家登录返回的data.accessToken填Authorize，只填token本身。

先按[商家受控下载](merchant-print-download.md)确认原PDF；如果无法履约且尚未开始，可按[商家取消](merchant-print-cancellation.md)退款。开始后普通取消会被拒绝。

## 2. 开始打印

在“07 商家订单履约”调用POST `/api/v1/merchant/orders/{orderId}/start`，填实际ID，**无请求体**；不发送空对象、JSON null、时间或金额。

预期200：status=IN_SERVICE、paymentStatus=PAID、printing.progress=PRINTING，startedAt有值、版本+1；acceptedAt、原payment、金额和冻结规格保持，refund=null。readyAt/pickedUpAt/completedAt仍null。事件只新增一次START，fromStatus=ACCEPTED、toStatus=IN_SERVICE、actorType=MERCHANT及本次账号ID。

重复同请求仍200，同一startedAt/版本/事件。此时再用同fileId下载应仍200；学生本人详情和双方列表可见IN_SERVICE/PRINTING，文件expiresAt仍null。

没有预约开始时间限制，原30分钟接单期限经过不阻止已接任务；正常商家/有效OWNER在店铺暂停、停业、下架后仍可履行旧单。两个动作都不生成资金，资金开关关闭仍可执行。

## 3. 标记待取件

调用新增POST `/api/v1/merchant/orders/{orderId}/ready-for-pickup`，同样无body。必须先开始，不能从ACCEPTED跳过打印。允许开始后立即操作，不设置最短打印时长。

预期200：主状态仍IN_SERVICE、paymentStatus=PAID，printing.progress=READY_FOR_PICKUP、printing.readyAt有值，版本再+1。startedAt不改，completedAt/pickedUpAt仍null；只新增一次READY_FOR_PICKUP事件，fromStatus/toStatus均IN_SERVICE，不另生成COMPLETE或资金事件。

重复待取件保留原readyAt/版本/事件；已经待取件时再点击start仍200当前详情，不改回PRINTING。通常完整事件顺序CREATE/PAYMENT/ACCEPT/START/READY_FOR_PICKUP，序号"0"至"4"。

商家下载继续可用，学生/商家详情、列表和事件反映真实待取件。文件继续expiresAt=null，尚未开始7天保留倒计时；待取件不是订单完成。

## 4. 边界与后续

商家与学生的普通cancel在打印开始后均409 ORDER_STATE_CONFLICT；商家complete对PRINT仍404，只用于原预约。学生可按[取件指南](student-print-pickup.md)确认完成；不确认则仍保持服务中和文件保护，不自动完成。管理员可按[打印异常指南](admin-print-exception.md)核实未完成旧单，全额模拟退款关闭。

不要求用户修改系统时钟或直接构造数据库状态。自动测试会检查锁等待、时间相等/倒退、并发和回滚；接口记录商家声明的阶段，并不会驱动真实打印机。

| 返回 | 含义 |
|---|---|
| 400 VALIDATION_FAILED | orderId格式错误或发送了禁止的body |
| 401/403 | 会话/账号/角色/OWNER无效 |
| 404 RESOURCE_NOT_FOUND | 不存在、跨店、无所属店铺，或BOOKING调用打印待取件 |
| 409 ORDER_STATE_CONFLICT | 未付、待接单、未开始就标待取件、关闭、进度错配或非法完成组合 |
| 409 PRINT_TIME_INVALID | 打印动作时钟早于前序动作，未写入 |
| 409 BOOKING_TIME_INVALID | 仅原BOOKING开始的预约时间窗口规则 |
| 503 FILE_STORAGE_UNAVAILABLE | 首动作文件保护元数据异常，整笔回滚 |
| 503 SERVICE_TEMPORARILY_UNAVAILABLE | 数据库竞争回滚，稍后重试同一动作 |

## 自动验证记录

2026-10-25：最终定向 **687个用例通过**（普通/Web172、真实MySQL/HTTP515，其中新增打印履约79），零失败/错误/跳过。V1～V12迁移及mysql模式JAR健康、UTF-8、trace和鉴权检查通过；运行55、完整62/55/7，未重跑全部历史业务IT。

实际执行（独立临时MySQL及测试文件根）：

```powershell
$env:MAVEN_ARGS='-Dit.test=MerchantPrintFulfillmentMysqlIT,MerchantBookingFulfillmentMysqlIT,MerchantBookingCancellationMysqlIT,MerchantPrintCancellationMysqlIT,MerchantPrintDownloadMysqlIT,PrintOrdersMysqlIT,MerchantPrintConfirmationMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

最终日志`.cache/merchant-print-fulfillment-mysql-final.log`。

| 套件 | 通过数 |
|---|---:|
| MerchantPrintFulfillmentMysqlIT | 79 |
| MerchantBookingFulfillmentMysqlIT | 54 |
| MerchantBookingCancellationMysqlIT | 47 |
| MerchantPrintCancellationMysqlIT | 83 |
| MerchantPrintDownloadMysqlIT | 72 |
| PrintOrdersMysqlIT | 79 |
| MerchantPrintConfirmationMysqlIT | 87 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |

新增用例实际走上传、创建、付款、商家接单、开始与待取件，验证主状态/进度/版本/时间/事件及学生与商家历史摘要、各阶段原PDF下载；允许同毫秒立即待取件，时钟早于前序动作拒绝，合法start/ready重试不覆盖时间或回退进度。身份/会话/OWNER/归属、严格ID/无body、暂停/停业/配置失效/学生停用、原期限经过、资金关闭、非法组合与BOOKING待取件404均覆盖。文件保护异常、订单/文件版本边界和主订单/明细/事件/最终会话逐点失败均验证原子回滚。

真实MySQL请求并发验证双开始/双待取件、开始与取消、开始与待取件及阶段重放；真实店铺/文件/订单锁等待后读取动作时刻。持有实例文件互斥及目标学生账号锁时两个元数据动作仍能完成且不调用磁盘/互斥方法；下载等待互斥期间可完成开始和待取件，新的RR复核仍按有效任务授权。待取件推进测试Clock8天、重新登录及扫描/清理后仍不自动完成、退款或变更文件NULL保护。原预约start/complete窗口、取消，以及打印接拒单/取消、创建和商家下载回归通过。

测试使用独立临时MySQL和自有文件根，成功实例由脚本结束并清理；不访问用户正式服务数据或持久上传目录，未修改本机VS Code凭据。两份OpenAPI同步55/62/7、9个唯一分组，实际模型及引用检查完成。Swagger/Apifox流程供用户复现，未代用户操作客户端。学生取件、评价及管理员PRINT异常仍待后续。
