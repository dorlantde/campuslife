# 打印待支付订单验收：Swagger / Apifox

2026-10-26 后续评价已按[ADR-0042](decisions/0042-student-order-review.md)接入，见[学生评价指南](student-order-review.md)。合法完成单可本人一单一评，详情reviewed反映真实记录（含隐藏），不改订单版本/状态事件/资源；旧阶段固定false或学生评价未开放由此取代，公开评价/统计后续。

2026-10-25 后续扩展：[商家打印接单后取消](merchant-print-cancellation.md)已按ADR-0037开放，原“PRINT取消404/接单后无终结路径”的阶段说明已替代。学生接单后仍不能直接取消；商家内容读取及打印正常履约继续后续开发。

2026-10-24 后续扩展：[商家打印确认](merchant-print-confirmation.md)已开放本店PRINT列表/详情/事件及接拒单；本页旧阶段中的“商家PRINT未开放/空页”已由ADR-0036替代。打印内容访问、接单后取消及打印后续履约仍待开发。

2026-10-24更新：[ADR-0035](decisions/0035-print-mock-payment.md)已开放打印模拟付款、待接单取消与超时全额退款，见[支付验收](print-payments.md)。本指南保留前一阶段待支付创建/关闭流程；商家打印确认与内容访问后续推进。

按[ADR-0034](decisions/0034-print-orders.md)实现。重启原VS Code **CampusLife (MySQL)** 配置，Flyway自动执行V12；不需要重建数据库或重新填写root。打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，或重新导入[运行接口快照](openapi/campuslife.openapi.json)。打印支付已按ADR-0035开放，商家打印履约尚未开放。

## 1. 商家准备

先按[打印报价指南](print-quotes.md)准备已批准PRINT商家、上架PRINT服务、启用规格价目，保留serviceId。测试使用虚构店铺，避免覆盖已有经营计划。

商家登录后将data.accessToken填入Authorize，只填token本身。GET `/api/v1/merchant/shop`取当前店铺version，然后PATCH同一路径设置：

```json
{"expectedVersion":"复制刚读取的version","businessEnabled":true}
```

再次GET取最新version。在PUT `/api/v1/merchant/shop/business-hours`填写expectedVersion和intervals。每项为`{"weekday":1,"startTime":"08:00","endTime":"22:00"}`，weekday整数1～7分别代表周一至周日。按[营业时间指南](merchant-business-hours.md)提交完整一周，遗漏日期会清空；选择能够覆盖**当前Asia/Shanghai时间**的时段。不要直接使用电脑当地星期判断，营业区间不跨午夜且不含结束时刻。

开关和营业计划共用店铺版本，修改一次后重新GET，不连续沿用旧版本。资格正常但停业、休息或无营业时间，报价仍成功，创建返回409 SHOP_UNAVAILABLE。

## 2. 学生上传、报价与创建

1. 切换为正常学生登录，替换Authorize为学生token；沿用本人上传文件，不使用商家或其他学生的fileId。
2. POST `/api/v1/files`选择仓库的[两页虚构示例PDF](samples/campuslife-print-demo.pdf)，记录data.id为fileId。也可使用本人仍有效的PDF。
3. POST `/api/v1/print-quotes`填写实际serviceId、fileId、A4/BW/DOUBLE、copies=2。复制响应priceVersion及payableAmount。双面按每份取整算纸张，金额按全部印刷面计算；两页×两份×每面20分为80分、2张纸。
4. 展开“05 学生订单 → POST /api/v1/orders/prints”，点Try it out。Idempotency-Key填`print-demo-001`，请求示例：

```json
{
  "serviceId":"201",
  "fileId":"301",
  "paperSize":"A4",
  "colorMode":"BW",
  "sideMode":"DOUBLE",
  "copies":2,
  "expectedPriceVersion":"0",
  "expectedPayableAmount":80,
  "note":"虚构打印测试"
}
```

**201、301、版本0和金额80只是格式示例，须全部替换为本次实际ID与报价值。** ID/版本有引号，份数/金额是JSON整数。拒绝额外字段、客户端页数/单价以及字符串金额或小数。备注最多300个Unicode码点，省略/null/空白等同无备注。

预期HTTP201：orderType=PRINT、status=PENDING_PAYMENT、paymentStatus=NOT_PAID、paymentDeadline比createdAt晚15分钟；booking=null，printing包含文件名、实际页数、份数、规格、纸张数、单价和QUEUED。payment/refund/readyAt/pickedUpAt为null，reviewed=false。记录data.id为orderId。

5. 相同键、相同内容再次Execute：HTTP200，响应头Idempotency-Replayed=true，订单ID与原单一致。用同键改变copies或备注应409 IDEMPOTENCY_KEY_CONFLICT；修改业务内容用新键。若网络丢失响应，先原键重试，不换键重复购买。
6. GET `/api/v1/files/{fileId}`：expiresAt=null，表示至少一笔打印订单仍未终结；不是丢失有效期。

## 3. 本人历史与取消

