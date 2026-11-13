# 学生订单评价验收：Swagger / Apifox

2026-10-26 后续扩展：管理员隐藏评价已按[ADR-0044](decisions/0044-admin-review-hide.md)实现，详见[操作指南](admin-review-hide.md)。原文较早“隐藏入口后续”现以本扩展为准；本页历史验证记录保留原任务范围。

2026-10-26 后续公开评价/真实评分已按[ADR-0043](decisions/0043-public-shop-reviews.md)接入，见[公开验收指南](public-shop-reviews.md)。目录null/0现在仅表示确实零条公开评价，VISIBLE按真实数量/两位HALF_UP均分展示，作者当前昵称，停用作者不自动隐藏。旧统计尚未接入的阶段说明以本次为准，管理员隐藏动作后续。

按[ADR-0042](decisions/0042-student-order-review.md)及[确认方案](student-order-review-design.md)实现。重启原VS Code **CampusLife (MySQL)** 配置，Flyway自动执行V13新增order_review表；不重建数据库、不改本机凭据。运行58个操作，完整规划62/已实现58/待开发4，业务表22张。

## 1. 准备本人已完成订单

打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，或在Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，基础地址使用实际本机端口。

预约按[预约履约指南](merchant-booking-fulfillment.md)走到COMPLETED/PAID/CONSUMED；打印按[学生取件指南](student-print-pickup.md)走到COMPLETED/PAID/PICKED_UP。记录实际orderId，切换到该订单学生的登录data.accessToken，填Authorize，只填token本身。

确认订单详情reviewed=false。先GET `/api/v1/orders/{orderId}/review`，未评价预期404 RESOURCE_NOT_FOUND。待取件仍IN_SERVICE，不能跳过学生取件直接评价；退款关闭单也不能评价。

## 2. 提交评价

在“05 学生订单”调用新增POST `/api/v1/orders/{orderId}/review`，填实际订单ID和两个字段：

```json
{
  "rating": 5,
  "content": "服务顺利完成，沟通清楚。"
}
```

rating填写整数1～5，不加引号、不写5.0；content去首尾空白后1～1000个Unicode码点，内部文字和换行保留。不要填写学生/店铺ID、visibility、金额、版本或时间。完成后可立即评价，没有截止天数；打印PDF已清理也不阻止评价。

预期**201**，data含id、orderId字符串、rating、归一化content、visibility=VISIBLE和UTC createdAt，不增加账号资料。原订单仍COMPLETED/PAID，version、完成时间、资金、占用、打印进度和文件期限不变，订单事件不增加REVIEW事件。

## 3. 查看和重复提交

GET `/api/v1/orders/{orderId}/review`应200相同记录。再次读取本人订单，reviewed=true；切换有效本店商家或ADMIN读取详情，也应true。原取件/完成等合法重试返回当前详情，同样显示true，不重写原履约事实。

重复相同内容或改评分/文字提交均409 REVIEW_ALREADY_EXISTS，原记录不覆盖。首次响应丢失时用GET确认，不需要换幂等键重发，也没有修改/删除评价接口。隐藏记录以后仍占用一次评价名额；本轮没有管理员隐藏入口，不需要手动修改数据库验收。

商家停用、OWNER停用、暂停/停业或配置下架不影响正常学生评价合法完成旧单；学生自身账号和会话仍必须有效。其他学生查询或提交此单统一404。

## 4. 本轮展示边界

公开评价和真实统计现已按[公开验收指南](public-shop-reviews.md)接入。只有VISIBLE公开并计入数量和两位小数平均分，零条null/0代表确实没有公开评价；管理员隐藏动作仍待后续。

没有图片、商家回复、匿名开关、富文本、自动审核或敏感词处理。内容按文字存储，未来页面按普通文字安全展示。

| 返回 | 含义 |
|---|---|
| 201 | 首次评价保存成功 |
| 200 | 本人评价读取成功 |
| 400 VALIDATION_FAILED | ID、评分类型/范围、文字或JSON字段错误，重复也校验输入 |
| 401/403 | 当前学生账号、角色或会话无效 |
| 404 RESOURCE_NOT_FOUND | 他人/不存在/不支持类型，GET未评价也404 |
| 409 REVIEW_NOT_ALLOWED | 不是合法已付完成单，或首次评价时钟早于完成 |
| 409 REVIEW_ALREADY_EXISTS | 已有任何可见性评价，不覆盖或重发 |
| 503 SERVICE_TEMPORARILY_UNAVAILABLE | 数据库竞争回滚；重试若已提交用GET确认 |

