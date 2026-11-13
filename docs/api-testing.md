# 浏览器与 Apifox 接口验收

M03.2-B新增“11 本人通知”两个入口；[通知指南](notifications.md)使用学生Authorize、本人分页及首次已读；未代用户人工点击，前端暂停。


2026-10-30 M03.2-A没有新接口分组。继续用原Swagger/Apifox接单和待取件入口观察订单，用[指南](reliable-messaging.md)的本机Broker界面/分类日志观察发布；学生通知消费与列表/已读尚未实现，不能用SENT判定学生已收到。


2026-10-30 M08.2-C增加POST /booking-quotes，并给打印报价、两类下单增加userCouponId。运行目录73操作，重新导入后按[订单用券指南](order-coupons.md)操作；原匿名候选保持原价，个人券报价需要学生Authorize。本轮人工点击未执行。以下为历史阶段记录。


2026-10-30新增“10 学生优惠券”分组，同步领取/本人读取及匿名活动目录，运行契约72操作。按[领取指南](coupon-claims.md)在Swagger/Apifox验证，自动结果见[报告](reports/coupon-claims-2026-10-30.md)；人工点击未记为已执行。

2026-10-29 M08.2-A：新增“09 商家优惠券活动”6个操作，运行/导入目录现为68操作；本店创建、分页/详情、完整修改、发布冻结及停止，见[点击指南](merchant-coupon-campaigns.md)和[自动验收报告](reports/coupon-campaigns-2026-10-29.md)。学生领取/订单用券未实现；本批未进行人工浏览器验收，不将HTTP自动测试写成人工点击通过。

2026-10-27：用户改选[预约即时隔离自动验收](reports/booking-immediate-2026-10-27.md)，四条HTTP旅程及配套检查合计190用例通过，临时MySQL/JAR检查与清理完成；测试Clock推进、运行规则不变。[浏览器预约记录](manual-booking-acceptance.md)仍只反映已执行的人工准备，不将自动结果填写为人工17项通过。

2026-10-27：[打印整链界面验收](manual-print-acceptance.md)16/16项通过，覆盖配置至取件评价、双方事件及跨账号隔离。主要动作经Swagger，管理员审核/首次文件选择及最后一次完成后本人下载由用户本机操作；用户确认该下载返回200且完成，不与自动回归用例数混算。

2026-10-26 V1后端全量回归通过：55个测试类、2675个用例，失败/错误/跳过均0；62个已实现接口、V1～V15迁移和23张业务表。真实MySQL、两类业务整链、mysql/web-only可执行包启动及最新存量升级均已核对，见[完整验收报告](reports/v1-acceptance-2026-10-26.md)。人工演示、性能与部署验证在报告中单独列为后续事项，不能将自动回归等同于公开上线。 人工点击可从[预约整链](booking-journey.md)或[打印整链](print-journey.md)开始。

2026-10-26 打印整链验收：[从入驻到打印取件评价](print-journey.md)提供PDF multipart上传、整数分报价、价目版本、阶段与下载授权、退款及文件保护点击流程。本轮196个用例通过，新增PrintJourneyMysqlIT6并回归预约旅程4；仍62操作、V1～V15/23表。

2026-10-26 预约整链验收：[从入驻到评价的点击指南](booking-journey.md)提供三种角色切换、四类版本、接单期限与等待后的重新登录步骤。新增BookingJourneyMysqlIT四个真实HTTP场景，本轮190个用例通过；仍62操作、V1～V15/23表，无新业务规则或时间调试入口。

2026-10-26 当前交付：账号停用/恢复按[ADR-0046](decisions/0046-admin-account-status.md)接入。禁止修改本人，双账号按ID排序主键锁后复核正常ADMIN；实际变化时状态/version+1、目标全部旧会话撤销、状态历史原子提交，恢复须重新登录。旧版本409，同版本同状态200无写入；不自动处理订单/资金或改文件期限。V15新增account_status_change，23表；运行62、完整62/62/0，当前规划入口齐全。见[Swagger / Apifox指南](admin-account-status.md)；旧阶段“账号状态待开发”以本次为准，接口齐全不等于公开部署或全部工程工作完成。

