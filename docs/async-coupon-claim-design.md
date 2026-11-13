# M08.3-A 异步领券受理与补偿协议（已确认，分批实现）

日期：2026-10-30。**用户已明确采用本方案，并授权从M08.3-B实现**；正式决定见[ADR-0054](decisions/0054-async-coupon-claims.md)。本稿保留协议与取舍，实际范围见[底座指南](async-coupon-foundation.md)及路线图：B数据库底座、C Redis预留/恢复及D公开受理/受控切换均已验证，前端暂停。原提案时基线75操作/V21/31表，实施后的版本与验证以报告为准。

## 1. 推荐与取舍

保留现有同步入口为默认模式；按活动显式选择ASYNC。异步路径是：身份/原键读取→Redis Lua有身份预留→MySQL短事务持久PENDING及outbox→返回202→独立消息消费事务发行→本人查询结果。MySQL始终决定成功发行，Redis只做热点资格/库存预留，MQ异步承接发行竞争。普通下单/支付/退款及订单券占用继续M08.2同步事务。

Lua不能与数据库事务一起提交。推荐保留Lua先行，但补上可枚举的预留、持久失败墓碑、代次围栏及恢复关闭协议；不采用“超时直接INCR”，不将outbox当作Redis原子提交。新受理仍需MySQL写请求和outbox，消费还会写库存/券/结果/凭据，不能承诺数据库零压力或一定更快。

## 2. 已确认的业务细则

| 项目 | 推荐规则及理由 |
|---|---|
| 每人进行中 | 每学生/活动最多一笔PENDING；已有成功永久不得再领。不同键同时申请，进行中冲突409，不为同学生占多份预留 |
| 持久处理期限 | deadline=min(acceptedAt＋5分钟,useEndAt)，等于截止即失败；不是原候选2分钟。M03实测死信目标恢复约178秒，5分钟容纳一次该类重试及调度余量，但不保证故障一定赶得上 |
| Lua孤儿期限 | 预留后30秒仍无持久请求，可由回收器竞争写FAILED墓碑；获胜者决定结果，超过30秒后若回收器尚未写入，HTTP仍可先提交PENDING；已有失败墓碑则不可绕过。它是回收资格，不是Redis自动过期 |
| 活动停止 | 停止前已持久PENDING仍可处理，不重新判STOPPED/claimEnd；仅Lua预留不算受理。使用截止、处理期限、当前学生/经营资格和最终库存仍限制成功 |
| 排队期间身份 | 消费不要求原HTTP会话继续有效；重新锁查正常STUDENT、可见店铺/OWNER资格。资格失效→FAILED，不批量取消其他旧单或已发券 |
| 失败与重试 | FAILED为永久终态、原键只读原结果。新键可在仍合格/有库存时重新尝试，成功限领次数不因失败消耗；已成功/订单退款不回发行库存 |
| 承诺范围 | 202仅代表PENDING/outbox已提交，不保证最终得券、不保证FIFO。消息积压、恢复、资格/期限变化、缓存失真都可能产生可查询FAILED |
| 模式与故障 | 新旧活动默认SYNC；只经本机受控维护启用指定活动ASYNC。ASYNC故障返回暂不可用，不自动退回同步抢库存；切换须停受理、排空并对账 |

原本店满减、每活动终身成功一次、正实付、未付释放/已付不返等已确认规则不再讨论或重写。

## 3. 提案时源码缺口（B处理前三项，C已隔离Redis）

- V18 coupon_claim_request只有非空user_coupon_id/completed_at，ClaimResult只允许SUCCEEDED：必须扩展PENDING/FAILED、nullable结果及截止/错误字段，不能把未发出的券伪装成功。
- CampaignMapper.issue()要求PUBLISHED：同步方法保持；新增仅限已持久受理的发行条件更新，允许PUBLISHED/STOPPED且issued_count<total_quantity。issuedCount变化仍不推进活动配置version/updatedAt。
- V20 outbox CHECK只允许两种通知，RabbitOutboxTransport固定路由notification：须显式增加COUPON_ISSUE_REQUESTED/COUPON_CLAIM及类型→路由白名单，独立coupon.issue队列/消费者/死信队列。不能让通知和券消费者竞争同一队列。
- M02 Redis使用allkeys-lru且关闭持久化，只适合可丢缓存。不得把券预留放进该实例，也不更改原评分缓存行为。

## 4. 持久数据、身份及不变量

