# CampusLife V1 设计讲解与代码导读

范围说明：本页主要解释V1交易基线。当前项目已增加Redis、MQ和优惠券等主线，请先从[M07证据地图](interview/project-evidence.md)及[源码复盘](interview/source-review.md)进入；V1历史报告不等于新版本全量回归。

本材料与[演示指南](project-demo.md)配套，按“遇到什么问题 → 当前怎么做 → 代价与验证”组织。链接指向当前源码与已有验收资料；先理解前三条，再逐步深入，无需一次读完全部文件。

## 1. 先看清工程和请求路径

采用模块化单体：同一 Maven 工程、同一应用与 MySQL 数据库，按 auth、merchant、order、file、admin 等业务模块组织，模块内部再按 controller/service/dto/vo/entity/mapper 分层。[完整包结构](project-structure.md)保留了演进过程。

| 职责 | 例子 | 阅读时关注 |
|---|---|---|
| Controller | [PrintOrderController](../src/main/java/com/campuslife/order/controller/PrintOrderController.java) | 路径、参数、身份、状态码与调用入口 |
| Service | [PrintOrderService](../src/main/java/com/campuslife/order/service/PrintOrderService.java) | 业务编排、文件IO/互斥与事务之间的边界 |
| Transactions | [PrintOrderTransactions](../src/main/java/com/campuslife/order/service/PrintOrderTransactions.java) | 锁后校验、状态变化、资金/事件和原子提交 |
| Policy | [PrintQuotePolicy](../src/main/java/com/campuslife/order/service/PrintQuotePolicy.java) | 严格参数、计价与无数据库的规则计算 |
| Mapper / Entity | [PrintOrderMapper](../src/main/java/com/campuslife/order/mapper/PrintOrderMapper.java)、[PrintDetailEntity](../src/main/java/com/campuslife/order/entity/PrintDetailEntity.java) | 查询、条件更新和表字段映射 |
| DTO / VO | [PrintCreate](../src/main/java/com/campuslife/order/dto/PrintCreate.java)、[OrderDetail](../src/main/java/com/campuslife/order/vo/OrderDetail.java) | 输入契约与输出范围，避免直接返回持久化实体 |

Transactions 是有实际事务职责的 Spring Bean，不是要求所有模块都套同样文件模板。Service 调用另一个Bean的事务方法，方便让数据库事务围住短业务操作，将文件等待/IO放在事务外，也避免把同类内部调用误当成代理事务入口。

MyBatis-Plus 提供基础映射与CRUD；明确的锁查询、联表和条件写入使用 Mapper 注解SQL。没有 XML 是当前实现选择，不能理解为 MyBatis-Plus 自动解决了事务或并发。

选择单体是因为两类业务需要紧密的本地事务，当前学习与单机规模没有拆服务需求。代价是模块仍存在代码耦合；未来只有实际部署、团队或容量问题出现时再评估拆分。V1基线当时未使用Redis或MQ；当前Redis评分缓存、Lua领券和RabbitMQ已实现，仍为模块化单体、未拆微服务。

## 2. JWT为什么还需要会话表

问题：JWT签名只能证明凭证由可信签发方生成、内容未被篡改，并不能单独解决“退出立即撤销当前登录”或“停用账号使旧登录失效”。签名密钥由服务端保管，与用户登录密码、数据库密码不同。

当前每次登录创建独立会话，JWT有效期2小时，不做刷新令牌。请求经 [SessionAuthenticationFilter](../src/main/java/com/campuslife/auth/security/SessionAuthenticationFilter.java) 验证token，再由 [SessionService](../src/main/java/com/campuslife/auth/service/SessionService.java) 检查数据库会话与账号；关键写事务还会在加锁后复核。退出只撤销当前会话，改密和实际账号状态变化撤销该账号全部旧会话，恢复账号后仍需重新登录。

代价是需要数据库读取，系统并非完全无状态JWT认证。收益是撤销语义可解释且能与关键写入协调。不声称已请求中的所有读和下载都会被瞬间打断：短RR读取按已取得快照工作，已授权文件传输允许完成。

