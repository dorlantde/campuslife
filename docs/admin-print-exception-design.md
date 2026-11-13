# 管理员打印异常处理：已确认方案

- 日期：2026-10-26；状态：用户明确“采用并实现”；落实见[ADR-0041](decisions/0041-admin-print-exception.md)，实际验证见[指南](admin-print-exception.md)。
- 依据：[业务基线](decisions/0001-v1-baseline.md)、[管理员预约异常 ADR-0027](decisions/0027-admin-booking-exception.md)、[打印文件保护 ADR-0034](decisions/0034-print-orders.md)、[商家下载 ADR-0038](decisions/0038-merchant-print-download.md)、[打印履约 ADR-0039](decisions/0039-merchant-print-fulfillment.md)、[学生取件 ADR-0040](decisions/0040-student-print-pickup.md)。

## 1. 推荐任务与完成标准

扩展既有两个入口支持PRINT，补齐已付款打印在无法正常履约时的管理员核实终结路径：

| 入口 | 本轮推荐 |
|---|---|
| GET /api/v1/admin/orders/{orderId} | 正常ADMIN查看任意店铺、所有状态的BOOKING/PRINT详情；PRINT返回冻结规格、阶段时间、版本及已有支付/退款事实 |
| POST /api/v1/admin/orders/{orderId}/close-exception | 保留原BOOKING规则，增加已付、未终结PRINT的核实全额模拟退款关闭 |

复用OrderDetail与既有reason/expectedVersion请求，不增加路径、表、迁移、依赖、管理员默认账号或基础设施；运行仍56个操作，完整规划62/已实现56/待开发6。实现落在现有order模块，PRINT不进入预约日期锁。

本轮完成标准：管理员能查看任意PRINT并基于版本核实退款；资金、状态、打印进度、事件和文件全部引用期限一致提交；与取件、商家履约、普通退款和超时扫描竞争时不重复退款、不覆盖已完成事实；同步注解、两份OpenAPI与Swagger/Apifox点击指南并用真实MySQL验证。

不新增管理员订单列表、PDF内容访问、争议工单、证据上传、部分退款、已完成售后或自动认定责任。管理员是否查看PDF是另一项权限决定，不能因本次可查打印订单就自动获得内容权限。

## 2. 权限与首次关闭

仅正常ADMIN及有效会话，无店铺归属限制。目标学生/经营账号或OWNER停用、店铺暂停/停业、服务或价目变化均不阻止处理旧单；不反向锁这些目标账号或要求当前OWNER有效。管理员自身停用/角色变化/会话失效仍拒绝，成功重试同样校验当前管理员身份。

详情只读RR，返回历史冻结明细，即使文件后来过期/清理也能查看；不附加联系方式、存储位置、下载链接，不打开磁盘，不代跑超时或退款。规范orderId，不存在或不支持类型404。

核实请求沿原严格JSON：reason去首尾空白后1～300个Unicode码点；expectedVersion为查看时取得的规范非负BIGINT字符串，最大9223372036854775807。缺失、null、未知字段或错误类型400 VALIDATION_FAILED，不收客户端金额、状态、时间或管理员ID。

首次只允许以下合法组合，均PAID、completedAt/closedAt/pickedUpAt为空，匹配原成功支付且未退款：

| 主状态 | 打印进度 | 已发生时间 |
|---|---|---|
| PENDING_ACCEPTANCE | QUEUED | acceptedAt、startedAt、readyAt均为空 |
| ACCEPTED | QUEUED | acceptedAt非空，startedAt、readyAt为空 |
| IN_SERVICE | PRINTING | acceptedAt、startedAt非空且有序，readyAt为空 |
| IN_SERVICE | READY_FOR_PICKUP | acceptedAt、startedAt、readyAt非空且有序 |

不要求先等到接单期限或普通取消窗口，不重新解释预约时间，也不代跑到期扫描。未支付、COMPLETED/PICKED_UP、已关闭或其他错配组合不能首次核实退款；已取件完成不允许该入口售后退款。

## 3. 关闭结果、时间与重试

首次固定必要锁后的毫秒now：不得早于原paidAt及当前阶段已保存的acceptedAt/startedAt/readyAt。等于允许，倒退409 PRINT_TIME_INVALID，无写入。这样关闭事件不会出现在已发生履约动作之前，不引入新的等待时长。

原子结果为CLOSED/REFUNDED/ABORTED，closeReason=ADMIN_EXCEPTION、closeNote=归一化原因、closedAt=now，订单版本+1。新增唯一全额成功模拟退款，金额固定为匹配的原成功支付/冻结应付金额；退款原因说明与closeNote、事件reason一致。仅一条ADMIN_EXCEPTION事件，fromStatus为真实原主状态、toStatus=CLOSED、actorType=ADMIN、actorId为实际管理员。

保留原支付、冻结规格和已发生的acceptedAt/startedAt/readyAt；不填写pickedUpAt/completedAt，不伪造取件、完成或另一条REFUND事件。即使原进度为READY_FOR_PICKUP，也转ABORTED并保留readyAt作为历史事实。关闭后商家start/ready、学生pickup均不能恢复该单。

