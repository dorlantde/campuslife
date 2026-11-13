# 预约开始与完成履约：已确认方案

- 日期：2026-10-22（Asia/Shanghai）。
- 状态：用户已明确“采用并实现”；依据 [ADR-0026](decisions/0026-booking-fulfillment.md)，实际验证见[履约验收](merchant-booking-fulfillment.md)。
- 依据：[业务基线](decisions/0001-v1-baseline.md)、[状态与事务](v1-state-transitions.md)、[接口契约](v1-api-contract.md)、[商家确认 ADR-0024](decisions/0024-merchant-booking-confirmation.md)、[无法履约取消 ADR-0025](decisions/0025-merchant-booking-cancellation.md)。理发由商家标记完成沿用基线；开始时间窗口、提前完成占用、重复操作及存量权限在本次定稿。

## 1. 推荐范围与理由

| 接口 | 推荐能力 |
|---|---|
| POST /api/v1/merchant/orders/{orderId}/start | 已接单预约开始服务，ACCEPTED→IN_SERVICE |
| POST /api/v1/merchant/orders/{orderId}/complete | 已开始的预约完成，IN_SERVICE→COMPLETED |

两接口均无请求体，200返回现有OrderDetail。新增后运行版41→43，完整规划仍62，待开发21→19。复用order模块、现有锁入口和V9/V10共19张业务表；不新增迁移、XML、依赖或基础设施。

这次补齐“学生创建→付款→商家接单→开始→完成”的正常预约流程。订单列表/事件查询、评价、管理员异常、打印流程和通知不在本轮；不以正常流程完成宣称全部异常闭环已完成。

## 2. 身份、归属与存量责任

仅正常ACTIVE / MERCHANT账号、当前有效会话及本店ACTIVE OWNER可操作本店BOOKING。学生/管理员403 ROLE_FORBIDDEN，账号停用403 ACCOUNT_DISABLED，本人OWNER未激活或停用403 ROLE_FORBIDDEN；不存在、跨店或非BOOKING统一404 RESOURCE_NOT_FOUND。

建议开始/完成均视为**已接单旧单的履约处理，不再要求店铺当前仍APPROVED**：店铺暂停、手动停业、分类停用、服务下架/改名/改价/改时长不阻止正常账号与有效店主按原约提供并完成服务。与首次接单仍须APPROVED的规则区分，不扩大接新单资格。

不重新报价、不改变原人员、服务时长和预约区间，不重新要求提前2小时或处于今天至第6天的创建窗口。账号/OWNER停用仍禁止操作，存量异常留待管理员任务，不新增代操作身份。

两动作不生成支付或退款，因此不要求模拟资金开关开启；原支付状态及成功流水须有效，已退款不能开始或完成。不把付款开关关闭视为清除已收款事实。

## 3. 开始服务的时间与原子结果

首次开始必须为BOOKING + ACCEPTED + PAID + ACTIVE，存在与冻结应付金额一致的成功支付且未退款。

锁后使用服务器now判断 **startAt <= now < endAt**：

- 等于预约开始允许，早于开始或等于/超过结束均409 BOOKING_TIME_INVALID，无写入。
- 不支持提前开始或超时补开始。已接单但预约结束仍未开始，保持ACCEPTED及原占用，不自动完成、退款或认定未到店；后续管理员核实异常。
- 通过判定后，startedAt固定为本次锁后服务器UTC毫秒时刻，不在写入过程中反复移动开始时间。提交前仍复查会话，身份过期整笔回滚。

成功一次提交：主单IN_SERVICE、startedAt、version+1，以及一条START事件（fromStatus=ACCEPTED、actorType=MERCHANT、actorId为实际账号）；预约仍ACTIVE，资金与其他业务快照不变。

开始动作只能表明商家记录到店服务，不引入学生扫码核销、定位或真实到店证明。

## 4. 完成服务与原区间占用

首次完成必须为BOOKING + IN_SERVICE + PAID + ACTIVE，具有合法startedAt及成功支付、无退款。不得从ACCEPTED跳过开始直接完成。

建议不设置最低服务时长，也不要求等到原endAt：**可开始后立即或提前完成，也可在预约结束后补记完成；锁后now须不早于startedAt**。等于startedAt允许，服务器时间倒退至其之前则409 BOOKING_TIME_INVALID，无写入；completedAt使用本次锁后UTC毫秒时刻。

成功一次提交：

- 主单COMPLETED、completedAt、version+1，一条COMPLETE事件（fromStatus=IN_SERVICE、MERCHANT及实际账号）。
- 占用ACTIVE→CONSUMED，保留原startAt/endAt及日期保护行，不删除预约或缩短区间，不改成RELEASED。
- 原payment保持SUCCEEDED、主单paymentStatus仍PAID，不生成新资金；refund仍null，reviewed仍false，评价后续实现。

保留原区间是为了避免提前完成后把同一已售时间再次开放。公开候选及创建继续排除CONSUMED；晚于endAt才完成也不自动延长原预约区间，超时服务的排期调整不在本轮。

