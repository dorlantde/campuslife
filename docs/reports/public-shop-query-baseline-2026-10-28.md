# 公开商家查询性能基线报告

- 日期：2026-10-28（America/New_York）。
- 对象：当前工作区的公开商家列表、详情、评价；未修改生产SQL、索引、业务规则或依赖。
- 结论：已建立两档数据、11类查询场景的可复现基线，发现联表分页、评分聚合和深分页的优化候选。**尚未进行优化或接入Redis，不能声明性能提升。**
- 本报告表格采用第二轮一键脚本复验结果；首轮数据同时保留，不能将两轮差异当作优化收益。

## 1. 环境、数据与验证

实际MySQL 8.0.45、Java 21.0.12.1、Windows 11 / amd64，JVM可见32个处理器，InnoDB缓冲池128 MiB，连接池最大5，HTTP客户端并发1。未采集CPU型号、物理内存或后台负载，不能直接跨机器比较。

V1～V15原迁移成功，23张业务表。每轮使用新的随机端口临时MySQL，写入前核对空库和实际datadir；成功后实例停止、自建目录清空。没有使用当前开发库、现有业务账号密码或持久文件。

| 数据 | 小档 | 大档 |
|---|---:|---:|
| shop | 1,000 | 10,000 |
| publicShops | 600 | 6,000 |
| account | 1,101 | 10,101 |
| shop_business_hour | 7,000 | 70,000 |
| service_order | 15,000 | 105,000 |
| booking_detail | 15,000 | 105,000 |
| payment_record | 15,000 | 105,000 |
| order_event | 75,000 | 525,000 |
| order_review | 15,000 | 105,000 |
| visibleReviews | 13,500 | 94,500 |

数据均为确定性SQL生成的虚构规模实验；并非真实运营数据，也不是HTTP逐笔造数。服务/人员/排班、完成订单、模拟支付、五条事件与一单一评价具有关联；60%商家公开，评价10%隐藏，热门店5,020条评价中4,518条可见。详细生成方式、关系断言及分布见[测量方法](../public-shop-query-baseline.md)。

第二轮普通/Web测试8类172用例＋基线类1个用例，共173，失败/错误/跳过0；基线用例内包含22个场景测量与关系/响应/只读RR/SQL条数断言。mysql可执行JAR健康、编码、trace和访问边界检查通过。未重跑其余历史MySQL业务套件，不将测量样本数当作JUnit用例数。

## 2. HTTP预热读基线

每档每场景显式预热5次、正式30次；正式请求按固定随机种子逐轮交错。p50/p95采用nearest-rank，单位毫秒；包含客户端HTTP往返、响应读取与JSON解析等开销。数据库由数据装载、执行计划采集和预热形成热读条件，未测冷启动。

| 场景 | 1,000店 p50 | 1,000店 p95 | 10,000店 p50 | 10,000店 p95 | SQL条数 |
|---|---:|---:|---:|---:|---:|
| 列表首页20条 | 18.11 | 24.56 | 61.06 | 68.11 | 4 |
| 列表首页100条 | 21.00 | 28.37 | 63.63 | 69.65 | 4 |
| 理发分类首页 | 18.09 | 21.82 | 53.53 | 71.42 | 4 |
| 常见名称包含搜索 | 19.43 | 22.72 | 43.98 | 50.69 | 4 |
| 稀有名称包含搜索 | 5.80 | 8.33 | 19.40 | 23.67 | 4 |
| 无匹配名称搜索 | 4.97 | 7.67 | 18.22 | 20.58 | 2 |
| 商家深分页 | 8.36 | 10.67 | 57.75 | 77.59 | 4 |
| 普通店铺详情 | 3.80 | 5.50 | 3.24 | 4.64 | 3 |
| 热门店铺详情 | 7.27 | 9.42 | 7.29 | 10.24 | 3 |
| 热门评价首页 | 4.75 | 7.13 | 3.89 | 4.48 | 3 |
| 热门评价第225页 | 10.30 | 14.45 | 10.47 | 14.60 | 3 |

商家深分页分别为第30页(offset=580)和第300页(offset=5,980)，热门评价深分页固定第225页(offset=4,480)。两档首页都包含热门店，不能直接用“深页比首页更快/慢”归因于offset；结果还受页内评分分布、执行计划与运行噪声影响。

