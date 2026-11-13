# CampusLife 面试证据与完成范围

更新：2026-11-08，M07.1-A第一版。用户已选择提前面试整理，服务器排障/派生镜像、最终综合测量延后；它们仍未完成。本轮核对真实源码和既有报告，没有重新运行历史实验。[源码复盘](source-review.md) / [简历初稿](resume-draft.md) / [路线图](../roadmap.md)。

2026-11-12补充：[亮点分级面试题库](highlight-question-bank.md) / [无答案自测](questions-only.md)。各题对应下表E01～E07；本人理解仍未验收，新增问题不增加任何技术实现或历史验证结论。

## 先用一分钟讲清项目

CampusLife是面向校园商家的本地生活交易学习项目，提供理发预约、PDF打印、优惠券及评价，涉及学生、商家和管理员三种角色。采用模块化单体，以MySQL承载交易事实，Redis用于评分缓存和异步领券预留，RabbitMQ负责通知与领券消息，私有S3保存打印文件。

个人学习/模拟交易项目，支付退款为模拟资金，没有真实运营用户或真实支付接入。既有接口验收口径为75个操作、Flyway V24、32张应用表；这些是实现规模，不是并发能力。最小前端已交付基础流程，优惠券/通知页面仍未补齐且继续暂停。

技术栈按[实际pom](../../pom.xml)：Java21、Spring Boot4.1.1、MyBatis-Plus、MySQL、Spring Data Redis/Lettuce、Spring AMQP/RabbitMQ、Spring Security/JWT、AWS SDK2.x/S3、PDFBox；Docker用于隔离验证和部署。S3后端是SeaweedFS，未使用MinIO；未引入微服务、Redis Cluster或Redisson分布式锁。

## 可以写的技术点与证据

下表“已验证”均指相应历史报告的指定条件，不表示本轮全量回归或当前云端综合压测通过。

| 编号/可讲能力 | 当前源码入口 | 既有验证 | 必须保留的边界 |
|---|---|---|---|
| E01 交易并发与幂等 | [BookingOrderTransactions.create](../../src/main/java/com/campuslife/order/service/BookingOrderTransactions.java)、[BookingMapper](../../src/main/java/com/campuslife/order/mapper/BookingMapper.java)、[IdempotencyMapper](../../src/main/java/com/campuslife/order/mapper/IdempotencyMapper.java) | [V1验收](../reports/v1-acceptance-2026-10-26.md)、[BookingOrdersMysqlIT](../../src/test/java/com/campuslife/BookingOrdersMysqlIT.java)中的20学生争同一时段及写入回滚检查；后续券接入见[用券验收](../reports/order-coupons-2026-10-30.md) | 日期保护行串行化同人员/同日相关写入，锁后重判重叠；不是Redis锁，不承诺永不死锁或无限并发 |
| E02 有范围的SQL优化 | [PublicShopCatalogService.list](../../src/main/java/com/campuslife/merchant/service/PublicShopCatalogService.java)、[PublicShopMapper.categoryFirstPage](../../src/main/java/com/campuslife/merchant/mapper/PublicShopMapper.java) | [实验](../reports/public-shop-sql-experiment-2026-10-28.md)、[采纳报告](../reports/public-shop-list-adoption-2026-10-28.md) | 只采纳分类、无有效名称关键词、第1页；搜索/count/后续页保留，不宣称新增索引或所有查询加速 |
| E03 版本化评分缓存 | [PublicShopDetailTransactions.read](../../src/main/java/com/campuslife/merchant/service/PublicShopDetailTransactions.java)、[RatingCache](../../src/main/java/com/campuslife/merchant/cache/RatingCache.java)、[RedisRatingStore](../../src/main/java/com/campuslife/merchant/cache/RedisRatingStore.java)、[RatingRevisionMapper](../../src/main/java/com/campuslife/merchant/mapper/RatingRevisionMapper.java) | [实现验收](../reports/redis-rating-cache-2026-10-28.md)、[故障/性能对照](../reports/redis-rating-cache-measurement-2026-10-28.md) | 只缓存详情评分SUM/COUNT；资格/营业仍读DB。合并仅进程内，热门受益，普通/零评价店中位数可能变慢 |
| E04 Lua预留＋持久受理＋异步发行 | [CouponAdmissionService.claim](../../src/main/java/com/campuslife/coupon/service/CouponAdmissionService.java)、[accept](../../src/main/java/com/campuslife/coupon/service/CouponAdmissionTransactions.java)、[reserve.lua](../../src/main/resources/redis/coupon/reserve.lua)、[CouponIssuance.consume](../../src/main/java/com/campuslife/coupon/service/CouponIssuance.java) | [公开入口验收](../reports/async-coupon-admission-2026-10-31.md)、[真实进程故障](../reports/coupon-faults-2026-10-31.md)、[重建](../reports/coupon-redis-recovery-2026-10-31.md) | HTTP202只表示持久受理。Redis不是最终持券事实，SQL仍校验库存/资格；提交未知时不直接加库存，异步故障不自动切同步 |
| E05 outbox与消费幂等 | [OutboxTransactions](../../src/main/java/com/campuslife/messaging/service/OutboxTransactions.java)、[RabbitOutboxTransport](../../src/main/java/com/campuslife/messaging/service/RabbitOutboxTransport.java)、[CouponIssueListener](../../src/main/java/com/campuslife/coupon/service/CouponIssueListener.java) | [消息故障](../reports/messaging-faults-2026-10-30.md)、[领券进程故障](../reports/coupon-faults-2026-10-31.md) | 发布确认和消费ACK含义不同；可能重复投递，靠事务/状态/唯一凭据保护业务效果。重试有上限，死信不等于自动处理成功 |
| E06 有界发布器优化 | [OutboxPublisher](../../src/main/java/com/campuslife/messaging/service/OutboxPublisher.java) | [旧新对照与11个真实崩溃窗口](../reports/outbox-publisher-comparison-2026-11-01.md) | 单工作线程、零队列、每轮最多32次逐条认领、1秒继续认领预算。不是32线程并发，也不是当前在途调用必须1秒结束 |
| E07 私有文件与跨存储恢复 | [FileUploadService](../../src/main/java/com/campuslife/file/service/FileUploadService.java)、[S3FileStorage](../../src/main/java/com/campuslife/file/storage/S3FileStorage.java)、[FileCleanup](../../src/main/java/com/campuslife/file/service/FileCleanup.java) | [文件链](../reports/s3-file-chain-2026-11-02.md)、[强杀/晚到PUT/恢复](../reports/s3-recovery-2026-11-02.md) | 存储IO在SQL事务外；持久墓碑循环处理晚到对象。下载不主动重算数据库业务SHA，不能称任意同长外部篡改都被拒绝 |

