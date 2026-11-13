# V1 接口契约

2026-11-02 M04.2-B：文件后端增加显式LOCAL/S3路由，HTTP路径、FileView、权限、期限及错误码保持，仍75操作；不返回对象地址/存储定位，不增加直传或调试接口。已有S3后端关闭/不可达/定位不匹配时503，不回退LOCAL；元数据及原订单纯数据库动作不依赖S3可用。见[指南](s3-file-storage.md)/[报告](reports/s3-file-chain-2026-11-02.md)。OpenAPI注解与导出契约没有字段或路径变化。以下为历史阶段记录。

2026-10-31当前：M08.3-D已验证，75操作/V23/32表，原POST /coupon-claims支持受控ASYNC首次202/Location、原键PENDING202/终态200；默认SYNC201/200。[公开受理/维护指南](async-coupon-admission.md)及[报告](reports/async-coupon-admission-2026-10-31.md)为最新状态；以下带批次/日期记录保留历史边界。

M03.2-B：当前75操作/V21/31表，通知消费按已确认ADR-0053接入；异步领取与前端消息页仍未实现。

| 方法 | 路径 | 权限与返回 |
|---|---|---|
| GET | /notifications | 正常学生本人，page/size/read；短RR，落库起90天展示；不标已读 |
| PATCH | /notifications/{notificationId}/read | 正常学生本人，无请求体，重复保留首次时间；他人/不存在/展示期外404 |


2026-10-30 M03.2-A无新增HTTP，当前仍73操作，V20/29表；预约接单/打印待取件首次成功同事务记录消息，原响应/合法重试保持。RabbitMQ异步发布不代表本人通知已落库；列表/已读留B批。见[ADR-0053](decisions/0053-reliable-order-messaging.md)和[指南](reliable-messaging.md)。


2026-10-30 M08.2-C：订单用券按[ADR-0052](decisions/0052-order-coupons.md)接入。V19新增order_coupon及持有券状态/指针/版本，当前73操作/28表；测试证据与人工步骤见[指南](order-coupons.md)。以下A/B为历史阶段记录，当前同步领券和两类用券均已实现；Lua/MQ仍未实现。

| 方法 | 路径 | 权限与返回 |
|---|---|---|
| POST | /booking-quotes | 正常学生，serviceId＋可选userCouponId；RR原价/优惠/实付、服务版本，不锁券或时段 |

现有POST /print-quotes、/orders/bookings、/orders/prints增加可选userCouponId（规范正BIGINT字符串；省略/null不用券）；创建期望金额为用券后实付。本人/商家/管理员详情增加nullable coupon冻结规则及处理事实；列表原三金额字段保持。本人券列表/领取结果反映LOCKED/USED，不因已付款退款返券。


2026-10-30 M08.2-B增加同步领券4操作，当前72操作；V18新增成功请求与持有券两表，共27表。规则见[ADR-0051](decisions/0051-synchronous-coupon-claims.md)，使用见[点击指南](coupon-claims.md)。订单用券/异步未实现。

| 方法 | 路径 | 权限与返回 |
|---|---|---|
| GET | /shops/{shopId}/coupon-campaigns | 匿名可见店铺当前窗口活动；分页含售罄，剩余仅快照 |
| POST | /coupon-claims | 正常学生；独立领取键＋campaignId，首次201/原事实重放200 |
| GET | /coupon-claims/{claimId} | 正常学生本人持久SUCCEEDED，跨账号404 |
| GET | /me/coupons | 正常学生本人分页，shopId/state可选；过期派生，无订单核销 |

2026-10-29 M08.2-A：商家优惠券活动按[ADR-0050](decisions/0050-coupon-business.md)增加6个操作，详细字段/时序及点击流程见[活动指南](merchant-coupon-campaigns.md)。本店草稿/详情/分页/修改/发布/停止，活动版本独立；V17新增活动一表。学生领券、报价/订单用券、Lua及MQ尚未接入；以下V1历史记录不代表这些营销能力已经实现。

| 方法 | 路径 | 权限与返回 |
|---|---|---|
| GET | /merchant/coupon-campaigns | 正常MERCHANT、ACTIVE OWNER本店分页；默认1/20，size至100，RR快照 |
| POST | /merchant/coupon-campaigns | 本店APPROVED，完整八字段创建DRAFT；201，重复会新建草稿 |
| GET | /merchant/coupon-campaigns/{campaignId} | 本店详情，跨店/不存在404；暂停仍可读 |
| PATCH | /merchant/coupon-campaigns/{campaignId} | 获批本店DRAFT，完整八字段＋活动expectedVersion；同版本无变化不写 |
| POST | /merchant/coupon-campaigns/{campaignId}/publish | 获批本店DRAFT，expectedVersion；锁后严格早于领取结束，发布冻结规则 |
| POST | /merchant/coupon-campaigns/{campaignId}/stop | 本店PUBLISHED，expectedVersion；暂停仍可，首次记录/同原版本同人重放 |

2026-10-26 当前交付：账号停用/恢复按[ADR-0046](decisions/0046-admin-account-status.md)接入。禁止修改本人，双账号按ID排序主键锁后复核正常ADMIN；实际变化时状态/version+1、目标全部旧会话撤销、状态历史原子提交，恢复须重新登录。旧版本409，同版本同状态200无写入；不自动处理订单/资金或改文件期限。V15新增account_status_change，23表；运行62、完整62/62/0，当前规划入口齐全。见[Swagger / Apifox指南](admin-account-status.md)；旧阶段“账号状态待开发”以本次为准，接口齐全不等于公开部署或全部工程工作完成。

2026-10-26 当前交付：管理员账号列表按[ADR-0045](decisions/0045-admin-account-list.md)接入。正常ADMIN查询全部业务账号，角色/状态交集、分页、创建时间/ID倒序，仅六字段AccountView；身份/会话与count/items同一只读RR快照，返回前核对到期，无业务行锁或写入。运行61、完整62/61/1，仍V1～V14/22表，无新迁移；账号停用/恢复尚未实现。见[Swagger / Apifox指南](admin-account-list.md)，旧阶段“账号列表待开发”以本次交付为准。

2026-10-26 当前交付：管理员隐藏评价按[ADR-0044](decisions/0044-admin-review-hide.md)接入。正常ADMIN说明原因后原子保存首次审核记录并隐藏；同人同原因重放保留事实，其他重复409。后续公开评价/真实评分排除，本人仍可读、reviewed=true且不能重发，不改订单/资金/文件期限。V14补三个审核字段，无新表，仍22表；运行60、完整62/60/2，仅管理员账号列表/状态管理待开发。见[Swagger / Apifox指南](admin-review-hide.md)，旧阶段“隐藏后续”以本次交付为准。

2026-10-26 当前公开评价扩展：[ADR-0043](decisions/0043-public-shop-reviews.md)新增匿名GET /shops/{shopId}/reviews，仅公开可见店铺VISIBLE记录、作者当前昵称；停用作者不自动隐藏。商家列表/详情rating/reviewCount改为真实全部VISIBLE均分/数量，两位HALF_UP、零条null/0，同响应短RR、页内批量统计。运行59/完整62/59/3，V1～V13/22表无新迁移，见[点击指南](public-shop-reviews.md)。旧统计阶段值说明由本次取代；隐藏入口后续。

2026-10-26 当前学生评价扩展：[ADR-0042](decisions/0042-student-order-review.md)接入POST/GET /orders/{orderId}/review，合法已付完成单一单一评，详情reviewed真实查询且隐藏也true；评价独立，不改订单版本/状态事件/资金/文件。V13/22表，运行58、完整62/58/4，见[点击指南](student-order-review.md)。公开评价/真实评分统计及隐藏入口后续；旧阶段固定reviewed=false的说明由本次取代。

2026-10-26 当前管理员PRINT扩展：[ADR-0041](decisions/0041-admin-print-exception.md)使原管理员详情/核实关闭支持两类订单；PRINT已付未结束四种阶段可全额模拟退款为CLOSED/REFUNDED/ABORTED，保留已发生时间，reason+expectedVersion防覆盖取件/履约新事实。全部文件引用保护、下载授权及重放沿已确认边界，见[点击指南](admin-print-exception.md)。运行仍56、完整62/56/6，无新迁移；旧阶段管理员仅BOOKING的描述以本次为准。

2026-10-25 当前取件扩展：[ADR-0040](decisions/0040-student-print-pickup.md)新增学生本人POST /orders/{orderId}/pickup，正常打印单可完成；COMPLETED/PAID/PICKED_UP与完成/取件时间、版本/事件、全部引用期限原子提交。合法终态pickup及商家start/ready可重试，不续期/回退，完成单撤销商家下载授权来源。运行56、完整62/56/6，见[点击验收](student-print-pickup.md)。下方旧阶段取件待开发、完成后start/ready拒绝的描述以本次为准。

当前打印履约扩展（2026-10-25）：[ADR-0039](decisions/0039-merchant-print-fulfillment.md)使start支持PRINT，新增ready-for-pickup，并扩展合法IN_SERVICE任务的商家下载；运行55、完整62/55/7，见[点击指南](merchant-print-fulfillment.md)。打印待取件仍IN_SERVICE，学生取件后续实现，complete仍仅BOOKING；旧阶段“PRINT开始404/履约中无下载”以本次为准。

当前商家内容扩展（2026-10-25）：[ADR-0038](decisions/0038-merchant-print-download.md)使原GET /files/{fileId}/content支持本店有效未开始打印任务的商家下载，无新路径，运行54/完整62/待开发8。学生上传/本人元数据权限不扩展，ADMIN无默认内容权限；旧阶段“商家下载未开放”由本次规则替代。操作见[点击指南](merchant-print-download.md)。

当前打印取消扩展（2026-10-25）：[ADR-0037](decisions/0037-merchant-print-cancellation.md)使POST /merchant/orders/{orderId}/cancel支持本店已接单未开始PRINT，复用reason与OrderDetail，无新路径/迁移，运行仍54、完整62/54/8。旧阶段PRINT取消404/接单后持续保留的描述按本次扩展更新；开始、完成及管理员PRINT仍未开放。见[点击指南](merchant-print-cancellation.md)。

当前商家打印扩展（2026-10-24）：[ADR-0036](decisions/0036-merchant-print-confirmation.md)使商家列表、详情、事件、接单/拒单支持PRINT，运行54、完整62/54/8。旧阶段“商家PRINT空页/入口拒绝”的说明已由本次扩展替代；cancel/start/complete、管理员及内容读取仍未开放PRINT。见[点击指南](merchant-print-confirmation.md)。