阅读 [TokenService](../src/main/java/com/campuslife/auth/security/TokenService.java)、[ADR-0005](decisions/0005-login-sessions-jwt.md)、[账号状态实现](../src/main/java/com/campuslife/admin/service/AdminAccountStatusService.java)。会话和权限验证证据见全量报告的账号模块；不是只测“能登录”。

## 3. 为什么共用订单，还保留两种明细

问题：预约和打印都要支付、接单、退款、列表与事件，但它们的资源和履约阶段不同。

当前 [ServiceOrderEntity](../src/main/java/com/campuslife/order/entity/ServiceOrderEntity.java) 保存通用状态、金额、版本等公共事实，预约的时段/占用放在 [BookingDetailEntity](../src/main/java/com/campuslife/order/entity/BookingDetailEntity.java)，打印的文件/规格/进度放在 [PrintDetailEntity](../src/main/java/com/campuslife/order/entity/PrintDetailEntity.java)。共用查询和事件入口，写动作按冻结订单类型分派。

| 维度 | 预约正常终态 | 打印正常终态 |
|---|---|---|
| 订单主状态 | COMPLETED | COMPLETED |
| 支付状态 | PAID | PAID |
| 专属资源/进度 | CONSUMED，保留原区间 | PICKED_UP，学生确认取件 |
| 结束入口 | 商家complete | 学生pickup |

这些维度分开，避免把所有业务细节塞进单个大枚举，但需要校验状态组合与明细一致。扩展新场景时先讨论其资源、状态和边界，不能仅加一个类型枚举就视为接入完成。

服务和价格可以后续修改，历史订单展示冻结快照，支付使用冻结金额。报价只做估算；创建时重新校验配置版本和金额，冲突要求用户重新确认。见[数据库设计](v1-database-design.md)、[状态迁移](v1-state-transitions.md)。

## 4. 两个人同时预约一个时段，怎么防止超卖

问题：如果只“查询没有订单，再插入”，并发请求可能同时查到空位。时间区间还允许部分重叠，单独对开始时间建唯一索引也不够。

当前 [BookingOrderTransactions.create](../src/main/java/com/campuslife/order/service/BookingOrderTransactions.java) 在写事务中按协议取锁：操作者账号→会话→店铺共享锁→理发师/日期保护行→后续配置、占用与订单。日期保护行来自排班，即使当天还没有订单，也有稳定的互斥点；新建预约不会凭空创建缺失保护行。

持有日期保护行后，通过 [BookingMapper.lockOccupied](../src/main/java/com/campuslife/order/mapper/BookingMapper.java) 当前读检查 HELD/ACTIVE/CONSUMED 区间，半开区间重叠条件为 `a.start < b.end && a.end > b.start`。相邻区间可共存，重叠请求在锁后重判，只有合法请求能写入。候选查询是普通只读快照，不占位，最终以下单事务为准。

经营配置使用店铺排他锁，再按日期顺序锁保护行与资源；遵守统一顺序，防止配置与预约相互破坏。提前完成仍CONSUMED保留原区间，未提交的过期HELD仍保守占用，释放必须是已提交事实。

代价是同一理发师同一天的相关写入会串行，店铺配置也可能阻塞下单；这是当前规模下简单可验证的方案。锁顺序减少竞争环路，仍需处理锁超时等数据库异常，不能声称“永不死锁”或“无限并发”。

依据：[ADR-0022](decisions/0022-booking-orders.md)；[BookingOrdersMysqlIT](../src/test/java/com/campuslife/BookingOrdersMysqlIT.java) 的 `twentyStudentsCompetingForAnIntervalOnlyCreateOneOrder` 验证20个学生并发竞争，`failureAfterEachWriteRollsBackTheEntireCreation` 验证写入故障回滚。使用真实MySQL；这些结果不等于容量压测。

## 5. 重复提交、重复付款和重试分别怎么处理

创建接口按“账号＋操作＋Idempotency-Key”隔离，并保存归一化请求摘要与orderId。相同键、相同请求返回原单；相同键改内容冲突。PRINT和BOOKING操作分开，数据库唯一约束兜底并发重复。查看 [IdempotencyMapper](../src/main/java/com/campuslife/order/mapper/IdempotencyMapper.java)、[预约V9迁移](../src/main/resources/db/migration/V9__create_booking_orders.sql)和两类创建事务。

