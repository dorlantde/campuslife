# M04.2-B S3文件业务与LOCAL存量兼容验收

日期：2026-11-02。状态：**B已实现并验证，C未实施**。方案依据 [ADR-0056](../decisions/0056-private-object-storage.md)，复现见[文件存储指南](../s3-file-storage.md)。本轮只接通已批准文件链，未启用开发S3、迁移项目库或搬迁用户文件；前端暂停。

## 实现与取舍

- `FileStorage`按数据库完整定位路由LOCAL/S3，`FileLocation`校验精确opaque key；`FileGuards`从本地存储抽出，原暂存组件保留。上传、下载、首次打印创建、到期清理和墓碑共用实例互斥。改变新上传目标不改旧定位，关闭或失配后端返回503，不自动回退。
- SDK2.57.8/Apache5进入生产包，仍使用A验证的path-style、固定长度PUT/关闭chunked编码、保留校验和、显式凭据及禁代理发现。池4、连接/获取2秒、socket5秒、尝试15秒/调用30秒、最多2次。后端默认关闭，客户端及标识请求不参与普通启动；启用配置错误早失败，临时不可达不阻止其它业务启动。
- 每次对象操作先读取只读 `_storage-id` 并比较UUID；只读取至37字节，拒绝时abort，不排空异常大标识。只接受已登记命名空间的随机精确key，不从请求接收地址、桶或key。沿用A窄Actions＋Deny策略，无管理员SDK或自动初始化入口。
- 原两个处理槽及本地PDFBox解析不变；UPLOADING占位先提交，PUT/完整GET计算SHA256及长度在SQL事务外，匹配后才短RC复核身份、版本、完整定位和30分钟发布期限并发布。完整回读有额外带宽/耗时成本，不声称上传变快。
- 受控代理下载保留新RR授权、首读后响应、完整长度、附件/no-store及已提交异常不追加JSON。第一次打印创建在事务外GET首读/长度检查，随后短事务核对kind/id/key/大小/hash；报价、支付、退款、取件、创建键重放不增加远端IO，原文件引用保护及锁序保持。
- 到期删除仍为DELETING→事务外精确删除→带定位/版本回写DELETED。S3的DELETED行保留为墓碑，游标循环执行精确删除，重新读当前状态/版本/命名空间和未结束引用，不列整桶、不复用key、不按固定时间丢弃墓碑。
- 清理改为私有单线程/零积压，公共调度仅唤醒；60秒触发、到期及墓碑每类最多100条，共享1秒继续预算，当前IO可收尾。命名空间/类别轮换、失败也推进游标避免头部坏对象饥饿；仍有规模增长的周期扫描成本。关闭等待8秒后中断，不承诺应用整体硬退出期限或服务端PUT被取消。
- V24只扩展LOCAL/S3 CHECK并新增`(storage_id,status,id)`索引，保持原四种状态及32表；V1～V23不改，无新HTTP路径，仍75操作。

## 验证结果

最终 **918 项不同相关检查** 分轮通过，无失败、错误或跳过。按各类最终XML去重计数，重复运行不累加；不是全项目覆盖率或吞吐量指标。

| 测试类 | 用例 | 失败/错误/跳过 |
|---|---:|---|
| AdminPrintExceptionMysqlIT | 108 | 0/0/0 |
| CouponOrdersMysqlIT | 19 | 0/0/0 |
| FileDownloadMysqlIT | 48 | 0/0/0 |
| FileUploadMysqlIT | 64 | 0/0/0 |
| MerchantPrintCancellationMysqlIT | 83 | 0/0/0 |
| MerchantPrintConfirmationMysqlIT | 87 | 0/0/0 |
| MerchantPrintDownloadMysqlIT | 72 | 0/0/0 |
| MerchantPrintFulfillmentMysqlIT | 79 | 0/0/0 |
| MysqlIntegrationIT | 12 | 0/0/0 |
| OpenApiMysqlIT | 3 | 0/0/0 |
| PrintOrdersMysqlIT | 79 | 0/0/0 |
| PrintPaymentsMysqlIT | 71 | 0/0/0 |
| S3FileChainMysqlIT | 28 | 0/0/0 |
| S3StorageUpgradeCheck | 1 | 0/0/0 |
| StudentPrintPickupMysqlIT | 79 | 0/0/0 |
| FileCleanupWorkerTest | 1 | 0/0/0 |
| FilePolicyTest | 38 | 0/0/0 |
| S3StoragePolicyTest | 16 | 0/0/0 |
| PrintOrderPolicyTest | 30 | 0/0/0 |
| 合计 | 918 | 0/0/0 |

