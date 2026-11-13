# M08.2-C 订单用券验收报告

日期：2026-10-30（America/New_York）。状态：两类订单MySQL用券闭环已验证。当前73个运行操作、V19、28张应用表。业务依据ADR-0050，实现决策见[ADR-0052](../decisions/0052-order-coupons.md)，点击验收见[指南](../order-coupons.md)，结构化证据见[JSON](order-coupons-2026-10-30.json)。

## 交付与取舍

新增POST /booking-quotes；打印报价、两类创建接可选userCouponId。锁券/释放/核销与原订单事务、资源/文件保护及资金原子提交。冻结优惠规则，历史订单不受后续活动影响。未付释放、已付退款不返券、正实付、原15分钟支付权和旧无券创建摘要兼容。

V19增加order_coupon与持有券状态/当前订单指针/版本；V1～V18迁移校验和及pom未变。预约旧单清理先锁相关旧订单，再把新旧关联券排序加锁，锁后判时。新增SQL和索引有写入成本；本轮没有性能收益结论，也没有将普通数据库测试当作Redis/MQ验证。

## 实际验证

**659项不同的相关检查最终通过，零失败/错误/跳过（分轮汇总，不是一次全项目全套回归）。** 其中192项普通/Web，467项MySQL；新增19项真实MySQL用券专项和2项历史摘要兼容。类级最终结果如下：

| 测试类 | 项数 | 失败/错误/跳过 |
|---|---:|---|
| FilePolicyTest | 38 | 0/0/0 |
| RatingCacheTest | 18 | 0/0/0 |
| BookingSlotPolicyTest | 22 | 0/0/0 |
| BookingOrderPolicyTest | 22 | 0/0/0 |
| CouponOrderHashTest | 2 | 0/0/0 |
| PrintOrderPolicyTest | 30 | 0/0/0 |
| PrintQuotePolicyTest | 18 | 0/0/0 |
| RegistrationPolicyTest | 12 | 0/0/0 |
| TokenServiceTest | 17 | 0/0/0 |
| WebFoundationTest | 13 | 0/0/0 |
| BookingJourneyMysqlIT | 4 | 0/0/0 |
| BookingOrdersMysqlIT | 65 | 0/0/0 |
| BookingPaymentsMysqlIT | 40 | 0/0/0 |
| CouponCampaignMysqlIT | 74 | 0/0/0 |
| CouponClaimsMysqlIT | 8 | 0/0/0 |
| CouponOrdersMysqlIT | 19 | 0/0/0 |
| MysqlIntegrationIT | 11 | 0/0/0 |
| OpenApiMysqlIT | 3 | 0/0/0 |
| PrintJourneyMysqlIT | 6 | 0/0/0 |
| PrintOrdersMysqlIT | 79 | 0/0/0 |
| PrintPaymentsMysqlIT | 71 | 0/0/0 |
| PrintQuoteMysqlIT | 87 | 0/0/0 |

19项专项覆盖BOOKING/PRINT报价、未付释放复用、支付核销、已付取消/超时按实付退款且保留USED、付款时券已到期/活动停止、错误金额/归属/格式、快照/核销/释放注入失败回滚、重复创建与并发竞争、真实持有券X行锁等待后到期拒绝、预约旧单清理后同券复用、门槛及1分实付。旧摘要测试计算原规范化文本的SHA-256，验证省略/null兼容且选券摘要不同。相关旧测试覆盖原资金、文件保护、权限、幂等和两类整链。

使用scripts/test-mysql.ps1自有GUID临时实例与专用文件根，不访问现有MySQL服务或用户持久上传目录；测试Clock推进，不修改系统时间。最后针对性重跑90项MySQL及2项摘要检查全部通过；MySQL模式可执行JAR健康、UTF-8、访问边界与启动检查通过。本轮失败留下的两个GUID目录另按核验绝对路径清理，成功轮由脚本清理。

OpenApiMysqlIT导出运行快照后更新两份导入文件，scripts/build-api-design.py及--check均通过：73 implemented / 0 planned，引用与契约覆盖有效；目录未将Lua/MQ标记已实现。

## 修正与口径

首轮3个失败来自新测试错误的超时取消重放预期及遗漏新报价路径，原已确认行为保持。相关回归末尾4个打印报价断言仍写16字段，本次新增nullable userCouponId；更新为17并明确断言无券为null，其余金额/权限/快照断言保留，重跑87项报价、3项OpenAPI和2项摘要通过。没有放宽业务规则。最初一次受限终端普通运行的文件链接测试遭环境权限拒绝，随后192项普通/Web在允许本机文件操作的运行中通过，文件安全代码未改。

已通过的相关类没有因文档变化重复执行；JSON按类保留最终结果/源码指纹及变动范围，不把重复执行累计为更多不同用例。

复现核心检查可用PowerShell的MAVEN_ARGS指定测试，再运行现有隔离脚本：

```powershell
$env:MAVEN_ARGS='-Dit.test=CouponOrdersMysqlIT,MysqlIntegrationIT,OpenApiMysqlIT -Dtest=CouponOrderHashTest'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
Remove-Item Env:MAVEN_ARGS
```

该命令模式已在本轮包装脚本实际执行通过。人工验收按点击指南，无需手写请求。

## 未执行与下一项

本轮未进行人工Swagger/Apifox点击、前端券页面、真实Redis联测、RabbitMQ/Lua、性能压测或公开部署；前端继续暂停。代理未代启正式库迁移，用户停止旧应用后按原VS Code配置启动即可执行V19。

下一项M03.1：可靠消息与通知方案定稿，明确消息身份、outbox提交/发布确认/消费提交、重复处理与恢复边界，再实现RabbitMQ。异步领券及Redis预扣窗口仍须专门定稿；M08.2同步基线已完整，不重复V1已有事务、锁和幂等。
