# 商家预约确认：已确认方案

- 日期：2026-10-22（Asia/Shanghai）。
- 状态：用户已明确“采用并实现”；依据 [ADR-0024](decisions/0024-merchant-booking-confirmation.md)，实际验证见[验收指南](merchant-booking-confirmation.md)。
- 依据：[业务基线](decisions/0001-v1-baseline.md)、[接口契约](v1-api-contract.md)、[状态与事务](v1-state-transitions.md)、[支付 ADR-0023](decisions/0023-booking-mock-payment.md)。30分钟接单期限、接单后学生距开始至少1小时可取消沿用已确认基线，不重新提请决定。

## 1. 推荐范围

| 接口/能力 | 本轮范围 |
|---|---|
| GET /api/v1/merchant/orders/{orderId} | 查询本店预约详情，返回现有 OrderDetail |
| POST /api/v1/merchant/orders/{orderId}/accept | 接受已付款待确认预约，无请求体 |
| POST /api/v1/merchant/orders/{orderId}/reject | 拒绝已付款待确认预约，必填原因，全额模拟退款 |
| 已有 POST /api/v1/orders/{orderId}/cancel | 落实 BOOKING + ACCEPTED 距开始至少1小时的本人取消及全额退款 |

先提供商家详情以便核对待处理预约；商家可用学生创建时取得的测试订单ID验收，不必同时引入列表分页。接单使 ACCEPTED 成为真实可达状态，因此同步落实已确认的学生取消权，避免新状态下缺失基线能力。

新增3个操作，运行版37→40，完整规划仍62，待开发25→22。复用 order 模块及V9/V10的19张业务表，不新增依赖、迁移或基础设施。商家列表/事件查询、接单后无法履约取消、开始/完成、管理员异常、打印订单留待后续小任务。

## 2. 身份、归属和经营资格

三个商家接口仅正常 ACTIVE / MERCHANT 账号及本人 ACTIVE OWNER 可用。订单须为本店 BOOKING；不存在、其他店铺或类型不符统一404 RESOURCE_NOT_FOUND。学生/管理员调用403 ROLE_FORBIDDEN；失效会话沿用401，停用账号沿用403 ACCOUNT_DISABLED。本人OWNER关系非ACTIVE为403 ROLE_FORBIDDEN，不新增员工账号或代理接单权限。

推荐区分接新履约承诺与处理既有责任：

- **首次及重复接单均要求店铺仍APPROVED**，否则403 SHOP_NOT_APPROVED。
- **详情与拒单不要求当前APPROVED**，但仍须正常账号和ACTIVE OWNER。店铺若后续被暂停，应可核对历史单及退还尚未接单的钱；本轮不新增暂停接口。账号或OWNER已停用时仍禁止操作，由已有超时退款处理待接单单。
- 手动停业、服务下架/改价/改名/改时长、分类停用不阻止处理原有合法订单。接单使用原服务与预约快照，不重新报价、不重新要求提前2小时；配置对存量占用的保护继续生效。
- 详情可读本店未付、待接单、已接单及已关闭预约，不自动退款或改状态。复用已有OrderDetail，不增加学生账号ID、登录名、联系方式或会话字段；payment/refund是已有模拟流水事实，不提供文件下载权限。

## 3. 商家接单

路径ID沿用规范正BIGINT字符串；拒绝业务请求体，包括JSON null、{}和任意金额/版本字段。无需新增 expectedVersion 或 Idempotency-Key，以锁内状态和一次接单事实处理并发及重复。

首次接单要求 BOOKING + PENDING_ACCEPTANCE + PAID + ACTIVE占用，存在金额一致的成功支付且未退款，锁后服务器 now 严格早于 acceptanceDeadline 及预约startAt。接单原子完成：

- 状态 PENDING_ACCEPTANCE → ACCEPTED，acceptedAt取锁后UTC毫秒时刻，version+1。
- 支付状态仍PAID，占用仍ACTIVE，不改变金额/预约/支付或接单截止时间。
- 写一条ACCEPT事件，actor=MERCHANT，记录实际账号，不生成新资金流水。

已接单且存在原acceptedAt、未退款的重复接单返回200当前详情，不覆盖接单时间、期限、版本或事件。未来IN_SERVICE/COMPLETED同样只重放既有接单事实；任何CLOSED状态不恢复为ACCEPTED。

锁内发现待接单单 now >= acceptanceDeadline：全额退款并以ACCEPTANCE_TIMEOUT关闭、释放，提交后返回409 ACCEPTANCE_DEADLINE_PASSED。已经如此关闭仍返回同码；其他非法状态409 ORDER_STATE_CONFLICT。合法期限却已到预约开始的异常组合返回409 BOOKING_TIME_INVALID，不接单；正常创建/付款期限使该组合不可自然出现。

## 4. 商家拒单与重复请求

请求仅为 `{"reason":"人员临时无法提供服务"}`。原因去首尾空白后1～300个Unicode码点；缺失、null、空白、错误类型、超长或未知字段400 VALIDATION_FAILED。

