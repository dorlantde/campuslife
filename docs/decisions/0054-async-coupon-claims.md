# ADR-0054：异步领券受理、发行与恢复协议

## 2026-11-05维护连接预检补充

用户采用[维护预检方案](../coupon-maintenance-preflight-design.md)，D.6c已[实现并定向验证](../reports/maintenance-preflight-2026-11-05.md)。ASYNC Resume和ASYNC→SYNC维护在原事务外、取票据之前做只读INFO；仅首次连接异常的原因链匹配Lettuce命令超时时最多额外尝试一次，不等待/增线程。失败固定安全码、保留原门闩；中断停止。成功仍由原取票据事务复核操作者版本/阶段，不把预检当授权，不重试整个维护动作。

该补充源于云端短命JVM冷连接在领到票据后超时；将其移出票据窗口。原300/500ms客户端配置、30秒租约、5分钟业务截止、90秒CLI预算及后台/HTTP行为保持；票据后失败仍沿原fencing/续约/接管处理。35项本机相关检查和真实Redis源码类路径验证通过，正式云端新制品仍待核验。以下保留原协议与各批记录。

日期：2026-10-30。状态：**用户已确认协议及B/C/D拆分；B/C/D已验证，M08.4性能与真进程窗口待执行。** 依据：[完整协议](../async-coupon-claim-design.md)。

## 决定与理由

1. 默认SYNC不改变；ASYNC按活动受控启用，不自动故障降级回同步。Lua预留后须MySQL同事务提交PENDING/outbox，才可返回202；202不保证得券、不保证FIFO。
2. deadline=min(acceptedAt+5分钟,useEndAt)，等值失败；30秒无持久请求的预留具备孤儿仲裁资格。5分钟考虑M03故障观测约178秒，但不是恢复保证。
3. 每学生/活动至多一笔PENDING或一笔成功，数据库生成列唯一约束兜底；失败永久占用原键但不占成功次数，新键可重试。失败终态禁止晚消息发行；订单券/资金事务保持原行为。
4. 先学生账号X，HTTP再会话X，随后店铺S→gate S→发行所需campaign X→请求/券/消费凭据。商家配置/停止gate X→campaign X；先提交的受理保留停止后处理资格。消费重新校验学生/经营资格，不依赖原HTTP会话。
5. 使用独立Redis实例，预留身份、持久失败墓碑、条件结算和代次重建；IO均在SQL事务外。库存及终身限领的最终事实在MySQL。Redis细节按完整协议C批实现，不能据B交付声称已具备补偿/重建。
6. 新券命令COUPON_ISSUE_REQUESTED/COUPON_CLAIM/eventSeq=0，独立路由/队列/死信和coupon-issue-v1消费凭据。复用既有outbox发布租约/确认、去重及提交后ACK。技术异常有限重试/死信；正常业务失败同事务持久FAILED并ACK。

## B批实际范围

- V22新增coupon_dispatch_gate，回填所有活动SYNC/OPEN；扩展coupon_claim_request及结果契约；旧成功ID、键、券和完成时间不变。75操作/V22/32张应用表，无新增HTTP路径。
- 原同步新领取须OPEN/SYNC；既有原键优先重放；商家修改/发布/停止取gate X，新建活动同事务建门闩。缺门闩报错，不能当作SYNC绕过。
- 专用发行事务允许PUBLISHED/STOPPED已受理请求；库存/持有券/请求终态/消费凭据原子提交。新券冻结受理配置版本，发行计数不推进配置version。
- 独立每秒100条超时扫描，每单短事务；等值失败、原期限不延长。PAUSED允许旧单处理；REBUILDING正常发行/扫描暂停，旧generation请求在恢复后仍合法。
- 当前只有测试自有PENDING夹具，**没有生产异步受理、模式启用或SQL维护脚本**。B不提供跳过Lua的临时HTTP。
- 门闩是技术控制，不承诺彻底移除数据库热点；请求/券外键仍可能取得父行锁，其实测成本留M08.4。

