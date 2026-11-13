# 商家打印确认与本店订单查询：已确认方案

- 日期：2026-10-24；校园业务时区Asia/Shanghai，接口响应UTC。
- 状态：用户明确“采用并实现”；已按[ADR-0036](decisions/0036-merchant-print-confirmation.md)落实，操作与实际验证见[指南](merchant-print-confirmation.md)。
- 依据：[业务基线](decisions/0001-v1-baseline.md)、[预约商家确认ADR-0024](decisions/0024-merchant-booking-confirmation.md)、[商家列表ADR-0029](decisions/0029-merchant-order-list.md)、[事件查询ADR-0030](decisions/0030-order-events.md)、[打印订单ADR-0034](decisions/0034-print-orders.md)、[打印支付ADR-0035](decisions/0035-print-mock-payment.md)。

## 1. 推荐范围及完成目标

让商家从本店列表找到打印单，查看冻结规格和付款情况，再决定接单或填写原因退款拒单。补齐事件读取，便于用已有界面核对付款与处理历史。

| 既有入口 | 推荐扩展 |
|---|---|
| GET /api/v1/merchant/orders | 默认本店BOOKING/PRINT，支持按类型/六种主状态筛选，PRINT不再阶段空页 |
| GET /api/v1/merchant/orders/{orderId} | 支持本店PRINT冻结详情及真实支付/退款对象 |
| GET /api/v1/merchant/orders/{orderId}/events | 支持本店PRINT原事件，沿用原权限与展示字段 |
| POST /api/v1/merchant/orders/{orderId}/accept | 支持已付待接单PRINT，成功ACCEPTED / PAID / QUEUED |
| POST /api/v1/merchant/orders/{orderId}/reject | 支持本店PRINT待接单全额退款拒单，成功CLOSED / REFUNDED / ABORTED |

不新增路径、表、迁移、依赖或基础设施，运行仍54个操作、完整62/54/8，V1～V12和21张业务表保持。BOOKING原规则、学生付款/取消和文件保留规则不改。

本轮不开放商家PDF内容读取、打印已接单无法履约取消、开始打印/待取件/学生取件、管理员打印异常或评价。既有商家cancel/start/complete及管理员预约入口遇PRINT继续404。

阶段取舍：接单后PRINT学生按已确认基线不能直接取消，也不会再因30分钟接单期限退款；本轮尚无打印完成或接单后商家取消动作。因此接单测试单会保持ACCEPTED并持续保护文件，不能宣称整个打印履约已闭环。下一项再定稿商家无法履约取消与后续履约/内容授权；本轮可用拒单、学生待接单取消或超时验证退款终结路径。

## 2. 商家身份、查询和展示

正常MERCHANT及本店ACTIVE OWNER才可操作，店铺由服务端所属关系确定，不接收shopId/userId改变归属。无OWNER所属店铺404；本店成员PENDING/DISABLED为403。路径ID沿用规范正BIGINT字符串，非法400；跨店/不存在/不属于本入口开放类型的订单404，不泄露其他店铺订单内容。

读取沿用商家存量权限：店铺暂停、手动停业、服务/价目停用或改名、学生停用，均不隐藏旧单。读取各状态历史，包括未支付PRINT；不要求文件仍在磁盘、仍AVAILABLE或未过保留期。正常商家及有效OWNER仍可查看暂停店铺旧单和拒单退款；店主账号或成员停用则拒绝访问，系统仍可按原规则处理待接单超时。

列表沿用page默认1、size默认20/上限100，六种精确大写状态，createdAt/id倒序，long偏移；参数非法或显式空值仍400。orderType省略查询本店两类，指定BOOKING/PRINT分别筛选。单次只读RR快照固定资格、total及items，联表取冻结摘要，不逐单调用详情或资金查询、不取业务写锁。PRINT摘要预约起止null、printingProgress为真实QUEUED/ABORTED；退款不把原应付金额改为0。

详情复用OrderDetail：PRINT时booking=null，printing含冻结文件ID/文件名、页数、份数、规格、纸张数、单价、进度和阶段时间，并返回已有note、版本、实际payment/refund。不返回磁盘路径、存储key/hash或下载URL，不增加学生姓名/登录名/联系方式或studentId字段。看到fileId不代表获得本人文件下载权限；现有学生文件入口继续限制STUDENT本人。