协议采用一张coupon_dispatch_gate，扩展已有领取请求/outbox约束，复用user_coupon及message_consumption。**B已通过V22新增一表至32表，接口路径仍75；V1～V21不修改。** C已按V23补resume_phase并运行独立Redis协调；公开入口已由D接通，实际验收见[指南](async-coupon-admission.md)。

| 位置 | 关键字段与约束 |
|---|---|
| gate，每活动一行 | campaign_id PK/FK，mode=SYNC/ASYNC，phase=OPEN/PAUSED/REBUILDING，generation、control_version、rebuild_token/lease_until、V23 resume_phase及目标Redis运行标识；这些是技术版本，不复用活动配置版本 |
| coupon_claim_request | status=PENDING/SUCCEEDED/FAILED、source_mode、created_at/accepted_at/completed_at/deadline、reservation_id/accepted_generation、source_campaign_version、failure_code、redis_settled_generation；原student/key唯一、coupon唯一保持 |
| 进行中/成功唯一 | 生成列active_campaign_id：PENDING/SUCCEEDED取campaign_id，FAILED取NULL；唯一(student_id,active_campaign_id)，允许失败后新键尝试，不重复成功 |
| 旧数据 | 原行回填SYNC/SUCCEEDED，原id/key/coupon/completedAt不变；新FAILED的coupon=null，已成功永不改回FAILED；老同步失败行为保持不留成功记录；孤儿FAILED允许accepted_at/deadline为空，不伪造曾经返回202的受理事实 |
| 消费凭据 | consumerName=coupon-issue-v1，messageId/hash；与发行/请求终态同事务提交，不代替活动＋学生的业务唯一约束 |

MySQL硬不变量：issuedCount=实际user_coupon数量≤totalQuantity；每学生/活动至多一张券；每请求唯一终态；SUCCEEDED有且仅有对应券，FAILED无新发行。金额、用户/活动和订单关联原约束保持。

Redis健康稳态核对free＋held＋spent=total；held含未持久孤儿和PENDING，不能把它直接说成数据库PENDING数量。Redis重启/丢数据/补偿未完成期间可能暂时高估或低估，硬正确性靠MySQL条件发行；异步受理本来不保证发券。

身份分别是全局业务键(studentId,requestKey)、Lua reservationId（32位UUID）、代次generation、持久claimId和投递messageId。首次Lua对同活动/学生/原键返回原reservationId；服务端自造身份，不从请求体接受studentId/库存/券规则/JWT。数据库里相同学生原键跨活动仍409；缓存里分活动去重不能代替这一全局唯一键。

## 5. Lua预留、条件结算和持久化边界

本节C已实现并经真实Redis验证，见[C报告](reports/coupon-redis-recovery-2026-10-31.md)。

建议独立本机Redis 8.2.4固定镜像/digest，持久卷、AOF everysec、noeviction、内存上限、回环端口及专用凭据；沿已有Lettuce集成方式，但不共用M02连接工厂/缓存配置。实施阶段再验证镜像与脚本。AOF不是MySQL的事务参与者，也不保证崩溃零损失。

键带活动hash-tag，如coupon:{campaignId}:control和对应generation的state hash。control与epoch初始化哨兵必须完整且匹配，缺失/类型错/非OPEN即拒绝，不由普通入口自动初始化库存。当前代次不靠TTL释放库存；不使用Redis过期事件驱动正确性。

每次Lua先完整校验类型、身份、阶段、整数边界及状态，尽量把最终变更合并成一次多字段HSET；避免先DECR再写记录、中途错误留下无法定位的扣减。脚本仅做有界O(1)操作，不扫描全活动；用HSCAN分批枚举预留，避免回收正确性依赖一个可能未写入的辅助ZSet。Lua执行原子不等于运行错误会回滚，更不等于跨数据库提交。

| 操作 | 原子判定与动作 |
|---|---|
| Reserve | 当前代次OPEN且已初始化；先原键/学生标记去重，再检查free>0。一次记录rid、学生/key/活动/generation/预留时间，free−1、held＋1及学生当前rid |
| SettleSuccess | 只依据已提交SUCCEEDED及当前代次。若本rid为held则held−1、spent＋1，free不增加；已SPENT重放无写。缺rid仅在当前epoch重建清单证明已计入spent时记CLAIMED而不改计数；无法证明则冻结重建，不能猜测或再减free/重复增spent |
| SettleFailure | 必须有数据库失败墓碑或其他永久拒绝证明。仅本rid为held时held−1、free＋1；只在学生指针仍指本rid时清除。缺rid也写CLOSED墓碑但不增free，阻止补偿先完成而旧Reserve后到再次扣减 |

