# 打印模拟支付与退款验收：Swagger / Apifox

2026-10-25 后续扩展：[商家打印接单后取消](merchant-print-cancellation.md)已按ADR-0037开放，原“PRINT取消404/接单后无终结路径”的阶段说明已替代。学生接单后仍不能直接取消；商家内容读取及打印正常履约继续后续开发。

2026-10-24 后续扩展：[商家打印确认](merchant-print-confirmation.md)已开放本店PRINT列表/详情/事件及接拒单；本页旧阶段中的“商家PRINT未开放/空页”已由ADR-0036替代。打印内容访问、接单后取消及打印后续履约仍待开发。

按[ADR-0035](decisions/0035-print-mock-payment.md)及[确认方案](print-payment-design.md)接入。重启原VS Code **CampusLife (MySQL)** 配置即可，无新迁移、不需要重建数据库或填写root。运行仍54个操作，打印付款复用既有支付入口；商家打印接单与内容访问留到后续任务。

## 1. 准备一个学生打印订单

打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，或在Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，服务地址`http://127.0.0.1:8080`。完整规划版仍保留8个未实现操作。

按[打印订单指南](print-orders.md)用虚构PRINT商家配置当前上海营业时段、上架服务/启用价目，学生上传本人PDF、报价后创建。记录实际orderId，开始时应PENDING_PAYMENT / NOT_PAID / QUEUED。

学生登录后，将data.accessToken填入Authorize，只填token本身。首次付款须在创建后15分钟内完成，等于截止已过期；不要复制指南的示例ID代替本次真实ID。

新资金需现有MOCK_PAYMENT_ENABLED=true，本机开发启动项此前已显式开启；本轮未修改启动配置或密钥。若503 MOCK_PAYMENT_DISABLED，检查所选启动配置中是否显式开启，再重启。公开/默认配置保持关闭。关闭该开关也会暂停新增退款，应保留到期订单等待恢复，不会假称退款成功。

## 2. 付款与重复请求

1. 在“05 学生订单 → POST /api/v1/orders/{orderId}/payments/mock”点Try it out，填写实际orderId。**无请求体，不填金额或Idempotency-Key。** 不发送JSON null、空对象或其他业务内容。
2. Execute预期200 PaymentResult：orderId对应本单、orderStatus=PENDING_ACCEPTANCE、paymentStatus=PAID，具有paymentNo、冻结amount、paidAt和acceptanceDeadline。
3. acceptanceDeadline固定为paidAt+30分钟。打印进度仍QUEUED，付款不是开始打印或商家已经接单。
4. 再Execute同一支付请求：仍200，同一paymentNo/amount/paidAt/acceptanceDeadline，不重复写流水、事件或延长期限。
5. GET `/api/v1/orders/{orderId}`：payment为SUCCEEDED事实，refund=null，status=PENDING_ACCEPTANCE、paymentStatus=PAID、printing.progress=QUEUED，版本比创建增加1。
6. GET `/api/v1/orders?orderType=PRINT&status=PENDING_ACCEPTANCE`查看本人待接单摘要；GET `/api/v1/orders/{orderId}/events`应CREATE序号0、PAYMENT序号1，ID/序号仍字符串。
7. GET `/api/v1/files/{fileId}`：expiresAt仍null，整个未终结期间文件受保护；付款不会重置为24小时或开始7天倒计时。

例如两页PDF、2份、黑白双面每面20分，按冻结金额付款80分。即使之后价目改为每面30分，原订单仍付80分；新订单须重新报价。下单后手动停业、营业结束、服务/价目停用也不阻止合法旧单付款；平台暂停店铺、店主失效或经营账号停用则首次付款409 SHOP_UNAVAILABLE，仍可取消或超时关闭。

付款只复核本人AVAILABLE及NULL保护元数据，不重新打开PDF；不通过手动删除持久目录验证此项，磁盘异常由下载或后续履约读取发现。

## 3. 待接单取消及文件保留

1. 接单期限前调用POST `/api/v1/orders/{orderId}/cancel`，无请求体。
2. 预期200：CLOSED / REFUNDED / ABORTED、closeReason=USER_CANCEL；payment保留原SUCCEEDED，refund新增SUCCEEDED，退款amount与原支付amount相等。
3. GET事件应仅新增一次USER_CANCEL关闭事件，eventSeq="2"；操作者STUDENT及本人ID。不会再人为补一条REFUND事件。
4. 再次取消仍200，同一refundNo/closedAt/version；同创建键重放仍返回这张关闭订单。再次付款409 ORDER_STATE_CONFLICT，不重新付款。
5. 若同文件仅关联这一单，expiresAt=实际closedAt+7天；若还有其他未终结单（未付或已付、同店或跨店均算），expiresAt继续null。最后一笔终结后才开始7天。

下载按[文件指南](file-download.md)继续受控、不续期；超过保留期限后410，清理随后回收。历史订单仍保留冻结名称/规格及成功支付/退款事实。其他学生订单统一404，商家/管理员不能调用学生支付/取消。

