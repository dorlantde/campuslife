# M08.5-C 有界发布故障与对照复现

2026-11-01，按已确认[ADR-0055](decisions/0055-bounded-outbox-publishing.md)执行并通过：[C报告](reports/outbox-publisher-comparison-2026-11-01.md)，62项不同相关检查最终通过、11个真实JVM窗口、正式48轮/4104 POST；各档完整p95中位0.58～1.15秒、较旧异步降93.88%～96.95%，达到约定目标。原[B正确性报告](reports/bounded-outbox-publishing-2026-11-01.md)及[M08.4-B口径](coupon-measurement-design.md)保留，下面是实际使用的入口与限制。

## 环境与版本

使用Java21和已启动的Docker Desktop Linux引擎。脚本创建带随机所有权标签的Redis、RabbitMQ及本机临时MySQL，只连接回环地址，不使用开发账号、活动或本机启动配置。

旧制品必须保留在`.cache/m085-baseline-2026-11-01`。驱动逐项验证376个旧类/资源、旧JAR及pom哈希、新旧115项生产依赖、新JAR与当前完整classes一致；除发布器相关类和批次配置外，不允许无关生产二进制变化。归档中的哈希是旧制品身份，不能从Git HEAD重建一个近似旧版，也不能用batch-size=1替代。该目录被Git忽略，当前可在本机复现；仅拉取仓库而没有封存资产时不能直接运行旧新对照，须取得并校验相同旧制品，不自动下载或发布二进制。

同一份测试驱动先运行OLD的24轮，完全退出后核对SQL/队列排空，再启动NEW的24轮。整个生产类目录被替换，子进程还核对实际CodeSource及唯一资源，禁止保留当前生产目录并前置旧目录。HTTP请求在独立无mock应用JVM处理；观察器夹具、真实时钟和采样口径不变。先旧后新仍有时序漂移限制，要同时报告两版SYNC参照，不能声称消除机器负载波动。

## 验收入口

在项目根目录PowerShell中运行：

```powershell
.\scripts\test-coupon-redis.ps1 -Phase Messaging -Cases 'OutboxBatchFaultsIT' -UnitCases 'OutboxBatchTest,OutboxPublisherTest'

.\scripts\benchmark-coupons.ps1 -Phase Measure -ComparePublisher
```

故障夹具只在测试类路径存在，无HTTP故障入口。`OutboxBatchFaultsIT`继承原8个真实JVM窗口，并增加3个发布窗口：认领提交后发送前、实际ACK后finish前、第一条SENT后下一条lockDue执行前。第三个停点已进入下一次短事务，但尚未选择或认领下一行；不是暂停整个应用后推测批间边界。每次强杀仅限父测试创建的子进程，重启使用真实系统时钟等待原30秒租约，不提前改SQL期限。

新增场景预先持久受理3笔真实HTTP请求；消费者在强杀前关闭，以便核对第一行状态和另外两行READY/attempts=0。恢复后核对原消息/正文、原截止、券/凭据唯一、outbox SENT、队列排空及Redis守恒。消费者提交前/ACK前、受理前后和重建窗口复用原8项。8秒关闭的可控阻塞检查在本轮单元中再次执行，不将它描述为整个应用退出SLA。

正式测量每版24轮（2预热、18主场景、4补充）；每档100名学生/20张券、并发1/5/10、三组SYNC/ASYNC配对，主场景至少25秒。两版均使用250ms完成GET、1秒采样、5连接、原消费者数和固定JVM堆；不在中途调参数。输出完整POST、首次受理、完成、GET、SQL/DML/锁、CPU/堆、Redis及队列指标。观察器成本和采样滞后仍存在，见原口径。

固定计时及成本窗口结束后，管理统计若仍显示在途，最多额外观察30秒排空，250ms读取一次，不purge、不代ACK、不改变已有业务结果。额外等待及每次队列统计写入`postWindowBrokerDrainMs/Samples`，不塞入POST/完成耗时或固定窗口成本；不收敛则该轮失败并保留。首次执行在旧版第11轮遇到这种未确认统计，完整失败证据保留后修复观察边界，再用全新依赖完整执行两版，不混用首次部分样本。

测试结束后读取`target/coupon-comparison-latest.json`中的folder，再归档：

```powershell
$result = Get-Content .\target\coupon-comparison-latest.json -Raw | ConvertFrom-Json
.\scripts\summarize-outbox-comparison.ps1 -InputDirectory $result.folder -ReportDate '2026-11-01'
```

汇总保留两版原始JSON、请求CSV、每轮统计和SHA256；完整对照必须是24+24轮、所有不变量通过。≤5秒且较旧版降≥70%按各档三轮完整p95的中位数判断，未达标也保留结果收口，不因性能失败自动重复测量或调参。失败轮次的原始诊断留在target，不能删除后只展示成功轮次。

## 清理与后续

外层脚本回收自有临时MySQL、容器与卷，父测试终止其创建的子JVM；失败也执行finally清理，诊断文件保留。不得全局prune、清空整个.cache或开发持久卷。源码中的旧基线校验用例只在显式对照环境启用，普通仓库构建不要求存在本机封存目录。

一次固定参数故障/测量后转工程交付收口；本项不增加生产组件、DDL、业务接口或前端。默认SYNC、消息/券Redis关闭、前端暂停保持。