2026-10-26 当前交付：管理员账号列表按[ADR-0045](decisions/0045-admin-account-list.md)接入。正常ADMIN查询全部业务账号，角色/状态交集、分页、创建时间/ID倒序，仅六字段AccountView；身份/会话与count/items同一只读RR快照，返回前核对到期，无业务行锁或写入。运行61、完整62/61/1，仍V1～V14/22表，无新迁移；账号停用/恢复尚未实现。见[Swagger / Apifox指南](admin-account-list.md)，旧阶段“账号列表待开发”以本次交付为准。

2026-10-26 当前交付：管理员隐藏评价按[ADR-0044](decisions/0044-admin-review-hide.md)接入。正常ADMIN说明原因后原子保存首次审核记录并隐藏；同人同原因重放保留事实，其他重复409。后续公开评价/真实评分排除，本人仍可读、reviewed=true且不能重发，不改订单/资金/文件期限。V14补三个审核字段，无新表，仍22表；运行60、完整62/60/2，仅管理员账号列表/状态管理待开发。见[Swagger / Apifox指南](admin-review-hide.md)，旧阶段“隐藏后续”以本次交付为准。

2026-10-26 当前公开评价扩展：[ADR-0043](decisions/0043-public-shop-reviews.md)新增匿名GET /shops/{shopId}/reviews，仅公开可见店铺VISIBLE记录、作者当前昵称；停用作者不自动隐藏。商家列表/详情rating/reviewCount改为真实全部VISIBLE均分/数量，两位HALF_UP、零条null/0，同响应短RR、页内批量统计。运行59/完整62/59/3，V1～V13/22表无新迁移，见[点击指南](public-shop-reviews.md)。旧统计阶段值说明由本次取代；隐藏入口后续。

2026-10-26 当前学生评价扩展：[ADR-0042](decisions/0042-student-order-review.md)接入POST/GET /orders/{orderId}/review，合法已付完成单一单一评，详情reviewed真实查询且隐藏也true；评价独立，不改订单版本/状态事件/资金/文件。V13/22表，运行58、完整62/58/4，见[点击指南](student-order-review.md)。公开评价/真实评分统计及隐藏入口后续；旧阶段固定reviewed=false的说明由本次取代。

2026-10-26 当前管理员PRINT扩展：[ADR-0041](decisions/0041-admin-print-exception.md)使原管理员详情/核实关闭支持两类订单；PRINT已付未结束四种阶段可全额模拟退款为CLOSED/REFUNDED/ABORTED，保留已发生时间，reason+expectedVersion防覆盖取件/履约新事实。全部文件引用保护、下载授权及重放沿已确认边界，见[点击指南](admin-print-exception.md)。运行仍56、完整62/56/6，无新迁移；旧阶段管理员仅BOOKING的描述以本次为准。

2026-10-25 当前取件扩展：[ADR-0040](decisions/0040-student-print-pickup.md)新增学生本人POST /orders/{orderId}/pickup，正常打印单可完成；COMPLETED/PAID/PICKED_UP与完成/取件时间、版本/事件、全部引用期限原子提交。合法终态pickup及商家start/ready可重试，不续期/回退，完成单撤销商家下载授权来源。运行56、完整62/56/6，见[点击验收](student-print-pickup.md)。下方旧阶段取件待开发、完成后start/ready拒绝的描述以本次为准。

打印开始与待取件现按[点击指南](merchant-print-fulfillment.md)验收：重启后原start支持PRINT，新增ready-for-pickup，同文件履约中仍可商家下载。运行55个操作，Apifox重新导入运行快照；待取件尚不代表完成，学生取件后续推进。

商家PDF内容现可按[商家下载指南](merchant-print-download.md)在“04 文件与打印报价”的原content入口读取，仅本店有合格已接单未开始PRINT才授权。重启并重新导入运行快照，仍54个操作；订单取消后保留文件不等于继续授权商家。

