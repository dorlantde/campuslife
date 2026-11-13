# M02.2 详情评分缓存验收（2026-10-28）

结论：已实施用户确认的详情评分统计缓存、V16技术版本及原评价事务维护，真实MySQL/Redis核心验证通过。当前62操作、V1～V16、24张应用表；Redis仅用于详情评分，列表、正文昵称、资格、订单与模拟资金规则保持。M02.3系统化故障与性能对照尚未执行。

运行与点击流程见[使用指南](../redis-rating-cache.md)，协议与取舍见[ADR-0048](../decisions/0048-public-rating-cache.md)。完整SQL、指标、逐类用例名与源码摘要见[JSON归档](redis-rating-cache-2026-10-28.json)。

## 验证结果与计数

**514个不同用例通过，19类，失败/错误/跳过均0**：普通/Web190（包括新增缓存策略18）＋定向MySQL304＋缓存联测19＋真实存量升级1。不同临时实例/阶段会重跑普通190，不叠加成更多独立用例；未重跑所有历史业务IT。

| 测试类 | 用例数 |
|---|---|
| FilePolicyTest | 38 |
| RatingCacheTest | 18 |
| BookingSlotPolicyTest | 22 |
| BookingOrderPolicyTest | 22 |
| PrintOrderPolicyTest | 30 |
| PrintQuotePolicyTest | 18 |
| RegistrationPolicyTest | 12 |
| TokenServiceTest | 17 |
| WebFoundationTest | 13 |
| OrderReviewsMysqlIT | 90 |
| AdminReviewHideMysqlIT | 61 |
| PublicShopCatalogMysqlIT | 67 |
| PublicShopReviewsMysqlIT | 62 |
| BookingJourneyMysqlIT | 4 |
| PrintJourneyMysqlIT | 6 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |
| RatingCacheIntegration | 19 |
| RatingRevisionUpgradeCheck | 1 |

全阶段脚本运行成功后，又补充Redis离线新应用启动及真实HTTP在途复用两项验证，在最新源码上重跑缓存阶段19例通过；其余304个业务回归、升级和普通检查来源按各自日志归档。最后一次只改测试，无生产代码变化，不重复执行没有新问题的全部业务回归。四个成功自建MySQL实例均完成mysql JAR启动检查和清理；两次成功脚本的自有Redis容器已删除。

## 实际命令与环境

```powershell
.\scripts\test-rating-cache.ps1 -Phase All
.\scripts\test-rating-cache.ps1 -Phase Cache
```

Java21.0.12.1，Spring Boot4.1.1，Spring Data Redis4.1.1，Lettuce7.5.2.RELEASE，MySQL8.0.45及Docker引擎29.8.0。Redis8.2.4固定官方镜像digest `sha256:320e9ce6d5c940815a84d989f0e53e3a4d065c0d9132cd9e1222265e78eb6080`，仅回环端口、独立标签/命名空间、128MiB可淘汰无持久化缓存。无自建后台加载线程池、Lua或Redisson。

All最终成功日志目录为`.cache/redis-it/193cfe58aab5401fbf96fabe4e8ccb9b/`；补充Cache目录为`.cache/redis-it/965df02e46f94eecb402d70223130cd1/`，含completed.json。前者脚本完成标记在驱动日志`.cache/m02-rating-cache-verified.log`，后者在`.cache/m02-rating-cache-final-core.log`。本轮未连接用户现有项目数据库，也未读取或修改VS Code/MySQL/JWT凭据。

## 真实SQL与一致性证据

- 完整匿名HTTP未命中3条业务SQL、命中2条；整个data相同。捕获的每条都是readOnly=true、JDBC隔离级别4（RR），命中没有SUM(rating)聚合，资格/版本与营业时间仍查询MySQL。普通关闭路径相关回归保持原语义。
- 首次评价创建版本，首次隐藏递增；重复评价及同原因隐藏不增加版本。实际版本写失败回滚评价，两个不同订单并发评价真实提交并递增两次，BIGINT溢出回滚隐藏。
- 旧RR跨越并发隐藏，清除MyBatis一级缓存后仍读原版本；新请求见新统计。旧请求在事务外晚回填不覆盖新键。真正HTTP并发请求在Redis尚未回填时复用已成功读取的同版本结果，捕获2条SQL、没有重复聚合。
- 仅靠已有缓存不能绕过店铺/账号/OWNER资格。停业状态现场生效；合法零统计、TTL到期、损坏JSON/错版本/未知schema回源重建；MySQL失败不返回缓存伪成功。
- 真实停止测试Redis后HTTP继续从MySQL读取，期间管理员隐藏仍成功且新统计正确。Redis离线时另起全新应用上下文（缓存开启）可启动并通过真实MySQL读取；重启同端点Redis后绕过期结束恢复回填。
- V15临时实例装入5,100条真实关联评价（含全部隐藏店），由Flyway只执行V16；升级前后评价逐行一致，5个有评价店版本为1、无评价店无技术行，重复migrate为0。没有用脚本重建表模拟升级。

缓存阶段混合场景计数：`{"store": {"hits": 6, "misses": 27, "bypasses": 33, "errors": 1, "invalid": 3, "writes": 26}, "mysql": "real isolated MySQL", "redis": "8.2.4", "loads": {"databaseLoads": 43, "coalesced": 1, "waitTimeouts": 0, "inFlight": 0}}`。这些包含故意停机、脏数据和事务竞争，不能把hits/(hits+misses)当作稳定负载命中率或性能容量。inFlight最终0；实例等待/容量边界另有策略单测，不能据一次复用声称所有并发只有一次查询。

## 发现、修正与资源状态

开发验证没有一次即通过：最初Docker桥接请求被Redis protected-mode拒绝；在专用回环发布端口的前提下调整测试/开发容器配置。随后恢复探针需兼容断连拒绝命令异常；实测发现Docker自动宿主端口重启会变化，最终改为先选空闲端口再显式固定。上述失败不计通过，旧日志保留；三份失败试跑的自有临时MySQL目录已核对无对应进程后清理，当前fixture父目录为空、测试Redis容器为0。

一次受限终端普通检查因回环/目录联接权限失败，随后在允许本地网络的环境复验通过；未把权限失败当作应用缺陷，也未跳过必要数据库验证。

本机开发入口Start/Status/Stop/Start已实际执行并核对重启后端口保持51864；留下一个项目自有开发Redis（区别于已删除测试Redis），`.local/rating-cache.properties`和身份文件均经Git忽略检查。它只保存可丢弃统计，本机配置显式启用；仓库仍默认关闭。用户应用未由代理重启、正式库V16未由代理代执行。

## 完成范围与下一项

M02.1方案已确认，M02.2核心实现/隔离验证完成。下一项M02.3：用同版本代码关闭/开启缓存测完整HTTP、普通/热门/零评价、冷热/并发、故障及新增版本写成本。还未提供速度提升、QPS、线上可用性或完整高可用承诺；本轮也未开发RabbitMQ、优惠券或ZSet排行。
