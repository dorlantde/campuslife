# 待支付预约验收：Swagger UI / Apifox

2026-10-22，创建、本人详情、未支付取消按 [ADR-0022](decisions/0022-booking-orders.md) 确认，位于“05 学生订单”。后续支付/待接单退款按 [ADR-0023](decisions/0023-booking-mock-payment.md)、商家确认按ADR-0024实现，当前运行43个操作，完整62个（19个待开发）。重启原有 CampusLife (MySQL) 配置自动执行 V9/V10，不重新建库。仍由你在本机填写数据库密码。

本页验收暂占、查看、未支付取消及超时释放，未付款订单的 payment/refund/printing 为null，reviewed=false。模拟付款及待接单退款另见[支付验收](booking-payments.md)；商家详情/接单/拒单与已接单学生取消见[商家确认验收](merchant-booking-confirmation.md)。当前43个运行操作，开始/完成见[履约验收](merchant-booking-fulfillment.md)。

## 准备

1. 重启项目，打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。Apifox 更新导入[运行快照](openapi/campuslife.openapi.json)或[完整规划](openapi/campuslife.v1-design.openapi.json)，服务地址仍为 http://127.0.0.1:8080。
2. 按[候选时段指南](booking-slots.md)准备已批准、营业开关开启的理发店，本店上架理发服务、启用且绑定该服务的人员，以及营业时间/排班。建议明天 09:00～12:00、服务 30 分钟，确保提前两小时。
3. 查询候选，记录实际 serviceId、barberId、data.serviceVersion、data.priceAmount 和某项 startAt。候选返回 UTC Z 时间，直接复制，不自行加减 8 小时后去掉时区。
4. 注册/登录学生 A、B，保存各自 accessToken。商家不能创建学生预约；Authorize 仅填学生 token，不加 Bearer 前缀。

## 创建、重试和真实占用

展开 POST /api/v1/orders/bookings，Try it out，填写 Idempotency-Key，例如 booking-demo-001；此键只用于本次选择，网络失败重试保留原键。

```json
{
  "serviceId": "201",
  "barberId": "301",
  "startAt": "2026-10-23T01:30:00Z",
  "expectedServiceVersion": "0",
  "expectedPayableAmount": 3000,
  "note": "模拟预约"
}
```

示例 ID、日期、版本和金额都需替换为本机实际查询值。不要添加 studentId、shopId、endAt、status 或单价字段。

1. A 执行后首次为 201。记录 data.id、paymentDeadline，核对 status=PENDING_PAYMENT、paymentStatus=NOT_PAID、booking.occupancyStatus=HELD、version="0"；期限为创建后 15 分钟。
2. 保持同一键和请求再次执行，为 200，响应头 Idempotency-Replayed=true，仍为同一订单。JSON 字段顺序、等价时区和备注首尾空白不造成新单。
3. 仅修改备注或时段而保留旧键，返回 409 IDEMPOTENCY_KEY_CONFLICT；调整选择或报价时需使用新键。
4. 查询公开候选，原预约重叠的选择消失；相邻区间仍可选。例如原 09:30～10:00 阻止 09:15/09:30/09:45 三个 30 分钟选择，09:00～09:30 和 10:00～10:30 仍可选。
5. 切换 B 的 token，使用新键提交原重叠时间，为 409 BOOKING_CONFLICT。不要把同一开始时间的唯一性当作全部重叠保护。

## 查看、取消与到期

切回 A，在 GET /orders/{orderId} 填本人订单 ID，应返回下单时的名称、时长、金额快照；B 读取/取消 A 的订单均为 404。

- A 在截止时间前 POST /orders/{orderId}/cancel，无请求体，返回 200 / CLOSED / NOT_PAID / RELEASED，closeReason=USER_CANCEL，version="1"。重复取消返回原结果，不重写关闭时间。
- 公开候选重新出现；重新预约使用新键。即使旧单已关闭，重放旧创建键仍返回旧单，不创建第二单。
- 再创建一单，等待 15 分钟及一次最多约 30 秒的扫描周期，应为 PAYMENT_TIMEOUT 关闭并释放。不修改电脑时间。数据库异常可能延迟扫描，但不会延长期限。
- 若恰好到期后、任务处理前调用取消，会先提交超时关闭/释放再返回 409 PAYMENT_DEADLINE_PASSED；重新 GET 可看到关闭事实。GET 本身不写状态，到期但扫描未处理时如实返回待支付与过期 deadline。

精确期限、锁等待、重启后的同类扫描和并发由注入 Clock 的自动化检查覆盖，无需手工等待或更改系统时钟。

## 配置保护与错误对照

商家登录，订单尚未结束且仍占用时：

