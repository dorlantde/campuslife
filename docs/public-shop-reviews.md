# 公开评价与商家评分验收：Swagger / Apifox

2026-10-26 后续扩展：管理员隐藏评价已按[ADR-0044](decisions/0044-admin-review-hide.md)实现，详见[操作指南](admin-review-hide.md)。原文较早“隐藏入口后续”现以本扩展为准；本页历史验证记录保留原任务范围。

按[ADR-0043](decisions/0043-public-shop-reviews.md)及[确认方案](public-shop-reviews-design.md)实现。重启原VS Code **CampusLife (MySQL)** 配置，无新迁移、凭据或数据库重建；仍V1～V13、22张业务表。运行59个操作，完整规划62/已实现59/待开发3。

## 1. 准备真实评价

打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，或在Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，基础地址使用实际本机端口。

按[学生评价指南](student-order-review.md)实际完成预约或打印单，并以本人学生提交评价。记录真实shopId。店铺须获批准、OWNER和经营账号有效；暂停或经营资格失效的店铺不公开，不能用评价入口绕过。

完成写入后可清除Authorize中的token测试匿名GET。若主动携带错误、撤销或过期token仍会失败，不会当成匿名请求处理。

## 2. 公开评价分页

在“03 商家入驻”调用新增GET `/api/v1/shops/{shopId}/reviews`，填实际shopId。默认page=1、size=20，size最大100。200的data含items、page、size、total、totalPages；按原评价时间倒序，同毫秒ID倒序，超出末页items为空。

每条items应只含：

- reviewId：评价ID字符串；不是订单ID。
- rating：原1～5整数分。
- content：保存的原文字与内部换行。
- reviewerNickname：作者当前昵称。
- createdAt：原创建时间UTC。

不出现学生/订单ID、登录名、联系方式、visibility或审核原因。只展示VISIBLE，包含预约和打印；total表示全部公开评价，不只当前页。不可见或不存在店铺404，参数格式错误400。

## 3. 核对真实平均分

匿名GET `/api/v1/shops/{shopId}`或GET `/api/v1/shops`找到同一店铺。reviewCount现在是全部VISIBLE评价真实数量，rating为这些记录的平均分，十进制两位小数四舍五入。

例如同店三条评分5、4、4，应4.33/count3；5、5、4应4.67。没有公开评价的店铺为null/0，已经不是“统计未接入”的占位值。JSON不保证补齐末尾零，4、4.0和4.00数值等价。

不要为了验收直接修改数据库批量制造评价。自动测试覆盖多评分与1.125→1.13等边界；人工可分别完成多笔真实订单并评价后观察变化。

## 4. 昵称、停用与隐藏边界

评价学生使用已有PATCH `/api/v1/me`修改昵称后，再匿名读取，历史评价应显示新昵称；评分、文字、评价时间与评价ID不变。作者停用或角色变化不会自动删除/隐藏历史VISIBLE评价或扣统计；该账号自身登录/操作仍受限制。

HIDDEN不展示或计入平均分，本人仍可查看原评价，reviewed=true且不能重发。本轮尚无管理员隐藏入口，不要求人工修改数据库来验证；下一项接入隐藏动作后按同一口径生效。

店铺本身暂停/经营资格失效则评价整体不可见；停业、休息或无上架服务仍可公开浏览。不同请求可能看到不同时间的数据，单次响应内部保持快照一致；分页期间新评价可能使后续页移动，不是跨页固定快照。

| 返回 | 含义 |
|---|---|
| 200 | 可见店铺的评价页或真实统计，即使零条也成功 |
| 400 VALIDATION_FAILED | 非规范shopId、分页格式或范围不正确 |
| 401/403 | 主动携带无效会话/停用账号凭据；不带token可匿名GET |
| 404 RESOURCE_NOT_FOUND | 不存在或不符合公开店铺资格 |
| 503 SERVICE_TEMPORARILY_UNAVAILABLE | 数据库暂时竞争，可重新请求；不伪装成没有评价的null/0 |
| 500 | 其他内部读取失败，不返回虚构统计 |

本轮没有评分排序、图片、回复、编辑、自动审核或额外内容处理。点击流程无需手写PowerShell请求、改时钟或数据库状态。

## 自动验证记录

2026-10-26：本轮各套件最终报告去重 **399个定向用例通过**（普通/Web172、真实MySQL/HTTP227，新增公开评价62）；最终报告零失败/错误/跳过。V1～V13迁移及mysql模式JAR健康、UTF-8、trace和鉴权启动检查通过；运行59、完整62/59/3，无新迁移。未重跑全部历史业务IT。

首轮3个数据库暂时竞争注入用例预期误写500，而项目已有全局处理实际返回503；修正测试预期为503/SERVICE_TEMPORARILY_UNAVAILABLE，并同步三个公开响应的错误文档，未改变既有异常处理行为。最终定向78个通过（Web13、公开评价62、OpenAPI3）；其他回归套件首轮通过，重复执行不重复累加。

实际命令，均使用独立临时MySQL及自有文件根：

```powershell
$env:MAVEN_ARGS='-Dit.test=PublicShopReviewsMysqlIT,PublicShopCatalogMysqlIT,OrderReviewsMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90

$env:MAVEN_ARGS='-Dtest=WebFoundationTest -Dit.test=PublicShopReviewsMysqlIT,OpenApiMysqlIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

日志：`.cache/public-reviews-mysql.log`保留首次错误预期诊断；`.cache/public-reviews-mysql-final.log`为最终复验和JAR启动记录。

| MySQL/HTTP套件 | 本轮最终通过数 |
|---|---:|
| PublicShopReviewsMysqlIT | 62 |
| PublicShopCatalogMysqlIT | 61 |
| OrderReviewsMysqlIT | 90 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |

新增正向用例实际完成BOOKING/PRINT并提交评价，匿名读取五字段、当前昵称、原文字/时间，商家列表/详情显示真实数量与平均分，原订单生命周期和本人评价不变。批量、精度、隐藏、角色与分页边界使用明确的测试SQL存量评价样本；不把这些只读样本伪装成业务写入验收。4.33/4.67、1.125→1.13、单条1/5、零条/全隐藏、跨店、同毫秒ID倒序、长分页偏移、参数与资格优先级均验证通过。

已有PATCH /me实际修改昵称后，历史公开展示更新，评价ID/时间不变；作者停用/角色变化仍公开并统计，HIDDEN不公开但本人仍可见/reviewed且不能重发。店铺获批/成员/经营账号资格、停业/休息/无服务/分类停用、精确匿名GET及无效凭据拒绝均覆盖。多店目录只调用一次页内批量统计，空页不查评分；未声称未经测量的性能提升。

真实MySQL RR验证评价页在新增/隐藏/改名/店铺暂停时资格/count/items/昵称仍同旧快照，列表/详情在新增/隐藏/经营账号停用时资格与统计同快照，下一请求更新。公开读暂停期间正常学生实际评价提交不受阻；持账号/店铺/订单/文件行锁和实例文件互斥时公开读仍完成，不调用磁盘/实例锁。数据库暂时竞争正确503，data=null，不静默返回虚构null/0。

原公开目录61项、本人评价90项及MySQL11项/接口3项回归通过；没有新增隐藏或账号管理接口。首次诊断实例停止后只清理其自有目录，最终成功实例由脚本停止清理，未访问用户正式数据/持久上传根或修改VS Code凭据。两份OpenAPI59/62/59/3、87/97模型、唯一分组/引用、文档链接和不变迁移打包核对同步完成。Swagger/Apifox流程供用户复现，尚未计入已执行客户端人工操作。
