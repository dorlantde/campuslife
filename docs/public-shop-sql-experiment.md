# M01.1 商家列表SQL等价优化对照实验

本任务沿[路线图](roadmap.md)推进，只比较公开商家列表的count与page，不修改生产Mapper、索引、接口或业务规则。实验结果和采纳建议见[2026-10-28报告](reports/public-shop-sql-experiment-2026-10-28.md)；是否应用到生产实现属于M01.2。

后续状态：M01.2已完成[分类首页限定采纳与HTTP复测](reports/public-shop-list-adoption-2026-10-28.md)。本指南及M01.1报告保留当次阶段事实，源码摘要对应实验时版本；不能用今天的源码覆盖或重新标注旧测量为实际优化接口收益。

## 为什么做

[原基线](reports/public-shop-query-baseline-2026-10-28.md)显示，原SQL在1万店数据下可能从账号或成员扫描开始，联表处理6,000个公开店铺，再排序取20个。查询条数固定，实际工作量却会随规模和优化器选出的计划变化。

本次选择两个SQL候选加原版，控制实验范围，不同时追加索引、直方图、评分优化或游标分页。先回答“是否存在语义等价、代价更合理的查询形态”，不预设必须得到某个百分比收益。

## 三个版本

| 标识 | count | page | 用途与风险 |
|---|---|---|---|
| original | 实际PublicShopMapper渲染后的SQL | 实际Mapper渲染，保留可选条件与四表联查 | 对照组，没有复制近似版原SQL |
| exists_derived | 从shop筛选，EXISTS验证有效OWNER和账号 | 先按同样资格选中一页shop.id，再加载店铺与分类展示字段 | 无提示；仍允许优化器将EXISTS改写成半连接，因此不保证一定从shop按顺序提前结束 |
| exists_no_semijoin | 上一版加NO_SEMIJOIN查询块提示 | ID分页查询块加同样提示，其余相同 | 用于验证相关资格查询与半连接改写的影响；可能只改善部分场景，有版本/计划维护代价，不直接推荐全局套用 |

候选只在[PublicShopSqlExperiment](../src/test/java/com/campuslife/PublicShopSqlExperiment.java)中。optional category/keyword为空时不生成相应条件，非空时仍绑定参数并使用`LIKE ... ESCAPE '!'`；不把包含匹配改成前缀，不拼接用户值，不改变id ASC与OFFSET契约。

### 等价依赖

- shop.category_id非空且有真实外键，分类必存在；原公开资格不检查分类enabled。count可以去掉仅验证存在的分类JOIN，page在取到ID后再加载分类。
- shop_member.shop_id唯一且OWNER角色受约束，account主键唯一，因此原JOIN每店最多一条；将资格写成EXISTS不会丢失合法重复结果。
- **完整公开资格先筛选，再LIMIT/OFFSET**。不能先取20家获批店，再过滤停用OWNER或账号，否则会丢条目、错误分页。
- count和page仍在同一个只读REPEATABLE_READ事务中。候选不是先读缓存或分两次HTTP拼装。

约束见[V2迁移](../src/main/resources/db/migration/V2__create_merchant_onboarding.sql)及[原Mapper](../src/main/java/com/campuslife/merchant/mapper/PublicShopMapper.java)。若未来允许多个OWNER或改变可见规则，必须重新评估等价性。

### 为什么还要看实际计划