事件复用九字段，RR内先归属/成员后按eventSeq升序读完整原数组，CREATE从0开始，SYSTEM actorId=null，原actorId和原因按ADR-0030保留，不附加账号资料。GET不自动关闭、退款、续期、补写事件或临时推断状态；资金开关关闭不影响读取。快照后成员停用，下次请求拒绝，跨请求不承诺固定快照。

## 3. 打印接单及合法重复

接单不接收业务请求体、金额、expectedVersion或额外幂等键。当前操作者/归属/成员均有效，店铺还须APPROVED；手动停业或服务/价目停用/改价不阻止已付款旧单处理。目标学生停用不反向加账号锁，也不阻止正常商家处理既有付款订单。

首次PRINT接单推荐满足：

1. 锁后订单PENDING_ACCEPTANCE / PAID / QUEUED，存在匹配orderId/冻结金额/paidAt的唯一成功支付且无退款事实。
2. 锁后统一now严格早于acceptanceDeadline，等于已经到期；不套用预约的startAt限制，打印没有预约起点。
3. 文件元数据仍属于订单学生、AVAILABLE且expiresAt=NULL；异常503 FILE_STORAGE_UNAVAILABLE并回滚。不访问或重新解析PDF、不等待实例文件互斥，沿ADR-0035元数据复核取舍。
4. 订单版本允许递增。

成功仅更新ACCEPTED、acceptedAt=锁后毫秒时刻、版本+1及一次ACCEPT事件（MERCHANT和操作者ID）；paymentStatus保持PAID、printing.progress保持QUEUED，原paidAt/接单截止/规格/金额不变。没有新资金或文件期限写入，合法接单不依赖模拟资金开关；文件版本上限不妨碍无需变更期限的合法接单。

正常账号/本店ACTIVE OWNER、店铺仍APPROVED且已有合法acceptedAt和未退款接单事实时，重复接单200返回原acceptedAt/版本/事件和当前详情，不重判首次接单窗口、文件元数据或写入新的事实。已关闭订单不能重新接单。未来履约状态仍须实际开放对应接口，不合成后续状态。

权限及店铺准入先于业务状态/期限：暂停店铺调用accept为403 SHOP_NOT_APPROVED，重复也一样；不会用接单入口绕过暂停自动退款。暂停后需要退款的待接单单可走合法reject、学生取消或系统扫描。

## 4. 拒单、截止与原因重放

拒单只接收JSON reason，必填字符串，strip后1～300个Unicode码点；拒绝null、数字、空白、超长和未知字段。不新增expectedVersion或创建幂等键。

本店PENDING_ACCEPTANCE / PAID / QUEUED且now < acceptanceDeadline时，正常MERCHANT及ACTIVE OWNER可全额模拟退款拒单，不要求店铺APPROVED、营业或服务仍上架，不读取磁盘对象。退款沿ADR-0035校验成功支付与文件保护元数据不变量。

同事务写入唯一全额退款、CLOSED/REFUNDED/ABORTED、closeReason=MERCHANT_REJECT、closeNote=归一化原因、closedAt/版本和一次MERCHANT_REJECT事件。退款reason_note和事件reason保存同一原因文本，actorType明确MERCHANT、actorId实际操作者，不能复用“非空actorId就当STUDENT”的学生退款推断。原支付事实保留，文件按所有引用统一重算：仍有未终结单则NULL，否则最晚实际终结+7天。

| 锁后情况 | 推荐结果 |
|---|---|
| 本店已MERCHANT_REJECT / REFUNDED关闭，归一化原因相同 | 合法重复200，原退款/关闭事实，不增版本/事件/保留期，不重判原期限或资金开关 |
| 已拒单但原因不同 | 409 ORDER_STATE_CONFLICT，不覆盖原因 |
| USER_CANCEL/其他关闭，或未支付/已接单及后续状态 | 409 ORDER_STATE_CONFLICT，不伪造成拒单或产生二次退款 |
| 权限/格式合法的accept或reject，待接单已到期限 | 先按ACCEPTANCE_TIMEOUT全额退款、关闭ABORTED并重算文件期限，再事务外409 ACCEPTANCE_DEADLINE_PASSED |
| 已ACCEPTANCE_TIMEOUT关闭，再接单/拒单 | 409 ACCEPTANCE_DEADLINE_PASSED，无新事实 |

