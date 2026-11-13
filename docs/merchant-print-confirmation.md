# 商家打印确认验收：Swagger / Apifox

2026-10-26 后续管理员PRINT异常已按[ADR-0041](decisions/0041-admin-print-exception.md)接入，见[点击指南](admin-print-exception.md)。已付款未完成打印可由管理员基于原因/版本核实全额退款关闭，开始/待取件时间保留、进度ABORTED；已完成不退款，ADMIN仍无内容权限。旧阶段“管理员PRINT未开放”的描述由本次取代。

2026-10-25 后续取件已开放：[学生取件指南](student-print-pickup.md)及[ADR-0040](decisions/0040-student-print-pickup.md)使待取件打印单可由本人完成。完成后合法start/ready重试200且不改事实，完成单不再授权商家下载；全部引用终结后保留7天。下方旧阶段取件未开放或完成后拒绝重试的说明由本次取代。

2026-10-25 后续扩展：[打印开始与待取件](merchant-print-fulfillment.md)已按ADR-0039开放；商家合法IN_SERVICE/PRINTING与READY_FOR_PICKUP任务现可受控下载。旧“PRINT开始404/履约中不授权”的阶段描述以本次为准；学生取件、评价及管理员PRINT异常仍后续开发。

2026-10-25 后续扩展：[商家PDF受控下载](merchant-print-download.md)已按ADR-0038开放，仅凭本店合格已接单未开始任务授权。旧“商家内容未开放/一律403”阶段说明由本次扩展替代；上传/本人元数据仍仅学生，打印中授权随履约后续推进。

2026-10-25 后续扩展：[商家打印接单后取消](merchant-print-cancellation.md)已按ADR-0037开放，原“PRINT取消404/接单后无终结路径”的阶段说明已替代。学生接单后仍不能直接取消；商家内容读取及打印正常履约继续后续开发。

按[ADR-0036](decisions/0036-merchant-print-confirmation.md)及[确认方案](merchant-print-confirmation-design.md)实现。重启原VS Code **CampusLife (MySQL)** 配置即可，无新迁移、不必重建数据库或填写root；本轮未修改本机启动配置或凭据。运行仍54个操作，扩展原商家五个入口。

## 1. 准备两笔已付款打印订单

打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，或在Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，服务地址`http://127.0.0.1:8080`。完整规划仍62个操作、8个待实现，不因这次扩展增加路径。

按[打印订单指南](print-orders.md)准备获批PRINT商家、当前上海营业时段、上架服务及启用价目；学生上传本人PDF、报价并创建两单，分别记录实际orderId。可共用文件，创建时使用不同Idempotency-Key。按[打印支付指南](print-payments.md)在15分钟内分别付款，确认PENDING_ACCEPTANCE / PAID / QUEUED，接单截止为paidAt+30分钟。

创建与付款时用学生token；下面切换为该店商家。登录接口返回的data.accessToken填入Authorize，只填token本身。新增退款沿用显式启用的模拟资金开关；默认公开配置保持关闭。

## 2. 商家查本店列表、详情和事件

在“07 商家订单履约”按以下流程操作：

1. GET `/api/v1/merchant/orders`：省略orderType查询本店两类订单；填写PRINT及PENDING_ACCEPTANCE筛选刚付款的打印单。page/size默认1/20，最多100，按创建时间及ID倒序。
2. 复制实际id到GET `/api/v1/merchant/orders/{orderId}`：200，booking=null，printing含冻结文件名、页数、份数、规格、纸张数、单价和QUEUED；payment是原成功支付，refund=null。服务之后改名或改价不覆盖冻结信息。
3. GET `/api/v1/merchant/orders/{orderId}/events`：CREATE、PAYMENT按eventSeq升序，ID和序号为字符串。该接口展示原事件，不会因查看而退款或续期。
4. 列表不附加学生姓名、登录名或联系方式；详情里的fileId本阶段不授予商家下载权限。

无本店关系404，无效OWNER403，跨店或不存在404；商家账号停用/会话过期不能读取。店铺暂停或停业仍可读旧单。

## 3. 第一单接单及重复

1. 在付款后30分钟内点击POST `/api/v1/merchant/orders/{orderId}/accept`，填写第一单ID；**不发送请求体**。
2. 预期200：ACCEPTED / PAID / QUEUED，acceptedAt有值，版本比待接单增加1；付款时间/金额、接单截止、打印规格不变，refund=null。
3. 再次Execute：原acceptedAt和版本，不重复写事件；GET事件只新增一次ACCEPT，actorType=MERCHANT、actorId为商家账号ID。
4. 切回学生token，GET本人详情应与商家看到的订单事实一致。POST本人cancel返回409 ORDER_STATE_CONFLICT。
5. GET本人文件元数据：expiresAt仍null。已接单不会因原30分钟期限自动退款。

接单须店铺当前APPROVED，暂停时403 SHOP_NOT_APPROVED，重复也一样。手动停业、营业结束、服务/价目停用不妨碍合法旧单；接单不生成新资金，模拟开关关闭也可执行。首次接单只复核文件保护元数据，不代表重新检查磁盘PDF。

商家可按[受控下载](merchant-print-download.md)取得PDF，再按[打印履约指南](merchant-print-fulfillment.md)开始及标记待取件；尚未开始时也可按[取消指南](merchant-print-cancellation.md)说明原因退款。待取件仍IN_SERVICE并保护文件，学生取件完成后续接入；complete对PRINT仍404。