当前代次的rid/已结算墓碑不单独短TTL删除，否则迟到Reserve可能再次扣减。活动关闭且在途已终结后，先将DB gate持久置PAUSED/升代次并封闭Redis control，再给旧epoch设置清理期限；旧命令必须先检查control，不能重建已过期epoch。物理数据库请求/去重墓碑暂保留；大规模内存治理不是本期已具备能力。

Lua结果超时属于未知，不可盲目再生成rid或INCR。保留原身份重试/由回收器查明；OOM/键损坏/代次不符按暂不可用处理。Redis变为不可信时停止新ASYNC受理并重建；不能把一个AOF快照当绝对库存。

## 6. 入口与原键语义

首次异步受理/202及Location现已由D接通，见[指南](async-coupon-admission.md)；B阶段仅读取/重放的限制保留为历史批次事实。

1. 现有身份机制验证当前正常学生，先查持久原键结果；有原记录直接原结果，跨活动409，不因Redis停止/活动结束而重新预留。重复成功不再发行，GET只读RR，不触发超时或补偿。
2. 新键定位活动、门闩及公开资格，读取当前generation；读取只是预检查，不能充当最终授权。执行Lua预留在数据库事务外。无库存/已领/已有进行中直接原业务错误，不能造202。
3. 获取Lua身份后进入短RC受理事务，按第8节锁序复核身份/会话、店铺、gate模式/阶段/代次、活动PUBLISHED/领取窗口、原键及活跃请求。插入PENDING，固定锁后毫秒acceptedAt、5分钟上限deadline及当时source_campaign_version；同事务写唯一COUPON_ISSUE_REQUESTED outbox。提交前会话再复核。
4. PENDING＋outbox提交后首次202＋claimId/Location结果查询。只凭Lua成功不能返回202。HTTP返回前中断，原键重试返回同一记录；PENDING重放202，终态重放200，沿用Idempotency-Replayed，不重新计时。
5. 受理事务失败/不确定：先重新查原键，不能假定回滚就马上返库存。确定无记录时由清理流程先持久写FAILED墓碑再条件释放；当前HTTP可返回409/503，原键之后可以读到FAILED。Lua之前被拒绝且没有持久记录的键不被消耗。

ClaimResult保留旧id/campaignId/status/completedAt/coupon，增加acceptedAt/deadline/failureCode等可空字段；PENDING与FAILED的coupon=null，PENDING completedAt=null。原SYNC首次201/原事实重放200保持。目录remainingQuantity继续是数据库“未发行张数”，不是Redis可受理额度；展示不承诺一定领到，不把缓存库存作为对外事实。

## 7. 消费、超时和孤儿补偿

B已实现发行消费/超时，C已实现孤儿墓碑仲裁与Redis结算；D已接公开新受理，默认仍SYNC。

发行消费者先校验消息身份/版本/hash与持久outbox及请求身份，业务只读取MySQL事实，不依赖原HTTP会话或Redis在线。受理与发行各自短事务，网络/等待不在SQL事务内。

- 锁后一次Clock决策：PENDING且now<deadline/useEnd、学生及经营资格有效才条件发行；原claimEnd结束/STOPPED不阻止合法已受理单。保存持有券、issuedCount＋1、SUCCEEDED/券ID/completedAt及消费凭据，同事务提交后ACK。券规则来自已冻结活动，sourceVersion使用受理时版本，不被STOPPED后的版本替换。
- 资格/期限失效、最终售罄：同事务写FAILED/错误码/completedAt及消费凭据，提交后ACK。这是业务失败，不当毒消息反复重试。
- 已终态合法重复：核对身份/hash；若超时扫描先终结而尚无该消息消费凭据，则在本事务补记凭据，已有凭据必须匹配，再提交后ACK，不再发行、改变终态或延长期限；FAILED禁止晚消息发行。技术故障沿M03有限重试/死信；独立超时扫描也可终结PENDING，谁先提交保留结果。
- 建议扫描每秒一次、每轮最多100条、每请求独立事务；启动时恢复。Redis离线不阻止数据库先决定超时失败，Redis结算可后补。GET反映已提交状态，过期而尚未扫描的PENDING仍按数据库返回。
- 数据库终态提交后，独立结算器在事务外调用Lua，成功后只条件记录redis_settled_generation。回包丢失或checkpoint写失败按同rid重试，Lua已结算则不重复变化。SUCCEEDED绝不因订单退款/文件清理变为可领取库存。