当前扩展（2026-10-24）：[ADR-0035](decisions/0035-print-mock-payment.md)使现有POST /orders/{orderId}/payments/mock支持本人PRINT，取消支持待接单全额退款，详情/列表/事件/创建重放读取实际资金与主状态。路径总数仍54，完整62/54/8，无新表或迁移；旧阶段“打印支付未开放”的说明仅代表当时状态。见[打印支付点击指南](print-payments.md)。

当前交付（2026-10-24）：[ADR-0034](decisions/0034-print-orders.md)已实现打印待支付订单，运行54/完整62/待开发8。POST /orders/prints首次201、同键重放200及Idempotency-Replayed:true；学生本人详情、取消、列表和事件支持PRINT，省略orderType包含两类。旧阶段中PRINT空页/未实现的说明只代表当时状态，商家PRINT仍为空页。操作见[点击验收](print-orders.md)。

版本：混合实现与设计稿 0.24，2026-10-23。当前共 51 个已实现操作，覆盖系统、认证、本人资料、商家入驻/审核/重提、经营资料与营业开关、每周营业时间、本店服务、打印价目、理发师/服务绑定、按日排班、公开商家目录、公开理发师及预约候选时段、预约订单与模拟支付/履约/异常/列表/事件，以及学生PDF上传和本人元数据。入驻审核规则见 ADR-0010，服务配置见 ADR-0015，打印价目见 ADR-0016，理发师见 ADR-0017，排班及营业时间保护见 ADR-0018，公开目录见 ADR-0019，公开理发师见 ADR-0020，候选时段见 ADR-0021，待支付预约与真实占用/释放见 ADR-0022；其余未涉及规则继续保留设计稿状态。参照 [业务基线](decisions/0001-v1-baseline.md)、[数据库设计](v1-database-design.md)和[状态迁移](v1-state-transitions.md)。

本次范围是统一协议与 V1 接口设计。2026-10-13 用户已确认“未到店、履约中异常由管理员核实后全额模拟退款并关闭订单”，异常接口据此定义。具体接口与参数仍属于实现前契约，不代表功能已经实现。

2026-10-14：用户确认学生注册规则与 MyBatis-Plus。2026-10-15：用户确认 JWT 有效期 2 小时、无 refresh token、多端独立会话、退出当前会话和停用拒绝规则，login / me / logout 已实现，见 [认证验收](authentication.md)。后续授权的改密与昵称修改任务已完成，见 [改密验收](password-change.md)与[资料验收](profile-update.md)；具体角色业务入口仍为后续契约。

## 1. 协议与字段约定

- 路径统一以 /api/v1 为前缀，以下目录省略此前缀。
- JSON 字段采用 camelCase；除上传和下载外使用 application/json。
- 认证头为 Authorization: Bearer <accessToken>。匿名接口不要求 token；携带无效 token 时统一返回 401，不忽略失败身份。
- 数据库 BIGINT 标识符对外表示为十进制字符串，例如 "101"，前端不得转为不安全的大整数 Number。version 也以十进制字符串表示。
- 金额以整数分传输，字段以 Amount 命名；V1 限制在 JSON 安全整数范围内，服务端验证乘法溢出并施加业务金额上限，不直接信任客户端金额。
- 时间用带时区的 ISO 8601 字符串，响应统一 UTC，例如 2026-10-15T02:00:00.000Z。请求时间必须携带 Z 或明确偏移量，拒绝无时区时间。
- 排班日期采用 YYYY-MM-DD，时间采用 HH:mm，明确按 Asia/Shanghai 解释，不能按浏览器所在时区解释。
- 枚举使用数据库设计中的大写字符串。主状态、支付状态、占用状态和打印进度分别返回，不混成一个状态。订单 status 的完整集合为 PENDING_PAYMENT / PENDING_ACCEPTANCE / ACCEPTED / IN_SERVICE / COMPLETED / CLOSED，筛选传入其他值为参数错误。
- JSON 请求使用明确 DTO，拒绝未声明字段，防止 role、studentId、status 等被静默绑定。密码不 trim、不转小写，不记录明文；登录名按同一规则归一化。
- 可变字段 PATCH：未传不修改；非空字段传 null 为参数错误。配置存在 version 时，请求携带 expectedVersion，版本过期返回 CONFIG_VERSION_CONFLICT。
- 身份、店铺成员关系和操作者从服务端会话取得。订单请求不接收 studentId；商家管理请求不接收可替代本人店铺的 shopId。

