# M08.4-B 领券同条件测量与复现

版本说明（M08.5-C后）：本文及2026-11-01 M08.4-B报告记录旧单条调度基线。当前发布实现已按[ADR-0055](decisions/0055-bounded-outbox-publishing.md)更新；脚本默认运行当前代码，结果须另选目录并标版本，不得覆盖或冒充旧基线。C已提供封存旧/新类目录替换、指纹和唯一资源校验，显式`-ComparePublisher`执行旧新对照，见[C复现](outbox-publisher-comparison.md)/[结果](reports/outbox-publisher-comparison-2026-11-01.md)。

本批依据[测量口径](coupon-measurement-design.md)比较现有SYNC与ASYNC完整公开路径，不以202数代替发行量。2026-11-01已完成，[正式结果](reports/coupon-measurement-2026-11-01.md)记录受理／完整成功与成本的区别。前端继续暂停；业务规则、消息频率、连接池与默认开关沿用当前实现。

## 运行

需要本机Java21、现有原生MySQL测试工具和已启动的Docker Linux引擎。使用项目根目录PowerShell：

```powershell
# 小规模核对驱动及指标，不作为正式性能结论。
.\scripts\benchmark-coupons.ps1 -Phase Pilot

# 正式配对：含预热、三档并发及补充场景，通常约十分钟。
.\scripts\benchmark-coupons.ps1 -Phase Measure

# 仅在完整执行及所有不变量通过后生成汇总与请求级CSV。
$taskMeasurement = Get-Content target/coupon-measurement-latest.json -Raw | ConvertFrom-Json
.\scripts\summarize-coupon-measurement.ps1 -InputFile (Join-Path $taskMeasurement.folder 'results.json')
```

隔离脚本每次创建专用MySQL、Redis和RabbitMQ及随机凭据，不连接开发数据库；Redis/Broker身份须与本轮test标签一致，原生MySQL观察连接还核对datadir属于项目随机临时目录。Performance Schema观察使用该临时实例的root只读连接，不需要用户填写开发密码。脚本结束精确清理本轮依赖；失败证据保留，不能全局清空Docker或用户文件目录。

原始数据位置由`target/coupon-measurement-latest.json`指向。`results.json`逐轮保存HTTP、完成观测、SQL摘要增量及采样，不包含JWT／密码／请求头；`application.log`是自有应用日志，`ready.json`与`process.json`记录进程元数据。构建会覆盖XML，需将原始JSON及相关测试报告另存后再运行下一批。

汇总脚本默认生成`docs/reports/coupon-measurement-2026-11-01.json`与请求CSV，可用`-ReportDate`和`-OutputDirectory`选择归档位置。原始JSON的SHA256写入汇总，以便追溯。本批正式报告已另存执行证据；复跑应指定新的归档目录／日期，保留原样本。

## 测量结构

- 观察与fixture在父测试JVM，计时请求全部发向无mock、无测试代理、无故障钩子的独立应用JVM；只增加一个每秒写进程指标的测试观测线程，不增加HTTP路径，测试入口不进入正式JAR。
- 固定应用堆256～512MiB、数据库池上限5，两个队列各2个消费者，原1秒单条outbox发布。两种模式使用同一进程及相同配置，切换的是新建活动的受理模式。
- 每轮创建新的学生、会话及活动，种子20261101排列请求；账号／活动准备、模式切换／重建与清理在计时窗外。每轮结束核对并删除本轮SQL fixture，Redis旧代次技术状态保留，不把内存增量当成空库总内存。
- 正式主场景每轮100人争20张；并发1／5／10，各按AB、BA、AB三组配对，共18轮，前四轮形成ABBA。两轮100人预热不计收益；另各测一轮同人同键16次、库存充足10人。SYNC与ASYNC每轮观察窗至少25秒，补充场景6／15秒。
- 每个POST完成后都通过本人GET观测结果；后续PENDING每250ms轮询，记录实际间隔。SQL、Redis、Broker和进程每1秒采样。到最小窗口后尚有PENDING则继续观测原截止；组件故障／异常HTTP会停止发新请求，保留无效／不完整结果并拒绝生成已完成报告。

## 读数方法

POST分布同时记录全部请求和首次成功受理：SYNC201与ASYNC202分别展示，200/202重放单独识别。完整成功时间从首次POST发起至GET观测SUCCEEDED，包含轮询误差。批量成功吞吐使用`获券数 / max(最后POST完成时刻, 最后成功观测时刻)`，不使用固定观察窗或202数量伪造吞吐。

分位数采用nearest rank；正式分组值是三轮指标的中位数，既不是合并60个获券样本重新算p95，也不是统计置信区间。每一轮及请求级数据保留，不能仅挑选较好结果。同一claimId多行代表重放；CSV的claimCompletionMs是原领取的完整时间，不能把重放数累计为获券数。

数据库观察连接默认库为performance_schema，监控SQL不落入业务库摘要；业务语句按SCHEMA_NAME筛选并计算前后差。固定窗口含后台调度及完成GET开销，ASYNC轮询更多，不能把总SELECT差额当作纯POST查询节省。DML单列；摘要LOCK_TIME不是InnoDB行锁等待，后者用独立原生计数／时间差。

CPU取应用进程累计CPU差，堆内存是每秒观测值，均有采样误差，堆不等于RSS。Redis INFO命令计数含观察器；运行期内存差不包含窗口前初始化。Broker管理统计有刷新滞后，队列峰值仅为采样下界。无超发、唯一领取身份、outbox／凭据、结算和Redis库存另用数据库事实核对，不能仅凭采样队列为0判成功。

本机少量闭环请求不等于生产到达率压测或最大容量；组件故障已有[M08.4-A](reports/coupon-faults-2026-10-31.md)证据，稳态测量不能替代HA或断电实验。当前调度的限制须按数据解释，优化另列任务定稿，不在测量中临时调快。
