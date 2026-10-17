# V1 接口契约

版本：讨论稿 0.6，2026-10-15。健康、注册、登录、本人资料读取与昵称修改、退出与改密已实现并验证，并提供 Swagger UI / OpenAPI；其他接口仍为设计约定。参照 [业务基线](decisions/0001-v1-baseline.md)、[数据库设计](v1-database-design.md)和[状态迁移](v1-state-transitions.md)。

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

已实现 register、login、logout、GET /me、PATCH /me、POST /me/password。mysql 模式除健康、注册、登录外默认要求身份；匿名未知路径先返回 401，已登录未知路径仍为 404。会话期限为 2 小时，已到期凭证即使访问重复退出也返回 401；不使用 Cookie、查询参数或请求体中的 token。

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

申请 DTO：username、password、nickname、categoryId、shopName、locationText、description（可选）。campusCode 由服务端固定为 XINGHAI_MAIN；不接收 role 或审批状态。同事务创建 MERCHANT 账号、PENDING 店铺及 PENDING OWNER 成员；不自动赋予经营权限。申请者通过正常登录读取 /merchant/shop 的审核结果；V1 不提供被拒后自行改角色或重提申请的接口。

ShopSummary：id、name、category、locationText、businessEnabled、openNow、rating（无评价为 null）、reviewCount。openNow 按店铺营业时间和开关计算，不等于理发师当前有空位。

ShopDetail 增加 description、businessHours、campusCode。ServiceView 返回 id、serviceType、name、description、priceAmount、durationMinutes（打印为 null）、version；打印起价不能当作最终报价。

slots 项为 startAt、endAt。服务时长来自服务配置；只返回满足日期、提前时间、排班和不重叠条件的候选。返回的空位不预留资源，创建订单仍需加锁复核。公开评价仅包含 reviewId、rating、content、reviewerNickname、createdAt，不暴露学生 ID、订单 ID 或账号名。

## 5. 文件与打印报价

| 方法 | 路径 | 权限 | 请求 | data / 内容 |
|---|---|---|---|---|
| POST | /files | STUDENT | multipart/form-data，字段 file | FileView，201 |
| GET | /files/{fileId} | 本人学生或有履约权限的商家 | 路径 ID | FileView，先鉴权再判断过期 |
| GET | /files/{fileId}/content | 同上 | 路径 ID | PDF 二进制附件，不包装 JSON |
| POST | /print-quotes | STUDENT | 打印规格 DTO | PrintQuote，200，不创建订单或占用文件 |

FileView：id、originalName、byteSize、mediaType、pageCount、status、expiresAt（有未终结订单时为 null）。下载使用 application/pdf 与安全 Content-Disposition: attachment，错误在写流之前返回统一 JSON；开始输出后中断仅记录日志，不能追加 JSON 到 PDF 流。

下载前验证角色、所有权或本店 ACCEPTED / IN_SERVICE 打印订单。merchant 仅能通过实际订单获得文件权限；管理员没有默认内容下载权限。不返回 storageBucket、objectKey 或公开直链。

打印规格 DTO：serviceId、fileId、paperSize = A4、colorMode = BW / COLOR、sideMode = SINGLE / DOUBLE、copies。copies 初始建议 1～100；PDF 页数从元数据获取，不接收 pageCount 或单价。

PrintQuote：serviceId、shopId、fileId、pageCount、copies、paperSize、colorMode、sideMode、sheetCount、priceRuleId、priceVersion、unitPriceAmount、originalAmount、discountAmount = 0、payableAmount、currency = CNY。报价不持久化、不保留价格；创建必须重新校验配置版本及金额。

示例：5 页 PDF、2 份双面、每面 20 分，纸张为 6 张，金额为 200 分，双面不把印刷面数减半。

## 6. 学生订单接口