已有订单付款和履约则根据锁后状态及已保存事实判断重放，并不要求每个动作都传创建键。[MoneyMapper](../src/main/java/com/campuslife/order/mapper/MoneyMapper.java) 操作唯一支付/全额退款事实；[V10迁移](../src/main/resources/db/migration/V10__booking_mock_payments.sql) 提供约束。新资金、订单状态、资源/文件引用和事件在同一事务提交；失败时整体回滚。

重复语义依动作决定：同原因商家拒单可重放；管理员异常还需匹配操作者、原因、原版本和完整关闭事实；评价无论相同或不同内容，重复提交均409。不能笼统宣称“所有接口都幂等”或“所有重复请求都成功”。

模拟支付是本地数据库事实，没有外部支付渠道。这里的一次支付/退款保证不能直接推广成跨系统恰好一次；接入真实支付时需要单独设计通知、对账与补偿。

## 6. 为什么超时关闭后还会返回409

未支付15分钟到期，已付待接单30分钟到期。扫描器按数据库期限恢复处理，每批有配额、每单独立事务，不依赖内存倒计时；GET只返回已保存状态，不自动退款。

遇到请求已过期限，可能需要先提交“超时关闭/退款/释放”的事实，再告诉客户端本次支付或接单已不能执行。如果在这个事务里直接抛出会触发回滚的异常，刚写入的关闭也会回滚。

当前事务方法返回结果和期限错误标识，由外层Service在事务提交后转换成HTTP409。可从 [PrintOrderService.deadline](../src/main/java/com/campuslife/order/service/PrintOrderService.java) 对照 [PrintOrderTransactions.pay](../src/main/java/com/campuslife/order/service/PrintOrderTransactions.java) 阅读；预约采用相同的提交边界思路。

新支付/退款受默认关闭的模拟开关保护。开关关闭时，已付到期单不会被假装退款；重新启用后按原期限处理。扫描存在延迟，接口不承诺到期瞬间所有状态同步变化。

## 7. 金额怎么计算，为什么不用浮点数

金额统一为整数分。打印单价按印刷面计，金额为 `页数×份数×单价`；双面纸张为 `ceil(页数/2)×份数`，每份独立取整。5页、2份、20分/面得到200分、6张纸。

[PrintQuotePolicy.calculate](../src/main/java/com/campuslife/order/service/PrintQuotePolicy.java) 使用long与精确乘法。已确认上限500页×100份×100000分=5000000000分，超过int范围；因此打印金额使用long。服务参考起价不代替规格价目，客户端不能通过自报页数或金额改变服务端计价。

long适合当前整数分模型；若未来引入折扣、税率或多币种，需要重新明确舍入和精度规则，不能直接复用本例推断。依据：[ADR-0033](decisions/0033-print-quotes.md)、[PrintQuoteMysqlIT](../src/test/java/com/campuslife/PrintQuoteMysqlIT.java)。

## 8. PDF为什么不能直接放到静态目录

文件属于学生，只向当前被授权的人提供内容。学生本人有效文件可以下载；商家须有本店合格已接单/打印中/待取件任务；管理员没有默认内容权限。文件仍在磁盘上、处于七天保留期或知道fileId，都不能代替业务授权。

上传先做PDF实际解析与限制检查，再用数据库状态协调发布与失败补偿。文件移动、读写和删除不能跟MySQL一起原子提交，所以 [FileTransactions](../src/main/java/com/campuslife/file/service/FileTransactions.java) 管理短数据库事务，[FileUploadService](../src/main/java/com/campuslife/file/service/FileUploadService.java) 与 [FileCleanup](../src/main/java/com/campuslife/file/service/FileCleanup.java) 在事务外做IO并恢复未完成步骤。

[FileDownloadService.download](../src/main/java/com/campuslife/file/service/FileDownloadService.java) 获得实例内文件互斥后，用新的短事务复核授权，再传输PDF；传输持有互斥但不持有数据库事务。清理跳过正在传输的文件，已获准传输允许结束，取消退款无需等下载结束。打印创建等待文件互斥也发生在数据库事务外，避免长时间占用行锁。

