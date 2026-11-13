# 学生本人订单分页列表：已确认方案

- 日期：2026-10-22（Asia/Shanghai）。
- 状态：用户已明确“采用并实现”。决策见[ADR-0028](decisions/0028-student-order-list.md)，交付与实际验证见[验收指南](student-order-list.md)。
- 依据：[接口契约](v1-api-contract.md)、[预约订单 ADR-0022](decisions/0022-booking-orders.md)、[模拟支付 ADR-0023](decisions/0023-booking-mock-payment.md)、[履约 ADR-0026](decisions/0026-booking-fulfillment.md)、[管理员异常 ADR-0027](decisions/0027-admin-booking-exception.md)。

## 1. 本轮范围与理由

新增 **GET /api/v1/orders**，让学生在Swagger / Apifox按状态找到本人订单，再使用现有详情、付款及取消接口。仅新增一个只读接口；运行操作45→46，完整规划仍62，待开发17→16。

本轮只展示已实现的BOOKING预约。保留规划中的orderType参数，合法PRINT筛选返回空页，不查询或组装尚未实现的打印订单；不以空结果宣称打印下单已经可用。打印接入时再扩展实际查询与类型摘要。

沿用order模块、现有MyBatis注解SQL与V1～V10共19张业务表，复用学生分页索引ix_order_student(student_id, created_at, id)。不新增迁移、分页插件、缓存、依赖或通用分页框架。本轮不包含商家订单列表、订单事件、搜索、日期筛选、导出或打印业务。

## 2. 权限与历史可见性

仅正常ACTIVE / STUDENT及有效会话可查本人预约；身份来自服务端会话，不提供切换学生或店铺归属的查询参数。无登录/失效会话沿用401，商家和管理员403 ROLE_FORBIDDEN，账号停用403 ACCOUNT_DISABLED。

本人所有预约状态均可查询，包括关闭、退款与完成。店铺暂停/停业、经营账号或OWNER停用、服务下架/改名/改价不隐藏历史订单；名称、金额及预约时间读取下单快照，不用当前服务资料重新计算，不暴露其他学生订单。

查询不执行超时清理、退款或状态推断。到期但扫描尚未提交关闭时，仍按数据库中的PENDING_PAYMENT或PENDING_ACCEPTANCE展示和筛选；不会在列表中临时伪装为CLOSED。打开详情可查看原截止时间，后续动作继续执行既有期限规则。

## 3. 查询参数与分页

| 参数 | 建议规则 |
|---|---|
| orderType | 可省略；只接受精确大写BOOKING或PRINT，省略与BOOKING均查询本人预约，PRINT返回空页 |
| status | 可省略；只接受精确大写PENDING_PAYMENT / PENDING_ACCEPTANCE / ACCEPTED / IN_SERVICE / COMPLETED / CLOSED；省略不筛选 |
| page | 省略默认1；十进制正整数，最大2147483647 |
| size | 省略默认20；十进制正整数1～100 |

枚举显式空值、空白、大小写错误或未定义值400 VALIDATION_FAILED；分页显式空值、符号、小数、空白、零/负数及超范围同样400，不做静默纠正。分页沿用现有公开目录的十进制数字解析，前导零不另设新限制。所有参数先完整校验，不能因为PRINT返回空页就跳过非法status/page/size。

固定排序 **createdAt DESC, id DESC**，相同创建时刻仍有确定顺序；不提供客户端排序表达式。页码越界返回200及items=[]，保留真实total/totalPages；无匹配记录时total=0、totalPages=0。页码偏移用long计算，不能在int乘法中溢出。

V1沿用页码分页；不同请求之间若有新增订单或状态变化，后续页可能移动，不承诺跨请求固定快照或实时游标。单次响应的总数与页内数据须一致。

## 4. 响应摘要

200统一ApiResponse，data为OrderPage：items、page、size、total、totalPages。items每项OrderSummary沿用既有规划字段：

| 字段 | 来源与语义 |
|---|---|
| id、orderNo、orderType | 订单标识；实体ID十进制字符串，本轮实际orderType为BOOKING |
| status、paymentStatus | 同一快照内的主状态及支付状态，分开返回 |
| shopId、shopName、serviceName | 店铺ID字符串；店铺及服务名称为下单快照 |
| payableAmount、currency | 冻结应付金额，整数分；人民币CNY；退款后仍保留原应付金额，不改为0 |
| createdAt | 订单创建时刻，UTC时间 |
| bookingStartAt、bookingEndAt | 冻结预约起止，UTC时间 |
| printingProgress | 本阶段固定null，打印流程后续接入 |

摘要不附带付款/退款流水对象、完整核实原因、学生备注、人员资料、订单事件或登录账号信息；这些字段需要时使用已有授权详情查询。摘要不新增version，后续需要基于版本操作时先读详情。

总数与记录数按现有分页协议用数值，实体ID用字符串；摘要不是详情的另一套状态模型。未来打印摘要在对应任务定稿，不创建空Java打印模块。

## 5. 一致性与验收标准

采用只读REPEATABLE_READ事务，总数与分页使用相同归属、预约类型及状态条件，在一个一致快照中查询；联表一次取得当前页预约快照，避免逐单调用详情或逐单查询资金。不获取账号写锁、店铺锁或日期保护行，不改变现有写事务锁协议，也不触发订单版本或资金写入。

实施与验证要求：

1. Swagger / Apifox：学生登录后Authorize，GET /orders省略筛选查看全部本人预约；选status查对应状态，记录id并调用已有详情核对快照。默认页、空页与下一页均可直接点击验证。
2. 独立真实MySQL/HTTP验证本人隔离、六种状态、类型/状态组合、固定排序及同毫秒创建的id次序；不同学生/商家数据不能进入结果。
3. 验证默认值、size上下限、超出末页、空数据、最大page偏移、非法枚举/分页与PRINT空页仍完整校验；停用账号和非STUDENT拒绝。
4. 验证店铺暂停/目标经营账号停用、配置名称/价格变化不影响历史；已退款仍保留原金额，过期未关闭仍显示实际状态；查询不增加支付/退款/事件或递增版本。
5. 在总数查询与分页查询之间，让真实并发事务创建订单或迁移状态，证明本次total/items来自同一个RR快照；后续独立请求能看到已提交变化。此为真实数据库一致性验证，不依靠mock证明隔离级别。
6. 同步Swagger注解、运行OpenAPI、完整规划、决策记录和人工点击指南；按影响范围执行相关验证，不为只读列表反复重跑全部退款写用例，不访问用户现有服务数据。

本方案已获确认。实际交付及检查结果以[验收指南](student-order-list.md)为准。
