# 商家查询性能基线：测量方法与复现

本任务测量当前公开商家列表、详情与评价查询，为后续SQL或缓存方案提供比较依据。生产SQL、索引、业务规则、接口和依赖均保持原状，未接入Redis；事务/行锁等正确性机制不在本轮重新开发。

以上是C06当时范围。2026-10-28后续[M01.2](public-shop-list-adoption.md)已仅对分类首页采纳等价SQL，原报告保留当次源码与数据；现在重跑基线将测当前实现，不能覆盖历史原版结果或直接跨阶段计算收益。

后续[M01.1 SQL对照实验](public-shop-sql-experiment.md)复用同一数据分布，造数逻辑已抽到PublicShopQueryFixture供两个入口使用；历史报告仍对应当次工具版本。对照实验只计count/page事务，不能与本指南完整HTTP耗时直接相除计算收益。

## 测量对象

| 接口 | 当前数据库工作 | 读取边界 |
|---|---|---|
| GET /api/v1/shops | 可见资格联表count＋分页；非空页再批量加载营业时间与评分 | 单个响应只读RR，非空页4条SQL，空页2条 |
| GET /api/v1/shops/{shopId} | 可见详情＋整周营业时间＋本店VISIBLE评分聚合 | 3条SQL，同一RR快照 |
| GET /api/v1/shops/{shopId}/reviews | 可见资格＋VISIBLE评价count＋带作者当前昵称的分页 | 3条SQL，同一RR快照 |

实现位置：[PublicShopMapper](../src/main/java/com/campuslife/merchant/mapper/PublicShopMapper.java)、[PublicReviewMapper](../src/main/java/com/campuslife/merchant/mapper/PublicReviewMapper.java)、[BusinessHourMapper](../src/main/java/com/campuslife/merchant/mapper/BusinessHourMapper.java)、[PublicShopCatalogService](../src/main/java/com/campuslife/merchant/service/PublicShopCatalogService.java)。

## 测试数据与安全边界

使用已有[test-mysql.ps1](../scripts/test-mysql.ps1)创建随机回环端口的独立MySQL、随机凭据和独立文件根。测量工具在写数据前检查数据库名、真实@@datadir属于工作区`.cache/mysql-it/<32位随机ID>/data`，以及账号/店铺/订单/评价表为空。成功后脚本停止并清理整个自建实例；不连接用户当前项目库，不使用用户密码，不读写持久上传目录。

数据由[PublicShopQueryBaseline](../src/test/java/com/campuslife/PublicShopQueryBaseline.java)用确定性SQL构造，不是通过注册/下单HTTP逐笔创建。用途是建立受控规模和分布；完整业务链正确性仍由已有旅程测试证明。

- 两档累计规模：1,000家和10,000家虚构商家，理发/打印各半；**这是压力规模实验，不代表一所校园真实存在这些商家**。
- 60%商家符合公开资格；其余分别模拟待审核、暂停、经营账号停用、OWNER停用，防止只测“全部可见”的理想数据。
- 每家都有整周营业时间。每家理发店20笔历史已完成预约，并有真实关联的服务、人员/绑定、历史日期保护/排班、支付、五条订单事件和一单一评价。
- 热门店额外5,000条历史完成预约与评价，合计5,020条，VISIBLE为4,518条；其余评价由虚构管理员隐藏，保留审核字段。普通理发店20条中18条VISIBLE。
- 部分学生当前停用，仍保留其历史公开评价，符合已确认规则。无文件、未结束订单或待处理资金任务。
- 生成账号只有随机密码摘要，没有固定演示密码或登录会话；所有数据只存在于本轮临时库。

数据断言核对公开数量、订单/评价/支付关系、完成状态、CONSUMED占用、排班覆盖及事件总数；响应断言核对独立计算的可见ID、排序、分页、total与真实评分。它是查询数据夹具，不宣称复现了历史每次API写入与管理员处理过程。

## 场景与统计口径

每档11个场景：首页20条、首页100条、理发分类、常见名称包含搜索、稀有名称包含搜索、无匹配搜索、接近末页的深分页、普通详情、热门详情、热门评价首页及深分页。