已接单打印可按[商家打印取消指南](merchant-print-cancellation.md)在“07 商家订单履约”的原cancel入口说明原因退款，见ADR-0037。重启并重新导入运行快照即可；运行仍54个操作，打印内容下载及正常履约尚未开放。

商家打印列表/详情/事件及接单/拒单现已按[商家打印指南](merchant-print-confirmation.md)扩展，重启后继续用“07 商家订单履约”原入口；Apifox重新导入运行快照，运行仍54个操作。下方旧阶段“商家打印后续”以本次指南为准，内容访问与打印后续履约尚未开放。

打印付款、待接单取消和超时退款已接入原支付入口，按[打印支付指南](print-payments.md)点击验收；运行仍54个操作，重新导入运行快照即可，无新增支付路径。付款无请求体/金额，打印商家接单及内容权限下轮推进。

新增打印创建及学生两类订单历史按[打印订单点击指南](print-orders.md)验收。先报价复制实际价目版本和金额，再确认商家当前上海营业；V12随原MySQL启动自动迁移。Apifox重新导入运行快照，完整规划仍保留8个未实现操作。

人工验收推荐使用本页流程，在浏览器填写 JSON 并点击发送。原有 PowerShell 请求示例保留供参考。

## 启动和打开页面

在原运行窗口按 Ctrl+C 停止旧应用，再用原来的方式启动新代码。数据库密码仍只在本机输入：

```powershell
.\scripts\database.ps1 -Action Run
```