| 方法 | 路径 | 权限 | 请求或查询 | data |
|---|---|---|---|---|
| POST | /orders/bookings | STUDENT | BookingCreate + Idempotency-Key | OrderDetail，201 / 重放 200 |
| POST | /orders/prints | STUDENT | PrintCreate + Idempotency-Key | OrderDetail，201 / 重放 200 |
| GET | /orders | STUDENT | orderType、status、page、size | 本人 OrderSummary 分页 |
| GET | /orders/{orderId} | 本人学生 | 路径 ID | OrderDetail |
| GET | /orders/{orderId}/events | 本人学生 | 路径 ID | 按 eventSeq 升序的事件数组 |
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

| 方法 | 路径 | 请求或查询 | data / 约束 |
|---|---|---|---|
| GET | /merchant/shop | 无 | 本店资料、审核状态、成员状态、version |
| PATCH | /merchant/shop | name、locationText、description、businessEnabled、expectedVersion | 本店资料；不接收审批状态 |
| PUT | /merchant/shop/business-hours | expectedVersion、intervals | 营业区间数组；同事务更新 shop.version |
| GET | /merchant/services | serviceType、enabled，可选 | 本店服务数组，含下架项目 |
| POST | /merchant/services | ServiceWrite | ServiceView，201 |
| PATCH | /merchant/services/{serviceId} | 可变服务字段、expectedVersion | 不允许改 shopId / serviceType |
| GET | /merchant/services/{serviceId}/print-prices | 无 | 本店打印价目数组，含 version |
| PUT | /merchant/services/{serviceId}/print-prices | rules 数组 | 按规格建立或更新价目，见下文 |
| GET | /merchant/barbers | 无 | 本店理发师数组，含 version |
| POST | /merchant/barbers | displayName、serviceIds | 创建本店理发师与服务关系，201 |
| PATCH | /merchant/barbers/{barberId} | displayName、enabled、expectedVersion | 本店理发师；存量预约需处理后才可停用 |
| PUT | /merchant/barbers/{barberId}/services | serviceIds、expectedVersion | 覆盖可提供服务关系，并递增 barber.version |
| GET | /merchant/barbers/{barberId}/schedule | date | 当天本地排班区间、barberVersion |
| PUT | /merchant/barbers/{barberId}/schedule | date、intervals、expectedBarberVersion | 保护行下覆盖该日排班，递增 barber.version |
| GET | /merchant/orders | orderType、status、page、size | 本店订单分页 |
| GET | /merchant/orders/{orderId} | 无 | 本店 OrderDetail，不包含密码或登录会话 |
| GET | /merchant/orders/{orderId}/events | 无 | 本订单事件数组 |
| POST | /merchant/orders/{orderId}/accept | 无 body | PENDING_ACCEPTANCE → ACCEPTED |
| POST | /merchant/orders/{orderId}/reject | reason，1～300 字符 | PENDING_ACCEPTANCE → CLOSED，全额模拟退款 |
| POST | /merchant/orders/{orderId}/cancel | reason，1～300 字符 | ACCEPTED → CLOSED，无法履约，全额模拟退款 |
| POST | /merchant/orders/{orderId}/start | 无 body | ACCEPTED → IN_SERVICE；打印同步 PRINTING |
| POST | /merchant/orders/{orderId}/ready-for-pickup | 无 body | 仅 PRINTING → READY_FOR_PICKUP |
| POST | /merchant/orders/{orderId}/complete | 无 body | 仅 BOOKING + IN_SERVICE → COMPLETED |

履约动作返回当前 OrderDetail；不提供 PATCH /orders/{id} 修改任意 status。商家不能替学生确认打印取件，学生不能自行宣布理发完成。

ServiceWrite：serviceType、name、description（可选）、priceAmount、durationMinutes、enabled。理发要求正价格、正时长且时长为 15 分钟倍数；打印 durationMinutes 必须为 null，priceAmount 为起价展示。商家服务不能越过已确认的店铺业务范围；增加另一类经营场景前讨论分类与服务支持策略。

营业 intervals 项：weekday（1～7）、startTime、endTime；空数组表示无营业时段。日排班 intervals 项：startTime、endTime；空数组表示该日不排班，仅在没有存量有效预约时允许。更新返回资源最新版本，拒绝交叠、跨午夜或侵占预约的区间。