沿用已有配置保护差异：提前完成后，人员启停/解绑可按原CONSUMED规则配置；未结束的原区间仍受排班/营业时间覆盖保护。完成不修改任何店铺、服务或人员版本，不借此静默改变ADR-0022的保护规则。

## 5. 请求、重复操作与状态错误

两个接口仅接收规范正BIGINT字符串orderId及Bearer身份，不接收业务请求体，包括{}、JSON null、客户端时间、金额、expectedVersion或任意目标状态。非法格式400 VALIDATION_FAILED；不增加Idempotency-Key。

| 情况 | 结果 |
|---|---|
| 首次合法开始/完成 | 200，提交对应一次迁移及事件 |
| 已IN_SERVICE，重复开始且已有原startedAt | 200当前详情，不覆盖startedAt/版本/事件 |
| 已COMPLETED，重复开始且存在原开始/完成事实 | 200当前完成详情，仅重放原开始事实，不恢复IN_SERVICE |
| 已COMPLETED，重复完成且存在原完成事实 | 200当前详情，不覆盖completedAt/版本/占用/事件 |
| 合法重复时已超原时间窗、资金开关关闭 | 权限仍有效则重放原事实，不重新判断首次时间窗口 |
| CLOSED（包括未来开始后异常关闭），请求开始或完成 | 409 ORDER_STATE_CONFLICT，不恢复履约 |
| 未付款、待接单、已接单直接完成等非法状态 | 409 ORDER_STATE_CONFLICT，无写入 |

所有重试仍先验证正常账号、当前会话和本店OWNER；成员或账号停用不能绕过权限重放。底层状态/占用/流水若不符合应有组合，不生成新履约事实，不掩盖内部不一致。

完成后已有接单接口可继续重放原接单事实；学生取消、商家普通取消及拒单均不能把COMPLETED改成退款关闭。支付接口仅重放既有未退款支付事实，不生成第二笔支付。

## 6. 锁、并发与失败处理

写事务RC，沿用商家私有锁入口：当前商家账号→会话→店铺S→OWNER复核→日期保护行→订单/预约当前读→状态/占用/事件。禁止反向锁学生或经营账号；等待前定位数据及Mapper缓存不能作为最终状态，提交前复查当前会话。

- 多端重复开始/完成只能产生一个对应时间事实、一条对应事件及一次版本递增。
- 开始/完成并发按锁后状态决定：开始先提交则完成可合法继续；完成先看到ACCEPTED则409，不能跳过开始。
- 取消与开始使用同一日期/订单锁及锁后时间判定。取消先提交则不能开始；到startAt后仍ACCEPTED时，普通商家取消已过窗口，开始可按窗口执行；IN_SERVICE不能普通退款。
- 完成与候选查询/新建竞争：RR只读看到提交前ACTIVE或提交后CONSUMED，都保留原区间占用，不出现可再次售卖的空档。
- 原待接单扫描不退款IN_SERVICE/COMPLETED；创建清理也不能关闭这两种状态以释放原区间。
- 状态、占用或事件任何一步失败整笔回滚；版本上限拒绝实际递增，真实死锁/锁超时503 SERVICE_TEMPORARILY_UNAVAILABLE，重试重新核对状态/权限/时间。

时间错误均不承担补偿关闭，不使用“先退款再返回409”的语义。没有后台自动开始、自动完成、异常批量退款或新增资金路径。

## 7. 完成标准与人工验收

1. Swagger / Apifox按原创建、付款、接单流程准备虚构订单；到预约开始后商家点击start，再点击complete，双方详情依次IN_SERVICE/ACTIVE、COMPLETED/CONSUMED，支付金额和快照保持。
2. Clock验证开始前/等于开始/结束前/等于结束/结束后，以及真实锁等待跨界；完成可提前、等于startedAt、晚于endAt，倒退时间拒绝。
3. 独立MySQL验证多端重复、开始/完成竞争、取消与开始锁后状态、配置变更协调；两种动作只各产生一次时间/版本/事件，原支付与退款记录不变。
4. 通过较长原预约提前完成及较短新服务候选，验证原未来区间CONSUMED仍被实际查询/创建排除；不能只验证已经过去的开始时刻。排班/营业时间仍保护未结束原区间。
5. 覆盖正常身份/OWNER、本店/类型、暂停/停业/下架、资金开关关闭下的旧单履约、非法状态与无请求体约束。
6. 实际数据库更新后注入事件/占用故障、会话过期及真实锁超时，确认整笔回滚和可安全重试；不以mock声明数据库锁成立。
7. 同步Swagger注解、实际运行OpenAPI、完整规划、决策及点击指南，按改动风险执行相关回归，不访问用户现有库。

人工正常流程需等待已创建预约的实际开始时刻，沿用至少提前2小时创建规则；不新增绕过时间的HTTP调试接口，不要求人工修改系统时间。精确边界及长时段提前完成保护由受控Clock自动测试验证。

本方案已获确认。实际构建、独立MySQL及接口文档验证见[履约验收](merchant-booking-fulfillment.md)。
