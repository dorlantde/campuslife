# M01.1 商家列表SQL等价优化对照报告

- 日期：2026-10-28（America/New_York）。
- 执行：2个独立临时MySQL实例；每实例两档1,000/10,000店、7场景、3版本、3轮×20样本。
- 结果：原版与两个候选的总数、全部返回列、排序及额外语义边界均通过；count/page保持真实只读RR快照。
- 范围：只比较SQL，生产Mapper/Service、索引、迁移、业务规则和接口未修改；候选仅存在测试工具中。
- 计时单位：count＋page两个SELECT的JDBC事务往返，**不是完整HTTP接口耗时**。

## 1. 环境与验证范围

MySQL 8.0.45、Java 21.0.12.1、Windows 11，InnoDB缓冲池128 MiB，JVM可见32处理器；单客户端、无计时阶段并发写。未采集CPU型号、物理内存与后台负载。

每实例沿原隔离脚本创建随机端口/凭据/文件根，校验空库及真实datadir后造数。沿用C06同分布：60%店铺公开，两档15,000/105,000历史完成预约与评价、热门店5,020条评价。成功结束后关闭并清理自建实例，不访问现有项目库或持久文件。

每实例普通/Web172用例＋实验1用例，共173，零失败/错误/跳过；mysql JAR启动检查通过。实验一个用例内包含多场景/多轮与语义断言，不能把5,040个样本说成5,040个JUnit用例，也不累加成全量业务回归。

语义验证包括：分类/名称交集、不存在分类、特殊字符字面量、越过末页、long偏移、店铺/OWNER/账号角色状态、分类停用与主动停业仍展示；每版验证另一连接提交资格变化后同RR页保持旧快照、新RR看到新资格。全部参数绑定、整页数据与顺序对齐。

## 2. 比较版本

| 版本 | 差异 |
|---|---|
| original | 从实际生产MyBatis MappedStatement渲染SQL，并与真实Mapper核对count/ID |
| exists_derived | count移除依赖外键保证的分类存在性JOIN；EXISTS核对OWNER/账号，先完整资格筛选并取ID页，再加载店铺/分类展示列；可选条件动态生成 |
| exists_no_semijoin | 上一候选在资格查询块增加NO_SEMIJOIN提示，其余相同；未修改全局/会话优化器设置 |

EXISTS与原JOIN等价依赖当前成员唯一约束，去掉count分类JOIN依赖真实非空外键。分类enabled没有加入公开资格。ID页内完整应用资格后才LIMIT；展示加载和count仍在同一RR快照。原参数/结果/分页契约保持。

每版每场景预热5次，3轮正式请求交错随机顺序，每轮20次，计时包括连接/事务/JDBC/结果映射与共同的连接检查；参数构造、结果等价比较在计时外。保存全部分轮样本，p50/p95为nearest-rank。没有清空缓存，计划采集和预热造成热读；存在调度、JIT、GC及统计信息差异。

## 3. 分实例、分轮结果

下表为每轮p50/p95，单位ms，保留完整结果而非只挑最快样本。各列来自同轮同场景；不能与C06的HTTP p50=61.06ms直接计算接口提升率。

### 实例1，1,000家店

| 场景 | 轮次 | original p50/p95 | exists_derived p50/p95 | exists_no_semijoin p50/p95 |
|---|---:|---:|---:|---:|
| 首页20条 | 1 | 7.12 / 10.97 | 6.70 / 8.60 | 9.00 / 12.33 |
| 首页20条 | 2 | 6.24 / 9.91 | 4.64 / 8.36 | 7.13 / 11.81 |
| 首页20条 | 3 | 5.58 / 10.35 | 3.92 / 7.98 | 5.89 / 11.07 |
| 首页100条 | 1 | 7.63 / 10.75 | 7.10 / 9.34 | 9.22 / 13.62 |
| 首页100条 | 2 | 5.23 / 10.28 | 6.05 / 8.75 | 8.72 / 12.93 |
| 首页100条 | 3 | 5.36 / 10.61 | 4.32 / 8.21 | 6.04 / 12.50 |
| 理发分类首页 | 1 | 5.64 / 8.07 | 5.32 / 7.89 | 3.55 / 5.55 |
| 理发分类首页 | 2 | 5.00 / 8.25 | 3.41 / 7.33 | 2.82 / 5.69 |
| 理发分类首页 | 3 | 3.51 / 6.80 | 3.49 / 7.57 | 2.63 / 4.88 |
| 常见名称 | 1 | 9.35 / 12.95 | 6.08 / 8.87 | 6.07 / 8.98 |
| 常见名称 | 2 | 6.96 / 12.18 | 4.09 / 8.69 | 4.32 / 8.86 |
| 常见名称 | 3 | 6.46 / 12.17 | 5.52 / 7.82 | 6.36 / 8.81 |
| 稀有名称 | 1 | 3.39 / 5.01 | 3.62 / 6.26 | 3.09 / 4.65 |
| 稀有名称 | 2 | 3.31 / 4.72 | 4.13 / 5.79 | 2.53 / 4.51 |
| 稀有名称 | 3 | 2.69 / 3.83 | 2.93 / 4.05 | 2.43 / 3.72 |
| 无匹配名称 | 1 | 4.06 / 4.94 | 3.80 / 5.88 | 3.55 / 4.41 |
| 无匹配名称 | 2 | 2.72 / 4.43 | 3.04 / 5.90 | 2.28 / 3.67 |
| 无匹配名称 | 3 | 2.86 / 4.31 | 3.25 / 5.39 | 2.41 / 4.07 |
| 深分页 | 1 | 5.97 / 11.43 | 6.82 / 8.57 | 9.08 / 12.97 |
| 深分页 | 2 | 5.14 / 10.60 | 4.65 / 8.55 | 6.18 / 12.74 |
| 深分页 | 3 | 5.05 / 9.47 | 4.38 / 7.98 | 8.60 / 11.70 |