Lua孤儿的关键仲裁：回收器枚举30秒预留，按学生原键查/插入FAILED记录；与受理插入使用同一唯一键竞争。PENDING先提交则不能释放；FAILED先提交则迟到受理必须读到FAILED、不得再写PENDING。如果原键已永久绑定另一活动/rid，不修改那条记录，仅据“该身份永远不能持久受理”的证明释放本孤儿。数据库键记录不物理删除。

缺Redis记录的补偿也要写CLOSED，不是假定没扣过就无事可做；缺记录不允许INCR。每个回收结果在Redis留本rid结算标记，不能把别人的新rid指针删掉。

## 8. 锁序、停止与模式切换

统一前缀为：需要学生身份/失败墓碑者先学生账号X；HTTP再会话X；随后店铺S→gate S→（需要发行时campaign X）→request/券/消费凭据。受理读活动在gate S内用当前RC读取，领取窗口/配置变化必须先取得gate X；不为受理更新热活动库存行。用户账号不存在的异常由维护冻结该活动排查，不在反向位置补账号锁。

商家发布/停止/草稿修改仍沿原商家账号/会话/店铺S/OWNER复核，再gate X→campaign X。活动S门闩让同活动新受理并发，停止的X门闩明确先后：PENDING已提交者保留资格，只有Lua预留者不能越过停止。新活动在创建事务内建立默认SYNC gate；迁移回填全部旧活动，写入口遇缺gate报错，不以“缺行默认SYNC”绕过并发模式控制。

维护冻结只短事务取店铺S→gate X并提交，不持它调用Redis、不在其后反向取学生账号。冻结后每条失败/超时决策仍按账号→店铺→gate→request顺序，维护只在规定REBUILDING状态执行清理。发行事务的campaign X后取request，不得在持request后反向取campaign X。

SYNC和ASYNC都检查同一gate，不允许旧同步代码绕过。启用前须停掉旧版实例、全部升级后再切换；协议不宣称对不遵守门闩的旧二进制也安全。切换先PAUSED拒绝新受理，原PENDING可按其ASYNC事实完成；终结并对账后升代次，构建/关闭Redis再OPEN目标模式。没有两种入口同时抢同一活动库存或自动熔断退回SYNC。

## 9. Redis丢数据、代次围栏与重建

本节C已实现，真实Redis/MySQL及注入协调者中断证据见报告；不宣称真实JVM所有崩溃窗口或多节点HA。

1. 发现缺初始化哨兵、重启/实例标识变化或库存校验异常，停止新异步受理。短事务gate X切REBUILDING并generation＋1，等待此前持gate S的数据库事务结束；阻止旧代次新受理。活动规则/用户持有券不改变。
2. 所有正常受理、发行、超时及结算checkpoint在REBUILDING暂停，不把它误判为业务失败，暂停不延长原deadline，恢复后仍按原期限判断。允许重建协调者按既定前缀逐条处理已过deadline/资格失败请求；网络IO不进冻结事务。
3. 从MySQL核对issued及所有PENDING，而非total-issued即重开。Redis回滚快照可能导致过去超额受理；先终结过期/不合格请求，若PENDING仍大于可发行数，按acceptedAt/id保留能覆盖的请求，其余FAILED(SOLD_OUT)。正常发行不承诺FIFO，这个排序仅用于确定性恢复。
4. 新epoch从total-issued-PENDING计算free，逐条重建PENDING的原rid/学生映射，历史已发用户标CLAIMED。已有PENDING不因其accepted_generation旧而作废；其发行权由持久请求决定，成功/失败结算映射到重建后的当前epoch。原键/业务状态不可覆盖。
5. 采用影子epoch分批构建（例如200条/批），不在Lua内循环全库。Redis control保持关闭，核对总量/保留映射/初始化标记及重建token后单次Lua激活，再在短事务条件开放DB gate。Redis先OPEN的短窗口里DB仍REBUILDING，不能产生新合法受理。
6. 协调者有持久rebuild_token与租约；租约过期接管必须再次升generation，新token从新影子空间构建。旧协调者Redis激活检查token，最终DB开放也必须匹配token/generation。迟到旧Lua即使曾碰到旧epoch也不能在新DB门闩下受理。
7. 结算器读取终态/当前generation后释放SQL锁再调用Lua，写checkpoint再检查generation；重建竞争导致旧结果过期则重试当前代次，不把旧完成标记当新代次已核对。缺rid成功须有重建计入证明、失败按第5节无增减规则处理，防重复扣/返。