启动成功后打开 [Swagger 接口调试页](http://127.0.0.1:8080/swagger-ui/index.html)，也支持 [swagger-ui.html 入口](http://127.0.0.1:8080/swagger-ui.html)。调整过端口时，把链接中的 8080 一并替换。

页面按系统、账号认证、本人资料、商家入驻、文件与报价、学生订单、经营配置、商家履约和管理员九组组织，显示已实现的 62 个操作。展开接口，点击 **Try it out**，填写请求，再点击 **Execute**。实际结果看 **Server response** 下的状态和 Response body；下方 Responses 列表是说明与示例。也可使用 VS Code 的 CampusLife (MySQL) 配置启动。

新增商家“分类 → 申请 → 登录 → 本人待审店铺”的点击流程见 [入驻验收](merchant-onboarding.md)。管理员初始化和审批见 [审核验收](admin-shop-review.md)，被拒后修改重提见 [重提验收](merchant-resubmission.md)。

审核通过后，进入“06 商家经营配置”修改资料和营业开关，按 [经营验收](merchant-operation.md) 操作；只传需要修改的字段，版本取 GET /merchant/shop。每周时段按 [营业时间验收](merchant-business-hours.md) 配置，PUT 是整周替换。

本店服务的查询、新增、修改与上下架按 [服务验收](merchant-service-catalog.md) 操作。价格单位是分；修改时使用该服务返回的 version，与店铺版本独立。打印规格价格按 [价目验收](merchant-print-prices.md) 配置，每条价目又有自己的版本；批量保存不是整表替换。理发师按 [人员与绑定验收](merchant-barbers.md) 操作：姓名/启停与绑定共用人员版本，绑定 PUT 则是完整替换集合。

请求会读写本机数据库中的业务账号。使用下文虚构测试账号即可，平台登录密码与启动时的 MySQL 密码是两回事。

## 注册并登录

打开“01 账号认证 → POST /api/v1/auth/register”，点击 Try it out，填写：

```json
{
  "username": "demo_api_01",
  "password": "DemoPass_123!",
  "nickname": "接口验收同学"
}
```

点击 Execute，首次应为 HTTP 201 / OK。若返回 409 / USERNAME_EXISTS，换一个用户名，或使用你已知密码的现有虚构账号登录。注册不会自动登录。

再打开“POST /api/v1/auth/login”，填写同一账号：

```json
{
  "username": "demo_api_01",
  "password": "DemoPass_123!"
}
```

成功应为 HTTP 200。从实际响应中复制 **data.accessToken 的值**，不含两侧双引号。

点击页面上方 **Authorize**，在 bearerAuth 输入框粘贴 token 本身，点击弹窗中的 Authorize，再 Close。不要自己添加 `Bearer ` 前缀，页面会自动添加。刷新页面后授权不会持久保存，需要重新填写。

注册、登录、健康检查可匿名使用；本人资料、改密、退出需要这个凭证。不要把 token 或完整登录响应发到聊天。

## 读取资料和修改昵称

打开“02 本人资料 → GET /api/v1/me”，点击 Try it out、Execute。记录实际响应 data.version，例如字符串 **"0"**；已有账号可能是其他版本，以刚读取的响应为准。

打开“PATCH /api/v1/me”，把读取到的版本填入 expectedVersion：

```json
{
  "nickname": "星海新昵称😀",
  "expectedVersion": "0"
}
```

版本带双引号，是字符串。点击 Execute，应返回 HTTP 200 / OK，昵称改变，version 从 "0" 增加为 "1"。如果本来就是这个昵称，版本不会增加，换一个新昵称再验证。

## 验证旧页面不能覆盖

保持旧 expectedVersion 不变，只修改 nickname，再点击 Execute：

```json
{
  "nickname": "旧页面尝试覆盖",
  "expectedVersion": "0"
}
```

若上一轮发生了实际修改，这次应返回 **HTTP 409 / CONFIG_VERSION_CONFLICT**，这是预期的保护结果。再次 GET /me，应仍是第一次成功的昵称，版本没再次增加。

如果决定继续修改，先读取最新版本，再填入 PATCH 请求。不要自动重试覆盖，也不要随意猜版本。

## 其他验收

| 验收项 | 操作 | 预期 |
|---|---|---|
| 未登录保护 | 在 Authorize 弹窗中 Logout，执行 GET /me | 401 / AUTH_REQUIRED |
| 非法昵称 | 授权后，把 PATCH 的 nickname 填为空字符串或 null | 400 / VALIDATION_FAILED，资料不变 |
| 退出当前设备 | POST /auth/logout，无请求体 | 200；再 GET /me 为 401 |
| 修改密码 | POST /me/password，填写当前密码、新密码 | 200、data=null；全部设备旧 token 失效 |

改密成功后，用新密码登录取得新 token，再通过 Authorize 的 Logout 清除旧值并重新 Authorize。退出接口不会自动清除输入框中的 token，因此退出后资料调用返回 401 是正常结果。

## 导入 Apifox

**想一次查看包含待开发业务的完整目录：**导入 [campuslife.v1-design.openapi.json](openapi/campuslife.v1-design.openapi.json)，共 62 个操作（36 已实现、26 待开发），详见 [完整规划说明](api-design.md)。下面的运行快照有 36 个已实现操作，两者用途不同。

可导入文件：[campuslife.openapi.json](openapi/campuslife.openapi.json)，磁盘位置为 D:\campuslife\docs\openapi\campuslife.openapi.json。文件从实际运行应用生成，仅把随机测试端口规范化为默认 8080，不含实际账号、密码或 token。

推荐使用 Apifox 桌面客户端：

1. 团队项目页面选择“导入项目”，格式选 **OpenAPI / Swagger**。
2. 选择上面的 JSON 文件；维护方式可选“以可视化界面维护接口”，按预览完成导入。
3. 检查本机环境服务地址为 **http://127.0.0.1:8080**，不要用 Mock 地址。启动 CampusLife 后才能调试。
4. 发送注册、登录请求，复制实际响应的 data.accessToken。
5. 在 GET /me、PATCH /me 等受保护接口的 **Auth / 认证** 中选择 **Bearer Token**，粘贴 token 本身。导入时若支持“导入鉴权组件”，可一并勾选；注册、登录无需配置登录凭证。
6. 按上文填写 JSON 并点击发送，验证成功和版本冲突。

也可尝试 URL 导入 [运行中的 OpenAPI 地址](http://127.0.0.1:8080/v3/api-docs)。工具无法访问本机地址时使用文件导入；通过远端服务抓取时无法访问你电脑的 127.0.0.1。导入文档不会自动启动应用。

后续接口变化后，运行中的 /v3/api-docs 是最新来源，重新导入更新定义。本地文件是本次已验证的快照；新增接口时同步更新注解、文件和验收说明。

## 常见情况

- 连接失败：检查应用是否启动、端口是否正确。
- Swagger 入口 401 / 404：确认停止旧进程并加载新代码；默认 mysql 模式提供文档，web-only 诊断模式关闭文档。
- Failed to load API definition：打开 /v3/api-docs 检查响应，确认没有设置 API_DOCS_ENABLED=false。
- GET /me 返回 401：先登录，将实际 token 填入 Authorize，检查是否已退出、改密或超过 2 小时。
- PATCH 返回 409：重新 GET /me 获取最新版本，由你决定是否继续修改。

## 配置与验证记录

使用 springdoc-openapi 3.1.1、OpenAPI 3.0 与 Swagger UI。默认本地 mysql 模式可匿名读取页面和定义，业务接口保留原鉴权。API_DOCS_ENABLED=false 同时关闭定义和页面，web-only 默认关闭。密钥、数据库密码和服务端认证参数不进入定义，页面不持久保存授权，并关闭远程 Swagger 校验。

2026-10-15：Windows PowerShell 5.1、Java 21、独立 MySQL 8.0.45 全流程 140 个用例全部通过，0 failures / errors / skipped，mysql 模式 jar 检查通过。新增 OpenApiMysqlIT 3 个，检查实际定义、字段类型、Bearer 标记、页面资源和示例的注册→登录→读取→修改→409；ApiDocsDisabledMysqlIT 1 个检查关闭后匿名访问 401、已登录访问 404。

未代用户登录或上传到 Apifox，未声称完成 Apifox 客户端导入测试。开发者验证仍使用 scripts/test-mysql.ps1，人工验收不要求手写 PowerShell 请求。

导出检查发现 springdoc 会裁剪最初未被引用的自定义错误模型，已调整为在补充错误响应引用时一并注册。增加所有 schema 引用可解析的断言，并校准 data=null 和版本正则。完整 140 个用例通过后，这些最终文档调整又运行了 42 个常规测试、4 个文档专项 MySQL 用例与 jar 检查，全部通过；业务用例没有因纯文档模型调整重复执行。最终快照与最新实际导出一致。

参考：[springdoc 官方文档](https://springdoc.org/)、[Apifox 新建与导入项目](https://docs.apifox.com/create-api-project)、[Apifox 导入设置](https://docs.apifox.com/import-settings)。

理发师按日排班与营业时间冲突验收见 [排班指南](merchant-schedule.md)，两个接口位于“06 商家经营配置”。运行版当前 51 个操作，完整规划仍为 62。

公开商家目录三个 GET 无需 Authorize，位于“03 商家入驻”。先清除旧 token 再按[公开目录验收](public-shop-catalog.md)查询列表、详情与上架服务；可见店铺停业仍展示，openNow 不代表可预约。

公开理发师 GET /shops/{shopId}/barbers 位于“03 商家入驻”，无需授权；步骤见[公开理发师指南](public-barbers.md)。可选 serviceId 筛选人员，但不会截断该人员全部公开服务 ID；运行版当前共 51 个操作。

预约候选时段 GET /shops/{shopId}/booking-slots 位于“03 商家入驻”，匿名访问；填写实际 shopId、serviceId、barberId 和上海今天至第 6 天的 date，步骤见[候选时段验收](booking-slots.md)。当前已扣除 HELD/ACTIVE/CONSUMED 真实占用；不要将返回的候选当作已预留资源。

待支付预约三个接口位于“05 学生订单”，按[订单点击验收](booking-orders.md)完成学生 A 创建/重放、B 重叠冲突、取消/超时释放。候选已扣真实占用；当前51个操作，模拟支付、商家履约及管理员异常退款均已实现，按对应指南验证。

预约模拟支付、重复付款、待接单取消及超时全额退款按[支付验收](booking-payments.md)点击测试。支付不填请求体，金额使用冻结快照；本机 VS Code 开发项显式开启模拟资金，公共默认关闭。商家接单/拒单已按ADR-0024交付，预约开始/完成已按ADR-0026实现。

本店预约详情、接单和拒单位于“07 商家订单履约”，按[商家确认验收](merchant-booking-confirmation.md)切换商家/学生token测试。接单无请求体，拒单填写reason；学生已接单预约距开始至少1小时可全额退款取消。商家可先在本店列表中筛选并复制订单ID，见[商家列表验收](merchant-order-list.md)。

商家已接单但无法履约，在预约开始前通过POST /merchant/orders/{orderId}/cancel说明原因全额退款，见[取消验收](merchant-booking-cancellation.md)。这条入口不受学生提前1小时限制；等于/超过预约开始409且无写入，同原因成功重放不重新判断窗口或重复退款。

预约开始/完成位于“07 商家订单履约”，见[履约验收](merchant-booking-fulfillment.md)。两动作不填请求体，开始仅在[startAt,endAt)；完成可提前或晚于原结束，保留原区间CONSUMED。人工需等待预约实际开始，精确时间由自动测试覆盖，不提供绕过时间的HTTP入口。

管理员核实预约异常与全额退款按[点击指南](admin-booking-exception.md)操作：先GET详情复制订单version，再提交原因与expectedVersion；未开始释放、开始后保留原区间。仅ADMIN，不是MySQL用户。

学生在“05 学生订单”使用GET /orders，按[列表点击指南](student-order-list.md)筛选本人预约状态、翻页并取得订单ID；PRINT暂空页，列表不会自动退款关单。

商家在“07 商家订单履约”使用GET /merchant/orders查本店预约；学生不能使用商家列表。按[商家列表点击指南](merchant-order-list.md)核对权限、状态和分页，暂停店铺仍可读历史，GET不自动退款关单。

学生本人及商家本店订单事件接口按[事件点击指南](order-events.md)验证，分别在05/07分组，按eventSeq升序返回完整原记录。操作者ID和原原因对关联双方可见，GET不自动退款关单。

在“04 文件与打印报价”按[PDF上传指南](print-file-upload.md)使用选择文件按钮上传，并查询本人元数据；本轮无内容下载/商家文件权限/报价/打印下单。测试样例见指南，不需手写PowerShell请求。

2026-10-23：本人PDF下载按ADR-0032开放，Swagger使用Authorize后调用GET /files/{fileId}/content并保存二进制响应；Apifox重新导入运行快照。完整步骤与实际验证见[下载验收](file-download.md)。地址栏不自动携带Bearer凭证，不能以直接打开路径判断已授权下载是否可用。

2026-10-24：POST /print-quotes按ADR-0033交付，先由获批打印商家配置上架服务及启用规格，再由学生上传本人PDF、复制实际serviceId/fileId报价。完整点击步骤、80分样例与改价验证见[报价验收](print-quotes.md)，无需手写PowerShell请求；两份OpenAPI已同步。

M08.3-B：默认同步领取点击流程沿[优惠券指南](coupon-claims.md)，新增状态/门闩、真实队列及存量升级验证见[底座指南](async-coupon-foundation.md)。PENDING schema代表可读取的持久状态，当前没有首次公开异步受理/模式启用入口。

M08.3-C组件验收见[Redis预留与恢复指南](coupon-redis-recovery.md)：真实Redis/MySQL/RabbitMQ及双实例缓存隔离已验证，C没有用于手工造PENDING的HTTP。Swagger沿既有同步流程核对兼容性，首次公开异步受理由D接入；接口75操作数不能表示D已完成。

M08.3-D：公开ASYNC已在原POST接通，202/Location、原键与本机维护操作见[点击及维护指南](async-coupon-admission.md)。75操作不变；导入最新快照。默认SYNC/依赖关闭，本轮真实HTTP自动化通过，浏览器人工点击未标完成。前面B/C段落为当时范围。