### 实例1，10,000家店

| 场景 | 轮次 | original p50/p95 | exists_derived p50/p95 | exists_no_semijoin p50/p95 |
|---|---:|---:|---:|---:|
| 首页20条 | 1 | 58.94 / 83.33 | 45.99 / 57.35 | 46.57 / 72.09 |
| 首页20条 | 2 | 54.60 / 82.99 | 43.28 / 71.06 | 48.32 / 73.22 |
| 首页20条 | 3 | 54.30 / 75.15 | 42.12 / 72.94 | 47.84 / 66.76 |
| 首页100条 | 1 | 54.41 / 69.01 | 48.07 / 83.76 | 47.13 / 74.85 |
| 首页100条 | 2 | 51.82 / 71.89 | 47.25 / 74.78 | 47.30 / 76.43 |
| 首页100条 | 3 | 53.18 / 60.02 | 42.85 / 78.68 | 47.78 / 59.43 |
| 理发分类首页 | 1 | 46.22 / 61.05 | 44.07 / 74.41 | 12.98 / 26.19 |
| 理发分类首页 | 2 | 45.97 / 72.88 | 45.27 / 71.86 | 12.83 / 27.45 |
| 理发分类首页 | 3 | 45.14 / 63.68 | 42.72 / 72.35 | 12.64 / 19.63 |
| 常见名称 | 1 | 38.16 / 50.77 | 37.56 / 59.32 | 32.44 / 65.36 |
| 常见名称 | 2 | 36.82 / 61.08 | 35.47 / 51.64 | 30.34 / 58.32 |
| 常见名称 | 3 | 36.20 / 49.29 | 35.94 / 61.76 | 32.78 / 58.34 |
| 稀有名称 | 1 | 16.34 / 23.61 | 15.40 / 20.83 | 12.15 / 17.49 |
| 稀有名称 | 2 | 15.44 / 19.13 | 14.59 / 20.11 | 11.26 / 12.53 |
| 稀有名称 | 3 | 15.65 / 24.18 | 14.37 / 22.95 | 11.35 / 13.66 |
| 无匹配名称 | 1 | 16.39 / 26.07 | 15.04 / 26.20 | 12.41 / 18.64 |
| 无匹配名称 | 2 | 15.46 / 22.32 | 15.59 / 24.39 | 11.37 / 18.34 |
| 无匹配名称 | 3 | 15.91 / 24.90 | 15.38 / 25.55 | 10.74 / 19.16 |
| 深分页 | 1 | 56.41 / 78.71 | 47.39 / 66.93 | 47.58 / 73.15 |
| 深分页 | 2 | 54.94 / 82.55 | 44.12 / 74.88 | 47.27 / 90.43 |
| 深分页 | 3 | 54.66 / 77.63 | 44.38 / 57.88 | 44.87 / 90.43 |

### 实例2，1,000家店

