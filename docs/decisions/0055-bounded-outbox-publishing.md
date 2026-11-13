# ADR-0055 有界小批次outbox发布

日期：2026-11-01。状态：**用户明确采用[方案](../outbox-bounded-publishing-design.md)，B/C已验证并收口。** [B报告](../reports/bounded-outbox-publishing-2026-11-01.md)：182项不同相关检查；[C报告](../reports/outbox-publisher-comparison-2026-11-01.md)：62项不同相关检查最终通过、11个真实JVM窗口、旧新各24轮/4104 POST，三档完整p95中位0.58～1.15秒、较旧异步降93.88%～96.95%，目标通过。仍保留额外写成本、首次失败及单机/时序漂移限制，不外推容量或整体优于SYNC。旧376类/资源及115依赖指纹保留，JAR/清理通过；原依据是[M08.4-B实测](../reports/coupon-measurement-2026-11-01.md)的单条调度与约19秒完整获券p95。

## 决定及取舍

1. 采用组件私有单工作线程，core=max=1、daemon、SynchronousQueue及AbortPolicy；原子防重入，忙/交接/关闭拒绝即延后，不使用CallerRunsPolicy，不声明通用Executor或TaskScheduler Bean。共享1秒调度仅唤醒，长SQL/网络操作在私有线程执行。
2. 每轮最多32次claim调用，配置`campuslife.messaging.publisher-batch-size`（环境变量`MESSAGING_PUBLISHER_BATCH_SIZE`）允许1～32、默认32、非法启动失败；固定1秒单调继续认领预算，已开始的单条允许收尾。这些上限不等同业务SLA。
3. 按条执行原短RC认领→事务外发送→短RC条件回写；不预先申请整批租约，不批量SQL/合并事务。只有ACK且finish成功才继续。保留原同步`publishOne()`单次调用及boolean“已尝试”语义，后台使用内部结果区分能否继续。
4. 首个非ACK、旧token回写失败、SQL异常/未知结果结束本轮。保留30秒租约、1～60秒退避/最多20次、messageId/正文、mandatory/return/confirm、消费幂等与死信；不在异常catch里猜测回收或补写SENT。当前执行一条不等于数据库没有历史未决PUBLISHING。
5. 关闭前已进入的认领/发送/回写可收尾；关闭后不开始下一次尝试。停止提交、锁等待与私有线程等待共用8秒截止，重复stop不延长。超时尽力中断自有worker，迟到结果仍受token约束；不强停外部单次调用线程，不声称应用总关闭或强制取消IO的8秒保证。
6. 通知和券命令共用原createdAt/messageId排序及SKIP LOCKED，无新优先级/FIFO保证。保持独立发布/声明连接、原消费者并发和数据库池，不增加线程并发或中间件。

## 批次、证据与停止条件

- B先封存旧生产classes、可执行包、依赖/源码指纹，再做实现、单元及真实MySQL/RabbitMQ正确性/调度隔离/关闭回归和JAR验收。无新业务/HTTP/schema/依赖；保持75操作/V23/32表、默认SYNC与消息/券Redis关闭，前端暂停。
- C在B正确性通过后再执行真实认领/confirm/批间强杀、原相关故障窗口及一次旧新各24轮同口径对照；完整隔离旧/新生产类路径，不混装Bean。batch-size=1只降低配额，不冒充旧二进制。
- C目标是各档ASYNC完整p95三轮中位≤5秒且较旧版降低≥70%，同时保留全量错误/成本/未完成数据。目标不保证胜过SYNC，20张的可靠持久化写入事实不裁剪；不能因GET减少而把总SQL变化全算作POST优化。
- 只做一次实现/固定参数对照；不达标如实记录并收口，默认SYNC，转原定工程方向，不无限加线程或堆组件。需要改变原协议时另行讨论，已确认规则不重复询问。

B实际数量与边界见B报告；C真实强杀和一次完整旧新对照已独立归档并达到目标，依据C正式样本作结论，不从B排空示例推断。M08.5结束，保留成本、首次失败及局限，后续按路线图推进工程交付。
