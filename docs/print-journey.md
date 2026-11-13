# 打印业务端到端验收：从入驻到取件评价

人工执行可使用[本轮验收记录与五页样本](manual-print-acceptance.md)，按检查项填写实际结果；准备材料不代表人工验收已经通过。

本指南串联已实现接口，不增加业务规则、迁移或默认账号。当前运行62个操作、V1～V15/23表。建议使用新的虚构学生和PRINT商家，准备一份无加密、仅含虚构内容的PDF。

## 准备

沿原VS Code MySQL配置重启应用，打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，或重新导入[运行文档](openapi/campuslife.openapi.json)到Apifox；[完整文档](openapi/campuslife.v1-design.openapi.json)也已无待开发操作。沿原本机开发配置启用模拟支付，503 MOCK_PAYMENT_DISABLED时按[支付指南](print-payments.md)检查开关。

使用已有受控ADMIN业务账号审核商家。管理员仍由[初始化脚本](../scripts/init-admin.ps1)显式创建，普通注册只创建STUDENT。MySQL数据库用户不是业务管理员。

PDF要求：单文件不超过20 MiB、1～500页，无加密；上传时服务端识别实际大小和页数，不能自己填写页数来计价。自动测试使用5页PDF，人工文件页数不同则按实际pageCount核对。[上传边界指南](print-file-upload.md)说明完整限制。

## 第一阶段：配置可下单的打印商家

| 顺序 | 身份与接口 | 操作 |
|---|---|---|
| 1 | 匿名 POST /auth/register、POST /auth/login | 注册并登录学生，保存ID和data.accessToken |
| 2 | 匿名 GET /shop-categories | 取code=PRINT的分类ID |
| 3 | 匿名 POST /merchant-applications | 填新商家账号、密码、昵称、分类ID、店名与位置，保存accountId/shopId |
| 4 | ADMIN GET /admin/shops/{shopId}，POST approve | 读取店铺version再审批；获批后仍停业 |
| 5 | 商家登录后 PUT /merchant/shop/business-hours | 使用审批后的店铺版本，配置当前校园星期的营业区间 |
| 6 | MERCHANT PATCH /merchant/shop | 使用最新店铺版本，businessEnabled=true |
| 7 | MERCHANT POST /merchant/services | 创建上架PRINT服务，durationMinutes必须显式null |
| 8 | MERCHANT PUT /merchant/services/{serviceId}/print-prices | 配置至少一项启用规格，保存其独立价目版本 |

角色切换时更换Authorize，只填token本身。匿名操作可以清除授权；失效token即使访问匿名接口也会被拒绝。登录前若保留了过期Authorization，先清除后再登录。

营业时间按Asia/Shanghai，weekday周一=1至周日=7、HH:mm不跨午夜。报价允许店铺休息，但创建打印订单要求当前正在营业；先确认选中的星期和时间区间包含当前校园时间。

以下花括号占位内容要替换为实际响应。服务参考起价不参与规格报价，例如：

```json
{"serviceType":"PRINT","name":"模拟A4打印","priceAmount":99,"durationMinutes":null,"enabled":true}
```

配置A4黑白双面、每印刷面20分：

```json
{"rules":[{"paperSize":"A4","colorMode":"BW","sideMode":"DOUBLE","pricePerImpression":20,"enabled":true,"expectedVersion":null}]}
```

新规格expectedVersion必须显式null；已有规格使用GET价目返回的version字符串。每批1～4项，省略规格保留。支持A4、BW/COLOR、SINGLE/DOUBLE四种组合。

| 版本 | 用在哪里 |
|---|---|
| 账号version | 本人资料/账号状态 |
| 店铺version | 审核、营业时间、经营开关 |
| 服务version | 服务资料修改 |
| 价目version / 报价priceVersion | 规格改价、打印创建的expectedPriceVersion |

创建打印订单使用命中规格的价目版本。每次修改后读取响应新版本，不把服务或店铺版本混入价目请求。

## 第二阶段：上传、下载本人文件与报价

1. 切换学生token，在“04 文件与打印报价”选择POST /files。Swagger点击Try it out后选择本机PDF；Apifox使用multipart/form-data，字段名file、类型File。不要提交JSON或把本机路径作为字符串传入。
2. 201返回id、originalName、byteSize、pageCount、status=AVAILABLE、expiresAt。保存fileId；初始24小时有效。响应不提供存储路径或公开下载地址。
3. 学生GET /files/{fileId}读取元数据，GET /files/{fileId}/content下载本人PDF。content成功返回PDF附件，不能按JSON解析；此时商家还没有内容访问权，管理员也无默认下载权。
4. 学生POST /print-quotes：

