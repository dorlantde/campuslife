# 商家打印接单后取消验收：Swagger / Apifox

2026-10-26 后续管理员PRINT异常已按[ADR-0041](decisions/0041-admin-print-exception.md)接入，见[点击指南](admin-print-exception.md)。已付款未完成打印可由管理员基于原因/版本核实全额退款关闭，开始/待取件时间保留、进度ABORTED；已完成不退款，ADMIN仍无内容权限。旧阶段“管理员PRINT未开放”的描述由本次取代。

2026-10-25 后续取件已开放：[学生取件指南](student-print-pickup.md)及[ADR-0040](decisions/0040-student-print-pickup.md)使待取件打印单可由本人完成。完成后合法start/ready重试200且不改事实，完成单不再授权商家下载；全部引用终结后保留7天。下方旧阶段取件未开放或完成后拒绝重试的说明由本次取代。

2026-10-25 后续扩展：[打印开始与待取件](merchant-print-fulfillment.md)已按ADR-0039开放；商家合法IN_SERVICE/PRINTING与READY_FOR_PICKUP任务现可受控下载。旧“PRINT开始404/履约中不授权”的阶段描述以本次为准；学生取件、评价及管理员PRINT异常仍后续开发。

2026-10-25 后续扩展：[商家PDF受控下载](merchant-print-download.md)已按ADR-0038开放，仅凭本店合格已接单未开始任务授权。旧“商家内容未开放/一律403”阶段说明由本次扩展替代；上传/本人元数据仍仅学生，打印中授权随履约后续推进。

按[ADR-0037](decisions/0037-merchant-print-cancellation.md)及[确认方案](merchant-print-cancellation-design.md)实现。重启原VS Code **CampusLife (MySQL)** 即可，扩展原cancel入口，运行仍54个操作，无新迁移或数据库重建；本轮未修改本机凭据。

## 1. 准备已接单但未开始的打印单

打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，或在Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，服务地址`http://127.0.0.1:8080`。完整规划仍62个操作、8个待实现。

按[商家打印确认指南](merchant-print-confirmation.md)操作：学生上传本人PDF、报价、创建并付款，商家在30分钟期限前接单。记录本次真实orderId、fileId，GET详情确认ACCEPTED / PAID / QUEUED，acceptedAt有值、startedAt及printing.readyAt/pickedUpAt为null。

已有上轮验收留下的合法ACCEPTED打印单，也可直接用于本次取消。无需重新付款；即使原30分钟接单截止已过，仍按已接单未开始的状态判断。会话过期时重新登录，从data.accessToken取token填Authorize，只填token本身。

## 2. 商家说明原因取消

使用本店商家token，在“07 商家订单履约”选择POST `/api/v1/merchant/orders/{orderId}/cancel`，填写实际ID和JSON：

```json
{"reason":"接单后设备故障，无法完成本次打印"}
```

reason必填字符串，去首尾空白后1～300 Unicode字符，不填其他字段、客户端金额或expectedVersion。新增退款沿用显式开启的模拟资金开关；默认配置关闭，本轮未改启动配置。

预期200 OrderDetail：

1. status=CLOSED、paymentStatus=REFUNDED、printing.progress=ABORTED。
2. closeReason=MERCHANT_UNAVAILABLE，closeNote是归一化原因，closedAt有值，版本增加1。
3. 原payment保持，refund是全额成功退款，amount与原支付一致；acceptedAt和冻结文件名/规格/金额不变，不生成开始或取件时间。
4. GET本店事件：CREATE、PAYMENT、ACCEPT、MERCHANT_UNAVAILABLE，通常eventSeq为"0"至"3"；最后一条fromStatus=ACCEPTED、toStatus=CLOSED、actorType=MERCHANT、actorId为本次商家ID，reason与closeNote一致。不会额外补一条REFUND事件。
5. GET本店列表，orderType=PRINT、status=CLOSED：可见真实关闭摘要，printingProgress=ABORTED，原应付金额保留。

店铺暂停、停业、营业结束或服务下架仍可处理合法旧单；商家账号与OWNER须有效。目标学生停用不阻止正常商家承担退款责任。

## 3. 重复操作与学生核对

用相同原因再次Execute：仍200，同一refundNo、closedAt、version和事件数，不延长文件期限；原因前后空白去除后相同也算同原因。改成另一原因再请求：409 ORDER_STATE_CONFLICT，不覆盖原责任。

切回学生token，GET本人详情及事件可核对同一退款和商家原因。本人列表支持PRINT/CLOSED筛选。已商家取消的订单再次付款、接单或用学生cancel入口改成本人取消，均409，不恢复订单；同创建键重放仍返回这张订单当前详情。