AOF everysec、run-id预检查和检测任务不能让Redis/数据库共同提交；重启发现前/部分数据损坏可能造成错误预检查或多余PENDING。硬正确性仍由MySQL库存/唯一约束保证；恢复期间宁可暂时拒绝，不能保证每个202成功。重建崩溃保持关闭，不凭PING成功或一个空实例自动开放。

## 10. 消息协议复用与实现顺序

COUPON_ISSUE_REQUESTED以claimId为aggregateId，eventSeq=0，recipientId为学生，消息/原受理快照固定；outbox唯一键保证一次持久请求一个发行消息。独立coupon.issue路由/队列和coupon-issue-v1凭据；原notification消息和消费规则不改。复用M03发布租约、确认/return、有限重试、消费者提交后ACK及受控重放，不复制一套通用消息平台。

5分钟业务期限与20次传输重试是不同维度：消息后来到达/死信重放时若请求已FAILED，只返回原终态并ACK，不重开结果。M03约178秒只是单次转移观测，不是可靠性SLA。结果通过原GET查询，不新增券成功营销通知。

已确认分三批后再M08.4（B/C/D已交付，M08.4待执行）：

| 批次 | 可验收范围及安全停止条件 |
|---|---|
| M08.3-B MySQL异步底座 | 请求状态/门闩迁移、旧数据兼容、独立发行路由/消费、超时及SQL竞争；全部默认SYNC，ASYNC公开启用入口尚关闭，组件测试自有PENDING夹具，不提供跳过Lua的临时HTTP |
| M08.3-C Redis预留与恢复 | 专用环境、Reserve/条件结算、孤儿墓碑仲裁、影子代次重建/协调者接管；真实Redis/MySQL/MQ验证，与M02隔离；公开ASYNC仍不可启用 |
| M08.3-D 入口联调与受控切换 | 接三阶段HTTP/202/原键查询、模式切换与Swagger；只有底座及补偿/重建均验证后才允许显式启用ASYNC，默认不改变已有SYNC活动 |
| M08.4 故障与对照验收 | 真进程/Lua后SQL前、持久受理后响应前、重复消息、超时/补偿竞争、Redis断电/缺键/重建中断，核对不变量；与同步基线同数据负载比较完整成功/受理延迟、失败比例、SQL/热锁/积压及恢复成本 |

## 11. 验证矩阵与本轮完成标准

必须覆盖：N库存≥2N学生无超发；同学生不同键最多一笔进行中/一次成功；原键跨活动冲突；同rid重试/补偿先于迟到Reserve；持久受理与孤儿墓碑插入竞争；消费/期限等值及前后1ms；停止前已受理后发行；原会话退出/角色停用；Redis超时未知/WRONGTYPE/OOM；结算后checkpoint失败；新rid不被旧失败释放；重建保留PENDING、过量处理、旧协调者晚激活、缺哨兵和运行标识改变；两模式混用/旧实例启用前拦截；既有SYNC与订单用券回归。

提案轮次仅做源码映射、锁序/崩溃推演与文档检查。用户确认后按B/C/D实施；上述矩阵包含C/D未来范围，不将B结果写为全协议通过。

## 12. 官方边界与确认记录

[Redis Lua执行范围](https://redis.io/docs/latest/develop/programmability/eval-intro/)限于Redis且脚本执行会阻塞服务，应保持有界；[持久化](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)及[复制](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/)不构成跨系统事务。M03已经证明确认与消费提交不同，继续沿[既有实测](reports/messaging-faults-2026-10-30.md)设计恢复，不能直接照搬教程秒杀下单或宣称恰好一次。

2026-10-30用户明确选择“采用方案，从M08.3-B开始实现”。第2节、独立Redis、Lua孤儿失败墓碑/代次重建、按活动显式模式及B/C/D拆分已获批准；采用5分钟取代原未批准2分钟候选，202仅受理/可失败/不保证FIFO。批准不等于所有阶段已实现，B仍不开放异步领取。