```json
{"serviceId":"{打印服务ID}","fileId":"{PDF文件ID}","paperSize":"A4","colorMode":"BW","sideMode":"DOUBLE","copies":2}
```

5. 保存返回的priceVersion和payableAmount，核对pageCount、sheetCount、unitPriceAmount。报价不锁价、不续期、不保护文件或创建订单。

若PDF为5页、2份、20分/印刷面：印刷面5×2=10，纸张ceil(5/2)×2=6张，金额10×20=200分（2元）。双面减少纸张数，计费仍按印刷面；每份分别取整，不能将总10页合并算5张。服务参考起价99分不会代替规格单价。

## 第三阶段：下单、付款与接单

1. 学生POST /orders/prints，Idempotency-Key填本次唯一键，如print-demo-001：

```json
{"serviceId":"{打印服务ID}","fileId":"{文件ID}","paperSize":"A4","colorMode":"BW","sideMode":"DOUBLE","copies":2,"expectedPriceVersion":"{报价priceVersion}","expectedPayableAmount":200}
```

金额用报价真实整数分，原样保留规格。首次201，保存订单id。相同键和归一化请求重试200同一订单；更改请求内容不能复用原键。409 QUOTE_CHANGED时重新报价并确认新金额后用新键创建。

2. 创建后学生GET /files/{fileId}应expiresAt=null，表示未结束订单保护；学生GET /orders与商家GET /merchant/orders筛选PRINT可以看到新单。只有付款仍不会给商家内容访问权。
3. 学生在15分钟内POST /orders/{orderId}/payments/mock，**无请求体**；详情变PENDING_ACCEPTANCE/PAID，金额使用订单冻结值，重复付款不会重复扣款。
4. 商家在付款后30分钟内POST /merchant/orders/{orderId}/accept，**无请求体**；详情ACCEPTED/PAID/QUEUED。本店正常商家及有效OWNER此时可以GET /files/{fileId}/content取得任务PDF。

## 第四阶段：打印、待取件、学生确认与评价

接单后的打印任务可立即开始，无需等待预约时刻：

| 操作 | 主状态 / 打印进度 | 正常链路订单版本 |
|---|---|---:|
| 创建 | PENDING_PAYMENT / QUEUED | 0 |
| 模拟支付 | PENDING_ACCEPTANCE / QUEUED | 1 |
| 商家accept | ACCEPTED / QUEUED | 2 |
| 商家start | IN_SERVICE / PRINTING | 3 |
| 商家ready-for-pickup | IN_SERVICE / READY_FOR_PICKUP | 4 |
| 学生pickup | COMPLETED / PICKED_UP | 5 |

1. 商家POST /merchant/orders/{id}/start，无正文；然后POST /merchant/orders/{id}/ready-for-pickup，无正文。主状态仍IN_SERVICE，具体阶段看printing.progress。有效打印中/待取件任务仍可受控下载。
2. 切换学生token，POST /orders/{id}/pickup，无正文；只有本人待取件单可确认。商家complete入口用于预约，打印完成由学生确认取件。
3. 已取件订单不再授予商家下载权；若该文件没有其他本店合格任务，下次商家下载404。文件仍按保留期存储，学生本人可在有效期内下载。
4. 全部关联订单终结后，expiresAt为最晚终结时刻+7天；任一未结束引用仍为null。保留期不是商家下载授权，GET/下载/合法重试不会续期。
5. 学生POST /orders/{id}/review，例如`{"rating":5,"content":"模拟打印取件完成"}`，首次201；订单reviewed=true，版本仍5，评价不改变事件或保留期。匿名查询商家评价/详情，核对真实评价数与评分。
6. 学生和商家事件应一致：CREATE→PAYMENT→ACCEPT→START→READY_FOR_PICKUP→PICKUP。重试已有合法事实不会新增资金或事件，完成后重试start/ready也不回退阶段。

## 退款与文件保护分支：各用新的订单键

| 分支 | 操作 | 核对 |
|---|---|---|
| 同文件两笔未付单 | 同一fileId/规格分别用两个新键创建，依次学生cancel | 第一单关闭后仍null；最后一单关闭后7天；无支付/退款，旧键重放不新建或续期 |
| 商家拒单 | 学生付款，商家reject，正文reason | CLOSED/REFUNDED/ABORTED，同原因重试不重复退款，未接单不授予内容访问 |
| 接单后未打印取消 | accept后商家cancel，正文reason | 全额退款，关闭任务的下载授权失效；其他本店合格关联可继续独立授权 |
| 打印中或待取件异常 | 正常ADMIN先查详情，再close-exception，正文reason/expectedVersion | CLOSED/REFUNDED/ABORTED，保留startedAt/readyAt；同原请求合法重试不重复退款，关闭单不可取件 |
| 报价后改价 | 商家用价目当前version更新单价，再以旧报价下单 | 409 QUOTE_CHANGED，无订单保护；重新报价确认新金额后用新键下单 |