仅已付待接单、且未到接单期限时可首次拒单：全额模拟退款，主单CLOSED/REFUNDED，closeReason=MERCHANT_REJECT，closeNote为归一化原因，占用RELEASED，version+1。退款reason存MERCHANT_REJECT、reason_note存原因，关闭事件记录商家账号及原因；保留成功支付事实，只增加一笔退款、一条关闭事件。

- 已因MERCHANT_REJECT关闭且归一化原因相同：200当前详情，不新增退款、事件或时间；不同原因409 ORDER_STATE_CONFLICT，不覆盖历史原因。
- 待接单时已到期限：沿用ACCEPTANCE_TIMEOUT全额退款，提交后409 ACCEPTANCE_DEADLINE_PASSED，不把系统到期改为商家拒单。
- 已接单、未付款、其他原因关闭等：409 ORDER_STATE_CONFLICT。已到期关闭仍409 ACCEPTANCE_DEADLINE_PASSED。

商家拒单和学生取消竞争，以先取得同一日期/订单锁并提交的合法迁移为准，失败方如实返回最终状态；不能把USER_CANCEL改写为MERCHANT_REJECT，也不能二次退款。

## 5. 已接单后的学生取消

落实既有基线：本人BOOKING + ACCEPTED + PAID + ACTIVE，锁后 `startAt - now >= 1小时` 允许取消，**等于1小时允许**；全额模拟退款，CLOSED/REFUNDED/RELEASED，USER_CANCEL，version+1，关闭事件fromStatus=ACCEPTED、actor=STUDENT。

不足1小时为409 CANCEL_WINDOW_CLOSED，不退款、不改状态；IN_SERVICE/COMPLETED仍409 ORDER_STATE_CONFLICT。USER_CANCEL关闭重放200，即使重试时已不足1小时也不重新判断原窗口。待接单取消继续按原30分钟期限处理。不得借接单重置原支付/预约时间来改变取消窗口。

## 6. 模拟资金、锁与失败处理

沿用默认关闭的模拟资金开关。接单不生成新资金，对合法未过期已付单可在开关关闭时执行；拒单、已接单学生取消、接单/拒单触发的超时关闭凡需新退款，必须开关启用，否则503 MOCK_PAYMENT_DISABLED且整笔无写入。已存在且合法的同原因拒单/USER_CANCEL结果可重放，不制造新钱。

写RC：当前操作者账号→会话→店铺S→本人OWNER资格复核（商家路径）→日期保护行→主订单/预约当前读→资金/事件；提交前复查会话。OWNER复核不改变店铺入口先于日期的顺序，不持店铺锁反向锁其他学生/商家账号。候选、超时、创建和配置继续遵守既有同一锁协议，锁等待前的普通SELECT或Mapper缓存不得用作最终状态。

详情读取RR，在同一快照验证本店资格及订单/占用/支付/退款；GET不写入。接单不递增店铺/人员/服务配置版本。退款、主状态、占用、事件任一步失败整笔回滚；死锁/锁超时503 SERVICE_TEMPORARILY_UNAVAILABLE，客户端可重试原操作。到期关闭须在事务提交后转为HTTP409。

接单与超时竞争：接单先成功则扫描不再关闭ACCEPTED；超时先成功则接单409且仅一笔退款。接单与学生取消竞争：若先接单，学生按已接单1小时窗口处理；若先取消，商家不可恢复已关闭单。未实现开始/完成前，已接单且学生不再可取消的订单会停在ACCEPTED，后续履约任务处理，不自动认定完成或未到店。

## 7. 完成标准与验收

1. 真实HTTP验证三接口权限/归属/请求体、详情字段及RR一致性；手动停业、下架、经营资格或成员变化分别验证。
2. 接单首次与多端重复只写一次acceptedAt/版本/事件，拒单同原因重放仅一笔退款，改原因不能覆盖历史；合成后续状态仅用于重放边界，不宣称履约已实现。
3. Clock覆盖接单截止前/等于/后、学生1小时窗口前/等于/后；真实MySQL锁等待跨界按取得锁后的时刻判断。
4. 接单/拒单/学生取消/超时扫描/新预约竞争，最终资金、状态与占用一致；已接单不被待接单扫描误退款。
5. 模拟资金关闭时合法接单及已有事实重放可用，所有新退款被阻止；恢复仍按原期限处理。
6. 故障注入验证接单事件失败、退款与关闭各阶段失败、最终会话过期均回滚；真实唯一约束及锁超时不得仅用mock声称正确。
7. 同步Swagger注解、实际运行OpenAPI、完整规划和点击指南。独立临时MySQL验证，不访问用户现有库；按风险执行相关回归，记录执行及未执行范围。

人工流程：学生创建并付款，保存订单ID→商家登录并Authorize→本店详情核对快照→接单并重复检查acceptedAt→学生在允许窗口取消并核对全额退款；另一笔由商家填写原因拒单并重复核对同一退款。商家列表未实现时直接填写测试订单ID。详细点击指南随实现交付。

本方案已获确认，当前实现与实际验证记录见[商家预约确认验收](merchant-booking-confirmation.md)。