## 后续与验证

C：专用Redis、孤儿墓碑仲裁/条件结算、围栏与重建；D：接三阶段入口、202/Location及受控模式切换；均按已确认协议逐批验收，不一次性扩张开发。B验收方法见[指南](../async-coupon-foundation.md)，实际执行证据见[报告](../reports/async-coupon-foundation-2026-10-30.md)。

## C批实际范围与技术细化（2026-10-31）

- C已按本协议交付，75操作/V23/32表，191项不同相关检查分轮最终通过；[指南](../coupon-redis-recovery.md)/[报告](../reports/coupon-redis-recovery-2026-10-31.md)。独立Redis ACL/AOF everysec/noeviction、自有卷与Lettuce资源，不使用M02的可丢缓存实例；B发行/超时/消息机制复用。
- V23新增resume_phase，保留重建前OPEN/PAUSED。升级时已有REBUILDING且原阶段未知的技术行回填PAUSED；不改旧业务事实。协调租约30秒，过期接管升新代次；这是恢复元数据，不改变5分钟业务截止。
- Lua业务计数与身份最终一次HSET，缺失败rid只写CLOSED不INCR；成功缺rid须有重建对应券证明。当前control/epoch拒绝TTL，旧epoch在新控制围栏后才设1小时技术清理期。DB已关闭且持有新票据时，可重置本命名空间已知错误类型control；bootstrap任一步失败继续关闭。
- 影子重建每批最多200条并核对批摘要/保留量；HSCAN COUNT100为分页提示，不声称响应硬上限。正常SQL终态与Redis不共同提交，回写checkpoint须同代次/终态；旧读取不会重新关闭已更新的正常代次。
- 较长重建用私有单工作线程，原子门闩与SynchronousQueue防重叠/积压；不定义通用Executor/TaskScheduler Bean，保留Spring原自动配置。原因是项目未另配调度线程，默认共享线程不能被长恢复占住；依据[官方调度说明](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html)。组件关闭中断、最多等待2秒并可租约接管，不承诺整个应用2秒退出。
- 普通独立MySQL测试默认关闭未由专用runner拥有的两类Redis，防止继承.local选择配置；真实联合验证仍用专有实例，不能用mock替代。
- 本机Start/Status/Stop已实测，最终停止并保留自有卷、关闭选择配置。**D的公开受理与模式维护未实现，M08.4性能/真实JVM故障留后续。**

## D批实际范围与维护细化（2026-10-31）

- D复用原POST/GET，完成SQL预检→事务外Lua→SQL受理，PENDING/outbox提交后202/Location；原键优先重放，未知提交先查事实，不直接释放。仍75操作/V23/32表，无DDL。
- 本应用新ASYNC受理要求券Redis和消息显式启用；默认关闭。临时Broker不可达仍依靠outbox及原处理期限，不因配置为true承诺在线或成功。SYNC保持原201/200，关闭依赖仍可重放原异步事实。
- 本机维护独立非Web进程；controlVersion与shop→gate锁序限制旧操作。Async只改PAUSED且已排空/核对的活动；Resume ASYNC创建新代次票据并持久OPEN恢复意图，沿C构建后开放。Sync先新代次构建PAUSED Redis，再以自己票据对应的固定版本切为SYNC/PAUSED，最后Resume；不采纳别人的新版本。失败保留关闭状态，不自动降级。
- 启用参数AllInstancesUpgraded是操作者声明，程序不能远程证明所有旧二进制退出；不承诺旧代码绕过门闩仍安全。PAUSED允许旧请求处理，跨模式切换必须PENDING=0并核对实际发行数；不引入提前强制失败。
- 147项不同相关检查分轮最终通过，含真实HTTP/Redis/MySQL/RabbitMQ、独立维护main子JVM、并发切换、旧同步/订单及C恢复；[报告](../reports/async-coupon-admission-2026-10-31.md)/[指南](../async-coupon-admission.md)。本轮故障窗含可定位注入，未替代M08.4全链真进程故障与同负载性能测量。