普通取消开始后拒绝；已完成单不支持异常退款。所有新增退款仍遵守模拟资金开关，不能通过异常处理绕过。管理员核实订单不授予其PDF内容访问权。

## 排查顺序

- 上传400/413/422：先核对multipart单字段file、实际PDF大小/页数及加密状态；不要手填页数。
- 401：清除旧Authorization后重新登录，切换正确角色token。
- 商家下载404：核对本店有效已接单/打印中/待取件关联；付款、保留期或知道fileId都不足以授权。
- 创建失败但报价成功：检查当前校园营业时间和开关；报价允许休息，下单要正在营业。
- 409 QUOTE_CHANGED：读取新报价；价目版本、服务版本和店铺版本不能混用。
- 410 FILE_EXPIRED：本人文件已到期或进入清理，不能只凭磁盘上仍有文件继续使用。

## 自动验收与实际记录

PrintJourneyMysqlIT通过真实HTTP完成入驻、审批、经营/服务/价目、multipart上传、报价与上述六条主/分支链路。管理员准备沿受控初始化；Clock只在测试环境固定，SQL仅用于断言和精确清理本次自建数据。文件根先校验为本轮独立测试根，不读取用户持久上传目录。

2026-10-26：本轮 **196个用例通过**（普通/Web172，真实MySQL/HTTP24：新增PrintJourneyMysqlIT6、预约整链4、数据库11、OpenAPI3），零失败/错误/跳过。V1～V15/23表及mysql JAR健康、UTF-8、trace与鉴权启动检查通过；测试实例和专用文件根已清理，未访问用户现有数据库、上传目录或启动密钥。

| 新增场景 | 实际结果 |
|---|---|
| 上传到取件评价 | 真实入驻/审核/营业/PRINT服务与价目，真实multipart PDF解析5页、报价、创建与重放、学生/商家列表、支付/接单重放、原文件下载与权限变化、开始/待取件/学生取件、本人评价和真实评分 |
| 同文件两笔未付单 | 第一单取消仍null保护；最后终结+7天；稍后创建重放/取消重放不续期，无资金流水 |
| 商家拒单 | 一笔支付/全额退款，CLOSED/REFUNDED/ABORTED，重复拒绝不增事件/资金，未接单不授予下载权 |
| 接单后取消 | 原本可下载；关闭后无其他任务则下载404，退款及七天期限正确，重试不重复事实 |
| 待取件异常 | 开始后普通取消409；管理员先查看版本再核实退款关闭，保留开始/待取件时间，重放不重复退款，关闭后不能取件 |
| 报价后改价 | 旧价目版本下单409 QUOTE_CHANGED且文件期限不变；新报价300分，重新确认后新键创建并保护文件 |

5页×2份×20分=200分，双面纸张ceil(5/2)×2=6，服务参考起价99分不参与规格计价。报价前后文件仍为原24小时期限，创建后null，完成/关闭且全部引用终结后7天。相同文件多引用按所有订单保护，不以单一订单决定是否可清理；本轮核对期限和授权，没有宣称实际等待七天完成清理。

本人、商家成功下载均核对原始PDF字节、application/pdf及no-store。付款前/已付未接单商家404；已接单、打印中、待取件可下载；单一任务完成/关闭后商家404，保留期内学生仍可下载；ADMIN无默认内容权限，其他学生元数据和下载404。

正常订单版本5、六条事件CREATE/PAYMENT/ACCEPT/START/READY_FOR_PICKUP/PICKUP，商家与学生事件一致，一笔支付无退款；评价使reviewed=true而不改版本、事件或文件期限，公开评分/数量为5/1。

实际命令，仅运行独立临时MySQL及自有文件根：

```powershell
$env:MAVEN_ARGS='-Dit.test=PrintJourneyMysqlIT,BookingJourneyMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

日志为.cache/print-journey-mysql.log，JUnit报告位于target/failsafe-reports。所有商家/服务/价目/文件/订单/评价准备和动作均经HTTP，ADMIN沿受控初始化；固定Clock只用于测试，SQL只断言与精确清理本次自建归属数据，文件操作限定独立测试根。未重跑全部历史业务IT，未替用户完成浏览器点击或真实打印。

本轮同步修正商家reject/cancel/ready及预约complete说明中的“内容访问/打印履约/管理员异常/评价后续开发”等旧描述，学生取消说明不再使用过时的“本轮”阶段字样；业务行为、62个接口和迁移数量不变。