### 首轮与复验差异

| 10,000店场景 | 首轮p50/p95 | 第二轮p50/p95 |
|---|---:|---:|
| 列表首页20条 | 60.13 / 103.81 | 61.06 / 68.11 |
| 理发分类首页 | 47.44 / 68.89 | 53.53 / 71.42 |
| 商家深分页 | 52.38 / 90.18 | 57.75 / 77.59 |
| 热门店铺详情 | 8.58 / 13.63 | 7.29 / 10.24 |
| 热门评价第225页 | 12.59 / 18.99 | 10.47 / 14.60 |

两轮均未修改生产实现。第二轮补充只读RR采集断言，并验证一键入口；统计信息、系统调度/JIT/GC和后台负载可能不同。30次样本的尾延迟有波动，后续对照需保留全部结果、多轮比较，不能选择更快的一轮声称优化。

## 3. 单条SQL与执行计划

以下为大档单条SQL的独立JdbcTemplate往返p50/p95，包括驱动及结果映射，不是纯服务端耗时。各SQL在不同时间独立测量，不能相加后硬解释为HTTP总耗时；EXPLAIN ANALYZE的迭代器时间也另有观测开销。

| 场景 | SQL顺序 | JDBC p50 | JDBC p95 | 访问方式（表:key/type） |
|---|---:|---:|---:|---|
| 列表首页20条 | 1 | 23.89 | 34.16 | a:无/ALL, m:uk_shop_member_account/eq_ref, s:PRIMARY/eq_ref, c:PRIMARY/eq_ref |
| 列表首页20条 | 2 | 29.09 | 38.72 | a:无/ALL, m:uk_shop_member_account/eq_ref, s:PRIMARY/eq_ref, c:无/ALL |
| 列表首页20条 | 3 | 0.47 | 0.64 | shop_business_hour:uk_shop_business_hour_start/range |
| 列表首页20条 | 4 | 4.48 | 11.62 | order_review:ix_order_review_shop/range |
| 理发分类首页 | 1 | 20.86 | 23.57 | c:PRIMARY/const, a:无/ALL, m:uk_shop_member_account/eq_ref, s:PRIMARY/eq_ref |
| 理发分类首页 | 2 | 24.40 | 33.26 | c:PRIMARY/const, a:无/ALL, m:uk_shop_member_account/eq_ref, s:PRIMARY/eq_ref |
| 理发分类首页 | 3 | 0.47 | 0.57 | shop_business_hour:uk_shop_business_hour_start/range |
| 理发分类首页 | 4 | 5.04 | 11.16 | order_review:ix_order_review_shop/range |
| 稀有名称包含搜索 | 1 | 7.24 | 7.87 | c:uk_shop_category_code/index, s:ix_shop_approval_category_id/ref, m:uk_shop_member_owner/eq_ref, a:PRIMARY/eq_ref |
| 稀有名称包含搜索 | 2 | 7.92 | 10.88 | s:ix_shop_approval_category_id/ref, m:uk_shop_member_owner/eq_ref, a:PRIMARY/eq_ref, c:PRIMARY/eq_ref |
| 稀有名称包含搜索 | 3 | 0.26 | 0.34 | shop_business_hour:uk_shop_business_hour_start/range |
| 稀有名称包含搜索 | 4 | 0.22 | 0.34 | order_review:ix_order_review_shop/range |
| 热门店铺详情 | 1 | 0.28 | 0.49 | s:PRIMARY/const, c:PRIMARY/const, m:uk_shop_member_owner/const, a:PRIMARY/const |
| 热门店铺详情 | 2 | 0.26 | 0.45 | shop_business_hour:uk_shop_business_hour_start/ref |
| 热门店铺详情 | 3 | 3.29 | 5.36 | order_review:ix_order_review_shop/ref |
| 热门评价第225页 | 1 | 0.26 | 0.44 | s:PRIMARY/const, c:PRIMARY/const, m:uk_shop_member_owner/const, a:PRIMARY/const |
| 热门评价第225页 | 2 | 0.87 | 0.96 | order_review:ix_order_review_shop/ref |
| 热门评价第225页 | 3 | 6.02 | 6.53 | r:ix_order_review_shop/ref, a:PRIMARY/eq_ref |

