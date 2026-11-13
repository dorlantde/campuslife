# 学生与商家订单事件查询：已确认方案

- 日期：2026-10-23（Asia/Shanghai）。
- 状态：用户已明确“采用并实现”。决策见[ADR-0030](decisions/0030-order-events.md)，交付及实际验证见[点击验收](order-events.md)。
- 依据：[接口契约](v1-api-contract.md)、[预约订单 ADR-0022](decisions/0022-booking-orders.md)、[商家确认 ADR-0024](decisions/0024-merchant-booking-confirmation.md)、[管理员异常 ADR-0027](decisions/0027-admin-booking-exception.md)、[商家列表 ADR-0029](decisions/0029-merchant-order-list.md)。

## 1. 本轮范围与理由

新增两个只读接口，让学生和商家从已有列表取得订单ID后，查看创建、付款、履约或关闭记录：

| 接口（含完整前缀） | 范围 |
|---|---|
| GET /api/v1/orders/{orderId}/events | 正常学生的本人BOOKING预约 |
| GET /api/v1/merchant/orders/{orderId}/events | 正常商家及ACTIVE OWNER的本店BOOKING预约 |

沿用order模块、MyBatis注解SQL和V9已有order_event表，复用唯一索引uk_order_event_seq(order_id,event_seq)，不新增迁移、依赖、缓存、消息队列或事件发布机制。此处查询的是已有业务事务保存的事件，不是新增一套事件驱动架构。

本轮运行操作47→49，完整规划仍62，待开发15→13。只查询现有预约事件；打印、评价、管理员专用事件入口、筛选/导出及新业务动作留到对应任务。

## 2. 权限与历史可见性

- 学生入口仅ACTIVE / STUDENT及有效会话，服务端核对订单student_id；他人、不存在、非BOOKING统一404 RESOURCE_NOT_FOUND。店铺暂停、经营账号/OWNER停用或服务下架不隐藏本人历史。
- 商家入口仅ACTIVE / MERCHANT及有效会话，服务端确定本人OWNER所属店铺，核对订单shop_id及BOOKING类型，再检查ACTIVE OWNER。无所属店铺、跨店、不存在、非BOOKING沿用详情的404；本店资源但成员PENDING / DISABLED为403 ROLE_FORBIDDEN。
- 商家查旧单不要求当前APPROVED；暂停/停业、服务下架或订单所属学生停用仍可查看，不放宽原接单条件。
- 两端都可读所有预约主状态，包含已关闭、已退款和已完成。无登录/失效会话沿用401，账号停用403 ACCOUNT_DISABLED，错误角色403 ROLE_FORBIDDEN。ADMIN不能借学生或商家入口越权查看，现有管理员详情/核实关闭能力不变。
- 事件操作者后来停用不隐藏原记录，也不联表当前账号姓名或资格。商家资格与事件在一个只读快照内判断；快照建立后成员被停用，当前请求可能完成，下一次请求拒绝，沿用ADR-0029读取边界。

orderId沿用现有订单详情解析：规范正BIGINT十进制字符串，不接受前导零、符号、空白、0或溢出；格式非法400 VALIDATION_FAILED。这里的ID解析与页码允许前导零是不同参数约定，不静默统一。

## 3. 响应、展示内容与排序

200统一ApiResponse，data直接为OrderEventView数组，沿用规划字段：

| 字段 | 建议语义 |
|---|---|
| id | 事件自身ID，十进制字符串 |
| eventSeq | 本订单内的非负序号，十进制字符串；CREATE从"0"开始 |
| action | 原事件动作码，见下表，不根据当前订单状态重新生成 |
| fromStatus / toStatus | 本次迁移的前后主状态；CREATE的fromStatus为null |
| actorType | STUDENT / MERCHANT / ADMIN / SYSTEM |
| actorId | 实际操作者账号ID字符串；SYSTEM为null，不附加账号资料 |
| reason | 已保存的原因原文，可为null；既可能是关闭原因码，也可能是商家/管理员填写的原因 |
| occurredAt | 原发生时刻，UTC；不以查询时刻覆盖 |

当前已有动作和记录方式：

