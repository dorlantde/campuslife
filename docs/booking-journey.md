# 预约业务端到端验收：从入驻到评价

2026-10-27：用户希望立即走完整链并选择[隔离自动验收](reports/booking-immediate-2026-10-27.md)，现有四条真实HTTP旅程通过，连同配套检查本轮190用例通过；测试时钟推进省去现实等待，运行规则保持。此前[浏览器预约准备记录](manual-booking-acceptance.md)保留为未完成的人工清单，不用自动结果填成人工通过。

本指南串联当前已实现的业务接口，不新增业务规则、迁移、调试时间接口或默认账号。当前运行62个操作、V1～V15/23表。适合按顺序在Swagger UI或Apifox点击验收；退款分支使用另外的新订单。

## 开始前

沿原VS Code MySQL配置启动应用，打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，或在Apifox导入[运行文档](openapi/campuslife.openapi.json)。完整[规划文件](openapi/campuslife.v1-design.openapi.json)当前也已无待开发操作。沿原本机配置启用模拟支付；若支付或退款返回503 MOCK_PAYMENT_DISABLED，按[支付指南](booking-payments.md)检查本机开关。

准备已有的受控ADMIN业务账号。ADMIN通过[初始化脚本](../scripts/init-admin.ps1)显式创建，普通注册只能创建学生；MySQL数据库用户不是业务管理员。以下学生和商家都使用新的虚构账号，用户名要唯一，密码由自己在本机填写。

本流程无需数据库手工插入或修改业务数据。人工开始服务必须等到真实预约时间；测试用例通过测试环境的Clock推进时间，生产接口没有快进开关，不需要修改电脑时间或数据库中的预约时间。

## 先记住四类版本

| 版本 | 从哪里取 | 用途 |
|---|---|---|
| 账号version | 注册/本人资料/管理员账号列表 | 本人昵称或管理员账号状态 |
| 店铺version | 管理员店铺详情、本人店铺资料 | 审核、营业时间、经营开关 |
| 服务version | 本店服务或候选时段serviceVersion | 服务修改、下单报价核对 |
| 理发师version | 本店人员列表；排班响应barberVersion | 姓名/启停、绑定和排班共用 |

每次实际修改后保存响应里的新版本。版本与ID都保留字符串；不要把账号版本当成店铺或理发师版本。409 CONFIG_VERSION_CONFLICT时重新GET相应资源，不猜测版本加几。

## 第一阶段：建立可预约店铺

| 顺序 | 身份 / 接口 | 填写与核对 |
|---|---|---|
| 1 | 匿名 POST /auth/register | 填username/password/nickname，保存学生ID；成功201，不会自动登录 |
| 2 | 匿名 POST /auth/login | 用学生账号登录，保存data.accessToken |
| 3 | 匿名 GET /shop-categories | 找到code=HAIRCUT的分类ID |
| 4 | 匿名 POST /merchant-applications | 新商家username/password/nickname、分类ID、shopName/locationText；保存accountId和shopId，PENDING |
| 5 | 匿名 POST /auth/login | 用商家账号登录，保存商家token；待审核时不能配置经营信息 |
| 6 | ADMIN GET /admin/shops/{shopId} | 切换Authorize为管理员token，取得当前店铺version |
| 7 | ADMIN POST /admin/shops/{shopId}/approve | 正文expectedVersion为上一步版本；APPROVED、OWNER激活，仍停业 |
| 8 | MERCHANT PUT /merchant/shop/business-hours | 切换商家token，使用审批后的店铺version，保存营业时间响应新version |
| 9 | MERCHANT PATCH /merchant/shop | 使用新店铺version，businessEnabled=true |
| 10 | MERCHANT POST /merchant/services | 创建HAIRCUT、整数分价格、15分钟倍数时长；enabled=true；保存服务ID/version |
| 11 | MERCHANT POST /merchant/barbers | displayName、serviceIds=[]；保存人员ID/version，默认停用 |
| 12 | MERCHANT PUT /merchant/barbers/{barberId}/services | 使用人员version，serviceIds填刚创建的服务ID数组；保存新version |
| 13 | MERCHANT PATCH /merchant/barbers/{barberId} | 使用新人员version，enabled=true；保存新version |
| 14 | MERCHANT PUT /merchant/barbers/{barberId}/schedule | date与expectedBarberVersion，时间段完整落在营业时间内；保存barberVersion |

Authorize只填写token本身，不加Bearer前缀。切换角色时应同步更换token；调用匿名接口时可以清除授权，携带失效token即使访问匿名接口也会被拒绝。