print-prices.rules 项：paperSize、colorMode、sideMode、pricePerImpression、enabled、expectedVersion。已有规格必须携带其版本；新规格 expectedVersion = null，同规格并发创建以唯一约束冲突收敛。未传入的规格保持不变；本接口对指定规格做批量 upsert，不表示删除或替换整张价目表。同一次请求重复规格为 400；任一规则失败整批回滚。

配置变更影响排班与预约时，遵守数据库文档中的保护行和配置锁顺序。API 的版本校验解决“两个商家页面覆盖配置”；预约的数据库锁解决“两个学生抢同一时间”，二者不能互相替代。

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
| GET | /admin/orders/{orderId} | 无 | 核实异常用订单详情，不包含打印内容访问链接 |
| POST | /admin/orders/{orderId}/close-exception | reason、expectedVersion | 已支付未终结订单，全额模拟退款并关闭 |

所有入口要求 ADMIN。V1 不提供公开创建管理员、任意修改角色、批量删除交易、强制变更订单状态或任意金额退款接口。分类初始数据随演示数据建立，分类管理完整 CRUD 留到 V2。

close-exception：只处理已支付未终结订单，全额一次模拟退款，按是否开始服务释放或保留预约占用，记录 ADMIN_EXCEPTION；已完成订单不能直接通过此接口退款。该接口不允许传 refundAmount 或 targetStatus。重试只有在最新事件确为同一管理员、相同原因的 ADMIN_EXCEPTION，且 expectedVersion = 当前订单 version - 1 时，才返回原关闭结果；其他情况返回状态或版本冲突，不能重复退款。

## 9. 实现顺序、验收与剩余事项

2026-10-15 补充：[完整 V1 OpenAPI 设计稿](openapi/campuslife.v1-design.openapi.json)覆盖本契约的全部 61 个操作，包括健康接口；7 个已实现、54 个待开发，按 9 个分组展示。待开发参数、响应视图及约束仍为设计讨论稿，导入不代表已经定稿或实现。状态与导入步骤见 [完整规划说明](api-design.md)。

现有 7 个操作已生成 [OpenAPI 导入文件](openapi/campuslife.openapi.json)，运行中定义为 /v3/api-docs，Swagger UI 为 /swagger-ui/index.html。文档入口本地匿名可读，业务权限保持原约定；API_DOCS_ENABLED=false 关闭文档与页面，web-only 默认关闭。人工验收按 [点击指南](api-testing.md)，商家、订单等未实现接口不出现在实际生成文档中。

已实现 GET /system/health、POST /auth/register、POST /auth/login、POST /auth/logout、GET /me、PATCH /me、POST /me/password（完整路径均带 /api/v1）。健康仅表示 HTTP 存活；认证业务运行于 mysql 模式。其他路径仍为设计契约。实际记录见 [工程基础](engineering-foundation.md)、[注册验收](registration.md)、[认证验收](authentication.md)、[改密验收](password-change.md)与[资料验收](profile-update.md)。

接口按认证与统一错误 → 商家服务 → 预约闭环 → 文件打印闭环 → 评价逐项实现，不一次生成全部 Controller。管理员异常入口随订单模块实现；设施、优惠券、MQ 和报表不在本契约的实现范围。

每批接口建立后，将实际 DTO、状态码和 OpenAPI 输出与本文对照。只有真正实现和验证的路径才标记可用，避免手写完整 Swagger 声称全部功能已经存在。

本轮文档检查项：JSON 示例可解析，方法/路径不重复，所有本地引用可打开，订单状态与现有状态文档一致，模拟交易标记及业务确认状态明确。文档检查不能证明 HTTP 或业务行为正确。

后续接口验收重点：401/403/404 区分；未知字段拒绝；长整型 ID 与版本保持字符串；金额按分；时间偏移量转换正确；重复创建同单；旧报价不静默下单；不同店铺无法履约或读取文件；非法状态动作拒绝且没有副作用。

当前先完成用户的浏览器 / Apifox 人工验收，再定稿商家申请规则，推进商家业务；讨论稿中的细节仍需确认。