| 场景 | 轮次 | original p50/p95 | exists_derived p50/p95 | exists_no_semijoin p50/p95 |
|---|---:|---:|---:|---:|
| 首页20条 | 1 | 4.53 / 8.33 | 3.55 / 7.30 | 5.32 / 10.57 |
| 首页20条 | 2 | 4.33 / 5.98 | 3.50 / 4.15 | 5.16 / 6.24 |
| 首页20条 | 3 | 4.23 / 4.97 | 3.39 / 6.31 | 5.19 / 10.73 |
| 首页100条 | 1 | 4.73 / 5.27 | 3.87 / 8.22 | 6.00 / 8.61 |
| 首页100条 | 2 | 4.60 / 7.73 | 3.87 / 8.27 | 5.64 / 8.15 |
| 首页100条 | 3 | 4.46 / 7.46 | 3.72 / 5.01 | 5.42 / 5.84 |
| 理发分类首页 | 1 | 3.52 / 5.07 | 3.57 / 6.96 | 2.75 / 3.35 |
| 理发分类首页 | 2 | 3.35 / 7.13 | 3.12 / 6.68 | 2.46 / 5.26 |
| 理发分类首页 | 3 | 3.23 / 3.60 | 3.09 / 3.87 | 2.41 / 2.87 |
| 常见名称 | 1 | 6.02 / 10.88 | 3.90 / 6.62 | 3.89 / 7.09 |
| 常见名称 | 2 | 5.53 / 9.19 | 3.90 / 5.71 | 3.74 / 5.81 |
| 常见名称 | 3 | 5.47 / 6.31 | 3.62 / 6.07 | 3.84 / 4.89 |
| 稀有名称 | 1 | 2.57 / 4.24 | 3.22 / 4.27 | 2.37 / 3.54 |
| 稀有名称 | 2 | 2.56 / 4.21 | 2.87 / 4.37 | 2.12 / 3.43 |
| 稀有名称 | 3 | 2.51 / 3.15 | 2.71 / 3.13 | 2.01 / 2.42 |
| 无匹配名称 | 1 | 2.52 / 3.95 | 2.99 / 3.75 | 2.31 / 2.88 |
| 无匹配名称 | 2 | 2.58 / 3.27 | 2.85 / 4.59 | 2.13 / 3.11 |
| 无匹配名称 | 3 | 2.32 / 2.80 | 2.51 / 3.15 | 1.92 / 2.61 |
| 深分页 | 1 | 4.48 / 6.89 | 3.65 / 5.63 | 5.28 / 11.30 |
| 深分页 | 2 | 4.34 / 5.44 | 3.53 / 4.10 | 5.23 / 8.43 |
| 深分页 | 3 | 4.51 / 8.92 | 3.57 / 4.34 | 5.24 / 6.10 |

### 实例2，10,000家店

| 场景 | 轮次 | original p50/p95 | exists_derived p50/p95 | exists_no_semijoin p50/p95 |
|---|---:|---:|---:|---:|
| 首页20条 | 1 | 38.33 / 49.38 | 29.32 / 39.25 | 56.61 / 75.39 |
| 首页20条 | 2 | 36.22 / 52.71 | 27.29 / 37.86 | 40.49 / 48.32 |
| 首页20条 | 3 | 53.88 / 66.47 | 42.27 / 57.31 | 42.63 / 66.23 |
| 首页100条 | 1 | 36.45 / 52.27 | 28.28 / 48.62 | 41.65 / 71.09 |
| 首页100条 | 2 | 37.26 / 56.05 | 30.76 / 44.58 | 45.52 / 74.28 |
| 首页100条 | 3 | 52.53 / 71.87 | 46.31 / 65.61 | 51.70 / 67.78 |
| 理发分类首页 | 1 | 26.32 / 49.96 | 22.45 / 31.16 | 11.84 / 19.46 |
| 理发分类首页 | 2 | 24.57 / 37.03 | 22.53 / 35.49 | 11.74 / 18.73 |
| 理发分类首页 | 3 | 52.31 / 70.00 | 43.61 / 66.61 | 12.38 / 26.79 |
| 常见名称 | 1 | 34.95 / 42.10 | 34.92 / 53.04 | 27.80 / 58.03 |
| 常见名称 | 2 | 35.74 / 52.56 | 34.71 / 48.70 | 28.17 / 43.11 |
| 常见名称 | 3 | 35.33 / 59.64 | 34.67 / 48.52 | 28.76 / 48.36 |
| 稀有名称 | 1 | 16.27 / 24.21 | 15.27 / 19.63 | 11.08 / 13.42 |
| 稀有名称 | 2 | 15.77 / 20.79 | 15.76 / 23.28 | 11.98 / 16.83 |
| 稀有名称 | 3 | 16.23 / 22.66 | 15.21 / 19.32 | 11.72 / 17.49 |
| 无匹配名称 | 1 | 16.10 / 22.92 | 14.45 / 24.84 | 11.36 / 17.35 |
| 无匹配名称 | 2 | 17.19 / 24.04 | 15.59 / 24.00 | 11.33 / 16.21 |
| 无匹配名称 | 3 | 16.03 / 22.89 | 14.94 / 20.38 | 11.01 / 14.29 |
| 深分页 | 1 | 41.23 / 57.33 | 27.68 / 51.06 | 44.14 / 63.47 |
| 深分页 | 2 | 39.73 / 60.18 | 27.87 / 45.79 | 48.38 / 78.66 |
| 深分页 | 3 | 55.63 / 71.82 | 42.77 / 54.99 | 42.77 / 52.31 |