学生在商家接单后仍不能直接取消；无法履约普通取消仅由本店商家在开始前执行。商家打印下载、开始和待取件现已开放，开始后普通取消409；学生取件和管理员PRINT异常尚未开放，complete仍仅用于预约。

## 4. 文件保留验证

若该文件仅关联本次订单，学生GET `/api/v1/files/{fileId}`：expiresAt应等于实际closedAt+7天，取消不会立即删除PDF。有效保留期内仍按[本人下载指南](file-download.md)受控下载，不续期。

若同文件还有任何其他未终结打印单（同店或跨店、未付/已付/已接单均算），expiresAt继续null。需要关闭所有引用后，才按最后实际终结时间+7天设置；不能只看一笔订单关闭就推断文件可清理。

保留期结束并被清理后，冻结订单/退款/事件仍可读。商家同原因合法重放不会使已过期文件复活或重新续期。

## 5. 边界和错误

| 返回 | 含义 |
|---|---|
| 400 VALIDATION_FAILED | orderId或reason格式、长度、类型/未知字段不符 |
| 401/403 | 会话无效、账号/角色/OWNER无效；核对本店商家身份 |
| 404 RESOURCE_NOT_FOUND | 不存在、跨店或无所属店铺 |
| 409 ORDER_STATE_CONFLICT | 未付款、待接单、其他关闭原因、已开始/完成，或同取消改原因 |
| 409 CANCEL_WINDOW_CLOSED | 仅BOOKING的预约开始窗口已过；PRINT没有该预约时间限制 |
| 503 MOCK_PAYMENT_DISABLED | 新退款未启用，原订单仍保持已接单及文件保护 |
| 503 FILE_STORAGE_UNAVAILABLE | 文件保护元数据或必要期限写入异常，整笔回滚 |
| 503 SERVICE_TEMPORARILY_UNAVAILABLE | 数据库竞争已回滚，稍后重试相同orderId及原因 |

待接单应使用reject，或由学生取消/扫描处理；即使已到30分钟，调用本cancel入口仍409且不自动退款。资金开关关闭时新取消503，恢复后再试；已有同原因成功取消在开关关闭时仍可重放。

## 自动验证记录

2026-10-25：最终定向 **607个用例通过**（普通/Web172、真实MySQL/HTTP435，其中新增打印取消83），零失败/错误/跳过。V1～V12迁移及mysql模式JAR健康、UTF-8、trace和鉴权检查通过；运行仍54、完整62/54/8，未重跑全部历史业务IT。

实际执行：

```powershell
$env:MAVEN_ARGS='-Dit.test=MerchantPrintCancellationMysqlIT,MerchantPrintConfirmationMysqlIT,MerchantBookingCancellationMysqlIT,PrintPaymentsMysqlIT,PrintOrdersMysqlIT,OrderEventsMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

最终日志`.cache/merchant-print-cancel-mysql-final.log`。

| 套件 | 通过数 |
|---|---:|
| MerchantPrintCancellationMysqlIT | 83 |
| MerchantPrintConfirmationMysqlIT | 87 |
| MerchantBookingCancellationMysqlIT | 47 |
| PrintPaymentsMysqlIT | 71 |
| PrintOrdersMysqlIT | 79 |
| OrderEventsMysqlIT | 54 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |

新增用例实际走PDF上传、创建、付款、商家接单与取消，检查学生/商家详情、列表和事件、唯一全额退款/原因及ACCEPTED来源事件、原acceptedAt及冻结明细、同原因/异原因重试、长期关闭且文件已删除后的合法重放。覆盖角色/会话/OWNER/归属、严格ID和Unicode原因、暂停/停业/配置/学生停用/磁盘缺失、已过原接单截止、非法主状态/打印进度/时间字段、文件保护异常、资金开关及恢复、50亿分、资金/状态/进度/事件/期限与最终会话逐点失败回滚、订单/文件版本边界。

真实MySQL并发验证同/异原因双取消、取消与接单重放/学生取消/扫描/新引用/其他订单关闭，以及跨店全部引用保护。真实店铺/文件/订单锁等待后固定closedAt；门闩安排另一受文件锁协调的合成打印开始状态提交，确认定位旧ACCEPTED不能绕过锁后进度；该测试只证明取消边界，不代表开始打印接口开放。持有目标学生账号锁及实例文件互斥仍可取消，方法探针确认没有磁盘读取。原预约取消窗口、打印接拒单、付款/学生取消/历史、事件及接口导出回归通过。

测试使用独立临时MySQL和自有文件根，由脚本结束并清理，不访问用户正式服务数据或持久上传目录；未修改本机VS Code凭据。Swagger/Apifox流程供用户复现，未代用户操作客户端。商家内容读取及正常打印履约仍待后续任务。