[FileReferenceProtection.refresh](../src/main/java/com/campuslife/file/service/FileReferenceProtection.java) 在文件行锁保护下检查全部订单：任一未结束则expiresAt=null；全部终结则最晚终结+7天。同文件的引用写入先锁文件行，避免一笔取消把另一笔活跃订单需要的文件释放。只有实际期限变化才更新文件版本。

代价与边界：本地存储和实例互斥适用于当前单实例。增加服务实例或迁移对象存储时，要重新设计协调、迁移与故障恢复，不能只把目录共享就宣称支持分布式。依据：[ADR-0031](decisions/0031-print-file-upload.md)、[ADR-0034](decisions/0034-print-orders.md)、[PrintOrdersMysqlIT](../src/test/java/com/campuslife/PrintOrdersMysqlIT.java)、[MerchantPrintDownloadMysqlIT](../src/test/java/com/campuslife/MerchantPrintDownloadMysqlIT.java)。

## 9. 乐观版本和数据库行锁为什么同时存在

expectedVersion回答“用户看到的那版资料是否仍有效”，过期页面409，防止无意识覆盖别人修改；行锁回答“并发事务怎样协调读取与写入”。两者负责的问题不同，关键写入仍会锁后核对版本。

账号、店铺、服务、价目和理发师有各自版本，不能混用。例如打印创建核对价目版本和金额；管理员异常退款使用刚读取的订单版本，防止核实过程中发生取件或其他变化后仍覆盖状态。版本不是每个HTTP请求都加1，合法重放不改事实，评价也不增加订单版本。

[AdminAccountStatusService.change](../src/main/java/com/campuslife/admin/service/AdminAccountStatusService.java) 是特殊并发例子：禁止改本人，双方账号按ID升序逐个加主键锁，复核操作者仍为正常ADMIN后，再锁会话、更新目标、撤销会话和追加历史。两名管理员互停时，失效的一方不能继续成功停用另一方。不要把这个结论说成已有完整“管理员人数配额”系统。依据：[ADR-0046](decisions/0046-admin-account-status.md)。

## 10. 验证如何支撑这些说法

| 层次 | 验证内容 | 当前证据 |
|---|---|---|
| 普通/JVM与Web | 参数、计算、token、响应协议 | 全量报告8类172用例 |
| 真实MySQL/HTTP | 约束、锁等待、并发、事务回滚与接口权限 | 全量报告47类2503用例 |
| 业务旅程 | 入驻/审批/配置到预约或打印完成、评价及退款分支 | BookingJourneyMysqlIT四条、PrintJourneyMysqlIT六条 |
| 可执行包 | 实际JAR启动、健康、编码、trace和访问边界 | 全量报告单列，不算JUnit用例 |
| 浏览器人工 | 用户可按界面完成业务、上传下载等 | 打印16项通过；预约人工清单仍未完成 |

引用[全量报告](reports/v1-acceptance-2026-10-26.md)与[预约即时报告](reports/booking-immediate-2026-10-27.md)时说清日期和范围。2675包含上述两类自动测试及旅程，不能再加190变成“总覆盖2865”；用例数不是代码覆盖率，也不能证明性能指标。

## 11. 分三次完成自己的阅读练习

1. **先讲清业务**：按演示指南完成打印流程，画出主状态、支付状态和打印进度，手算5页双面2份。能解释同键重复下单与新买一单的区别。
2. **跟一条请求**：从PrintOrderController进入PrintOrderService.create，再到PrintOrderTransactions.create，标出事务开始/结束、文件互斥、重报价和事件写入。理解后再对照预约创建的日期锁。
3. **解释一个失败**：阅读预约并发测试或打印写失败回滚测试，指出它制造了什么竞争/故障、断言什么不变量、为何mock不足以证明数据库锁正确。说出当前单机文件方案的限制。

每次阅读后尝试关掉文档复述，再用源码核对。尚不能解释的细节列入下次学习，不用把答案记熟当作已经掌握；本材料交付也不表示你已完成这些练习。