超时优先于本次商家原因，超时事件仍SYSTEM/null、不记录为MERCHANT_REJECT；扫描先提交、学生取消先提交或商家操作先提交，以已提交的原关闭事实为准。非法请求、身份/归属/准入失败不触发退款。

所有新退款（含商家操作发现超时）受现有模拟资金开关保护，关闭时503 MOCK_PAYMENT_DISABLED，原状态/进度/文件保护保持，不返回假退款后的409。原成功同原因拒单可在关闭开关后重放。合法接单无新资金，即使开关关闭也能进行；不修改本机启动配置/凭据。

## 5. 事务、并发及实现边界

商家写RC：当前商家账号X→会话X→本店店铺S→OWNER当前资格复核→文件X→主订单X→打印明细X→资金/事件。锁后重新核对归属、主状态和进度、截止与事实，提交前复查会话；不反向锁学生/其他经营账号，不跨店锁其他关联订单。成员/店铺配置仍走现有店铺入口，系统扫描继续店铺S→文件X→订单/明细。

商家接/拒单与学生取消、超时、同文件新建在文件行处协调；接单先提交后，原待接单扫描不退款，学生普通取消409。学生取消/超时先提交则接单不能恢复已关闭订单。拒单及最后引用7天更新失败，资金/状态/事件必须全部回滚；仅期限实际变化更新文件版本。

写返回与重放使用锁后的订单/打印明细，避免MyBatis一级缓存重复返回定位旧结果（ADR-0035）。读取继续RR快照，无磁盘IO或实例文件互斥；不把磁盘传输放进资金事务。

复用既有merchant路径按服务端归属及冻结orderType分派，打印流程放现有PrintOrderService/PrintOrderTransactions，不塞入预约日期锁事务。商家定位不得直接调用“学生本人订单”判断而误用学生归属。列表沿既有模块扩展混合查询，复用DTO/VO及注解SQL，不引入XML或空模块；具体普通命名/文件整理由实现自主处理。

## 6. 验收范围（实际执行记录见指南）

1. Swagger/Apifox：学生打印创建/付款→商家本店列表找到PRINT→详情/事件→接单或带原因拒单；学生读取实际结果及商家事件。
2. 真实MySQL/HTTP验证角色、OWNER/店铺暂停、跨店、学生停用、输入/Unicode原因、未付及六种状态筛选、混合列表分页/排序/快照，以及历史文件删除后的冻结详情。
3. 接单严格截止前/等于/后、真实锁等待跨界；停业/改价后旧单接单、资金关闭合法接单、暂停接单403但可拒单退款；重复不重置acceptedAt/版本/事件/期限。
4. 真实并发接单/拒单/学生取消/超时扫描，明确学生接单后不可直接取消、已接单不会接单超时退款；跨店共用文件最后终结、拒单与新建竞争、同/异原因重放及原因/actor归属准确。
5. SQL及最终会话失效故障回滚，订单/文件版本边界，资金关闭与恢复；接单不写钱或文件期限，拒单资金/状态/进度/事件/期限同事务。
6. 复现定位读后其他事务先提交的时序，确认商家写返回/重放不复用旧缓存；RR列表count/items及详情/事件资格快照测试，不用mock结果声称MySQL锁正确。
7. 回归原BOOKING商家查询/接拒单、打印支付/学生取消/历史/文件保护；打印商家cancel/start/complete、管理员及内容下载未开放的边界保持。同步注解、两份OpenAPI、ADR及点击指南，用独立临时MySQL/文件根验证，不访问用户持久数据。

本次已确认商家PRINT可见范围、接单/拒单资格与重复操作细则、原因展示、接单后本阶段边界及沿用的文件/退款并发协议。PDF内容授权及打印履约不在本次授权范围。
