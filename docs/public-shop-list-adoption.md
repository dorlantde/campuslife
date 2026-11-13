# M01.2 商家列表SQL采纳与完整HTTP复测

本任务承接[M01.1实验](reports/public-shop-sql-experiment-2026-10-28.md)，目标是确定实际查询的适用边界并验证完整接口，生产采纳结论与数据见[本轮报告](reports/public-shop-list-adoption-2026-10-28.md)。

## 选择原则

M01.1的计时只包含count/page事务，未包含HTTP、营业时间和评分；限制半连接的版本也存在无分类、小数据回退。因此M01.2先保留原count和搜索/后续页路径，只评估首页分页的两个分支：分类首页的NO_SEMIJOIN候选，以及无分类首页的无提示ID派生分页。是否保留两个分支根据本轮数据决定，不要求把候选全部应用。

最终仅采纳指定分类、没有有效名称关键词、第1页的分支；未分类首页候选未采纳。总数、搜索、后续页和详情保留原查询，空白关键词按原规则规范化为空。决策及取舍见[ADR-0047](decisions/0047-public-shop-list-query.md)。

公开资格、名称包含匹配、id ASC、页码/大小、total/items只读RR、openNow和真实评分规则保持。使用已有索引，无新增迁移、Redis或其他基础设施。最终使用静态Java注解@Select，沿现有Mapper组织，不增加XML文件。

## 验证与测量设计

[PublicShopListAdoption](../src/test/java/com/campuslife/PublicShopListAdoption.java)是显式启用的测试入口，复用C06独立数据夹具。它只在工作区指定的测试库、真实随机datadir和空库确认后写入，随机测试密码不回传；成功后由原隔离脚本停止并清理。用户现有数据库、上传目录和启动配置不参与。

每次实例依次运行1,000/10,000店，两档各三种公开资格分布：

| 分布 | 用途 |
|---|---|
| mixed60 | C06原分布，60%可见；包含批准状态、OWNER和账号状态过滤 |
| dense90 | 90%经营账号有效，店铺/OWNER均有效；观察大部分商家公开时的行为 |
| sparse10 | 10%经营账号有效，类别分布平衡；观察低通过率是否导致相关资格查询扫描更多 |

分布切换仅用于测试，包含直接SQL构造的当前资格状态，不声称是逐笔真实审核或用户运营。计时阶段只读，写入切换和ANALYZE TABLE不计入样本。

九个正式场景：无分类、HAIRCUT、PRINT各自的首页20条、首页100条、接近末页的深分页。候选/当前版本与原版比较**整个API data响应**，包括total、页信息、条目顺序、营业状态和评分。另按分布独立计算期待ID与total，避免两种SQL一起算错。

每个状态额外验证分类/名称交集、特殊字符、无效分类、空白关键词及超int范围页偏移。两个分类分别验证count后由另一连接停用经营账号，清除MyBatis一级缓存后重跑SQL仍读同RR快照，新请求才看到停用。测试不依赖一级缓存来伪装数据库快照正确。

Clock仅在测试中固定为2026-10-28T01:00:00Z，避免两种响应跨营业时间边界而误判；真实应用Clock不改。

## 对照通道与公平性边界

原版SQL来自保留的四表JOIN表达式，仅在测试拦截器中替换分页方法的SqlSource；其它Service、Mapper、数据和HTTP入口共用。候选预验阶段调用当时的待采纳SQL构造函数；正式采纳阶段使用真实categoryFirstPage或page的MappedStatement/SqlSource，验证静态@Select实际路径。

两种版本都经过相同的测试Executor拦截器与MappedStatement复制步骤，保留参数、结果映射与事务；生产JAR不含该对照拦截器。两边静态SQL预解析，原版通道为categoryFirstPage补上旧SQL要求的keyword=null/offset=0参数。只有分页被替换，count/营业时间/评分和身份过滤器沿真实实现。

每场景每版本预热4次，之后3轮×12次，交错随机化场景和版本。每次完整请求响应读取及JSON解析均在计时内，结果比较在计时后。六个状态、九场景、两版本，共3,888个正式HTTP样本/实例；只读RR与非空4条/空页2条SQL也单独核对。

这是一客户端热读实验，不是压测容量。预验阶段不能称为生产已采纳；最终阶段也需限定当前机器、分布与样本。保留分轮p50/p95、原始样本、完整实际page SQL/参数和ANALYZE树，不把单个最快结果当收益。

## 复现与人工核对

在项目根使用原Java21/Maven/MySQL二进制：

```powershell
.\scripts\verify-public-shop-list.ps1 -Phase adopted
```

adopted阶段用于最终实际SQL。validation是预验阶段标签，历史输出记录双分支原型；当前代码重跑只会测当前实现，不会恢复历史原型，也不能覆盖当时结论。输出为每次独立的`output/performance/public-shop-list-<UTC时间>-<随机ID>/`与对应.cache日志；completed.json仅在测试、JAR检查和清理成功后生成。

本轮还实际执行了相关目录/评价/整链/契约回归：

```powershell
$env:MAVEN_ARGS='-Dit.test=PublicShopCatalogMysqlIT,PublicShopReviewsMysqlIT,BookingJourneyMysqlIT,PrintJourneyMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
Remove-Item Env:MAVEN_ARGS
```

汇总脚本`scripts/report-public-shop-list.py`接收validation/adopted输出目录、回归日志及日期，检查正式源码摘要、分支SQL、样本数量和本轮新XML后归档；只读取资料，不连接数据库。归档含完整原始记录，历史证据不依赖本机忽略目录长期保留。

如已有应用运行，按原VS Code配置重启后核对。Swagger/Apifox人工核对仍使用原GET /api/v1/shops：分别省略categoryId、选HAIRCUT/PRINT，调整size=20/100、page=1/2，再添加keyword。核对数量、ID顺序、total和字段；要看具体执行计划与查询分支，阅读本轮报告中的真实SQL。无需在现有项目库导入上万店数据。

仅当采纳分支的相关回归及实际HTTP复测支持结论时结束M01；否则保留原查询，记录取舍后进入M02。后续数据库版本或数据分布改变，应重新评估提示，而不是把此次计划当作永久保证。[MySQL提示说明](https://dev.mysql.com/doc/refman/8.0/en/optimizer-hints.html)、[复合索引说明](https://dev.mysql.com/doc/refman/8.0/en/multiple-column-indexes.html)。