| 操作 | 预期 |
|---|---|
| 停用已预约人员、删除目标服务绑定 | 409 BOOKING_CONFLICT；配置版本不变 |
| 清空/缩短排班导致原预约不被覆盖 | 409 SCHEDULE_CONFLICT；排班和版本不变 |
| 缩短营业时间影响已有排班/预约 | 409 SCHEDULE_CONFLICT |
| 修改名称、价格、时长，或临时关店/服务下架 | 允许；旧订单快照不变，取消仍可操作 |
| 学生持旧价格/服务版本用新键下单 | 409 QUOTE_CHANGED；重新查询并确认新报价 |
| 缺少/非法请求键 | 400 IDEMPOTENCY_KEY_REQUIRED |
| 非法字段、无时区时间、不对齐15分钟、备注超长 | 400 VALIDATION_FAILED |
| 当前窗口/提前时间/营业或排班不满足 | 409 BOOKING_TIME_INVALID |
| 商家或管理员操作学生订单 | 403 ROLE_FORBIDDEN |
| 锁竞争超时 | 503 SERVICE_TEMPORARILY_UNAVAILABLE；事务回滚，保留原创建键重试 |

过期但尚未提交关闭的暂占仍受保护，等待关闭后重试配置。先取消订单，再重试对应配置操作；每次使用最新人员/店铺版本。修改营业时间仍须覆盖所有今天及以后排班，取消单笔预约不会自动清空排班。

## 自动化执行记录

2026-10-22，使用项目 Java 21 和独立临时 MySQL 8.0，运行 `scripts/test-mysql.ps1 -StartupTimeoutSeconds 90`。先执行全回归，再修正问题并对相关模块定向复测；最后通过进程内 Maven 参数选择 BookingOrdersMysqlIT、OpenApiMysqlIT，**本次 154 个用例全部通过**，零失败/错误/跳过：普通/Web 86、预约 MySQL/HTTP 65、接口文档 3。普通测试中含新增订单时间/归一化策略 22 个。

本轮各阶段最终保留报告合计 **965 个**（普通/Web 86、集成 879），全部零失败/错误/跳过；这不是最后一条命令单次重跑全部 965 个。人员 108、排班 91、营业时间 58、候选 66、公开目录 61 及基础迁移等相关用例也已在本轮复测；其他旧业务在前面的全回归执行，不能将历史报告拼成一次全绿构建。

重点覆盖：

- 20 名学生争抢相同/交叠区间，恰好一单成功；相邻区间、不同人员不误冲突。相同创建键的并发重试只生成一单，改内容冲突，取消后重放仍为原单。
- 真实 MySQL 锁等待后重新判断提前时间及会话；实际 InnoDB 锁等待超时返回 503，整笔回滚且原创建键可重试。重放与超时并发后主状态和占用一致，避免复用等待前的 Mapper 缓存。
- 取消/超时与创建竞争、精确 15 分钟边界、重复扫描、任务失败后重试，以及过期暂占在创建锁内清理。到期取消的 409 在关闭提交后返回，GET 不承担关闭写操作。
- 停用、解绑、清空排班、修改营业时间与创建分别验证双方先取得店铺锁的情况：成功预约和最终配置始终相容，冲突不递增版本；营业时间仍保护既有排班。
- 主订单、扩展、事件、幂等写入后的故障回滚；关闭事件失败也撤销订单关闭和占用释放。数据库金额/时间 CHECK 与事件唯一约束以实际约束名、MySQL 错误码验证。
- 受控扫描验证重新构建扫描器后可从已有数据库截止时间恢复，不依赖内存计时器；没有执行携带存量订单的完整 JVM 重启场景。可执行 JAR 的真实启动、HTTP 健康/UTF-8/身份边界检查另已通过。

首轮修正了非法头用例在 Java HTTP 客户端发送前被拒的问题，以及 Swagger 未扫描新订单模块的问题；后续把 MySQL CHECK 的断言从 Spring 包装类型改为真实约束/错误码。最初受限终端普通构建的 Web 检查曾被回环权限阻止，允许本地回环后重新验证通过，未将权限错误当作应用缺陷。

V1～V9 迁移通过；运行 OpenAPI 36 个操作，完整规划 62 个（36 已实现、26 待开发）、9 分组、85 个 schemas。生成器一致性、官方 OpenAPI 3.0 schema、9 个请求示例、参数/模型引用、JSON 和本地链接检查通过。未来支付/打印等操作使用 PlannedOrderDetail 讨论稿，运行接口仍使用实际 OrderDetail。

使用合成数据，未访问用户现有项目库或密码。所有自有临时实例及目录已停止并清理；尚未代用户进行 Swagger/Apifox 点击验收。新增 V9 是否已在你的项目库执行，以你重启后的 Flyway 日志为准。