成功响应统一如下；data 内结构因接口不同而变化：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "101",
    "role": "STUDENT",
    "nickname": "星海同学",
    "version": "0"
  },
  "traceId": "c48c3b0e-95b8-4e32-a1c4-83bdbf3ac607"
}
```

创建资源首次成功使用 HTTP 201，其他成功默认 200。无返回内容时 data = null，V1 不混用 204 和 JSON 包装。第一次创建与幂等重放分别为 201 / 200，重放响应头带 Idempotency-Replayed: true。

所有订单创建必须携带 Idempotency-Key，长度 1～64，限定 ASCII 字母、数字、短横线、下划线，建议 UUID。网络失败重试使用原键和原请求内容；确认修改下单内容时生成新键。请求摘要包含报价版本和预期金额，错误请求不消耗永久幂等键。

分页参数 page 默认 1，size 默认 20、范围 1～100。V1 使用页码分页，列表统一返回 items、page、size、total、totalPages；金额与记录数为数值，实体标识符为字符串。超出最后一页返回空列表，不返回 404。排序固定为 createdAt DESC, id DESC，公开商家列表例外按 id ASC，客户端不能传任意 SQL 排序表达式。

```json
{
  "items": [],
  "page": 1,
  "size": 20,
  "total": 0,
  "totalPages": 0
}
```

## 2. 错误、权限与重试

业务错误使用准确 HTTP 状态，不统一以 200 包装失败。不向客户端返回 SQL、堆栈、存储密钥、原始对象键或密码摘要。

```json
{
  "code": "BOOKING_CONFLICT",
  "message": "该时间段已被预约，请重新选择",
  "data": null,
  "traceId": "5812bdcc-217a-42da-8fa9-41f0cde5fd6d"
}
```

| HTTP | code | 使用场景 |
|---|---|---|
| 400 | VALIDATION_FAILED | 字段缺失、非法枚举、未知字段、时间无时区、非法页码 |
| 400 | CURRENT_PASSWORD_INCORRECT | 改密时当前密码不匹配，原会话不受影响 |
| 400 | IDEMPOTENCY_KEY_REQUIRED | 创建订单未提供合法请求键 |
| 401 | AUTH_REQUIRED / TOKEN_INVALID / SESSION_EXPIRED / SESSION_REVOKED | 无有效会话；响应带 WWW-Authenticate: Bearer |
| 401 | LOGIN_FAILED | 用户不存在或密码错误，使用同一外部提示 |
| 403 | ROLE_FORBIDDEN / ACCOUNT_DISABLED / SHOP_NOT_APPROVED | 身份不能使用该功能或未审核商家尝试经营 |
| 404 | RESOURCE_NOT_FOUND | 资源不存在或不属于调用者可见范围 |
| 405 | METHOD_NOT_ALLOWED | 请求方法不支持，保留 Allow 响应头 |
| 406 | NOT_ACCEPTABLE | Accept 不包含支持的响应格式，失败响应使用 JSON |
| 415 | UNSUPPORTED_MEDIA_TYPE | 普通 JSON 接口收到不支持的请求类型 |
| 409 | USERNAME_EXISTS | 注册登录名已占用 |
| 409 | ACCOUNT_CREDENTIALS_CHANGED | 改密校验后摘要被其他操作改变，拒绝覆盖 |
| 409 | CONFIG_VERSION_CONFLICT | 配置被其他请求修改，需要重新读取 |
| 409 | SHOP_STATE_CONFLICT | 店铺/成员状态不允许当前审核或重提操作 |
| 409 | IDEMPOTENCY_KEY_CONFLICT | 相同创建键对应不同归一化请求 |
| 409 | BOOKING_CONFLICT / SCHEDULE_CONFLICT | 预约占用或排班修改冲突 |
| 409 | SHOP_UNAVAILABLE / SERVICE_UNAVAILABLE / BARBER_UNAVAILABLE | 已可见资源目前不能接受新订单 |
| 409 | QUOTE_CHANGED | 报价版本或金额变化，重新报价、确认并用新创建键提交 |
| 409 | ORDER_STATE_CONFLICT / CANCEL_WINDOW_CLOSED / BOOKING_TIME_INVALID | 操作不满足状态或时间条件 |
| 409 | PAYMENT_DEADLINE_PASSED / ACCEPTANCE_DEADLINE_PASSED | 锁内判定操作期限已到 |
| 409 | REVIEW_NOT_ALLOWED / REVIEW_ALREADY_EXISTS | 未完成订单、重复评价等 |
| 410 | FILE_EXPIRED | 本人可见文件已过期或进入删除流程 |
| 413 | FILE_TOO_LARGE | 上传超过 20 MiB；代理层与应用层限制要一致 |
| 415 | FILE_TYPE_UNSUPPORTED | 不是允许的 PDF 类型 |
| 422 | PDF_INVALID / PDF_ENCRYPTED | PDF 损坏、不可解析或加密 |
| 503 | MOCK_PAYMENT_DISABLED | 当前部署未启用模拟交易，支付和产生模拟退款的操作均不得伪成功 |
| 503 | SERVICE_TEMPORARILY_UNAVAILABLE | 锁重试耗尽、数据库或存储暂不可用；事务已回滚 |
| 500 | INTERNAL_ERROR | 未预期失败，细节仅写服务端日志 |

敏感资源按可见范围查询：学生读取他人订单、商家读取其他店铺订单或文件，统一 404，不泄露存在性。角色本身不允许访问某类入口时返回 403。公开查询不展示未审核商家，查询其详情也返回 404。

401 由客户端重新登录；409 需读取最新资源或调整选择，不能无条件循环重试。网络不确定或 503 可有限重试：创建保留原键，状态操作通过原订单的幂等规则收敛。503 不代表允许更换请求键重新支付或下单。

模拟交易开关必须覆盖支付与退款：公开部署中不能保留会成功生成模拟资金流水的取消、拒单或超时路径。公开演示部署策略在工程配置时单独验证，不能仅隐藏前端支付按钮。

## 3. 认证与个人资料

| 方法 | 路径 | 权限 | 请求 | data / 行为 |
|---|---|---|---|---|
| POST | /auth/register | 匿名 | username、password、nickname | AccountView，固定创建 STUDENT，201 |
| POST | /auth/login | 匿名 | username、password | accessToken、tokenType = Bearer、expiresAt、account |
| POST | /auth/logout | 登录用户 | 无 body | 撤销当前会话，data = null |
| GET | /me | 登录用户 | 无 | AccountView |
| PATCH | /me | 登录用户 | nickname、expectedVersion | 更新后的 AccountView，不接受 role/status |
| POST | /me/password | 登录用户 | currentPassword、newPassword | 修改摘要并撤销本人全部会话，200；之后重新登录 |

已实现 register、login、logout、GET /me、PATCH /me、POST /me/password。mysql 模式匿名白名单包含健康、注册/登录、分类 GET、商家申请 POST，以及公开目录的五个 GET /shops、/shops/{shopId}、/shops/{shopId}/services、/shops/{shopId}/barbers、/shops/{shopId}/booking-slots；其他路径默认要求身份；匿名未知路径先返回 401，已登录未知路径仍为 404。会话期限为 2 小时，已到期凭证即使访问重复退出也返回 401；不使用 Cookie、查询参数或请求体中的 token。

注册已采用：username 去首尾空白、转小写后为 4～32 位 ASCII 字母、数字及下划线；nickname 去首尾空白后为 1～40 个 Unicode 码点。密码至少 8 个 Unicode 码点、UTF-8 不超过 72 字节，不修改内容、不静默截断；使用 BCrypt 随机加盐摘要。注册固定 STUDENT / ACTIVE、version = "0"，返回 201，不自动登录；重复用户名返回 409。

AccountView：id、username、nickname、role、version；自己的账号可返回 status。公开商家及评价接口不复用此对象。登录响应禁止缓存，其他敏感接口同样设置 Cache-Control: no-store。

改密的两个字段均遵守现有密码规则，不接受账号 ID 等额外字段；当前密码不正确为 400 / CURRENT_PASSWORD_INCORRECT。成功原子更新摘要、account.version + 1，并撤销本人全部未撤销会话（包含当前会话），data=null；不自动登录。相同新旧密码仍允许，重新加盐并撤销会话。事务内重新核对账号、当前会话和已验证摘要，避免并发覆盖；输入失败与异常回滚不留下部分改动，见 [改密验收](password-change.md)。

PATCH /me 的 nickname 复用注册昵称规则；遗漏不修改，显式 null 或非字符串返回 400。expectedVersion 必填，为 0～BIGINT 最大值的规范十进制字符串，拒绝数字、首尾空白与前导零等隐式转换。版本不匹配为 409 / CONFIG_VERSION_CONFLICT，包括无变化请求。实际昵称变更时同事务 version + 1 并返回最新 AccountView；昵称遗漏或归一化后相同不增加版本。版本达到上限时实际修改返回 409，不溢出。修改昵称保留全部登录会话，锁内重新检查身份；详见 [资料验收](profile-update.md)。

退出重复请求若携带签名正确、尚未到期、已撤销的同一 token，退出入口可返回成功；它是普通鉴权规则的唯一例外，不能因此允许已撤销 token 访问其他接口。过期/伪造 token 返回 401。

## 4. 商家申请与公开查询

| 方法 | 路径 | 权限 | 请求或查询 | data |
|---|---|---|---|---|
| POST | /merchant-applications | 匿名 | 申请 DTO，见下文 | accountId、shopId、approvalStatus = PENDING，201 |
| GET | /shop-categories | 匿名 | 无 | 启用分类数组 |
| GET | /shops | 匿名 | keyword、categoryId、page、size | ShopSummary 分页 |
| GET | /shops/{shopId} | 匿名 | 路径 ID | ShopDetail |
| GET | /shops/{shopId}/services | 匿名 | serviceType，可选 | 已上架 ServiceView 数组 |
| GET | /shops/{shopId}/barbers | 匿名 | serviceId，可选 | 启用且能提供所选服务的理发师数组 |
| GET | /shops/{shopId}/booking-slots | 匿名 | serviceId、barberId、date | serviceVersion、priceAmount、durationMinutes、slots 数组 |
| GET | /shops/{shopId}/reviews | 匿名 | page、size | VISIBLE 评价分页 |

2026-10-21 上表申请、分类及公开店铺列表/详情/上架服务/理发师/候选时段已实现；评价仍待开发；真实订单占用按 ADR-0022 已接入。公开规则见 [ADR-0019](decisions/0019-public-shop-catalog.md)。申请 DTO：username、password、nickname、categoryId（字符串）、shopName、locationText、description（可选）。campusCode 固定 XINGHAI_MAIN；拒绝 role、审批状态等未知字段。同事务创建 ACTIVE / MERCHANT 账号、PENDING 店铺及 PENDING OWNER 成员，营业开关 false；不自动登录或赋予经营权限。名称、位置、介绍去首尾空白后分别为 1～80、1～200、0～1000 Unicode 码点；未填介绍存为空字符串，分类无效或停用返回 400 / VALIDATION_FAILED。所有角色共用登录名唯一索引，冲突 409 / USERNAME_EXISTS。

申请者正常登录读取 GET /merchant/shop（已实现），返回本人店铺的 id、name、category、locationText、description、campusCode、approvalStatus、approvalReason、businessEnabled、memberStatus、version。待审/被拒也可读；其他角色 403，无归属店铺 404。身份来自会话，不接收可切换归属的 shopId。每周营业时间现已加入真实 businessHours；评价统计仍待实现。

用户已确认允许被拒后修改重提，取代旧稿“不支持重提”限制：仅 REJECTED 可提交店铺申请资料与 expectedVersion，复用原账号和店铺，REJECTED → PENDING，版本 + 1，保留历次审核。PENDING 不可修改/撤回。下表重提接口已实现，规则与锁顺序见 ADR-0012，点击流程见 [重提验收](merchant-resubmission.md)。仅 PENDING OWNER 可重提，保持停业，清空当前审核信息但保留历史。资料完整替换，description 省略/null/空白均清空旧值。

| 方法 | 路径 | 权限 | 请求 | data |
|---|---|---|---|---|
| POST | /merchant/shop/resubmit | 本人 MERCHANT，店铺 REJECTED | categoryId、shopName、locationText、description（可选）、expectedVersion | MerchantShopView；旧版本或状态不符 409 |

ShopSummary：id、name、category（id/code/name）、locationText、businessEnabled、openNow、rating、reviewCount。公开资格为 APPROVED + ACTIVE OWNER + ACTIVE/MERCHANT 经营账号；停业、未配置服务/排班/营业时间及分类字典停用不隐藏店铺。列表只统计可见项，详情/服务对不可见与不存在统一 404。openNow 以一个 Clock 时刻按 Asia/Shanghai 星期/时刻与开关计算，区间左闭右开；无时段/午休/关店为 false，不表示可预约。评价尚未实现，本轮 rating=null、reviewCount=0，不能当作已实现评价统计。名称 keyword 去首尾空白后 0～80 码点，空白不筛选，%/_/转义符按字面匹配；categoryId 合法但不存在为空页。page 默认 1，正整数且不超过 int 上限；size 默认 20、1～100，offset 用 long。列表 id ASC，越界页 items=[]。只读 RR 保持同一响应的资格、数量、资料、营业时间与服务一致；次次请求重新查询，不预留资源。

ShopDetail 增加 description、businessHours、campusCode。ServiceView 返回 id、serviceType、name、description、priceAmount、durationMinutes（打印为 null）、version；打印起价不能当作最终报价。公开服务只含本店上架项目，创建时间/ID 倒序，合法 serviceType 无匹配返回 []，非法值 400。公开 VO 不含账号/成员/审核信息或店铺版本。点击步骤见[目录验收](public-shop-catalog.md)。

公开理发师按 [ADR-0020](decisions/0020-public-barbers.md) 已实现，见[验收指南](public-barbers.md)。PublicBarberView 只含 id、displayName、serviceIds；仅启用且至少绑定一项本店上架 HAIRCUT 服务的人员可见，所有绑定均核对同店/类型/上架。人员创建时间/ID 倒序、服务 ID 数值升序；serviceId 筛选不截断返回的全部公开服务 ID。省略为不筛选，非法/显式空 ID 为 400；目标服务不存在、跨店、下架、非理发或 PRINT 店带合法筛选为 404；合法服务无人员返回 []，可见 PRINT 店无筛选为 []。停业或无排班仍可浏览，不表示有空位；只读 RR 保持资格、目标服务及绑定一致，不修改版本。

候选时段已按 [ADR-0021](decisions/0021-booking-slot-candidates.md) 实现，见[验收指南](booking-slots.md)。三个查询参数均必填，ID 为规范正 BIGINT 字符串，date 为真实 YYYY-MM-DD、上海今天至第 6 天（含）。先校验公开 HAIRCUT 店铺、本店上架理发服务、启用人员及实际绑定，失败统一 404 / RESOURCE_NOT_FOUND；资格成立后，停业、休息或无合适时段为 200 / slots=[]。同一个 Clock 时刻用于日期窗口和 startAt >= now+2h 判定，等于允许，不截断毫秒。开始按校园 15 分钟网格，完整服务必须在同一连续营业/排班交集内，不跨断档或午夜，允许结束等于终点。响应只含 serviceVersion、priceAmount、durationMinutes、slots；每项 startAt/endAt 为 UTC Z 时间，按开始升序去重，候选彼此允许重叠。服务版本、金额、时长来自服务端同一只读 RR 快照；不写保护行或配置。**当前已按 ADR-0022 排除 HELD/ACTIVE/CONSUMED 真实占用，未提交关闭的过期 HELD 仍保守占用，不预留资源**；创建待支付预约在日期保护行内重新检查，配置变更保护随同实现。公开评价仅包含 reviewId、rating、content、reviewerNickname、createdAt，不暴露学生 ID、订单 ID 或账号名。

## 5. 文件与打印报价

2026-10-23：上传及本人元数据已按[ADR-0031](decisions/0031-print-file-upload.md)实现。当前仅学生本人、24小时有效期；本人下载现按[ADR-0032](decisions/0032-file-download.md)实现；打印报价现按[ADR-0033](decisions/0033-print-quotes.md)实现；订单保护下的null有效期已按ADR-0034接入；商家履约内容访问仍为后续目标。

| 方法 | 路径 | 权限 | 请求 | data / 内容 |
|---|---|---|---|---|
| POST | /files | STUDENT | multipart/form-data，仅一个file | 已实现PDF上传，201 FileView，见ADR-0031 |
| GET | /files/{fileId} | 本轮仅本人STUDENT | 路径 ID | 已实现FileView，先归属后过期，见ADR-0031 |
| GET | /files/{fileId}/content | 本人STUDENT | 路径 ID | 已实现200原PDF附件，不包装JSON，见ADR-0032 |
| POST | /print-quotes | 本人STUDENT | 六字段打印规格DTO | 已实现200 PrintQuote，不创建订单/续期，见ADR-0033 |

FileView：id、originalName、byteSize、mediaType、pageCount、status、expiresAt（有未终结订单时为 null）。下载使用 application/pdf 与安全 Content-Disposition: attachment，错误在写流之前返回统一 JSON；开始输出后中断仅记录日志，不能追加 JSON 到 PDF 流。

当前下载验证正常STUDENT及本人归属。未来merchant仅能通过实际本店ACCEPTED / IN_SERVICE打印订单获得文件权限，该授权尚未实现；管理员没有默认内容下载权限。不返回 storageBucket、objectKey 或公开直链。

打印规格 DTO：serviceId、fileId、paperSize = A4、colorMode = BW / COLOR、sideMode = SINGLE / DOUBLE、copies。copies现按ADR-0033确认严格JSON整数1～100；PDF 页数从元数据获取，不接收 pageCount 或单价。

PrintQuote：serviceId、shopId、fileId、pageCount、copies、paperSize、colorMode、sideMode、sheetCount、priceRuleId、priceVersion、unitPriceAmount、originalAmount、discountAmount = 0、payableAmount、currency = CNY。报价不持久化、不保留价格；创建必须重新校验配置版本及金额。

示例：5 页 PDF、2 份双面、每面 20 分，纸张为 6 张，金额为 200 分，双面不把印刷面数减半。

## 6. 学生订单接口

| 方法 | 路径 | 权限 | 请求或查询 | data |
|---|---|---|---|---|
| POST | /orders/bookings | STUDENT | BookingCreate + Idempotency-Key | OrderDetail，201 / 重放 200 |
| POST | /orders/prints | STUDENT | PrintCreate + Idempotency-Key | OrderDetail，201 / 重放 200 |
| GET | /orders | STUDENT | orderType、status、page、size | 已实现本人BOOKING/PRINT摘要分页，见ADR-0028/0034 |
| GET | /orders/{orderId} | 本人学生 | 路径 ID | OrderDetail |
| GET | /orders/{orderId}/events | 本人学生 | 路径 ID | 已实现本人BOOKING/PRINT原事件完整数组，eventSeq升序，见ADR-0030/0034 |
| POST | /orders/{orderId}/payments/mock | 本人学生 | 无 body | PaymentResult |
| POST | /orders/{orderId}/cancel | 本人学生 | 无 body | 当前 OrderDetail，退款事实包含在结果中 |
| POST | /orders/{orderId}/pickup | 本人学生 | 无 body | PRINT + READY_FOR_PICKUP → COMPLETED |
| POST | /orders/{orderId}/review | 本人学生 | rating、content | 本人评价详情，201 |
| GET | /orders/{orderId}/review | 本人学生 | 路径 ID | 本人评价详情，未评价时 404 |

BookingCreate：serviceId、barberId、startAt、expectedServiceVersion、expectedPayableAmount、note（可选，最长 300）。endAt、shopId、studentId 和服务单价由后端确定，不由客户端指定。

```json
{
  "serviceId": "201",
  "barberId": "301",
  "startAt": "2026-10-15T02:00:00.000Z",
  "expectedServiceVersion": "2",
  "expectedPayableAmount": 3000,
  "note": "模拟预约"
}
```

PrintCreate：打印规格 DTO 的全部字段，加 expectedPriceVersion、expectedPayableAmount、note（可选）。后端以当前规格定位价目规则，客户端不能通过传入另一条 priceRuleId 使用优惠单价。

```json
{
  "serviceId": "202",
  "fileId": "401",
  "paperSize": "A4",
  "colorMode": "BW",
  "sideMode": "DOUBLE",
  "copies": 2,
  "expectedPriceVersion": "1",
  "expectedPayableAmount": 200,
  "note": "模拟打印订单"
}
```

OrderSummary：id、orderNo、orderType、status、paymentStatus、shopId、shopName（快照）、serviceName（快照）、payableAmount、currency、createdAt；类型特有摘要分别为预约开始/结束时间或打印进度。

OrderDetail 在摘要上增加 originalAmount、discountAmount、paymentDeadline、acceptanceDeadline、paidAt、acceptedAt、startedAt、completedAt、closedAt、closeReason、closeNote、note、version、reviewed、booking、printing、payment、refund。不存在的可选对象与时间返回 null；booking 与 printing 恰好一个非空，不能省略类型判别字段。

booking：barberId、barberName（快照）、startAt、endAt、durationMinutes、occupancyStatus。printing：fileId、fileName（快照）、pageCount、copies、paperSize、colorMode、sideMode、sheetCount、unitPriceAmount、progress、readyAt、pickedUpAt。payment / refund 仅返回本订单流水号、金额、状态及对应时间。

PaymentResult：orderId、orderStatus、paymentStatus、paymentNo、amount、paidAt、acceptanceDeadline。只能根据冻结金额支付；V1 不接收金额、回调地址或外部支付渠道。

评价 rating 在 1～5，content 去除首尾空白后 1～1000 字符；拒绝图片、商家回复和客户端 userId/shopId。COMPLETED 且 PAID 才可评价，已隐藏的评价也阻止再次创建。

截止时间以锁内服务端判定为准。期限到达时返回相应 409，不能依赖定时任务先关闭订单。重复操作的返回规则遵循状态文档，不重复生成事件或覆盖事实时间。

## 7. 商家经营与履约

商家资料读取允许未审核申请者查看本人审核信息；经营写入要求 MERCHANT + ACTIVE 成员 + APPROVED 店铺。已审核店铺临时停业不妨碍存量订单履约；平台暂停店铺后存量处理策略需另行确认，不能按普通开关静默关闭订单。

2026-10-19：PATCH /merchant/shop 已按 [ADR-0013](decisions/0013-merchant-operation.md) 确认并实现，点击验收见 [经营指南](merchant-operation.md)。只允许 name、locationText、description、businessEnabled 和必传字符串 expectedVersion；省略可变字段保留原值，null/错误类型/未知字段拒绝。文本去首尾空白后长度沿用申请规则，清空介绍传空字符串。资料直接生效不重新审核，分类/校区/审核信息不变。实际变化版本 + 1，无变化不写入但仍验证权限和版本；版本上限只允许无变化。开关独立保存营业意愿，未来接单仍须检查营业时间和服务。其他经营与履约接口仍待实现及具体规则确认。

| 方法 | 路径 | 请求或查询 | data / 约束 |
|---|---|---|---|
| GET | /merchant/shop | 无 | 本店资料、审核状态、成员状态、version |
| PATCH | /merchant/shop | name、locationText、description、businessEnabled、expectedVersion | 本店资料；不接收审批状态 |
| PUT | /merchant/shop/business-hours | expectedVersion、intervals | 营业区间数组；同事务更新 shop.version |
| GET | /merchant/services | serviceType、enabled，可选 | MerchantServiceView 数组，含下架项目 |
| POST | /merchant/services | ServiceCreateRequest | MerchantServiceView，201 |
| PATCH | /merchant/services/{serviceId} | ServicePatchRequest | MerchantServiceView；不允许改 shopId / serviceType |
| GET | /merchant/services/{serviceId}/print-prices | 无 | PrintPriceView 数组，含停用项与价目版本 |
| PUT | /merchant/services/{serviceId}/print-prices | PrintPricesRequest | 完整 PrintPriceView 数组，按规格批量新增/更新 |
| GET | /merchant/barbers | 无 | MerchantBarberView 数组，含停用人员及 serviceIds |
| POST | /merchant/barbers | BarberCreateRequest | 人员与关系同事务建立，默认停用，201 |
| PATCH | /merchant/barbers/{barberId} | BarberPatchRequest | MerchantBarberView，实际变化才加人员版本 |
| PUT | /merchant/barbers/{barberId}/services | BarberServicesRequest | 完整替换绑定，与 PATCH 共用人员版本 |
| GET | /merchant/barbers/{barberId}/schedule | date | 当天本地排班区间、barberVersion |
| PUT | /merchant/barbers/{barberId}/schedule | date、intervals、expectedBarberVersion | 已实现：完整替换一人一天，实际变化才递增 barber.version |
| GET | /merchant/orders | orderType、status、page、size | 已实现本店预约摘要分页，见ADR-0029；PRINT暂空页 |
| GET | /merchant/orders/{orderId} | 无 | 本店 OrderDetail，不包含密码或登录会话 |
| GET | /merchant/orders/{orderId}/events | 无 | 已实现本店BOOKING原事件完整数组，见ADR-0030 |
| POST | /merchant/orders/{orderId}/accept | 无 body | PENDING_ACCEPTANCE → ACCEPTED |
| POST | /merchant/orders/{orderId}/reject | reason，1～300 字符 | PENDING_ACCEPTANCE → CLOSED，全额模拟退款 |
| POST | /merchant/orders/{orderId}/cancel | reason，1～300 字符 | ACCEPTED → CLOSED，无法履约，全额模拟退款 |
| POST | /merchant/orders/{orderId}/start | 无 body | ACCEPTED → IN_SERVICE；打印同步 PRINTING |
| POST | /merchant/orders/{orderId}/ready-for-pickup | 无 body | 仅 PRINTING → READY_FOR_PICKUP |
| POST | /merchant/orders/{orderId}/complete | 无 body | 仅 BOOKING + IN_SERVICE → COMPLETED |

履约动作返回当前 OrderDetail；不提供 PATCH /orders/{id} 修改任意 status。商家不能替学生确认打印取件，学生不能自行宣布理发完成。

2026-10-20 服务配置按 [ADR-0015](decisions/0015-merchant-service-catalog.md) 实现：GET /merchant/services 返回本店数组，按 created_at DESC、id DESC；POST 返回 201 / MerchantServiceView，PATCH 返回 200 / MerchantServiceView。serviceType 与分类代码匹配，创建后不允许修改。创建必传 serviceType/name/priceAmount/durationMinutes，description 省略为空字符串、enabled 省略为 false。金额严格整数分 1～100000；HAIRCUT 时长严格整数 15～240 且为 15 倍数，PRINT 必须显式 null、金额仅参考起价。

修改字段省略保留、null 拒绝（仅 PRINT 时长例外）；实际变更增加服务版本，无变更仍校验版本，BIGINT 上限仅允许无变更。不递增店铺版本，不提供删除或创建幂等回放；同名允许。GET 可按 serviceType、enabled=true/false 筛选。店铺手动停业或没有营业时间仍可管理服务，但必须具备已审核资格与有效成员；跨店铺资源统一 404。步骤见 [服务验收](merchant-service-catalog.md)。

营业时间 PUT 已按 [ADR-0014](decisions/0014-merchant-business-hours.md) 实现。intervals 是完整一周，weekday 为严格整数 1～7，startTime/endTime 为 Asia/Shanghai 的 HH:mm；开始早于结束、不跨午夜、不接受 24:00。原始每天最多 4 段、整周 28 段，重叠/重复返回 400，相邻合并排序；缺席星期休息，空数组清空。expectedVersion 与经营 PATCH 共用，实际变化才加版本，不改变营业开关。所有 MerchantShopView 增加真实 businessHours。步骤见 [营业时间验收](merchant-business-hours.md)。

日排班按 [ADR-0018](decisions/0018-merchant-schedule.md) 已确认并实现，见[点击验收](merchant-schedule.md)。GET 必传严格 YYYY-MM-DD 的真实 date（1000～9999 年），可读历史/未来，不创建记录；返回 date、intervals、barberVersion。PUT 必传 date、expectedBarberVersion、intervals，校园时区今天至第 6 天可写；原始最多 4 段，startTime/endTime 为严格 HH:mm、15 分钟对齐。开始早于结束，不跨午夜；排序合并相邻、拒绝重复/交叠，每段须位于一个连续营业区间。[] 清空该日，省略/null/未知字段拒绝。与姓名/启停、绑定及其他日期共用 barber.version，旧版本包括无变化均 409；实际变化加一，不改 shop/service 版本。清空保留保护行，未配置日期空保存不创建。写后及等待后复查窗口与会话，失败整体回滚。停业、人员停用/无绑定可准备排班。营业时间实际变更须覆盖本店今天及之后全部已有排班（含停用人员），否则 409 SCHEDULE_CONFLICT；过去日期不限制修改，今天按完整日期保护。预约表尚未实现，其存量保护仍需在对应模块补齐。

2026-10-20 打印价目按 [ADR-0016](decisions/0016-merchant-print-prices.md) 确认并实现。PrintPricesRequest.rules 每批 1～4 条，每项 PrintPriceRuleRequest 的 paperSize=A4、colorMode=BW/COLOR、sideMode=SINGLE/DOUBLE、pricePerImpression（严格整数分/面 1～100000）、enabled（布尔）、expectedVersion 均必须提供。新规格版本显式 null，已有规格传其价目版本字符串；未知字段/null/错误类型及重复规格返回 400。已存在规格传 null，或不存在规格传版本字符串、旧版本或版本上限下实际变更，均为 409 / CONFIG_VERSION_CONFLICT。遗漏规格保留，任一项失败整批回滚；不删除，不修改参考起价，不递增店铺/服务版本；无变化仍核对版本但不写入。GET 和 PUT 返回含停用项的完整数组，BW/SINGLE、BW/DOUBLE、COLOR/SINGLE、COLOR/DOUBLE 固定排序；新服务无价目为 []。仅正常且已批准的本人 PRINT 商家可维护，停业/服务下架允许。本店理发服务为 400，跨店或不存在服务统一 404。点击流程与执行记录见 [价目验收](merchant-print-prices.md)。

配置变更影响排班与预约时，遵守数据库文档中的保护行和配置锁顺序。API 的版本校验解决“两个商家页面覆盖配置”；预约的数据库锁解决“两个学生抢同一时间”，二者不能互相替代。

2026-10-21 理发师四个接口按 [ADR-0017](decisions/0017-merchant-barbers.md) 实现，步骤见 [人员与绑定验收](merchant-barbers.md)。要求正常且已批准的本人 HAIRCUT 商家、ACTIVE OWNER；PRINT 分类为 400。人员不是登录账号，创建必传姓名及 serviceIds，默认停用、版本 "0"，同名允许；姓名去首尾空白后 1～40 Unicode 码点。绑定为 0～50 个不同的本店 HAIRCUT 服务字符串 ID，含下架服务；空数组允许，重复/非法 ID 或未知字段为 400，不可见服务为 404。创建人员和关系整体提交，不提供幂等回放。

PATCH 仅部分修改 displayName / enabled，省略保留、null 拒绝；PUT serviceIds 为完整目标集合，遗漏解绑、空数组清空。两者必传人员 expectedVersion，实际变化才加一次人员版本；无变化仍检查权限和版本，数组仅改变顺序不算变化。旧版本或上限下实际变更为 409 / CONFIG_VERSION_CONFLICT，任何异常整体回滚。返回 MerchantBarberView：id、displayName、enabled、serviceIds（数值 ID 升序）、version。GET 含停用人员、创建时间/ID 倒序、不分页，空列表 []。不改变店铺/服务版本、参考价格或登录账号，不提供删除；预约存量保护在相关模块接入前落实。

## 8. 管理员基础接口与异常处理

| 方法 | 路径 | 请求或查询 | data / 约束 |
|---|---|---|---|
| GET | /admin/shops | approvalStatus、page、size | 含待审核店铺的分页 |
| GET | /admin/shops/{shopId} | 无 | 商家申请资料与当前审核版本 |
| POST | /admin/shops/{shopId}/approve | expectedVersion | PENDING → APPROVED，激活成员 |
| POST | /admin/shops/{shopId}/reject | expectedVersion、reason | PENDING → REJECTED，不激活成员 |
| GET | /admin/accounts | role、status、page、size | 账号管理分页，禁止密码与会话字段 |
| PATCH | /admin/accounts/{accountId}/status | status、expectedVersion | 停用或恢复账号；停用撤销其会话 |
| POST | /admin/reviews/{reviewId}/hide | reason | 隐藏评价、保存操作者与原因 |
| GET | /admin/orders/{orderId} | 无 | 已实现BOOKING/PRINT核实详情，不包含打印内容访问链接 |
| POST | /admin/orders/{orderId}/close-exception | reason、expectedVersion | 已实现BOOKING/PRINT已付未终结的核实全额模拟退款关闭，见ADR-0027/0041 |

所有入口要求 ADMIN。V1 不提供公开创建管理员、任意修改角色、批量删除交易、强制变更订单状态或任意金额退款接口。分类初始数据随演示数据建立，分类管理完整 CRUD 留到 V2。

2026-10-19 补充：分类改为基础迁移初始化，不依赖演示数据。平台管理员通过 scripts/init-admin.ps1 显式创建，无默认密码、不覆盖已有账号。审批已实现：只接受 PENDING 和匹配的 expectedVersion，批准同事务激活 OWNER、默认仍停业；拒绝原因去首尾空白后 1～300 码点；每次审核保留历史与店铺版本，并发或旧请求返回 409，不重复追加记录。

close-exception：只处理已支付未终结订单，全额一次模拟退款，按是否开始服务释放或保留预约占用，记录 ADMIN_EXCEPTION；已完成订单不能直接通过此接口退款。该接口不允许传 refundAmount 或 targetStatus。重试只有在最新事件确为同一管理员、相同原因的 ADMIN_EXCEPTION，且 expectedVersion = 当前订单 version - 1 时，才返回原关闭结果；其他情况返回状态或版本冲突，不能重复退款。

## 9. 实现顺序、验收与剩余事项

2026-10-15 补充：[完整 V1 OpenAPI 设计稿](openapi/campuslife.v1-design.openapi.json)覆盖本契约的全部 61 个操作，包括健康接口；7 个已实现、54 个待开发，按 9 个分组展示。待开发参数、响应视图及约束仍为设计讨论稿，导入不代表已经定稿或实现。状态与导入步骤见 [完整规划说明](api-design.md)。

现有 7 个操作已生成 [OpenAPI 导入文件](openapi/campuslife.openapi.json)，运行中定义为 /v3/api-docs，Swagger UI 为 /swagger-ui/index.html。文档入口本地匿名可读，业务权限保持原约定；API_DOCS_ENABLED=false 关闭文档与页面，web-only 默认关闭。人工验收按 [点击指南](api-testing.md)，商家、订单等未实现接口不出现在实际生成文档中。

已实现 GET /system/health、POST /auth/register、POST /auth/login、POST /auth/logout、GET /me、PATCH /me、POST /me/password（完整路径均带 /api/v1）。健康仅表示 HTTP 存活；认证业务运行于 mysql 模式。其他路径仍为设计契约。实际记录见 [工程基础](engineering-foundation.md)、[注册验收](registration.md)、[认证验收](authentication.md)、[改密验收](password-change.md)与[资料验收](profile-update.md)。

接口按认证与统一错误 → 商家服务 → 预约闭环 → 文件打印闭环 → 评价逐项实现，不一次生成全部 Controller。管理员异常入口随订单模块实现；设施、优惠券、MQ 和报表不在本契约的实现范围。

每批接口建立后，将实际 DTO、状态码和 OpenAPI 输出与本文对照。只有真正实现和验证的路径才标记可用，避免手写完整 Swagger 声称全部功能已经存在。

本轮文档检查项：JSON 示例可解析，方法/路径不重复，所有本地引用可打开，订单状态与现有状态文档一致，模拟交易标记及业务确认状态明确。文档检查不能证明 HTTP 或业务行为正确。

后续接口验收重点：401/403/404 区分；未知字段拒绝；长整型 ID 与版本保持字符串；金额按分；时间偏移量转换正确；重复创建同单；旧报价不静默下单；不同店铺无法履约或读取文件；非法状态动作拒绝且没有副作用。

2026-10-19：商家入驻规则已定稿，分类、提交申请和本人店铺查询已实现。管理员初始化与审核已实现，被拒后修改重提也已实现，经营资料与营业开关按 ADR-0013 已确认并实现，每周营业时间按 ADR-0014 已确认并实现，服务配置按 ADR-0015 交付，打印价目按 ADR-0016 交付，理发师与绑定按 ADR-0017 交付，按日排班及营业时间保护按 ADR-0018 交付；公开商家目录按 ADR-0019 交付；公开理发师按 ADR-0020 交付，候选时段按 ADR-0021 交付，待支付预约、真实占用及存量保护按 ADR-0022 交付，模拟支付与待接单退款已按 ADR-0023 交付，下一项建议定稿商家接单/拒单；订单及经营细化稿的未确认部分仍需讨论。

2026-10-22 [ADR-0022](decisions/0022-booking-orders.md)：POST /orders/bookings、GET /orders/{orderId}、POST /orders/{orderId}/cancel 已实现，仅正常 STUDENT 的本人预约。创建首次 201、同键同规范请求重放 200 并带 Idempotency-Replayed；同键改内容 409，报价变化 409 QUOTE_CHANGED。无备注/null/首尾空白空串统一无备注，最多 300 Unicode 码点；Z 与偏移等价，严格起点网格。订单为 PENDING_PAYMENT/NOT_PAID/HELD，15 分钟内可 USER_CANCEL 关闭；now>=deadline 提交 PAYMENT_TIMEOUT 关闭/释放后，取消返回409 PAYMENT_DEADLINE_PASSED。GET不关闭到期单；该阶段未实现支付、列表、事件查询和商家履约。V9阶段详情的 payment/refund/printing 为 null；当前支付/退款已按下文 ADR-0023 实现，reviewed=false，保留原金额/时长/名称快照。候选接入真实占用；具体时间、锁、保护和错误规则见[已确认方案](booking-order-design.md)及[点击验收](booking-orders.md)。

2026-10-22 [ADR-0023](decisions/0023-booking-mock-payment.md) 已实现 `POST /orders/{orderId}/payments/mock`，首次/合法重复均200，PaymentResult 含 orderId、orderStatus、paymentStatus、paymentNo、amount、paidAt、acceptanceDeadline。不接收请求体，不接收客户端金额，不要求额外幂等键。仅正常STUDENT本人BOOKING；店铺经营资格失效首次付款409 SHOP_UNAVAILABLE，手动停业/下架/改价不阻止原单付款。锁后判定 now < paymentDeadline，付款转 PENDING_ACCEPTANCE/PAID/ACTIVE，期限=paidAt+30分钟。到支付期限先提交未付关闭再409 PAYMENT_DEADLINE_PASSED；已退款/取消后付款409 ORDER_STATE_CONFLICT。

原 cancel 扩展待接单全额模拟退款，USER_CANCEL 重放200；now>=acceptanceDeadline 提交 ACCEPTANCE_TIMEOUT/REFUNDED/RELEASED 后409 ACCEPTANCE_DEADLINE_PASSED。OrderDetail.payment/refund 为真实类型，未发生为null；退款保留原支付事实，printing=null，reviewed=false。新模拟资金默认关闭，503 MOCK_PAYMENT_DISABLED，无资金/状态/事件写入；已有事实重放允许，未支付释放照常。运行37个操作，完整62/37/25；规则见[确认方案](booking-payment-design.md)，步骤见[支付验收](booking-payments.md)。商家履约、列表和事件查询仍待开发。

2026-10-22 [ADR-0024](decisions/0024-merchant-booking-confirmation.md) 已实现 GET /merchant/orders/{orderId}、POST /merchant/orders/{orderId}/accept、POST /merchant/orders/{orderId}/reject（均返回现有OrderDetail）；运行40个操作，完整62/40/22。仅正常MERCHANT及本店ACTIVE OWNER，本店BOOKING以外统一404；接单另须APPROVED，详情/拒单可处理暂停店铺旧单。接单无body，严格早于acceptanceDeadline及startAt，成功ACCEPTED/PAID/ACTIVE、acceptedAt和版本/事件一次写入，合法重复不改时间。拒单仅reason（去首尾空白1～300码点），全额模拟退款MERCHANT_REJECT关闭释放，原因写closeNote、退款reason_note及事件，同原因重复200、改原因409。接单/拒单到期先提交ACCEPTANCE_TIMEOUT退款关闭再409；新退款均受模拟开关保护，合法接单不生成资金可在开关关闭时执行。原学生cancel已支持ACCEPTED距startAt至少1小时（等于允许）的全额退款取消，不足409 CANCEL_WINDOW_CLOSED，USER_CANCEL重放不重新判断窗口。列表/事件、开始/完成及商家已接单无法履约取消仍待开发。完整规则及点击步骤见[商家确认验收](merchant-booking-confirmation.md)。

2026-10-22 [ADR-0025](decisions/0025-merchant-booking-cancellation.md) 已实现POST /merchant/orders/{orderId}/cancel，返回200 OrderDetail，运行41个操作、完整62/41/21。请求BookingCancellation只有reason，去首尾空白1～300码点，未知字段拒绝。正常MERCHANT及本店ACTIVE OWNER，本店BOOKING以外404；处理存量无需当前APPROVED，停业/暂停/下架不改原金额。首次仅ACCEPTED/PAID/ACTIVE，锁后now<startAt，不受学生1小时限制；窗口已过409 CANCEL_WINDOW_CLOSED且不退款、不关闭。成功全额退款，CLOSED/REFUNDED/RELEASED，MERCHANT_UNAVAILABLE及closeNote，版本+1和一条MERCHANT事件；保留原支付与acceptedAt。同原因已成功重放200，时间/资金开关变化不重算首次窗口；改原因或其他状态409 ORDER_STATE_CONFLICT，不覆盖原责任。新退款仍受默认关闭的模拟资金开关保护，503无写入，具体权限/时间/验证见[取消指南](merchant-booking-cancellation.md)。开始/完成、管理员异常、打印及商家列表/事件仍待开发。

2026-10-22 [ADR-0026](decisions/0026-booking-fulfillment.md) 已实现POST /merchant/orders/{orderId}/start及/complete，均无请求体、200 OrderDetail，当前43个操作、完整62/43/19。仅本店BOOKING，正常MERCHANT及ACTIVE OWNER，暂停/停业/下架可履行已接旧单，不要求当前APPROVED，不生成资金、无需启用模拟资金。首次开始ACCEPTED/PAID/ACTIVE、startAt<=锁后now<endAt，成功IN_SERVICE、startedAt、版本+1及START事件；首次完成IN_SERVICE/PAID/ACTIVE、now>=startedAt（等于允许），可立即/提前/晚于预约结束完成，成功COMPLETED、completedAt、版本+1及COMPLETE事件，占用CONSUMED保留原区间不再次售卖。非法状态409 ORDER_STATE_CONFLICT，时间无效409 BOOKING_TIME_INVALID且无写入，ID/请求体非法400。合法重复返回已有时间事实及当前详情，不更新版本/事件；CLOSED不恢复，正常权限每次校验。支付不改写，refund=null、reviewed=false；开始路径当前不支持PRINT。规则、时间与验证见[履约指南](merchant-booking-fulfillment.md)，管理员异常、评价及打印仍待开发。

2026-10-22 [ADR-0027](decisions/0027-admin-booking-exception.md)实现GET /admin/orders/{orderId}及POST /admin/orders/{orderId}/close-exception，仅BOOKING，200 OrderDetail；当前45个运行操作、完整62/45/17。正常ADMIN可查任意预约，目标账号/OWNER停用或店铺暂停不阻止处理。关闭请求BookingExceptionClose仅reason和expectedVersion：原因去首尾空白1～300码点，版本规范非负BIGINT字符串，未知字段拒绝。首次PAID/ACTIVE且PENDING_ACCEPTANCE、ACCEPTED或IN_SERVICE，不受普通取消时间窗口；已完成不可退款。非关闭先核对版本，旧版本409 CONFIG_VERSION_CONFLICT，非法状态或版本上限409 ORDER_STATE_CONFLICT。全额退款、CLOSED/REFUNDED、ADMIN_EXCEPTION/closeNote、closedAt、版本+1及实际ADMIN事件同事务；未开始RELEASED，开始后CONSUMED保留原区间。已关闭只在最后事件对应同管理员/原因及expectedVersion=当前version−1、原事实一致时重放200，否则409 ORDER_STATE_CONFLICT。新退款须启用模拟资金；合法原结果重放不受开关影响。管理员不代跑到期关闭，与扫描先提交者保留关闭原因；GET不自动退款。参数400，角色/账号403，不存在或非预约404，竞争/资金关闭503，见[验收指南](admin-booking-exception.md)。打印及已完成售后未实现。

2026-10-22 [ADR-0028](decisions/0028-student-order-list.md)实现GET /orders，正常STUDENT只查本人BOOKING，200 OrderPage(items/page/size/total/totalPages)；当前46个操作，完整62/46/16。orderType省略/BOOKING查预约，PRINT合法空页；status省略或取六种精确大写主状态。显式空/非法枚举400；page/size省略默认1/20，仅正十进制数字、page最大2147483647、size1～100，前导零按现有目录解析，符号/空白/小数/越界400，PRINT也完整校验。按createdAt DESC、id DESC，越末页items=[]保留真实总数，无匹配totalPages=0；偏移long计算。OrderSummary字段按上文摘要，printingProgress=null，名称/金额/预约起止取冻结快照，退款后payableAmount不归零。GET不推断期限或清理超时，不暴露备注/核实原因/事件/完整资金/版本；需要时读取详情。目标经营资格失效不隐藏本人历史。只读RR内同条件count及单页联表共享快照，不取业务写锁、不逐单查询资金，不保证不同请求固定分页快照；401/403沿用身份规则，空页不404。点击与实际验证见[列表验收](student-order-list.md)，打印及商家列表/事件仍待开发。

2026-10-23 [ADR-0029](decisions/0029-merchant-order-list.md)实现GET /merchant/orders，正常MERCHANT及ACTIVE OWNER仅查询服务端确定的本店BOOKING，200复用OrderPage/OrderSummary；运行47、完整62/47/15。无OWNER所属店铺404，无效成员403；暂停/停业/下架或学生停用不隐藏历史，不改变接单原资格。参数/分页/冻结摘要沿用ADR-0028，PRINT空页仍校验权限和全部参数；不附加学生个人信息。RR从本人店铺读取固定资格、count/page同一快照，快照建立后成员停用当前请求可完成、下一次403；不用业务写锁、不清理超时/退款/推断状态或逐单查详情资金。具体边界、点击及实际验证见[商家列表验收](merchant-order-list.md)，事件查询及打印后续开发。

2026-10-23 [ADR-0030](decisions/0030-order-events.md)实现学生本人及商家本店GET /orders/{orderId}/events、/merchant/orders/{orderId}/events，200 data为OrderEventView完整数组，运行49、完整62/49/13。仅BOOKING，沿用详情历史权限；跨归属/不存在/非预约404，本店无效成员403。orderId规范正BIGINT字符串、禁止前导零/符号/空白/溢出，非法400。九字段id/eventSeq/action/fromStatus/toStatus/actorType/actorId/reason/occurredAt：ID与序号字符串，CREATE序号0/前状态null，SYSTEM操作者ID=null，原原因（含管理员核实）可null，UTC时间。不附加姓名/登录名/联系方式。RR内先归属/资格后事件，按eventSeq ASC完整返回、无分页/截断/合成；授权无事件[]，GET不清理期限/写版本/事件/资金、不需模拟开关或业务写锁。快照后新动作下次可见，商家资格快照边界沿用ADR-0029；不保证与另一详情请求同一快照。点击及实际验证见[事件验收](order-events.md)，打印/评价后续开发。

2026-10-23 [ADR-0031](decisions/0031-print-file-upload.md)实现POST /files、GET /files/{fileId}，当前51、完整62/51/11。正常学生、单file无额外字段，20MiB（含）/1～500页，原名末段1～200码点、PDF后缀/类型/签名/页树/加密及实际大小哈希由服务端核验；重复上传新ID，无幂等/去重，两个处理槽忙429。上传201 FileView七字段，仅AVAILABLE和发布起24小时有效期；不返回存储key/hash/路径/URL。GET先归属：他人/不存在/未发布404，本人到期（等于即过期）/删除中/已删除410。400表单/ID/命名，413大小，415类型，422无效/加密/超页数，429忙，503存储或数据库暂不可用。V11 file_asset内部UPLOADING、短RC复核身份后发布；IO/PDF解析不进事务，失败补偿、清理两步和进程恢复按ADR。内容下载、商家/管理员文件访问、报价/打印下单及订单引用保护仍待开发，先前此节宽权限和7天保护属于后续目标；实际操作/验证见[上传指南](print-file-upload.md)。

2026-10-23 [ADR-0032](decisions/0032-file-download.md)实现GET /files/{fileId}/content，当前52、完整62/52/10。本人STUDENT、规范ID，先归属后生命周期：他人/不存在/UPLOADING404，本人到期或删除中/已删除410；有效记录对象缺失/不可读/长度不符/根不匹配503。实例互斥后新只读RR复核账号/会话/文件及一次Clock，获准传输允许完成；IO不进事务、清理跳过活动传输，下载不续期/改版本。200原PDF与准确长度、安全UTF-8附件名、no-store/nosniff，Range及条件头仍完整200。未提交错误重置附件缓冲/头返回JSON；已提交IO中断回收资源、交由容器终止不完整响应，不追加JSON。商家订单授权、打印报价与引用保护待后续。

2026-10-24 [ADR-0033](decisions/0033-print-quotes.md)交付POST /print-quotes，当前53、完整62/53/9。六字段严格必填非null、规范字符串ID、精确A4/BW-COLOR/SINGLE-DOUBLE、copies整数1～100，拒绝未知/客户端页数或金额。正常学生本人文件优先404/410，经营资格可见及PRINT上架服务，否则404；价目缺失/停用409 PRINT_SPEC_UNAVAILABLE。停业/休息/无营业时间可估价，分类停用不另隐藏。16字段PrintQuote按实际页数×份数×启用价目计分，双面每份取整算纸张，最大5000000000分用long，优惠0/CNY；独立价目版本字符串。单一只读RR，读取后一次Clock判身份/文件期限，无写锁/磁盘IO/下载互斥；不续期/锁价/写订单、事件或资金。未来下单另行锁内重计价与引用保护。

2026-10-24 [ADR-0034](decisions/0034-print-orders.md)：POST /orders/prints接收报价六字段、expectedPriceVersion规范非负字符串、expectedPayableAmount严格整数1～5000000000分及可选note≤300码点；未知字段拒绝。键1～64位ASCII字母/数字/-/_，CREATE_PRINT隔离；同键异摘要409。文件归属/生命周期404/410，安全对象检查503，店铺不可见/非PRINT资源404，服务下架409 SERVICE_UNAVAILABLE，规格缺失/停用409 PRINT_SPEC_UNAVAILABLE，当前非营业409 SHOP_UNAVAILABLE，版本/金额变化409 QUOTE_CHANGED。15分钟待支付，打印对象含fileId/fileName/pageCount/copies/paperSize/colorMode/sideMode/sheetCount/unitPriceAmount/progress/readyAt/pickedUpAt，booking=null，打印payment/refund本轮null。取消前截止200 USER_CANCEL，达到截止先关闭再409 PAYMENT_DEADLINE_PASSED；重复不续期。本人详情/列表/事件不依赖当前店铺或磁盘，商家/支付/管理员预约入口继续拒绝PRINT。文件保护具体规则见ADR及指南。

2026-10-24 ADR-0035：PRINT首次模拟支付200 PaymentResult，无body/客户端金额/创建键，严格15分钟内，按冻结金额（最高5000000000分）写唯一成功事实；当前公开PRINT经营资格仍有效，下单后手动停业/营业结束、服务/规格停用/改价不重判。文件本人AVAILABLE且expiresAt=NULL，否则503 FILE_STORAGE_UNAVAILABLE；不打开磁盘。付款PENDING_ACCEPTANCE/PAID/QUEUED，paidAt+30分钟acceptanceDeadline；已有成功事实200重放，不改时间/事件且不重新校验店铺/文件或资金开关。待接单取消全额退款关闭ABORTED，USER_CANCEL重复200；到期ACCEPTANCE_TIMEOUT先提交退款/关闭/文件引用重算再409 ACCEPTANCE_DEADLINE_PASSED。支付截止关闭先提交再409 PAYMENT_DEADLINE_PASSED；已退款/USER_CANCEL关闭付款409 ORDER_STATE_CONFLICT。新资金未开启503 MOCK_PAYMENT_DISABLED，无写入；原事实仍可重放。详情payment/refund复用既有VO，退款保留原支付；GET/创建重放不自动退款。每单关闭仅一事件，最后引用终结后才计7天。商家/管理员预约入口仍拒绝PRINT。

## 商家PRINT确认：ADR-0036

GET /merchant/orders省略类型混合BOOKING/PRINT，指定类型分别筛选；仍固定本店，六种主状态及分页沿原规则。PRINT摘要bookingStartAt/bookingEndAt=null，printingProgress=QUEUED/ABORTED。列表RR内资格/count/page共用快照。详情200 OrderDetail，事件200 OrderEventView完整原数组，历史文件删除不隐藏冻结明细；不增加学生个人资料、存储key或下载URL。

正常MERCHANT及ACTIVE OWNER才可读/写，无所属店铺404，无效成员403，跨店/不存在404；暂停/停业仍可读和拒单。accept额外须当前APPROVED，暂停403 SHOP_NOT_APPROVED，重复亦然。首次PRINT接单须PENDING_ACCEPTANCE/PAID/QUEUED且锁后now<acceptanceDeadline，存在匹配支付无退款，文件本人AVAILABLE且NULL保护；200 ACCEPTED/PAID/QUEUED、acceptedAt/版本及一次MERCHANT ACCEPT事件，不改资金或文件期限。无body，重复不重判首次窗口/文件，不重写事实。

reject复用BookingRejection(reason)，strip后1～300码点、未知字段400。期限前待接单全额退款，200 CLOSED/REFUNDED/ABORTED，MERCHANT_REJECT及closeNote/refund.reason_note/event.reason一致，事件MERCHANT/实际账号。同原因重放200，不同原因/未付/已接单等409 ORDER_STATE_CONFLICT。两动作到期按SYSTEM ACCEPTANCE_TIMEOUT退款关闭提交后409 ACCEPTANCE_DEADLINE_PASSED；重复超时同409。所有新退款受模拟开关保护503，无新资金的合法接单不受开关限制。文件保护元数据异常503 FILE_STORAGE_UNAVAILABLE，竞争回滚503 SERVICE_TEMPORARILY_UNAVAILABLE。文件全部引用仍原NULL/最后终结+7天；无新表或路径，具体见ADR-0036。

## PRINT接单后取消：ADR-0037

POST /merchant/orders/{orderId}/cancel复用严格BookingCancellation(reason)，strip后1～300码点，不接受unknown字段/expectedVersion/金额。正常MERCHANT及本店ACTIVE OWNER，暂停/停业可处理旧单，无店/跨店/不存在404、成员无效403；不反向锁目标学生账号。

首次仅ACCEPTED/PAID/QUEUED、acceptedAt非空，startedAt/readyAt/pickedUpAt为空。PRINT无预约时间窗口，不受原30分钟接单截止限制。200 OrderDetail：CLOSED/REFUNDED/ABORTED，MERCHANT_UNAVAILABLE/closeNote、closedAt/版本、全额退款及一次MERCHANT事件；fromStatus=ACCEPTED，退款原因说明与事件一致。原payment/acceptedAt/冻结明细保持，文件全部引用未结束则NULL、最后终结+7天。同原因合法重复200不重写，异原因/其他状态409 ORDER_STATE_CONFLICT；待接单到期调用此入口也不自动退款。

首次退款复核文件保护元数据，503 FILE_STORAGE_UNAVAILABLE回滚；不读磁盘或等实例文件互斥。新退款开关关闭503 MOCK_PAYMENT_DISABLED，原事实可重放。订单版本上限409，必要文件期限失败503、数据库竞争503 SERVICE_TEMPORARILY_UNAVAILABLE；全部原子回滚。BOOKING的CANCEL_WINDOW_CLOSED及预约开始窗口保持原规则。

## 商家PDF内容授权：ADR-0038

GET /files/{fileId}/content仍只收规范fileId，无orderId/店铺参数。正常学生本人沿原404/410、等于到期边界；正常商家及本店ACTIVE OWNER须至少一笔本店PRINT关联ACCEPTED/PAID/QUEUED、acceptedAt非空、startedAt/readyAt/pickedUpAt空且文件属于订单学生。无店或无合格关联404，无效成员403；ADMIN403。暂停/停业/配置失效、学生停用、原接单截止经过或资金开关关闭不阻止合法旧任务。仅其他店有合格引用不授权；本店还有另一合格引用则允许，保留7天的历史文件本身不授权。

互斥后新短RR复核当前身份/会话/成员/关联/文件，等待前许可不可复用；已授权商家文件保护元数据异常503 FILE_STORAGE_UNAVAILABLE，根/长度/对象异常沿原503。数据库暂时竞争503 SERVICE_TEMPORARILY_UNAVAILABLE，可重试。新快照后取消/停用不打断获准传输，之后请求重新检查。传输无业务行锁或跨IO数据库事务，取消无需等待实例互斥，清理tryLock跳过活动文件。

200原PDF二进制，安全UTF-8附件头/Content-Length/no-store/nosniff，完整200，不包装JSON/续期/改进度/写事件或资金；提交后IO失败终止连接不尾接JSON。IN_SERVICE/打印中内容权限随后续履约接入，不因枚举存在而授权。

## PRINT开始与待取件：ADR-0039

POST /merchant/orders/{orderId}/start支持两类：BOOKING沿原时间窗，PRINT首次ACCEPTED/PAID/QUEUED、acceptedAt非空，startedAt/completedAt/readyAt/pickedUpAt空，锁后now≥acceptedAt。200 IN_SERVICE/PRINTING，startedAt、版本+1和MERCHANT START事件。新增POST /merchant/orders/{orderId}/ready-for-pickup仅PRINT，BOOKING/跨店/不存在404；首次IN_SERVICE/PAID/PRINTING，acceptedAt/startedAt非空，completedAt/readyAt/pickedUpAt空，now≥startedAt，允许立即待取件。成功200主状态仍IN_SERVICE、READY_FOR_PICKUP/readyAt、版本+1和同名MERCHANT事件（IN_SERVICE→IN_SERVICE），不修改startedAt或生成完成时间。

两动作无body、规范ID、正常商家/本店ACTIVE OWNER；暂停/停业/下架/学生停用仍可履行旧单，无新资金，不受模拟开关/原接单期限限制。非法body400，非法组合/版本上限409 ORDER_STATE_CONFLICT，时钟早于前序动作409 PRINT_TIME_INVALID；原预约BOOKING_TIME_INVALID保持。首次复核支付无退款和文件本人AVAILABLE/NULL保护，异常503，业务竞争503 SERVICE_TEMPORARILY_UNAVAILABLE。合法start在PRINTING/READY重放不回退，ready在READY重放不改事实，关闭不恢复；COMPLETED本阶段不重放。

商家content授权增加合法IN_SERVICE/PRINTING、READY_FOR_PICKUP组合，均PAID、acceptedAt/startedAt非空，READY还须readyAt，completedAt/pickedUpAt空；错配/关闭/完成不授权，RR与清理互斥规则保持。列表printingProgress及PrintingOrderView枚举含QUEUED/PRINTING/READY_FOR_PICKUP/ABORTED。文件持续NULL保护不写期限/版本；学生取件与管理员PRINT异常未开放。

## 学生PRINT确认取件：ADR-0040

POST /orders/{orderId}/pickup，无body；正常STUDENT本人PRINT，非本人/BOOKING/不存在404，ID/body非法400。首次IN_SERVICE/PAID/READY_FOR_PICKUP，前序时间齐全有序，完成/取件/关闭时间空，锁后now≥readyAt。200 COMPLETED/PAID/PICKED_UP，completedAt=pickedUpAt，版本+1、PICKUP事件（IN_SERVICE→COMPLETED，STUDENT/本人ID，reason=null）。首次支付匹配、无退款、文件本人AVAILABLE/NULL；无磁盘IO或资金，经营方资格失效不阻止学生完成旧单。

合法已完成pickup及本店商家start/ready返回当前完成详情，不重写事实或文件期限，文件清理后也可重试；当前身份/归属/成员及body仍校验。阶段冲突/订单版本上限409 ORDER_STATE_CONFLICT，首次时间倒退409 PRINT_TIME_INVALID，保护元数据/必要期限写异常503 FILE_STORAGE_UNAVAILABLE，事务竞争503 SERVICE_TEMPORARILY_UNAVAILABLE。文件全部引用结束后最晚终结+7天，有未终结引用仍NULL。完成单不授予商家下载，另一本店合格任务仍可授权，已获准传输允许结束。complete仅BOOKING；管理员PRINT异常及评价仍未开放。

## 管理员PRINT异常：ADR-0041

GET /admin/orders/{orderId}支持所有状态BOOKING/PRINT，正常ADMIN不限店铺，目标账号/OWNER停用或店铺暂停仍可核实；只读RR返回冻结规格、阶段时间、版本及真实资金，不读磁盘/自动退款或提供内容权限。文件清理不隐藏历史，不完整PRINT资源统一404。

POST /admin/orders/{orderId}/close-exception复用reason/expectedVersion严格JSON，支持PAID的PENDING_ACCEPTANCE/QUEUED、ACCEPTED/QUEUED、IN_SERVICE/PRINTING、IN_SERVICE/READY_FOR_PICKUP。首次阶段字段完整有序且未关闭/完成/取件，匹配成功支付未退款，保护文件本人AVAILABLE/NULL。锁后毫秒now≥paidAt及已有阶段，否则409 PRINT_TIME_INVALID；允许等于，无普通取消窗口。200 CLOSED/REFUNDED/ABORTED、ADMIN_EXCEPTION/closeNote、closedAt、版本+1、唯一全额退款和一次真实fromStatus→CLOSED的ADMIN事件；原接单/开始/待取件时间保留，不造完成/取件时间。

非关闭先校验版本，旧版本409 CONFIG_VERSION_CONFLICT，状态/版本上限409 ORDER_STATE_CONFLICT。关闭仅同管理员/原因/原版本、最后事件及支付/退款/ABORTED阶段事实一致时200重放，不重判首次时钟/文件保护，不重复写入/续期，文件清理后仍可重放；不一致业务重试409，服务器保存事实损坏500。新退款503 MOCK_PAYMENT_DISABLED受开关保护，保护元数据或必要期限写入503 FILE_STORAGE_UNAVAILABLE，竞争503 SERVICE_TEMPORARILY_UNAVAILABLE；全部回滚。文件引用全结束才最晚终结+7天；关闭撤销该单的商家内容来源，已获准传输可结束，管理员无默认内容权限。BOOKING仍按ADR-0027。

## 学生订单评价：ADR-0042

POST /orders/{orderId}/review：正常STUDENT本人BOOKING/PRINT，严格JSON rating为整数1～5（拒绝小数/字符串/布尔）、content strip后1～1000个Unicode码点，未知字段400。首次合法COMPLETED/PAID、completedAt及阶段事实齐全，BOOKING CONSUMED或PRINT PICKED_UP，匹配成功支付无退款，锁后now≥completedAt；未满足409 REVIEW_NOT_ALLOWED，无评价截止期。首次201 OwnReviewView（id/orderId/rating/content/visibility/createdAt），VISIBLE、ID字符串、UTC时间，无账号资料；重复同/异内容或隐藏记录均409 REVIEW_ALREADY_EXISTS，先归属/输入再重复判定，不编辑、不加幂等键。提交响应丢失用GET确认。

GET同路径200本人原记录，短只读RR内当前身份/会话、订单归属和评价同快照，未评价/他人/不存在404；隐藏本人仍可读。经营方停用、配置失效或PDF清理不影响正常学生的合法历史评价。新资金开关不影响两入口，数据库竞争503 SERVICE_TEMPORARILY_UNAVAILABLE；不提供公开评价/真实评分统计/隐藏接口。

reviewed在两类学生/商家/管理员详情及OrderDetail原重放查询EXISTS全部评价，不再固定false；它独立于订单version，不改订单状态、事件、资金、资源或文件期限。V13新增order_review，一单唯一；字段与锁协议见ADR-0042，运行58/完整62/58/4。公开目录null/0明确是统计尚未接入阶段值。

## 公开评价与真实评分：ADR-0043

新增GET /shops/{shopId}/reviews精确匿名放行，仅APPROVED+ACTIVE OWNER+ACTIVE/MERCHANT经营账号的店铺，沿ADR-0019停业/休息/分类停用不隐藏。规范shopId，page默认1、至INT最大，size默认20/1～100，全部格式先校验400，不可见/不存在404；主动携带无效token仍401/403。只查该店VISIBLE，createdAt/id倒序，offset long，越界空页。PublicReviewPage含items/page/size/total/totalPages，项仅reviewId/rating/content/reviewerNickname/createdAt，ID字符串和UTC，无学生/订单ID或账号资料。

作者当前昵称动态展示，改名影响历史后续快照；停用/角色变化不自动隐藏，内容资格只看visibility，不重核原订单/服务或作者资格。HIDDEN不公开或统计，本人仍可读、reviewed=true且不能重发；隐藏入口后续。商家列表/详情reviewCount=全部VISIBLE数，rating=十进制SUM/COUNT、两位HALF_UP，零条null/0。单响应短RR保证资格/数量/页/昵称/统计一致，列表批量当前页ID聚合，不逐店查，无写锁/IO/业务写入；跨请求/页不保证同快照。运行59、完整62/59/3，无新迁移。

## 管理员隐藏评价：ADR-0044

POST /admin/reviews/{reviewId}/hide现已实现，正文仅reason字符串（strip后1～300码点），正常ADMIN有效会话。200 OwnReviewView/HIDDEN，不含审核原因；不存在404，格式400，首次时钟倒退409 REVIEW_TIME_INVALID，异管理员/异原因或旧HIDDEN无审核记录409 REVIEW_STATE_CONFLICT，数据库暂时故障503。同管理员同归一化原因重放保留首次事实，无版本请求参数。

审核记录三字段原子提交，目标作者/经营方失效仍可处理；后续公开页/评分排除，本人仍可读且不能重发。无订单事件或资金/文件变动。运行60，完整62/60/2；[点击验收](admin-review-hide.md)。

## 管理员账号分页：ADR-0045

GET /admin/accounts现已实现，role/status精确大写交集、page/size沿只接受数字的分页解析，默认1/20、size最多100；排序created_at DESC/id DESC。正常ADMIN可查全部业务账号，包括本人、其他ADMIN及停用账号；AdminAccountPage.items严格AccountView六字段，不返回密码摘要/会话。身份/会话/count/items同RR，返回前核对会话到期；401/403、参数400、数据库暂时故障503，空/越界页200，无新404/409。状态PATCH尚待开发，不应在运行页面出现。见[点击验收](admin-account-list.md)。

## 账号状态：ADR-0046

PATCH /admin/accounts/{accountId}/status现已实现；严格status/expectedVersion两字段，ACTIVE/DISABLED与规范非负BIGINT版本字符串。正常ADMIN仅管理其他账号，本人含同状态409 ADMIN_SELF_MODIFICATION_FORBIDDEN；目标不存在404，旧版本或实际改变时版本上限409 CONFIG_VERSION_CONFLICT，格式400、暂时DB故障503。匹配且同状态200无写入，实际改变状态/version/全部旧会话撤销/历史原子提交，恢复必须重登。返回AccountView六字段。两份OpenAPI均覆盖62个可调用操作，完整文件已无待开发操作；[点击验收](admin-account-status.md)。

## M08.3-B 领取结果扩展（75操作，无新增路径）

默认新领取仍POST /api/v1/coupon-claims同步201/原键200。非OPEN/SYNC活动新请求503 COUPON_ADMISSION_UNAVAILABLE，B没有ASYNC启用或绕过Lua的新受理接口。本人原键若已有PENDING返回202、终态200；响应头Idempotency-Replayed表达重放。GET /coupon-claims/{claimId}读持久PENDING/SUCCEEDED/FAILED，不代跑发行/超时。

ClaimResult保留id/campaignId/status/completedAt/coupon，增加nullable acceptedAt/deadline/failureCode；PENDING无coupon/completedAt，FAILED无coupon且带失败码，旧SYNC新字段为null。截止/资格失败/售罄可持久FAILED，原键不重开；成功事实保留原券。首次公开异步202/Location待D接入，不能因出现PENDING schema就认定Lua入口已实现。详见[底座指南](async-coupon-foundation.md)。

## M08.3-C 接口边界

C增加Redis预留/恢复组件，**没有新增或开放异步受理HTTP入口**，仍75操作；默认SYNC原201/200行为保持。真实预留→持久夹具→MQ→发行→结算组件链已验证，不等于公开POST /coupon-claims的新异步202已开放。D才接首次受理/Location与受控模式；当前剩余量仍MySQL未发行快照，不承诺Redis可受理量。使用见[C指南](coupon-redis-recovery.md)。

## M08.3-D 公开受理

同一POST /coupon-claims按gate.mode选择默认SYNC首次201或显式ASYNC首次202；所有成功响应提供结果Location及Idempotency-Replayed。原PENDING202，原SUCCEEDED/FAILED200；只有SQL持久PENDING/outbox后才首次202。网络IO不在SQL事务内；新ASYNC复核OPEN/代次/运行标识、身份会话及领取窗口，不取活动库存X。GET仅读本人已提交状态，目录剩余量仍MySQL快照。本机Inspect/Pause/Async/Sync/Resume不新增HTTP接口。详情及错误/复现见[指南](async-coupon-admission.md)。