## 4. 查询工作量与关键计划

以下保留实例1、大档的三个分类分页计划。ANALYZE时间是独立带观测开销的一次执行，不能用它替代正式样本分布；主要比较输入行数、循环和排序工作。

### 分类分页：original

```text
-> Limit: 20 row(s)  (actual time=26.7..26.7 rows=20 loops=1)
    -> Sort: s.id, limit input to 20 row(s) per chunk  (actual time=26.7..26.7 rows=20 loops=1)
        -> Stream results  (cost=1081 rows=2.03) (actual time=0.0584..25.6 rows=3000 loops=1)
            -> Nested loop inner join  (cost=1081 rows=2.03) (actual time=0.0564..24.7 rows=3000 loops=1)
                -> Nested loop inner join  (cost=1080 rows=5.02) (actual time=0.045..16.2 rows=7000 loops=1)
                    -> Filter: ((a.`role` = 'MERCHANT') and (a.`status` = 'ACTIVE'))  (cost=1045 rows=100) (actual time=0.0364..2.46 rows=9000 loops=1)
                        -> Table scan on a  (cost=1045 rows=10043) (actual time=0.0244..1.88 rows=10101 loops=1)
                    -> Filter: ((m.member_role = 'OWNER') and (m.`status` = 'ACTIVE'))  (cost=0.25 rows=0.05) (actual time=0.00141..0.00144 rows=0.778 loops=9000)
                        -> Single-row index lookup on m using uk_shop_member_account (account_id=a.id)  (cost=0.25 rows=1) (actual time=0.00131..0.00133 rows=1 loops=9000)
                -> Filter: ((s.category_id = '1') and (s.approval_status = 'APPROVED'))  (cost=0.258 rows=0.404) (actual time=0.00114..0.00115 rows=0.429 loops=7000)
                    -> Single-row index lookup on s using PRIMARY (id=m.shop_id)  (cost=0.258 rows=1) (actual time=0.00102..0.00104 rows=1 loops=7000)
```

### 分类分页：exists_derived

```text
-> Sort: s.id  (actual time=32.5..32.5 rows=20 loops=1)
    -> Stream results  (cost=3.26 rows=0) (actual time=32.5..32.5 rows=20 loops=1)
        -> Inner hash join (c.id = s.category_id)  (cost=3.26 rows=0) (actual time=32.5..32.5 rows=20 loops=1)
            -> Table scan on c  (cost=0.18 rows=2) (actual time=0.0041..0.0048 rows=2 loops=1)
            -> Hash
                -> Nested loop inner join  (cost=3 rows=0) (actual time=32.4..32.5 rows=20 loops=1)
                    -> Table scan on selected  (cost=2.5..2.5 rows=0) (actual time=32.4..32.4 rows=20 loops=1)
                        -> Materialize  (cost=0..0 rows=0) (actual time=32.4..32.4 rows=20 loops=1)
                            -> Limit: 20 row(s)  (actual time=32.4..32.4 rows=20 loops=1)
                                -> Sort: s.id, limit input to 20 row(s) per chunk  (actual time=32.4..32.4 rows=20 loops=1)
                                    -> Stream results  (cost=1081 rows=2.03) (actual time=0.067..32 rows=3000 loops=1)
                                        -> Nested loop inner join  (cost=1081 rows=2.03) (actual time=0.0663..31.7 rows=3000 loops=1)
                                            -> Nested loop inner join  (cost=1080 rows=5.02) (actual time=0.054..21 rows=7000 loops=1)
                                                -> Filter: ((a.`role` = 'MERCHANT') and (a.`status` = 'ACTIVE'))  (cost=1045 rows=100) (actual time=0.042..3.35 rows=9000 loops=1)
                                                    -> Table scan on a  (cost=1045 rows=10043) (actual time=0.0288..2.41 rows=10101 loops=1)
                                                -> Filter: ((m.`status` = 'ACTIVE') and (m.member_role = 'OWNER'))  (cost=0.25 rows=0.05) (actual time=0.00183..0.00187 rows=0.778 loops=9000)
                                                    -> Single-row index lookup on m using uk_shop_member_account (account_id=a.id)  (cost=0.25 rows=1) (actual time=0.00165..0.00167 rows=1 loops=9000)
                                            -> Filter: ((s.category_id = 1) and (s.approval_status = 'APPROVED'))  (cost=0.258 rows=0.404) (actual time=0.00142..0.00144 rows=0.429 loops=7000)
                                                -> Single-row index lookup on s using PRIMARY (id=m.shop_id)  (cost=0.258 rows=1) (actual time=0.00121..0.00123 rows=1 loops=7000)
                    -> Single-row index lookup on s using PRIMARY (id=selected.id)  (cost=0.3 rows=1) (actual time=0.00136..0.00137 rows=1 loops=20)
```