| action | 来源与reason |
|---|---|
| CREATE / PAYMENT | 学生创建/付款；reason通常null |
| ACCEPT / START / COMPLETE | 商家接单/开始/完成；reason通常null |
| USER_CANCEL | 学生取消；reason为USER_CANCEL |
| PAYMENT_TIMEOUT / ACCEPTANCE_TIMEOUT | 系统超时关闭；reason为对应原因码，actorId=null |
| MERCHANT_REJECT / MERCHANT_UNAVAILABLE | 商家拒单/无法履约取消；reason为当时提交并已归一化的原因 |
| ADMIN_EXCEPTION | 管理员核实退款关闭；reason为当时保存的核实原因 |

学生和本店商家看到相同的已保存事件字段，包括关联订单的管理员核实原因和实际操作者ID；不新增私有内部备注、姓名、登录名、联系方式或凭证展示。reason仅作为JSON文本返回，未来界面按纯文本展示，不作为HTML执行。操作者ID用于辨认该订单的实际操作来源，不提供按ID反查账号的新增接口。

退款关闭沿用一条关闭事件，不合成额外REFUND事件；占用变化也不伪造另一条时间线。业务重复操作已约定不新增事件，查询须如实反映原事实，不去重拼接或补写。正常订单至少包含CREATE；若授权订单没有事件，返回200空数组，不将其当作订单不存在或自动补齐。

固定eventSeq ASC，不按occurredAt排序；同一毫秒发生或时钟调整不改变业务顺序。不接受客户端排序表达式。V1预约状态只向终态推进且合法重复不追加，当前最长正常/异常链路均仅5条事件，采用完整数组，无分页或静默截断。未来增加循环处理或高频事件时再评估分页，不把当前数量当作数据库硬上限。

## 4. 事务与一致性

两个入口均在只读REPEATABLE_READ事务内完成订单归属、商家成员资格及事件查询，使用普通一致性读。事件查询必须带已授权order_id条件，使用参数绑定及event_seq升序；权限失败不得先读取事件内容。

若付款、关闭或履约在请求读取快照后提交，本次查询仍返回原快照内的完整事件序列，下一次请求看到新提交事件；不承诺返回绝对最新记录，也不保证与另一次详情请求共享快照。

不取账号、会话、店铺、日期保护行或订单的业务写锁；不触发超时清理、状态/版本/占用变化、新事件或资金流水。模拟资金开关关闭也可查询，保留现有写事务及管理员重放所用latest查询的语义，不改写既有历史数据。

## 5. 完成与验收标准

实施同步补充ADR、Swagger注解、运行OpenAPI、完整规划及学生/商家切换token的点击指南；运行49个操作与完整62/49/13状态以实际检查为准。

1. 在Swagger / Apifox从本人/本店列表复制id调用事件接口，核对CREATE→PAYMENT→ACCEPT→START→COMPLETE及关闭分支，确认序号字符串、空前状态/原因、UTC时间和SYSTEM空actorId。
2. 独立真实MySQL/HTTP覆盖学生本人隔离、商家跨店隔离、角色/账号/会话/成员边界、非法/不存在/非BOOKING的ID和历史可见性，验证无权查询不读取事件内容。
3. 验证系统两种超时、学生取消、商家拒单/无法履约及管理员异常，原因与实际actorType/actorId和原数据库一致；重复动作不新增记录、退款不伪造单独事件。
4. 验证相同时间戳及不同记录ID下仍按eventSeq排列，授权空事件返回[]，PRINT资源不提前暴露。查询不会自动关单退款、补写事件或改版本。
5. 用真实并发事务在归属/资格读取之后提交付款、关闭或履约，验证查询使用同一RR快照；验证成员停用的跨请求边界、读取不等待业务写锁。时序控制不能以mock结果代替实际数据库隔离验证。
6. 按影响范围执行事件、相关订单/权限及OpenAPI回归；同步导入文件和验收指南，不为只读接口强制重跑全部资金写用例，不访问用户现有服务数据。记录实际执行和未执行范围，不预先声称测试通过。

事件展示范围（含操作者ID及原原因）、历史读取权限和完整数组规则已获确认。实际完成情况和验证结果以验收指南为准。
