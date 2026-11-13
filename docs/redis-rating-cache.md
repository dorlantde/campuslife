# 商家详情评分缓存：使用与验收

此功能按用户已确认的[M02.1方案](redis-rating-cache-design.md)实施，决策见[ADR-0048](decisions/0048-public-rating-cache.md)。只缓存公开商家详情的评分总和/数量；列表、评价正文/昵称、身份与订单查询沿原实现。接口路径/响应字段不变，运行仍为62操作；V16新增一张技术元数据表，应用表共24张。

## 本机使用

仓库默认关闭缓存，不需要Redis即可按原方式运行。项目提供独立本机入口，不读取或修改MySQL密码、JWT密钥或.vscode启动配置：

```powershell
.\scripts\rating-cache.ps1 -Action Start
.\scripts\rating-cache.ps1 -Action Status
```

Start使用已固定版本/digest的Redis8.2.4镜像，首次选择空闲端口后显式固定回环绑定，重启保持端点。只管理带本项目专用标签及保存ID的容器，不检查/清理无关容器。首次需Docker Desktop Linux引擎可用，之后仍用同一实例和命名空间。

连接参数和本机启用状态保存在被Git忽略的`.local/rating-cache.properties`，容器身份保存在`.local/rating-redis/manifest.json`。应用可选导入该配置；本机Start显式启用，仓库/新部署缺少该文件时仍默认关闭。RATING_CACHE_ENABLED/HOST/PORT/NAMESPACE环境变量可覆盖本机文件中的默认值，隔离测试不会误连开发Redis。

**先停止旧应用，再按原VS Code MySQL配置启动新版本。** Flyway会执行V16并为已有评价店铺回填统计版本；不要在升级后继续用旧应用写评价。已有商家/学生、订单和评价不重建；本机Redis运行检查不等于应用已重启或正式库已迁移，开发代理不代用用户密码连接正式库。

关闭本机缓存：

```powershell
.\scripts\rating-cache.ps1 -Action Stop
```

它停止已核对身份的自有Redis，更新本机启用默认值为false；重新启动应用后使用原查询路径。应用运行中若Redis离线会自动回源，无需等待用户手工恢复才能读取详情。重新Start会启用缓存，仍需应用重启读取配置。

## Swagger / Apifox 点击流程

无需新建账号或重新做全部订单；使用已有测试店铺和完成订单：

1. 启动应用后打开原Swagger UI，匿名执行`GET /api/v1/shops/{shopId}`两次。两次的评分/数量/资料应一致（营业边界变化时openNow可自然改变）。缓存不会在HTTP响应中暴露内部键、版本或调试字段。
2. 用正常学生登录/Authorize，对本人合法完成订单执行原评价提交接口。再次匿名读详情和公开评价，核对数量增加、平均分符合两位HALF_UP。
3. 用正常管理员隐藏该评价，再读详情和公开评价，核对数量/评分已排除，学生本人仍能读取评价且不能重发。
4. 商家关闭营业开关后，详情仍可见、openNow=false；资格暂停、经营账号或OWNER停用后的新请求仍404，即使此前已有评分缓存。
5. 停止项目自有Redis但保持应用运行，再读取详情，应返回真实数据库数据；重新Start且端点保持不变后，最初5秒绕过期结束，再次读取可恢复回填。Stop同时写入的本机关闭配置仅在下次应用启动时生效。

响应相同不能单独证明缓存命中。精确SQL、RR和命中/回源证据由下述隔离自动验证输出；不要求用户手写HTTP命令，不提供公开缓存管理或故障注入接口。

## 实现与一致性

- `RatingRevisionMapper`在评价首次提交/首次隐藏的原RC事务最后推进版本。缓存关闭和Redis故障也维护版本；同原因隐藏重放不递增，版本写失败或溢出回滚整个评价变化。订单/配置版本、事件、资金和文件期限保持原规则。
- `PublicShopCatalogService.detail`为事务外编排，独立`PublicShopDetailTransactions.read`执行一个短只读RR；开启时公开资格/资料与统计版本在同一联表查询读取，营业时间仍查DB。Redis未命中时原SUM/COUNT也在这个RR中，命中预计跳过该聚合。
- Redis键包含数据集命名空间、店铺和不可变统计版本。新评价或隐藏与版本一起提交，新快照只能选择新键；旧请求晚回填旧键不会覆盖新统计。TTL用于回收历史键，不承担资格授权或唯一一致性保证。
- 回填在读取事务成功返回后进行；Redis异常不改正常HTTP结果，MySQL错误不能用缓存伪装成功。GET发生在短RR内，客户端连接/命令超时分别200/50 ms，故障绕过5秒并仅放行单个恢复探测；这些不是端到端硬耗时保证。
- 实例内最多256个在途键，同店同版本等待最多20 ms；只有已成功返回数据库事务的结果可共享。超时/满额回到自己的原RR聚合，不无限排队；不宣称多实例分布式互斥或所有并发只查一次。
- 显式JSON只保存schema/店铺/版本/精确sum/count；零统计与未命中区分，损坏数据回源重建。正统计TTL300～360秒，零统计30～45秒，SET NX同时设置过期，不反复续期。

V16仅保存版本，不预计算持久评分。shop_id主键/外键、revision>0；技术行随父店铺删除级联清理，已有业务外键仍保护真实订单/评价。当前没有公开店铺删除接口。数据库恢复/重建导致ID或版本可能复用时，须使用新的数据集命名空间；正常重启保持原命名空间。

## 隔离自动验证

已安装Java21/Maven/MySQL二进制、Docker Linux引擎可用时，在项目根运行：

```powershell
.\scripts\test-rating-cache.ps1 -Phase All
```

All依次在三个**不同的临时MySQL实例**运行缓存联测、V15存量升级、相关业务回归；每个阶段同时运行普通/Web测试及mysql可执行JAR检查。专用Redis只有随机命名、标签和固定回环端口，脚本结束核对自身容器身份后删除；开发Redis不参与。输出日志在`.cache/redis-it/<随机ID>/`，新版脚本的completed.json只在所选测试、JAR检查及自有Redis清理成功后写入。

针对性复现也实际使用过：

```powershell
.\scripts\test-rating-cache.ps1 -Phase Cache
```

Cache包含完整HTTP的未命中3条/命中2条业务SQL、RR、公开资格、零统计、损坏载荷、事务回滚/并发/溢出、旧快照与晚回填、真实Redis停机/恢复；测试用自有fixture而非现有项目库，并会短暂停止自身测试Redis。Upgrade以真实V15已有数据执行Flyway V16并核对原评价逐行不变；不会降级或清空用户数据库。

`RatingCacheIntegration`和`RatingRevisionUpgradeCheck`为显式启用入口，不匹配默认Test/IT扫描；只有隔离标记和脚本环境下才运行。普通默认MySQL回归不能代替缓存联测通过。共享性能夹具已适配V16及直接造数时的版本维护，历史M01报告仍保留当次源码/测量。

M02.2核心结果、逐类计数、SQL及源码摘要见[当次报告](reports/redis-rating-cache-2026-10-28.md)。后续M02.3已完成[同版本关/开、冷热/并发与故障/写成本对照](reports/redis-rating-cache-measurement-2026-10-28.md)，热门受益而普通/零评价中位更慢，适用边界及原始数据已保留；不把少一条SQL直接等同于所有接口性能提升。复现口径见[测量方法](redis-rating-cache-measurement.md)。

该Redis实例是可淘汰、无持久化的展示缓存；未来优惠券受理/库存状态不会直接复用这一配置。未接入RabbitMQ、Lua领券或ZSet排行。