## 4. 第二单说明原因拒单

1. 切回商家token，在期限前调用POST `/api/v1/merchant/orders/{orderId}/reject`，填第二单ID和下方JSON。reason必填，去首尾空白后1～300 Unicode字符，不填其他字段。

```json
{"reason":"打印设备维护，无法承接本次订单"}
```

2. 预期200：CLOSED / REFUNDED / ABORTED，closeReason=MERCHANT_REJECT，closeNote为原因；原payment保留，refund为唯一全额成功退款。
3. GET事件只新增一条MERCHANT_REJECT，操作者为MERCHANT/本次商家ID，reason为同一文本。
4. 相同原因重复仍200，refundNo/closedAt/version不变；改原因409 ORDER_STATE_CONFLICT，不覆盖原记录。
5. 切回学生token核对详情与事件。若文件还关联第一笔已接单订单，expiresAt保持null；另用一个文件仅建一笔并拒单，可验证expiresAt=实际closedAt+7天。

暂停店铺仍可说明原因拒单退款，学生停用不阻止正常商家处理旧单。新退款要求模拟开关开启，关闭时503 MOCK_PAYMENT_DISABLED且整笔回滚；已有同原因结果仍可重放。

## 5. 到期与常见返回

另建已付款但不接单的订单，等待30分钟及扫描提交。GET不主动退款；扫描提交后CLOSED / REFUNDED / ABORTED、ACCEPTANCE_TIMEOUT，事件SYSTEM且actorId=null。若到达期限但扫描还未提交，合法接单/拒单会先提交超时退款，再409 ACCEPTANCE_DEADLINE_PASSED；重新GET核对退款。等于截止已经到期，不记录本次商家原因。

| 返回 | 检查内容 |
|---|---|
| 400 VALIDATION_FAILED | 规范orderId，接单无请求体，拒单原因类型/长度/未知字段 |
| 401/403 | 会话、账号、角色、OWNER；接单还须当前APPROVED |
| 404 RESOURCE_NOT_FOUND | 订单不存在、非本店或该入口本阶段不支持PRINT |
| 409 ORDER_STATE_CONFLICT | 未付款、已取消、拒单原因变化或其他不允许状态 |
| 409 ACCEPTANCE_DEADLINE_PASSED | 超时退款关闭已提交，读取原事实 |
| 503 MOCK_PAYMENT_DISABLED | 新退款关闭；原事实保持，恢复开关后重试 |
| 503 FILE_STORAGE_UNAVAILABLE | 保护元数据或必要期限写入异常，整笔回滚 |
| 503 SERVICE_TEMPORARILY_UNAVAILABLE | 数据库竞争已回滚，保留原orderId重试 |

## 自动验证记录

2026-10-24：最终定向 **676个用例通过**（普通/Web172、真实MySQL/HTTP504，其中新增商家打印确认87），零失败/错误/跳过；V1～V12迁移及mysql模式JAR健康、UTF-8、trace和鉴权检查通过。运行仍54个操作、完整62/54/8；未重跑全部历史业务IT。

实际执行（独立临时MySQL及测试文件根，不访问现有服务数据库或用户持久上传目录）：

```powershell
$env:MAVEN_ARGS='-Dit.test=MerchantPrintConfirmationMysqlIT,PrintPaymentsMysqlIT,PrintOrdersMysqlIT,MerchantOrderListMysqlIT,MerchantBookingConfirmationMysqlIT,OrderEventsMysqlIT,StudentOrderListMysqlIT,FileDownloadMysqlIT,MockPaymentConfigurationMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

最终日志`.cache/merchant-print-mysql-final.log`。

| 套件 | 通过数 |
|---|---:|
| MerchantPrintConfirmationMysqlIT | 87 |
| PrintPaymentsMysqlIT | 71 |
| PrintOrdersMysqlIT | 79 |
| MerchantOrderListMysqlIT | 48 |
| MerchantBookingConfirmationMysqlIT | 59 |
| OrderEventsMysqlIT | 54 |
| StudentOrderListMysqlIT | 43 |
| FileDownloadMysqlIT | 48 |
| MockPaymentConfigurationMysqlIT | 1 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |

新增用例覆盖实际PDF上传/下单/付款后的商家读取、接单及原因退款，身份/会话/成员/暂停和跨店，学生停用、历史文件删除与冻结摘要，分页/六种状态筛选，Unicode原因与严格请求体，截止前/等于/后、真实店铺/文件/订单锁等待跨界、固定acceptedAt；原资金开关、版本上限、逐步SQL及最终会话失效回滚，同文件跨店引用、拒单/新建及接单/拒单/学生取消/扫描竞争。真实RR门闩验证列表、详情及事件的资格/事实快照，真实RC时序复现扫描先提交后的定位缓存；持有学生账号锁和实例文件互斥时仍可拒单，确认没有反向学生锁或PDF IO。

首次回归发现新增商家事件归属判断比较了两个Long对象的引用，导致部分本店订单误404；已改成数值比较，保留原预约事件与新增打印事件用例，修复后上述定向套件全部通过。失败临时实例在进程结束后定向清理，成功实例由脚本清理；未修改本机VS Code凭据。Swagger/Apifox流程供用户复现，未代用户操作客户端。