1. 通过测试专用MyBatis拦截器捕获实际HTTP请求发出的SQL和绑定参数，验证4/3/2条数量，以及请求连接为只读RR。没有复制一套手写“近似生产SQL”来代替测量。
2. 每条捕获的SELECT执行传统EXPLAIN和EXPLAIN ANALYZE，保留索引、预计行数以及实际迭代器行数/loops/time。仅对读语句执行；EXPLAIN ANALYZE本身会真正运行查询且有观测开销，不把它的时间当作接口延迟。[MySQL官方说明](https://dev.mysql.com/doc/refman/8.0/en/explain.html)
3. 每条SQL先预热5次，再用JdbcTemplate测量30次往返（包括连接取得、驱动、结果读取及映射）；这些值不是纯数据库执行时间，也不等于一次完整RR请求。
4. 每个HTTP场景先预热5次，之后按固定随机种子交错执行30轮，单客户端、单并发、连接复用。计时包含HTTP往返、服务处理、响应读取和JSON解析等测试侧开销。
5. 输出原始样本、min/p50/p95/max/mean，百分位使用nearest-rank。没有人为合格阈值；运行通过表示测量和正确性断言成功，不表示容量达标。

这是**预热后的本机只读基线**。数据装载、ANALYZE TABLE和执行计划采集均在计时样本之外；计划采集会预热数据库。没有清空操作系统或InnoDB缓存，没有并发写入、网络远程访问、长稳测试或QPS容量探测。30次样本的p95容易受JIT、GC、调度和后台负载影响，比较优化时应多轮重复并控制环境。

## 复现

本机需有原项目已可用的Java21、Maven和MySQL客户端/服务端二进制，无需停止当前开发应用或填写项目数据库密码。先保留需要比较的旧JSON，再在项目根执行：

```powershell
.\scripts\benchmark-public-shops.ps1
```

脚本选择`-Dit.test=PublicShopQueryBaseline`并临时设置专用启用标记，普通Surefire测试仍运行，并沿原隔离脚本完成mysql可执行JAR检查和清理。该类刻意不以Test/IT结尾，普通全量回归不会意外装载大规模基线数据；显式指定且带标记才能运行。脚本结束恢复调用前的环境值。不要在正式项目库直接运行测量类。

输出`output/performance/public-shop-query-baseline.json`（下一次成功测量会覆盖）和`.cache/public-shop-baseline-<UTC时间>.log`。原始JSON只保存技术环境、SQL/绑定的虚构ID与关键字、执行计划、计时样本、索引与源码指纹，不导出JUnit环境属性、凭据或用户目录。正式本轮结论见[2026-10-28报告](reports/public-shop-query-baseline-2026-10-28.md)。

## 如何解读

- 先看EXPLAIN的访问表、key、type和Extra，再用ANALYZE核对实际rows与loops；估算行数不能直接当作实际扫描总数，嵌套循环的行数需要结合循环次数理解。
- 名称按`%关键字%`包含匹配，不能因新增普通名称B-tree索引就承诺缩小为前缀范围扫描；MySQL的LIKE范围条件要求模式不以通配符开头。[MySQL范围优化](https://dev.mysql.com/doc/refman/8.0/en/range-optimization.html)
- 首页LIMIT可能提前结束，而精确count仍需处理全部匹配资格；深分页需要跳过前面的记录。是否出现filesort、选哪条索引要看实际计划，不仅凭索引定义推断。[MySQL LIMIT优化](https://dev.mysql.com/doc/refman/8.0/en/limit-optimization.html)
- 当前评分SQL已经按页面ID批量聚合，避免逐店N+1，但热门店的可见评价仍可能增加聚合工作。固定SQL条数不等于固定查询成本。
- 增加覆盖索引、改写查询、引入游标分页、统计表或Redis各有写入/存储/语义代价。先记录证据再提出方案；本轮不改变现有分页、实时资格、当前昵称或RR快照承诺。

后续缓存设计需要先决定哪些字段可以短暂陈旧、商家/账号状态变化如何失效、评分与营业时间如何处理、Redis失败如何降级。不能直接缓存整个响应后声称保留了原先的读取一致性。