## 4. 超时与开关行为

另建一单并付款，等待支付后的30分钟，再等待扫描提交（任务默认每30秒，处理耗时或失败会延后）。GET本身不触发退款：扫描提交后变为CLOSED / REFUNDED / ABORTED、closeReason=ACCEPTANCE_TIMEOUT，事件SYSTEM且actorId=null，具有一次全额退款。

若已达到期限但扫描尚未提交，点击取消会先提交退款关闭，再409 ACCEPTANCE_DEADLINE_PASSED；重新GET可见已关闭。重复取消仍同样409，不改成USER_CANCEL，也不延长7天。已有成功付款在超时退款尚未提交时重复支付仍仅返回原事实，不重新计30分钟。

开关关闭时新付款和待接单退款503 MOCK_PAYMENT_DISABLED。已有合法支付/退款重放可用；未支付取消和15分钟超时继续。已付到期保持PAID及文件保护，恢复true并重启后扫描按原截止补退款，实际关闭后才计7天。不要求人工调整系统或数据库时间验收。

## 5. 错误对照

| 返回 | 含义与处理 |
|---|---|
| 400 VALIDATION_FAILED | 检查规范orderId，付款不得携带业务请求体 |
| 401/403 | 当前会话无效、账号停用或角色不符，重新核对学生登录 |
| 404 RESOURCE_NOT_FOUND | 不存在或非本人订单 |
| 409 SHOP_UNAVAILABLE | 首次付款时经营资格已失效，不能用营业开关代替审核/成员资格 |
| 409 PAYMENT_DEADLINE_PASSED | 未付订单已超时关闭，查询结果，不重新付款 |
| 409 ACCEPTANCE_DEADLINE_PASSED | 待接单超时已退款关闭，查询原退款事实 |
| 409 ORDER_STATE_CONFLICT | 已退款/取消或当前状态不允许操作 |
| 503 MOCK_PAYMENT_DISABLED | 新模拟资金未启用，保留原订单待恢复 |
| 503 FILE_STORAGE_UNAVAILABLE | 打印文件保护元数据异常或必要文件期限写入失败，事务回滚 |
| 503 SERVICE_TEMPORARILY_UNAVAILABLE | 数据库竞争回滚，保留原orderId重试，不创建第二单 |

商家PRINT列表/详情/事件、接单/拒单、文件内容读取及管理员打印异常本轮仍未开放。现在可以完成付款及退款验证，正常商家打印履约下一项单独推进。

## 自动验证记录

2026-10-24：最终定向 **586个用例通过**（普通/Web172、真实MySQL/HTTP414，含新增打印支付71），零失败/错误/跳过；V1～V12迁移及mysql模式JAR健康、UTF-8、trace和鉴权启动检查通过。运行仍54个操作，完整62/54/8，未运行全部历史业务IT。

实际执行：

```powershell
$env:MAVEN_ARGS='-Dit.test=PrintPaymentsMysqlIT,PrintOrdersMysqlIT,BookingPaymentsMysqlIT,StudentOrderListMysqlIT,OrderEventsMysqlIT,FileDownloadMysqlIT,FileUploadMysqlIT,MockPaymentConfigurationMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

最终日志`.cache/print-payment-mysql-final.log`。首次定向检查在学生取消与系统退款竞争中发现响应混读：事务内普通selectById缓存了定位旧状态，后台提交后即使RC也可能复用该缓存。已改为写返回与创建重放使用锁后主订单/明细查询，补充真实MySQL门闩精确控制的两条确定时序用例；随后本轮相关回归全部通过，不以忽略500或放宽断言处理。

| 套件 | 通过数 |
|---|---:|
| PrintPaymentsMysqlIT | 71 |
| PrintOrdersMysqlIT | 79 |
| BookingPaymentsMysqlIT | 40 |
| StudentOrderListMysqlIT | 43 |
| OrderEventsMysqlIT | 54 |
| FileDownloadMysqlIT | 48 |
| FileUploadMysqlIT | 64 |
| MockPaymentConfigurationMysqlIT | 1 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |

新用例验证实际上传/冻结付款/退款历史、50亿分、权限/会话、无请求体、付款和接单截止前/等于/后、真实店铺/文件/订单锁等待跨界、固定paidAt、会话最终失效回滚、资金/主状态/进度/事件/期限逐步故障回滚、版本上限、付款/取消/扫描竞争、跨店系统退款、退款与新引用竞争、独立100+100批次、资金关闭及恢复、新JAR进程已付超时恢复。付款/退款在人工持有实例文件互斥时仍能完成且不调用磁盘方法；旧预约支付与打印创建、本人历史、事件、文件上传/下载回归通过。

自动测试使用独立临时MySQL和自有文件根，成功实例及失败诊断实例均在进程结束后清理；未访问用户正式服务数据或`.local/files`，未修改VS Code凭据。Swagger/Apifox流程供用户自行点击，未代用户操作客户端。商家打印接单和内容授权仍待后续任务。