对非CLOSED先比较expectedVersion：不匹配409 CONFIG_VERSION_CONFLICT，需重新读取并核实；匹配但状态不允许409 ORDER_STATE_CONFLICT，版本无法递增同样409。管理员读取后商家开始/待取件或学生完成导致版本变化时，禁止用旧核实请求直接退款。

对CLOSED仅同一管理员、同归一化原因、同原expectedVersion（当前version−1）、最后事件序号/动作/操作者/原因/原状态匹配且支付、退款、ABORTED及保留的阶段事实一致时，返回当前详情200。其他关闭原因、另一管理员、更改原因或版本均409 ORDER_STATE_CONFLICT，不改写已有关闭原因。

成功重放不重判首次动作时间、文件保护或版本递增，不续期、不重复资金或事件；文件后续清理仍可重放。新增退款必须显式开启模拟资金，关闭时503 MOCK_PAYMENT_DISABLED且整笔无写入；已有成功事实重放不受开关限制。

## 4. 文件保留与内容权限

首次只复核文件属于订单学生、AVAILABLE且expiresAt=NULL的保护元数据，不打开磁盘、不等实例文件互斥。实际PDF丢失或暂时不可读本身不阻止核实退款；保护元数据异常503 FILE_STORAGE_UNAVAILABLE，不能在此入口静默修复另一套存储事实。

关闭后复用全部引用保护：仍有任一未结束订单则继续NULL；全部CLOSED/COMPLETED按最晚closedAt/completedAt+7天保留。仅实际期限改变才更新文件版本。订单版本上限或必要期限写入失败整笔回滚，不能出现已退资金但订单/文件保护未更新；若无需改期限，文件版本上限不阻止合法关闭。

该关闭单不再授予商家下载权限；本店另一笔合格未终结任务可继续授权，只有其他店任务不授权本店。7天保留本身不授予商家权限，管理员仍无默认PDF内容权限。学生本人沿既有期限规则下载，学生已停用仍不能访问。

下载在互斥后新RR授权快照若已看到异常关闭，则按新状态判断；已获准传输可完成，之后请求重新检查。核实退款不等待活动下载，清理继续跳过活动传输；IO不进入业务事务。

## 5. 事务与竞争

PRINT写沿RC：管理员账号X→会话X→店铺S→文件X→主订单X→打印明细X→资金/事件及引用期限，提交前复核会话。无OWNER或目标账号反向锁、预约日期锁、业务锁内磁盘IO或实例互斥等待。写返回及重放使用锁后视图，避免定位查询缓存混读。

同文件全部引用写仍先持file_asset X；跨店引用统计不反向锁其他店订单。退款、关闭、进度、保留的时间、版本、事件、文件期限任一步失败全部回滚。数据库竞争503 SERVICE_TEMPORARILY_UNAVAILABLE，可重试原核实请求。

管理员与超时扫描/学生或商家普通退款先提交者保留关闭原因，另一方不得重复退款。管理员与商家开始/待取件竞争时，后提交者按当前状态及版本重判；与学生取件竞争时，取件先提交则不能再退款，核实关闭先提交则pickup拒绝。两名管理员同时核实只有合法先提交者写入，同管理员相同请求可重放。

## 6. 验收范围（实际执行记录见指南）

1. Swagger/Apifox：准备待接单、已接单、打印中、待取件四类实际PRINT，用ADMIN读取版本后填写原因关闭；核对冻结金额、单次退款、ABORTED、已发生时间、版本和完整学生/商家事件。详情覆盖未付/完成/关闭及文件清理后的历史。
2. 真实MySQL/HTTP：角色/账号/会话、跨店不限范围、目标学生/商家/OWNER停用、暂停和配置失效；严格ID、reason/版本/未知字段/类型；未付/完成/关闭与错配组合拒绝；原BOOKING规则回归。
3. 毫秒now等于/早于各阶段时间、真实锁等待后确定时刻与版本；旧版本冲突、同管理员成功重放/其他管理员及改原因冲突、资金关闭首次失败但成功重放允许、文件清理后重放。
4. 匹配支付/唯一全额退款，保护元数据、订单/文件版本边界及必要期限写失败；逐点注入资金/主单/打印进度/事件/文件期限/最终会话异常核对全部回滚。
5. 同文件多单、跨店引用与最后终结+7天、重复不续期；真实双管理员、管理员与开始/待取件/取件/扫描/普通退款竞争，先提交事实不被覆盖。
6. 关闭前后商家下载来源、另一合格关联、等待后新授权与已获准传输；持实例文件互斥及目标账号锁时核实仍可完成，无磁盘读取。测试只用独立临时MySQL与自有文件根。
7. 注解、两份OpenAPI仍56/62/56/6，错误码/字段/唯一分组/引用检查及可执行包启动；实际命令和结果完成后记录，不将提案验收目标当作已执行事实。

本次已确认PRINT适用阶段、ABORTED与阶段时间保留、精确时间边界，以及与取件/扫描竞争和重试的规则。原已确认BOOKING规则、全部引用保护和获准传输规则沿用；实际执行结果仅以验收指南记录为准。