MySQL能将符合条件的EXISTS改写为半连接；改成EXISTS本身不保证执行顺序变好。[官方半连接说明](https://dev.mysql.com/doc/refman/8.0/en/semijoins.html)

带LIMIT的派生表不会被直接合并进外层查询块，便于将展示字段加载限制在选中的ID页；但内部资格查询本身仍可能扫描大量候选。[官方派生表说明](https://dev.mysql.com/doc/refman/8.0/en/derived-table-optimization.html)

NO_SEMIJOIN提示按查询块限制半连接改写。实验保留SHOW WARNINGS并拒绝Hint语法警告，同时检查实际ANALYZE树；不用全局关闭优化器设置。[官方提示说明](https://dev.mysql.com/doc/refman/8.0/en/optimizer-hints.html)

## 数据、正确性与隔离

复用C06相同的1,000/10,000店分布与历史订单评价，生成器抽为[PublicShopQueryFixture](../src/test/java/com/campuslife/PublicShopQueryFixture.java)，原SQL和关系断言沿用。[基线入口](../src/test/java/com/campuslife/PublicShopQueryBaseline.java)也使用该夹具，避免维护两套不同造数逻辑。旧报告保留当次工具指纹，不因本次测试代码抽取而改写历史结果。

每次独立实例装载两档规模：60%店铺公开，分类各半，含待审核/暂停/停用账号/OWNER，热门店5,020条评价。默认运行两个独立MySQL实例，随机端口、临时目录、随机密码摘要；写入前确认真实datadir属于工作区的`.cache/mysql-it/<随机ID>/data`、项目测试库且业务表为空。成功后原脚本关闭并清理整个自建实例。

每档验证：

- 七个测量场景：20条首页、100条首页、理发分类、常见/稀有/无匹配名称、接近末页的深分页。
- 原SQL与真实MyBatis Mapper核对总数和ID；用独立计算的资格/筛选/分页期望核对原版；两个候选核对**全部返回列、顺序与total**。计时样本结束后也核对结果，避免只测快却没查对。
- 额外边界：不存在分类、分类＋名称交集、字面量`%/_/!`、带特殊字符的实际店名、SQL样式字符串按字面量查询、越过末页、超int范围偏移。
- 临时改变分类enabled、店铺批准/营业状态、OWNER状态、账号状态/角色，核对当前公开规则，随后恢复数据再计时。
- 每版在读count后，由另一连接提交经营账号停用；同RR的page仍读原快照，新请求才看到少一店。使用真实MySQL，不以mock声称验证了快照。

这些写入只用于隔离夹具的语义验证，计时阶段无并发写；不访问用户现有店铺、数据库密码、文件或本机启动密钥。

## 测量口径

每实例、每档、每场景和版本预热5次，随后执行3轮，每轮20次。每个样本交错随机化场景与版本，避免一直先测原版或先测候选。每版每场景得到60个原始样本；默认两个实例共5,040个正式**count＋page事务**样本，每样本包含两个SELECT。

计时包括事务/连接、JDBC往返及Map结果读取与实验检查开销；参数构造和结果等价比较在样本计时之外。所有版本使用同一通道，不把Mapper开销与JDBC开销直接相比。每版输出完整SQL、参数、传统EXPLAIN、EXPLAIN ANALYZE和提示警告。

**本次不是完整HTTP响应测量**，不包括营业时间/评分批量查询、接口过滤器、HTTP传输和JSON；不得直接与C06的HTTP p50=61.06ms计算接口提升率。若M01.2采用候选，需要另做实际Mapper与完整HTTP回归/复测。

不清空OS/InnoDB缓存；装载、ANALYZE、计划采集与预热会产生热读条件。每轮p50/p95采用nearest-rank，保留分轮和跨实例结果。样本不能证明并发容量，重复测量不能替代分布/环境代表性。

## 复现与阅读

在项目根运行，沿已有Java21/Maven/MySQL二进制，不输入项目库密码：

```powershell
.\scripts\experiment-public-shop-sql.ps1
```

脚本默认Runs=2，也可显式Runs=1先检查；每次生成独立的`output/performance/public-shop-sql-<UTC时间>-<随机ID>/`，不覆盖C06原始数据。`completed.json`仅在全部实例的断言、测量、普通测试、mysql JAR检查与清理成功后写入。

然后用已安装的Python运行汇总工具，把该目录替换为本次实际路径：

```powershell
python .\scripts\report-public-shop-sql.py --input .\output\performance\public-shop-sql-实际时间-实际随机ID
```

汇总工具校验样本分位数、源码指纹和完成记录，默认将summary.md/json写入该轮输出目录；正式交付报告使用本次日期归档到docs/reports。底层类需专用启用标记且不匹配普通Test/IT扫描，不在正式项目库直接运行。

阅读时先比较同一实例、同一场景各版本的分轮p50/p95，再看rows×loops、排序输入与展示字段回表数量；不能只挑一个最快样本。保留原版，允许“无收益”或“部分场景退化”的结论。M01.2决定采纳、按场景使用或保留原状，本阶段不直接修改生产实现。