无需手写PowerShell请求、改时钟或数据库状态。自动测试验证Unicode边界、并发、真实MySQL锁、约束与回滚。

## 自动验证记录

2026-10-26：本轮各套件最终报告去重 **714个定向用例通过**（普通/Web172、真实MySQL/HTTP542，其中新增评价90），零失败/错误/跳过。V1～V13迁移、22张业务表及mysql模式JAR健康、UTF-8、trace和鉴权启动检查通过；运行58、完整62/58/4。未重跑全部历史业务IT。

首轮713个通过（普通/Web172、MySQL/HTTP541，含新增评价89）。补充商家完成提交与等待评价的缓存边界用例、校验提示及运行注解后，最终106个通过（Web13、评价90、OpenAPI3）。重复执行不重复累加，以上714使用各套件本轮最后通过报告。

实际命令，均使用独立临时MySQL及自有文件根：

```powershell
$env:MAVEN_ARGS='-Dit.test=OrderReviewsMysqlIT,MerchantBookingFulfillmentMysqlIT,StudentPrintPickupMysqlIT,AdminPrintExceptionMysqlIT,AdminBookingExceptionMysqlIT,PrintOrdersMysqlIT,PublicShopCatalogMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90

$env:MAVEN_ARGS='-Dtest=WebFoundationTest -Dit.test=OrderReviewsMysqlIT,OpenApiMysqlIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

日志：`.cache/order-review-mysql.log`与`.cache/order-review-mysql-final.log`。

| MySQL/HTTP套件 | 本轮最终通过数 |
|---|---:|
| OrderReviewsMysqlIT | 90 |
| MerchantBookingFulfillmentMysqlIT | 54 |
| StudentPrintPickupMysqlIT | 79 |
| AdminPrintExceptionMysqlIT | 108 |
| AdminBookingExceptionMysqlIT | 57 |
| PrintOrdersMysqlIT | 79 |
| PublicShopCatalogMysqlIT | 61 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |

新增评价用例真实完成BOOKING/PRINT后创建201/读取200，验证学生/商家/管理员详情和原开始/完成/待取件/创建重放的reviewed与评价事实一致；订单状态、版本、时间、事件、资金、占用和文件期限不变。严格JSON评分（含小数/指数/字符串/布尔拒绝）、Unicode码点/换行/首尾空白、1与1000/1001边界、角色/账号/会话/归属、未完成/异常关闭及非法完成/支付事实、时间倒退等均覆盖。暂停、经营账号/OWNER停用、配置变化、资金关闭和PDF清理后正常学生仍可评价合法旧单。

数据库真实约束覆盖评分、空/过长文字、可见性、订单/学生/店铺外键与一单唯一；双提交只成功一次，明确的订单唯一键冲突在回滚后查原事实映射409，无关唯一错误不被掩盖。插入、最终会话失效和数据库竞争故障回滚评价，不改原订单。人工构造HIDDEN验证本人仍可读、reviewed=true且不能重发，不宣称已实现隐藏接口。

真实店铺/订单锁等待后确定创建时刻；商家持订单锁提交完成期间，评价定位读仍见未完成，等待后必须按当前锁读的COMPLETED/CONSUMED成功，防止复用旧定位缓存。取件/评价竞争不能提前评价；持经营账号、文件行/预约日期保护行及实例文件互斥时评价仍完成，无这些资源锁或IO调用。本人评价GET与订单详情的RR快照在并发评价提交后仍返回原快照，下次请求看到新记录，普通读取不阻止提交。

原预约履约、打印取件/创建、预约/打印管理员异常及公开商家目录回归通过；公开评分仍null/0阶段值，未开放公开评价，未宣称真实统计。测试没有访问用户正式数据库、持久上传根或修改VS Code凭据，成功临时实例及文件由脚本停止清理。两份OpenAPI58/62/58/4、84/96模型、唯一分组/引用、文档链接及V13打包检查同步完成。Swagger/Apifox流程供用户复现，未计入已执行客户端人工操作。