时间按Asia/Shanghai解释：weekday周一=1至周日=7，营业/排班使用HH:mm，不跨午夜。选择校园今天至第6天内的日期，并确保所选日期对应星期已设置营业时间。人工验收选明天的营业时段通常更容易准备；若今天距离结束不足2小时，候选为空是正常结果。

以下JSON中的花括号占位内容都要替换成实际响应值，不能原样提交。

营业时间（只配置所选星期，其他星期休息；完整替换整周）：

```json
{"expectedVersion":"{店铺版本}","intervals":[{"weekday":1,"startTime":"08:00","endTime":"22:00"}]}
```

上例weekday=1只适用于周一，请按所选日期修改；不要误清空已有演示店的其他星期。本流程建议使用新建虚构店铺。

服务、人员与绑定示例：

```json
{"serviceType":"HAIRCUT","name":"模拟标准理发","priceAmount":3000,"durationMinutes":30,"enabled":true}
```

```json
{"displayName":"模拟理发师小林","serviceIds":[]}
```

```json
{"expectedVersion":"{人员版本}","serviceIds":["{服务ID}"]}
```

人员启用用`{"expectedVersion":"{人员新版本}","enabled":true}`。随后保存排班：

```json
{"date":"{校园日期YYYY-MM-DD}","expectedBarberVersion":"{人员新版本}","intervals":[{"startTime":"08:00","endTime":"22:00"}]}
```

## 第二阶段：查询空位、下单与付款

1. 清除授权，依次GET /shops/{shopId}、/shops/{shopId}/services和/shops/{shopId}/barbers?serviceId=实际服务ID，确认店铺、服务和人员公开可见。
2. GET /shops/{shopId}/booking-slots，填写serviceId、barberId、date。保存serviceVersion、priceAmount、durationMinutes以及某一slots元素的startAt/endAt。startAt是UTC时刻，直接复制带Z或时区偏移的原值，不把本地HH:mm直接当UTC。
3. 切换学生token，POST /orders/bookings；Idempotency-Key填本次自选唯一键，如booking-demo-001，正文如下：

```json
{"serviceId":"{服务ID}","barberId":"{人员ID}","startAt":"{候选原始startAt}","expectedServiceVersion":"{候选serviceVersion}","expectedPayableAmount":3000}
```

金额要使用候选真实priceAmount，上例3000分即30元。候选不预留、不锁价，若409 QUOTE_CHANGED或BOOKING_CONFLICT，重新查候选再决定是否以新键下单；不要改请求内容复用旧创建键。

4. 首次201，保存订单id，核对PENDING_PAYMENT/NOT_PAID、booking.occupancyStatus=HELD。相同创建键和请求重试200且订单ID相同；候选已排除这个占用区间。学生GET /orders、本店商家GET /merchant/orders都应能找到它。
5. 学生在15分钟内POST /orders/{orderId}/payments/mock，**不填请求体**。应200，随后详情为PENDING_ACCEPTANCE/PAID；金额来自冻结快照。重复付款保留同一成功事实。
6. 商家在付款后的30分钟内POST /merchant/orders/{orderId}/accept，**不填请求体**。应ACCEPTED/PAID/ACTIVE。先完成接单再等待预约开始；不要等两小时后才接单，否则可能已被超时退款。
7. 此时可用当前人员version尝试停用人员：应409 BOOKING_CONFLICT，原人员仍启用。正常验收无需清空或修改排班来“释放”已接预约。

## 第三阶段：到店履约与评价

到预约开始前可以暂停人工操作。至少提前2小时创建，与JWT有效2小时是两条独立规则：到开始时原token很可能已过期。

1. 先清除Authorize中的过期token（Apifox也取消继承旧Authorization），再重新调用匿名登录，取得新的商家和学生token；否则过期请求头会让登录本身被拒绝。
2. 商家在startAt≤服务器时间<endAt时POST /merchant/orders/{orderId}/start，无正文。提前或错过首次开始窗口返回409 BOOKING_TIME_INVALID。
3. 商家POST /merchant/orders/{orderId}/complete，无正文。已开始即可完成，无最低服务时长；核对COMPLETED/PAID/CONSUMED、version="4"（未发生其他订单动作时）。原预约区间保留，不因提前完成重新售卖。
4. 切换学生token，GET /orders/{orderId}确认相同状态；POST /orders/{orderId}/review，例如`{"rating":5,"content":"模拟预约全流程完成"}`，首次201。
5. 再查订单，reviewed=true，订单version和事件不因评价变化；重复评价409。匿名GET /shops/{shopId}/reviews及商家详情，核对该条公开评价、当前昵称、真实评分和数量。
6. 学生和商家分别GET各自订单事件接口，正常链路动作应按序CREATE→PAYMENT→ACCEPT→START→COMPLETE，eventSeq是字符串0～4。同次付款/接单/履约重试不增加事件和资金事实。

