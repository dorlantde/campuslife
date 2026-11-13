# ADR-0037：商家已接单打印无法履约取消

- 日期：2026-10-25。
- 状态：用户明确“采用并实现”；精确规则见[确认方案](../merchant-print-cancellation-design.md)，操作与实际验证见[验收指南](../merchant-print-cancellation.md)。

## 决定与取舍

扩展既有POST /merchant/orders/{orderId}/cancel支持PRINT，给已接单但尚未开始的打印单提供全额退款终结路径。复用BookingCancellation(reason)、OrderDetail、唯一支付/退款、MERCHANT_UNAVAILABLE原因和现有文件引用保护；无新路径、表、迁移、依赖或生产Java文件。运行54、完整规划62/54/8，V1～V12、21张业务表、生产Java184个保持。

打印没有预约起点，取消以锁后的履约事实判断：ACCEPTED/PAID/QUEUED、acceptedAt非空，startedAt/readyAt/pickedUpAt为空；不设置额外取消时限，不再判断原支付或30分钟接单截止。未来开始打印后普通取消拒绝，不能将此入口用于履约中退款。原BOOKING严格早于预约startAt的窗口不变。

正常MERCHANT/有效会话、本店ACTIVE OWNER可处理旧单，无所属店铺/跨店/不存在404，成员或角色无效403；暂停、停业、服务/价目下架改价、学生停用不阻止承担旧单退款责任。不向目标学生账号反向取锁。

## 请求、资金与重放

reason严格字符串，strip后1～300 Unicode码点，未知字段拒绝；无需expectedVersion或额外幂等键。首次核对成功支付与冻结金额/paidAt、无既有退款，关联文件仍属于学生、AVAILABLE且expiresAt=NULL。保护元数据异常503回滚；不重新打开磁盘或等待实例互斥，磁盘丢失不阻止正常元数据下退款。

锁后固定毫秒时刻，原子提交唯一全额退款、CLOSED/REFUNDED/ABORTED、MERCHANT_UNAVAILABLE及closeNote/closedAt/版本、一次MERCHANT关闭事件和必要文件期限变更。保留原payment、paidAt、acceptedAt及冻结明细，不伪造开始/取件。退款reason_note与事件reason保存相同归一化原因，事件fromStatus=ACCEPTED，通常序号3，不额外合成REFUND事件。

同原因合法已取消重放200，不改退款/关闭时间/版本/事件/文件期限，也不重判首次文件保护或资金开关；仍校验当前角色、会话、归属和成员。异原因/其他关闭原因、未付、待接单、开始或完成等状态409 ORDER_STATE_CONFLICT，不覆盖责任。待接单即使已过期，本cancel入口也不代跑超时退款。

所有新增退款受原模拟开关保护，关闭时503 MOCK_PAYMENT_DISABLED保持原事实；恢复后可重试原orderId及原因。版本耗尽或数据库竞争整笔回滚，金额沿long支持最高50亿分；已有成功事实不重复计费或退款。

## 事务与文件保护

商家RC账号X→会话X→店铺S→OWNER资格复核→文件X→主订单X→打印明细X→资金/事件，提交前复核会话。接单、拒单和取消复用商家打印锁入口，写返回/重放从锁后视图构造，保留MyBatis定位缓存修复。泛用打印退款事件读取实际原状态，待接单退款仍从PENDING_ACCEPTANCE关闭。

所有引用终结写先file_asset X，其他引用用RC普通读聚合，不跨店锁其他订单。任一未终结则NULL，最后终结后为最晚实际closedAt/completedAt+7天；仅期限实际变化递增文件版本。必要期限写失败连同资金、主状态、打印进度和事件全部回滚；取消不删除磁盘。

与同单重复取消/接单重放、学生取消/扫描、同文件新建和跨店终结遵守同一锁序。不同请求先后返回不承诺结果永远不变；唯一退款和序号约束兜底，状态校验决定能否执行。正常学生仍不能直接取消已接单PRINT，扫描也不将ACCEPTED当待接单超时退款。

## 当前范围

已接单且未开始的打印现在可由本店商家取消退款终结。商家内容下载、开始打印、待取件/取件及管理员PRINT异常尚未开放；原start/complete及管理员预约入口仍拒绝PRINT。正常打印完成闭环等待后续任务，不以合成状态的拒绝测试宣称履约已经实现。
