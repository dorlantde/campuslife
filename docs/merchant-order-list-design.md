# 商家本店订单分页列表：已确认方案

- 日期：2026-10-23（Asia/Shanghai）。
- 状态：用户已明确“采用并实现”。决策见[ADR-0029](decisions/0029-merchant-order-list.md)，交付及实际验证见[验收指南](merchant-order-list.md)。
- 依据：[接口契约](v1-api-contract.md)、[商家确认 ADR-0024](decisions/0024-merchant-booking-confirmation.md)、[履约 ADR-0026](decisions/0026-booking-fulfillment.md)、[学生列表 ADR-0028](decisions/0028-student-order-list.md)。

## 1. 本轮范围与理由

新增 **GET /api/v1/merchant/orders**，让商家先找到本店待处理预约，再点击已有详情、接单、拒单、开始或完成接口，不再依赖学生提供订单ID。

本轮仅新增一个只读列表，查询已实现的BOOKING预约；复用OrderPage / OrderSummary和现有order模块。运行操作46→47，完整规划仍62，待开发16→15。沿用V1～V10共19张业务表、MyBatis注解SQL，无新增迁移、依赖、分页插件或缓存。

本轮不包含订单事件查询、学生姓名/联系方式检索、时间范围筛选、导出或打印下单。打印业务接入时另行定稿实际查询与摘要。

## 2. 权限、店铺范围与历史可见性

仅正常ACTIVE / MERCHANT账号及有效会话可用，必须是本人店铺的ACTIVE OWNER。店铺ID由服务端根据当前账号确定，不接受客户端指定其他店铺或学生来改变归属条件。

- 无登录、失效或已撤销会话沿用401；学生、管理员或无效成员403 ROLE_FORBIDDEN；账号停用403 ACCOUNT_DISABLED。
- MERCHANT账号没有OWNER所属店铺时沿用商家订单详情的404；有店铺但OWNER为PENDING / DISABLED时403。即使筛选PRINT或店内无订单，也先检查商家归属与成员资格，不能以空页绕过。
- 列表不要求店铺当前APPROVED。暂停、主动停业、服务下架仍可查看本店旧单，沿用现有商家详情权限；这不赋予暂停店铺新的接单资格。
- 订单所属学生停用不隐藏历史预约，不查询或锁定学生账号。店铺、服务或人员改名/改价不改写摘要，下单名称、金额和预约区间读取冻结快照。
- 本店六种主状态均可展示，包括待支付、已关闭、退款及已完成；其他店铺订单不进入总数或items。

只读查询以本次事务快照内的OWNER资格为准。若成员在请求读取快照后被停用，当前请求可能完成；下一次请求拒绝。这里不为读取历史订单增加成员写锁，也不改变既有写接口的锁后权限复核规则。

## 3. 查询、分页与摘要

沿用已确认的学生列表规则，商家查询条件中的归属改为服务端确定的本店：

| 参数 | 规则 |
|---|---|
| orderType | 可省略；精确大写BOOKING或PRINT；省略/BOOKING查本店预约，PRINT暂返回空页 |
| status | 可省略；精确大写PENDING_PAYMENT / PENDING_ACCEPTANCE / ACCEPTED / IN_SERVICE / COMPLETED / CLOSED |
| page | 省略默认1；十进制数字表示的正整数，最大2147483647 |
| size | 省略默认20；十进制数字表示的正整数1～100 |

显式空值、空白、非法枚举、分页符号/小数/溢出等400 VALIDATION_FAILED；前导零沿用学生列表允许。PRINT也完整校验所有参数。权限和参数均通过后，PRINT返回total=0、totalPages=0、items=[]，不读取尚未支持的打印订单。

固定createdAt DESC、id DESC；偏移用long计算。超过末页200空items并保留实际总数，无数据totalPages=0。不提供客户端排序或归属表达式；不同请求之间新增订单或状态变更可能移动页码，不承诺跨请求固定快照。

响应为统一ApiResponse，data为OrderPage（items、page、size、total、totalPages）。每项复用OrderSummary的14个字段：id、orderNo、orderType、status、paymentStatus、shopId、shopName、serviceName、payableAmount、currency、createdAt、bookingStartAt、bookingEndAt、printingProgress。实体ID为字符串、金额为整数分、时间为UTC；本轮实际仅BOOKING，printingProgress固定null。退款后payableAmount仍为原应付金额。

摘要不附加学生账号信息、备注、核实原因、资金流水对象、人员详情、事件或订单版本。需要履约细节时再读取已有本店订单详情；不为本轮扩大个人信息的展示范围。

## 4. 数据库一致性与实现取舍

只读REPEATABLE_READ事务内依次读取本人店铺、OWNER资格、同条件count和分页数据，均使用普通一致性读；资格与订单结果来自同一快照。count/page始终包含本店shop_id、BOOKING及可选status，页内联表一次取得预约摘要，避免逐单读取详情或资金。

不获取账号、会话、店铺、日期保护行或订单的业务写锁，不触发超时关闭、退款或版本/事件变化。过期但尚未提交关闭的订单按数据库实际状态返回；模拟资金开关关闭也可查询。

复用现有ix_order_shop_status(shop_id, status, created_at, id)。带状态查询可利用其前缀；不带状态可能需要额外排序，本轮不声称所有筛选均免排序或已有性能提升。未出现实际数据量瓶颈前不新增索引或分页框架。

## 5. 完成与验收标准

实施时补充ADR、Swagger注解、运行OpenAPI、完整规划及Swagger / Apifox点击指南；预计同步47个运行操作和62/47/15规划状态。实际交付和验证结果以验收指南为准。

1. 商家登录并Authorize后，在商家订单分组调用列表，按PENDING_ACCEPTANCE查找订单，复制id打开本店详情，并复用现有履约接口。学生/管理员不能使用该列表。
2. 独立真实MySQL及HTTP验证多店隔离、本店不同学生订单、六种状态和组合筛选、固定排序、默认/空页/越界/最大偏移、非法参数及PRINT完整校验。
3. 验证未绑定商家、PENDING / DISABLED OWNER、停用账号、失效会话；店铺暂停/停业、服务下架、学生停用仍可读历史；无资格者不能靠PRINT空结果绕过权限。
4. 验证改名/改价后的冻结摘要、退款金额语义、过期未关闭实际状态、关闭资金开关不影响读取；GET不新增支付/退款/事件或递增版本。
5. 让真实事务在count与page之间创建、支付或关闭订单，证明本次total/items来自同一RR快照，后续独立请求看到提交变化；验证资格检查与订单查询同一快照，并验证查询不会等待店铺/日期/订单的业务写锁。
6. 按影响范围执行学生列表、商家权限/详情及OpenAPI相关回归，使用独立临时数据库，不访问用户现有服务数据。实际执行结果另记验收指南，不提前声称测试通过。

商家列表的查询范围、权限及摘要边界已获确认。实际完成情况见验收指南。