列表SQL顺序为count、分页、营业时间批量、评分批量；详情为资格/详情、营业时间、评分；评价为资格、count、评价分页。完整SQL、绑定参数、索引、传统EXPLAIN和ANALYZE树均保留在原始JSON。

### 列表首页分页实际计划

```text
-> Limit: 20 row(s)  (actual time=30.5..30.5 rows=20 loops=1)
    -> Sort: s.id, limit input to 20 row(s) per chunk  (actual time=30.5..30.5 rows=20 loops=1)
        -> Stream results  (cost=1056 rows=2.45) (actual time=24.8..28.3 rows=6000 loops=1)
            -> Inner hash join (c.id = s.category_id)  (cost=1056 rows=2.45) (actual time=24.8..26.6 rows=6000 loops=1)
                -> Table scan on c  (cost=0.148 rows=2) (actual time=0.0078..0.0087 rows=2 loops=1)
                -> Hash
                    -> Nested loop inner join  (cost=1055 rows=2.45) (actual time=0.0593..23.2 rows=6000 loops=1)
                        -> Nested loop inner join  (cost=1054 rows=4.9) (actual time=0.0498..15.3 rows=7000 loops=1)
                            -> Filter: ((a.`role` = 'MERCHANT') and (a.`status` = 'ACTIVE'))  (cost=1019 rows=97.9) (actual time=0.0386..2.31 rows=9000 loops=1)
                                -> Table scan on a  (cost=1019 rows=9792) (actual time=0.0265..1.76 rows=10101 loops=1)
                            -> Filter: ((m.member_role = 'OWNER') and (m.`status` = 'ACTIVE'))  (cost=0.25 rows=0.05) (actual time=0.00135..0.00138 rows=0.778 loops=9000)
                                -> Single-row index lookup on m using uk_shop_member_account (account_id=a.id)  (cost=0.25 rows=1) (actual time=0.00126..0.00127 rows=1 loops=9000)
                        -> Filter: (s.approval_status = 'APPROVED')  (cost=0.26 rows=0.5) (actual time=0.001..0.00104 rows=0.857 loops=7000)
                            -> Single-row index lookup on s using PRIMARY (id=m.shop_id)  (cost=0.26 rows=1) (actual time=932e-6..945e-6 rows=1 loops=7000)
```

### 热门详情评分实际计划

```text
-> Group aggregate: sum(order_review.rating), count(0)  (cost=2250 rows=5481) (actual time=3.84..3.84 rows=1 loops=1)
    -> Index lookup on order_review using ix_order_review_shop (shop_id=1000006, visibility='VISIBLE')  (cost=1452 rows=7982) (actual time=0.0448..3.64 rows=4518 loops=1)
```

### 热门评价深分页实际计划

```text
-> Limit/Offset: 20/4480 row(s)  (cost=4245 rows=20) (actual time=6.67..6.7 rows=20 loops=1)
    -> Nested loop inner join  (cost=4245 rows=7982) (actual time=0.0558..6.62 rows=4500 loops=1)
        -> Index lookup on r using ix_order_review_shop (shop_id=1000006, visibility='VISIBLE') (reverse)  (cost=1452 rows=7982) (actual time=0.0508..4.27 rows=4500 loops=1)
        -> Single-row index lookup on a using PRIMARY (id=r.student_id)  (cost=0.25 rows=1) (actual time=437e-6..450e-6 rows=1 loops=4500)
```

## 4. 发现、推断与下一步

### A. 优先研究商家列表的联表与排序

**实测**：大档首页HTTP p50=61.06 ms、p95=68.11 ms；单SQL独立测量中，count p50=23.89 ms、分页p50=29.09 ms。分页ANALYZE显示先生成6,000条可见结果，再排序取20条；传统EXPLAIN包含temporary/filesort。现有索引存在，并不意味着这条执行路径能够按店铺ID提前结束。

第二轮由account表扫描10,101行开始，角色/状态过滤实际留下9,000行，而估算约97.9；首轮同一生产SQL从shop_member扫描10,000行开始，过滤实际8,000行、估算约99。两轮驱动表不同，但都产生大量联表工作。这里可以确认**估算与实际存在偏差、当前分页先处理大量候选再取小页**，不能仅凭这份报告认定某个索引或某项优化器设置必然是根因。