## 指标只能带着口径使用

优先使用E06的完整旧新对照，它有明确版本隔离和原始数据。2026-11-01固定本机隔离MySQL/Redis/RabbitMQ、主场景100学生/20券、并发1/5/10；旧新各24轮包含预热/正式/补充，三档正式完整成功p95各取三轮中位数：

| 前台并发 | 旧异步完整p95/ms | 新异步完整p95/ms | 降幅 |
|---:|---:|---:|---:|
| 1 | 18751.81 | 1147.49 | 93.88% |
| 5 | 18959.08 | 578.33 | 96.95% |
| 10 | 18990.79 | 1149.38 | 93.95% |

“完整成功”从首次POST发起至GET观察到SUCCEEDED，包含250ms轮询误差，不是POST响应时间。两版共4104次POST；不能把422券/版、批次速率或请求样本数当线上用户/QPS。新版仍慢于同步参照，异步每20张券的180次DML未减少；不能写“异步全面优于同步”“数据库写入减少三倍”。[报告](../reports/outbox-publisher-comparison-2026-11-01.md) / [机器汇总](../reports/outbox-comparison-2026-11-01/comparison.json)。

E03的例子可用于追问，默认不堆在简历：10,000店、热门店4,518条可见评价、16线程，两独立轮次热详情p50分别30.75→6.78ms、33.87→7.13ms。普通/零评价中位数普遍更慢；热读命中率是预热固定数据口径，不是生产长期命中率。[原表](../reports/redis-rating-cache-measurement-2026-10-28.md)。

## 当前不应写入简历的说法

| 容易写过头的表述 | 应如何表述 |
|---|---|
| 线上高并发、万级QPS、真实运营用户、生产SLA | 写个人学习项目及指定隔离负载；最终综合测量尚未执行 |
| MQ保证Exactly Once、任何故障消息都不丢 | 写outbox持久化、发布确认、消费幂等、有界重试和已验证故障窗口，说明死信及恢复边界 |
| Redis与MySQL通过Lua同一个原子事务 | Lua仅保证Redis脚本内操作；跨系统由SQL事实、消息与恢复协调 |
| 整站Redis缓存、分布式锁/集群/微服务 | 详情评分缓存与券预留是真实范围；合并锁为进程内，当前单实例 |
| 接入支付宝/微信支付、MinIO、ZSet排行 | 当前为模拟资金、SeaweedFS私有S3；ZSet排行未实现 |
| 云端已完全稳定、HTTP新探针已部署、全栈压测已通过 | 云端有既有定向验证；健康检查未收口，候选缺客户端，综合测量延后 |
| 本人已熟练掌握全部设计 | 本轮只生成材料，理解和现场复述还需本人完成 |

## 本次交付与后续

本轮完成首版证据地图、源码复盘路线及简历初稿，不等于M07全部验收通过。下一项建议本人先复述“异步领券为何分三阶段、202表示什么、提交结果未知为什么不能直接返库存”，再按真实理解修订初稿；不为回答问题再增加业务或技术栈。