### 分类分页：exists_no_semijoin

```text
-> Sort: s.id  (actual time=0.191..0.192 rows=20 loops=1)
    -> Stream results  (cost=423 rows=20) (actual time=0.173..0.181 rows=20 loops=1)
        -> Inner hash join (c.id = s.category_id)  (cost=423 rows=20) (actual time=0.171..0.174 rows=20 loops=1)
            -> Table scan on c  (cost=0.0224 rows=2) (actual time=0.0034..0.0041 rows=2 loops=1)
            -> Hash
                -> Nested loop inner join  (cost=419 rows=20) (actual time=0.131..0.159 rows=20 loops=1)
                    -> Table scan on selected  (cost=409..412 rows=20) (actual time=0.126..0.127 rows=20 loops=1)
                        -> Materialize  (cost=409..409 rows=20) (actual time=0.126..0.126 rows=20 loops=1)
                            -> Limit: 20 row(s)  (cost=407 rows=20) (actual time=0.0311..0.119 rows=20 loops=1)
                                -> Filter: exists(select #3)  (cost=407 rows=4000) (actual time=0.0308..0.116 rows=20 loops=1)
                                    -> Covering index lookup on s using ix_shop_approval_category_id (approval_status='APPROVED', category_id=1)  (cost=407 rows=4000) (actual time=0.0132..0.0186 rows=27 loops=1)
                                    -> Select #3 (subquery in condition; dependent)
                                        -> Limit: 1 row(s)  (cost=0.273 rows=0.0025) (actual time=0.00328..0.00329 rows=0.741 loops=27)
                                            -> Nested loop inner join  (cost=0.273 rows=0.0025) (actual time=0.00318..0.00318 rows=0.741 loops=27)
                                                -> Filter: ((m.`status` = 'ACTIVE') and (m.member_role = 'OWNER'))  (cost=0.255 rows=0.05) (actual time=0.00204..0.00204 rows=0.741 loops=27)
                                                    -> Single-row index lookup on m using uk_shop_member_owner (shop_id=s.id)  (cost=0.255 rows=1) (actual time=0.00191..0.00192 rows=1 loops=27)
                                                -> Filter: ((a.`role` = 'MERCHANT') and (a.`status` = 'ACTIVE'))  (cost=0.35 rows=0.05) (actual time=0.00137..0.00137 rows=1 loops=20)
                                                    -> Single-row index lookup on a using PRIMARY (id=m.account_id)  (cost=0.35 rows=1) (actual time=0.00129..0.00129 rows=1 loops=20)
                    -> Single-row index lookup on s using PRIMARY (id=selected.id)  (cost=0.255 rows=1) (actual time=0.00143..0.00143 rows=1 loops=20)
```

<!-- RECOMMENDATION -->

## 6. 复现与证据

实际已执行：

```powershell
.\scripts\experiment-public-shop-sql.ps1
```

- [实验方法与学习入口](../../../docs/public-shop-sql-experiment.md)。
- 同名JSON归档包含全部原始样本、SQL/参数、传统EXPLAIN、ANALYZE、提示警告、语义检查名、环境/索引和源码指纹。
- 不导出JUnit系统属性、环境变量、数据库密码、token或用户文件。
- 正式count/page事务样本共5,040个，每个两个SELECT；计划共168组EXPLAIN＋ANALYZE。

- 实例1：[原始JSON](run1.json)，日志`.cache/public-shop-sql-20261028-074626-de93cf9b-run1.log`。
- 实例2：[原始JSON](run2.json)，日志`.cache/public-shop-sql-20261028-074626-de93cf9b-run2.log`。

未新增索引、直方图或优化器全局/会话设置；NO_SEMIJOIN仅为候选语句内提示。未做评分/营业时间优化、HTTP候选替换、并发容量或部署验证。生产采纳和完整HTTP回归留到M01.2，不将实验收益写成已上线接口收益。