**建议的下一项小任务**：在相同隔离夹具中对比等价SQL写法、统计信息与候选索引，核对可见资格、排序、count/items及RR一致性，观察是否减少行访问与排序，再决定是否修改生产查询。不要一开始固定JOIN顺序或添加多个索引；这些只是实验候选，均未在本轮实施。

### B. 名称搜索不能靠普通名称索引直接承诺加速

**实测**：稀有搜索最终仅6店，大档分页仍从approval索引读取8,000条APPROVED店铺，再做名称包含过滤。无匹配查询仍需要count和page两条SQL，空结果不代表零扫描。

**推断**：已有按分类/批准状态索引能先缩小部分范围，但`%关键字%`本身不构成普通名称B-tree的前缀范围。保留包含搜索语义的前提下，先降低联表和无关候选成本；改前缀搜索、引入全文搜索或独立搜索服务都涉及效果/规则或基础设施讨论，本轮不改。[MySQL范围优化说明](https://dev.mysql.com/doc/refman/8.0/en/range-optimization.html)

### C. 评分已经批量查询，热门店仍有聚合成本

**实测**：大档列表非空页始终4条SQL，营业时间范围查询首页140行，未出现逐店N+1。热门店详情资格/营业时间是少量记录，评分SUM/COUNT实际读取4,518条VISIBLE评价；单评分SQL JDBC p50=3.29 ms，整个详情HTTP p50=7.29 ms。

**候选**：可研究评分覆盖索引以减少回表，或后续按明确一致性规则设计评分统计/Redis缓存；两者都有写入、存储或失效代价。当前结果说明存在重复聚合工作，不证明必须引入缓存或已经获得缓存收益。

### D. 评价深分页有明确的跳过成本

**实测**：热门评价第225页只返回20条，但索引读取4,500条评价，并对作者执行4,500次主键查询。使用了现有评价索引的反向扫描；这不是“没走索引”，而是offset与联表时机仍有成本。

**候选**：保持现有页码契约，可先对比“先取该页评价ID，再关联内容/作者”的等价延迟关联方案；游标分页则会改变客户端契约，需另行定稿。首页与深页评分/数据分布不同，不能只拿两个HTTP耗时相除作为优化收益。

### E. 后续Redis方案的边界

缓存可研究重复商家信息读取与评分计算，但可见资格依赖店铺、OWNER、经营账号；openNow随上海当前时间改变，评价昵称来自作者当前资料。直接缓存完整响应会引入原方案没有的陈旧窗口，也可能破坏同一RR响应的一致性承诺。

下一阶段需明确缓存对象、哪些字段仍实时读取、允许的陈旧时间、写后失效与并发回填、空值/热点保护、Redis故障降级以及可重复对照指标。以上尚未定稿，不能把本轮基线当作Redis实现完成。

## 5. 复现与证据

实际已执行的一键复现命令（无需项目数据库密码）：

```powershell
.\scripts\benchmark-public-shops.ps1
```

- 第二轮日志：`.cache/public-shop-baseline-20261028-042117.log`。
- 首轮日志：`.cache/public-shop-baseline-2026-10-28.log`。
- [第二轮原始证据](public-shop-query-baseline-2026-10-28.json)、[首轮原始证据](public-shop-query-baseline-2026-10-28-first.json)。
- [测试工具源码](../../src/test/java/com/campuslife/PublicShopQueryBaseline.java)、[一键脚本](../../scripts/benchmark-public-shops.ps1)、[方法与阅读说明](../public-shop-query-baseline.md)。
- 原始数据保留每场景30个HTTP样本、每SQL30个JDBC样本，共660个正式HTTP样本与2,280个正式单SQL样本；76组传统EXPLAIN＋ANALYZE。
- JSON内记录生产查询源码SHA-256、索引定义与测试工具指纹。未复制系统环境、密码或token。

本轮没有新增业务接口、迁移、Redis、SQL/索引优化或分布式部署。已确认的业务和读取一致性保持。测量未包含并发写、远程网络、满负载、长稳及故障容量，不能据此宣称QPS、承载用户数或性能提升。