## 另外三条分支：各用新订单

| 分支 | 操作 | 关键结果 |
|---|---|---|
| 未支付取消 | 学生下单后POST /orders/{id}/cancel | CLOSED/NOT_PAID/RELEASED；不付款不退款；原创建键仍绑定原关闭订单，新键才新建 |
| 商家拒单 | 学生付款后，商家POST /merchant/orders/{id}/reject，正文reason | CLOSED/REFUNDED/RELEASED；同原因重复不重复退款；候选重新出现 |
| 开始后异常 | 已接单并开始，ADMIN先GET /admin/orders/{id}，再POST close-exception，正文reason和读取的expectedVersion | CLOSED/REFUNDED/CONSUMED，保留原区间；同管理员/原因/原版本合法重放不重复退款，关闭单不可评价 |

异常处理需按既有规则核实；新增退款仍受模拟资金开关保护，已完成订单不能借异常入口退款。测试权限隔离时，用另一个学生token查该订单应404，用学生token调用商家履约入口应403。

## 排查顺序

- 401：清除旧授权后重新登录，再切换正确角色token。
- 403：核对角色、账号启用、OWNER与店铺审批资格。
- 404公开资源：核对店铺获批、经营账号/OWNER有效、服务上架、人员启用且已绑定；不要把真实ID填成文档示例ID。
- 无候选：核对日期/星期、营业开关/营业时间、排班/绑定、至少提前2小时、完整服务可容纳及真实占用。
- 409版本/报价/占用冲突：重新读取对应资源；四种版本不能混用。
- 到期关闭：检查创建15分钟、支付后接单30分钟、开始[startAt,endAt)三条不同时间边界。

## 自动验证与实际记录

BookingJourneyMysqlIT会通过真实HTTP完成上述准备及四条链路。唯一非HTTP的业务准备是受控AdminAccountInitializer；测试Clock只在测试环境推进，SQL只用于读取不变量和清理本次自建数据，未用SQL预置商家、服务、排班或订单。

2026-10-26：本轮 **190个用例通过**（普通/Web172；真实MySQL/HTTP18：新增完整旅程4、数据库11、OpenAPI3），零失败/错误/跳过。V1～V15/23表和mysql模式JAR健康、UTF-8、trace、鉴权启动检查通过。仅使用独立临时MySQL及自有文件根，结束后清理；未改用户现有数据库、上传目录、启动配置或密钥。

| 新增场景 | 已实际验证的结果 |
|---|---|
| 正常履约到公开评价 | 学生注册、商家申请、受控管理员、审批、营业时间/开关、上架服务、创建人员/绑定/启用/排班、公开查询、候选、创建/创建重放、列表/归属隔离、支付/接单重放、存量人员保护、时间窗口、重新登录、开始/完成、本人评价、改昵称与公开真实评分 |
| 商家拒单 | 同一条公开准备链；一次支付/退款、CLOSED/REFUNDED/RELEASED、原因重放不增事件，原时段重新成为候选 |
| 未支付取消 | 无支付/退款，释放时段；旧创建键仍返回原关闭单，新键可以重新预约 |
| 服务中管理员异常 | 同一公开准备链；先查管理员详情再带版本核实、单笔全额退款、CLOSED/REFUNDED/CONSUMED、重放不重复资金、关闭不可评价 |

正常完成核对订单版本4、五条事件CREATE/PAYMENT/ACCEPT/START/COMPLETE、一笔支付无退款，评价不改变订单版本或事件。学生与商家事件响应一致；学生改名后公开评价显示当前昵称，商家真实rating/reviewCount为5/1。测试SQL仅用于断言与精确清理，业务准备/迁移均走真实HTTP与Flyway；唯一非HTTP账号准备为受控AdminAccountInitializer。

实际命令：

```powershell
$env:MAVEN_ARGS='-Dit.test=BookingJourneyMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

日志：.cache/booking-journey-mysql.log；JUnit报告：target/failsafe-reports。没有重跑全部历史业务IT，也没有宣称替用户在浏览器中等待真实两小时并完成点击。人工验收使用真实校园日期/时间，自动测试固定Clock为2026-10-26并在需要时推进至候选startAt。

本轮修正BarberController与MerchantOperationController中“预约尚未实现/未来接单”等过时说明，同步两份OpenAPI，业务行为、路径数量和迁移均不变。不增加时间快进接口或修改任何已确认规则。