完整相关轮为830项MySQL＋85项策略/线程检查；最终S3轮28项替换其中原26项（另85项重跑不重复计数），独立V23→V24升级1项，合计918。普通构建、mysql及web-only JAR启动检查通过，启动检查不另计为JUnit用例。

真实S3覆盖20MiB PDF、本人与商家权限、完整字节/hash、首次下单、重复创建、多订单保护、退款/完成后的7天、取件后撤销商家下载授权；PUT前后失败、回读错内容、发布前会话撤销、提交后响应丢失不得删AVAILABLE、DELETED墓碑回收合成迟到对象。latch＋真实MySQL复核网络期间不持文件行锁、取消退款不等下载，获准传输可完成；新上传切回LOCAL不改旧S3定位，已登记但不可达S3不阻止mysql JAR启动。

V23升级检查比较四种原LOCAL状态的完整行与文件字节，V24只执行一次，保留32表并验证新CHECK/索引及重复迁移无操作。未对实际开发库执行升级。V1～V23迁移及45个前端文件指纹保持；115项既有生产依赖SHA256保持，生产包新增33项固定SDK相关依赖，共148项，探针/业务测试类不入包。

测试自有容器、卷、网络、MySQL进程和JVM均为0；失败诊断目录及M08.5旧基线保留。具体核对时间见JSON，不停止或删除用户的现有服务/文件。


## 失败记录与修正

1. 首次新增验收编译失败：把已解析的报价JsonNode再次传给HTTP响应解析方法。修正测试类型后进入真实联测，未运行的检查不算通过。
2. 首次隔离启动发现Docker引擎未运行，没有运行S3检查；启动已安装的Docker Desktop后继续，未更换组件或复用开发实例。
3. S3业务首轮23项中21通过、1失败/1错误：测试把NULL保护期限转为时间；另一个测试对已被订单占用的券再次报价，合法返回409。修正验收场景/空值处理，业务用券规则未改。失败日志和XML保留在忽略目录。
4. 升级命令曾被自动审批服务用量限制阻止，未执行；用户回复“继续”后按原路径重试并完成，未绕过审批。
5. 主相关回归830项MySQL＋85项策略检查通过；随后补充新上传目标回LOCAL及S3不可达启动检查，并复核标识拒绝时不排空响应的实现。最终只重跑受影响S3/策略、升级及启动检查，不重复整套历史用例。

## 证据与限制

原始日志在本机忽略目录：`.cache/m042b-units1.log`、`m042b-business1.log`、`m042b-business2.log`、`m042b-regression1.log`、`m042b-final-s3.log`、`m042b-upgrade.log`；分轮XML在`.cache/m042b-evidence`。脱敏机器汇总与依赖/源码核对见同名JSON。缓存中的S3 manifest/config含临时凭据，不能提交或转发。

本轮S3为真实SeaweedFS，锁/约束/事务为真实隔离MySQL；边界故障注入使用spies/latches，重放迟到对象是合成场景。未用这些检查冒充真实杀进程或服务端晚到PUT窗口。上传回读校验不等于承诺每次下载重算业务SHA256；本轮没有性能、容量、多实例或高可用结论，也没有人工点击验收结论。

下一项M04.2-C：真实JVM/受控迟到IO及故障恢复、MySQL＋整个SeaweedFS状态＋LOCAL根的备份恢复、隔离迁移预演和具体Swagger验收清单。真实历史文件迁移Apply、源文件删除及公网部署仍需独立明确授权，本轮不执行。