1. GET `/api/v1/orders/{orderId}`核对冻结明细；GET `/api/v1/orders`默认包含本人两类订单，orderType=PRINT只看打印。打印摘要bookingStartAt/bookingEndAt为null，printingProgress=QUEUED。分页规则保持原样。
2. GET `/api/v1/orders/{orderId}/events`：仅CREATE、eventSeq字符串"0"，STUDENT和本人ID；不返回存储key、磁盘路径或下载直链。
3. 支付期限前POST `/api/v1/orders/{orderId}/cancel`，无请求体：200，CLOSED / NOT_PAID / ABORTED，closeReason=USER_CANCEL。不产生退款或支付流水。事件数组新增一次关闭动作。
4. 重新取消仍200，closedAt/version及事件数不变。同键创建重放返回这张已关闭订单，不创建新单或延长保留期。
5. 若该文件只关联这一单，元数据expiresAt=closedAt+7天。用同文件及新创建键再下一单，期限重新为null；先取消其中一笔，仍为null，直到最后一笔终结才开始7天。

保留期内文件仍可按[下载指南](file-download.md)受控下载，下载不续期。超过保留期先410，清理任务随后回收磁盘内容；元数据保留以支持历史订单。店铺暂停、服务下架和文件删除不会隐藏学生冻结历史；他人订单统一404。

## 4. 超时与常见错误

可另外创建一单，等待15分钟，再GET观察。GET本身不关闭；默认30秒扫描提交后变为PAYMENT_TIMEOUT / ABORTED，7天从实际关闭时刻起算。恰好达到期限时点击取消，会先提交超时关闭再返回409 PAYMENT_DEADLINE_PASSED；重新查询可见已关闭。测试无需修改本机或数据库时间。

| 返回 | 处理 |
|---|---|
| 400 IDEMPOTENCY_KEY_REQUIRED | 提供1～64位ASCII字母/数字/-/_，不使用中文 |
| 400 VALIDATION_FAILED | 检查字段类型、范围、必填和未知字段 |
| 401/403 | 重新登录或检查STUDENT/正常账号，不使用MySQL账号 |
| 404 RESOURCE_NOT_FOUND | 检查本人文件和公开PRINT商家/服务 |
| 409 SHOP_UNAVAILABLE | 检查开关、上海星期/时间和营业计划 |
| 409 SERVICE_UNAVAILABLE / PRINT_SPEC_UNAVAILABLE | 服务上架且目标规格启用 |
| 409 QUOTE_CHANGED | 重新报价并确认新价格，再用新创建键提交 |
| 410 FILE_EXPIRED | 本人文件已过期/进入删除，不可新建引用；重新上传 |
| 503 FILE_STORAGE_UNAVAILABLE | 对象缺失、根标识/长度异常或文件版本无法写入，先排查存储 |
| 503 SERVICE_TEMPORARILY_UNAVAILABLE | 数据库竞争已回滚，保留原键稍后重试 |

本指南的创建及未付关闭无需资金开关；原模拟支付入口现支持PRINT，首次付款/待接单退款需开关。商家及管理员预约入口遇PRINT仍404。

## 自动验证记录

2026-10-24：最新相关报告合计 **798个用例通过**（普通/Web172、真实MySQL/HTTP626；本轮新增打印策略30及打印订单79），零失败/错误/跳过。该数字合并本轮相关套件的最新通过结果，不是一次全项目回归。先执行12套相关IT，修正营业边界测试的登录时间设置后，最终定向复测打印订单、迁移和OpenAPI共265个用例通过；V1～V12迁移及mysql模式JAR检查通过。

实际执行：

```powershell
$env:MAVEN_ARGS='-Dit.test=PrintOrdersMysqlIT,StudentOrderListMysqlIT,OrderEventsMysqlIT,MerchantOrderListMysqlIT,BookingOrdersMysqlIT,BookingPaymentsMysqlIT,PrintQuoteMysqlIT,FileUploadMysqlIT,FileDownloadMysqlIT,PrintPricesMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90

# 修正测试时钟、补充新进程恢复/批次及V12约束后，最终复测
$env:MAVEN_ARGS='-Dit.test=PrintOrdersMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

最终日志`.cache/print-orders-mysql-verified.log`，相关回归日志`.cache/print-orders-mysql-final.log`。最初测试中的非ASCII请求头在HTTP客户端被拒绝，以及测试时钟位于签发前/两小时登录到期边界，均修正测试安排；补充CHECK测试改为核对MySQL实际错误码3819/HY000，而非假设Spring异常子类。未放宽业务窗口或数据库约束。相关套件逐项数量：

| 套件 | 通过数 |
|---|---:|
| PrintOrdersMysqlIT | 79 |
| StudentOrderListMysqlIT | 43 |
| OrderEventsMysqlIT | 54 |
| MerchantOrderListMysqlIT | 48 |
| BookingOrdersMysqlIT | 65 |
| BookingPaymentsMysqlIT | 40 |
| PrintQuoteMysqlIT | 87 |
| FileUploadMysqlIT | 64 |
| FileDownloadMysqlIT | 48 |
| PrintPricesMysqlIT | 84 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |

真实验证涵盖安全对象检查、50亿分、权限/归属、精确营业与支付期限、幂等命名空间、数据库CHECK、SQL故障全回滚、文件版本上限、等待互斥不持有数据库锁、跨店系统同时关闭、关闭与新建竞争、清理竞争与七天后回收、重复不续期、每批100条及新JAR启动恢复。预约专属入口拒绝PRINT，原预约创建/支付、学生/商家列表、事件、文件上传/下载及打印价目/报价相关回归通过。未运行其余所有历史IT，未代用户点击Swagger或Apifox；使用自有临时MySQL及文件根，不访问用户持久数据。

