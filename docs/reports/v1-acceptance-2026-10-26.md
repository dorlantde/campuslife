# CampusLife V1 后端全量回归与验收报告

- 报告日期：2026-10-26（用户本地时区America/New_York）。
- 测试对象：当前本机工作区，包含尚未提交的源文件；不是已发布版本或生产部署声明。
- 范围：已确认V1的理发预约与PDF打印后端、三种角色、模拟资金及现有全部自动化测试。
- 结论：**全量自动化回归通过：55个测试类、2675个用例，失败0、错误0、跳过0。** 当前62个已实现接口、V1～V15迁移、23张业务表可进入V1演示与交付准备。

## 1. 执行与证据

清理经路径检查的target构建目录后，重新编译当前源码；未设置-Dtest/-Dit.test筛选、skipTests或maven.test.skip。实际调用独立实例脚本：

```powershell
$env:MAVEN_ARGS='clean'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

脚本内部执行Maven clean及mysql-check配置的verify，创建独立回环MySQL与专用文件根，结束时执行mysql模式JAR启动检查并清理。没有连接用户已有项目服务数据库，也没有读写用户持久上传目录或修改本机启动密钥。

| 验证项 | 本轮结果 |
|---|---|
| 普通/JVM与Web测试（Surefire） | 8类，172用例，全部通过 |
| 真实MySQL/HTTP集成测试（Failsafe） | 47类，2503用例，全部通过 |
| 已有自动测试类是否全部执行 | 与源码清单逐一核对，55/55，无缺失、额外旧报告或跳过 |
| 从空库执行Flyway迁移 | V1～V15成功；23业务表及约束验证通过 |
| 存量V13→V14升级 | 独立SQL复验，VISIBLE/HIDDEN原事实保留，无虚构审核人 |
| 存量V14→V15升级 | 独立SQL复验，账号/会话保持，状态历史从空表开始记录 |
| mysql可执行JAR | 启动、健康、UTF-8、PowerShell中文、trace、访问边界通过 |
| web-only可执行JAR | 单独启动检查通过；这项不代表数据库验证 |
| OpenAPI与完整目录 | 62/62已实现，待开发0，9个唯一分组；规范/引用/运行快照与生成目录一致 |

存量升级补充验证在核实@@datadir属于本轮临时实例后，创建独立临时数据库执行真实DDL和存量断言；结束仅删除自建数据库。它们不计入上述JUnit用例数，也不代表已验证所有历史版本的任意升级组合。

本次环境为Windows、Java21.0.12.1、Maven3.9.16、MySQL8.0.45，连接会话UTC。打包产物实际依赖：spring-boot-4.1.1.jar; mybatis-plus-core-3.5.17.jar; flyway-core-12.4.0.jar; pdfbox-3.0.8.jar; mysql-connector-j-9.7.0.jar。

## 2. 按模块汇总

| 模块 | 测试类 | 用例 | 已覆盖重点 |
|---|---:|---:|---|
| 工程、迁移与接口配置 | 5 | 29 | Web协议、UTF-8/trace、15次迁移与数据库约束、文档关闭/鉴权、模拟资金默认关闭 |
| 账号、会话与账号管理 | 8 | 253 | 注册登录/退出/改密/昵称、角色与归属、只读分页快照、排序锁与管理员互停、状态/会话/审计原子提交 |
| 商家入驻与审核 | 3 | 87 | 独立申请、受控管理员、审核和OWNER激活、拒绝后重提、并发与回滚 |
| 经营配置与排班 | 6 | 469 | 店铺/服务/价目/人员版本、批量原子性、营业时间及绑定/排班存量保护 |
| 公开目录、空位与评价管理 | 6 | 305 | 精确匿名范围、经营资格、候选扣占用、RR分页/真实评分、评价隐藏及审核记录 |
| 预约交易与履约 | 7 | 344 | 幂等创建、时段竞争、超时扫描、支付/全额退款、接单/取消/开始完成、异常核实与保留占用 |
| 公共订单查询与本人评价 | 4 | 235 | 两类订单列表/详情/事件、归属与只读快照、完成评价/唯一约束、真实reviewed |
| 私有PDF与打印报价 | 5 | 255 | PDF限制与发布/补偿/清理、授权下载及IO边界、印刷面计费/纸张取整、报价不锁价/不续期 |
| 打印交易与文件引用 | 9 | 688 | 冻结报价、幂等/超时、支付退款、任务授权、打印/待取件/取件、管理员异常与全部引用保留期 |
| 真实HTTP业务整链 | 2 | 10 | 从入驻审批及公开配置开始的4条预约、6条打印旅程，含退款、评价、下载权限及文件生命周期 |

用例数包含参数化边界，不能视作代码覆盖率或性能指标。异常分支部分使用故障注入，时间边界使用测试Clock；数据库锁、事务与数据约束使用真实MySQL，不以mock结果代替锁正确性。全量结果只汇总本轮新生成报告，不累加之前各阶段的重复执行。

### 首轮发现及复验

首轮同样执行全部55类、2675个用例，失败1、错误0、跳过0。唯一失败位于PublicBarbersMysqlIT.otherMethodsAndFuturePathsRemainProtected：旧测试仍将GET /shops/{shopId}/reviews当作待开发受保护接口，但公开评价已按ADR-0043实现并精确匿名放行，因此实际200符合已确认规则。

将旧断言改为检查仍受保护的/reviews/1子路径，保留原来其他方法、人员子路径和商家入口的鉴权断言；公开评价的匿名GET、非GET及子路径边界由PublicShopReviewsMysqlIT独立验证。本轮没有修改生产业务代码、权限配置或业务规则。修正后重新clean构建并执行全部55类，上表及逐类结果均为最终完整复验结果。测试中的预期故障注入可能输出ERROR日志，以JUnit断言及Maven最终结果判断通过情况。

## 3. 业务整链与人工复现

预约整链4个场景：正常完成/评价、未付取消、拒单退款、服务中管理员核实退款；从真实入驻、审批、营业、服务、人员绑定与排班开始。打印整链6个场景：取件评价、共享文件多单取消、拒单、已接未开始取消、待取件异常、改价后重新报价；包含真实multipart PDF解析、原始字节下载和授权变化。

- [预约完整点击指南](../booking-journey.md)：真实预约时刻、15分钟付款、30分钟接单以及等待后的重新登录。
- [打印完整点击指南](../print-journey.md)：文件上传、印刷面金额、份内纸张取整、任务授权与七天保留。
- [管理员账号状态](../admin-account-status.md)：本人保护、旧会话撤销、恢复重登及存量业务边界。

本轮执行的是自动HTTP与数据库验收，未代替用户在浏览器逐个点击所有接口、等待真实预约或进行实体打印。管理员准备使用受控无HTTP初始化，测试数据其余业务准备按各套件说明执行；没有公开管理员注册或默认生产演示账号。

## 4. 验收边界与后续事项

| 项目 | 当前结论 |
|---|---|
| 已确认V1后端自动化功能回归 | 通过，现有自动测试全部执行 |
| 用户Swagger/Apifox人工演示 | 具备指南，仍需用户按真实环境完成并记录 |
| 演示与大规模压测数据 | 本轮仅临时隔离数据，未交付常驻演示/压测数据集 |
| 性能、吞吐、容量、长稳 | 本轮未压测，没有QPS、延迟或容量达标结论 |
| 服务器部署、备份恢复、运行监控 | 不在本轮验证范围，不宣称可直接公开上线 |
| 多实例文件与分布式一致性 | 当前按单实例私有本地存储设计，未验证分布式部署 |
| 真实资金、真实用户运营 | 仅模拟支付/退款与虚构数据，不代表真实交易或运营业绩 |
| Redis、MQ、优惠券/设施/收藏/站内消息 | 后续范围，本轮不因V1接口齐全将其计为已实现 |

本轮结果支持“V1后端自动化功能验收通过”。下一步建议准备小规模可复现演示数据，再开展部署演练或基于测量的技术深化；新增业务与基础设施仍需按协作约定单独定稿。

## 5. 可核对产物

- 全量执行日志：`.cache/full-regression.log`。
- 首轮问题记录：`.cache/full-regression-first.log`、`.cache/full-regression-first.json`。
- 逐类原始报告：`target/surefire-reports/TEST-*.xml`、`target/failsafe-reports/TEST-*.xml`。
- mysql / web-only启动检查日志：`.cache/full-regression.log`、`.cache/full-regression-web-smoke.log`。
- 补充升级：`.cache/full-upgrade-v14.log`、`.cache/full-upgrade-v15.log`。
- [脱敏汇总证据JSON](v1-acceptance-2026-10-26.json)：仅测试类、计数、测试耗时和产物指纹，不复制JUnit系统属性、环境变量或请求日志。
- [运行接口文档](../openapi/campuslife.openapi.json)、[完整接口目录](../openapi/campuslife.v1-design.openapi.json)。

可执行包：`target/campuslife-0.0.1-SNAPSHOT.jar`，SHA-256：`67746a6bcc27a4e3f0ba9dc8b2ce799556a5333775a7d8e0ac5b989e97416e5e`。

Java/SQL/pom文件指纹：`8a000871a9dcf4218744c2eba66aeeca707be4e3d0a49b40be6697e59f437a0b`，不包含启动配置、环境变量或密钥。测试耗时仅为本机测试运行时间，不是生产接口响应时间。

## 6. 本轮逐类结果

| 测试类 | 类别 | 用例 | 失败 | 错误 | 跳过 |
|---|---|---:|---:|---:|---:|
| file.FilePolicyTest | 普通/Web | 38 | 0 | 0 | 0 |
| merchant.service.BookingSlotPolicyTest | 普通/Web | 22 | 0 | 0 | 0 |
| order.service.BookingOrderPolicyTest | 普通/Web | 22 | 0 | 0 | 0 |
| order.service.PrintOrderPolicyTest | 普通/Web | 30 | 0 | 0 | 0 |
| order.service.PrintQuotePolicyTest | 普通/Web | 18 | 0 | 0 | 0 |
| RegistrationPolicyTest | 普通/Web | 12 | 0 | 0 | 0 |
| TokenServiceTest | 普通/Web | 17 | 0 | 0 | 0 |
| WebFoundationTest | 普通/Web | 13 | 0 | 0 | 0 |
| AdminAccountListMysqlIT | MySQL/HTTP | 66 | 0 | 0 | 0 |
| AdminAccountStatusDomainMysqlIT | MySQL/HTTP | 7 | 0 | 0 | 0 |
| AdminAccountStatusMysqlIT | MySQL/HTTP | 68 | 0 | 0 | 0 |
| AdminBookingExceptionMysqlIT | MySQL/HTTP | 57 | 0 | 0 | 0 |
| AdminPrintExceptionMysqlIT | MySQL/HTTP | 108 | 0 | 0 | 0 |
| AdminReviewHideMysqlIT | MySQL/HTTP | 61 | 0 | 0 | 0 |
| AdminShopReviewMysqlIT | MySQL/HTTP | 23 | 0 | 0 | 0 |
| ApiDocsDisabledMysqlIT | MySQL/HTTP | 1 | 0 | 0 | 0 |
| AuthenticationMysqlIT | MySQL/HTTP | 41 | 0 | 0 | 0 |
| BarberManagementMysqlIT | MySQL/HTTP | 108 | 0 | 0 | 0 |
| BarberScheduleMysqlIT | MySQL/HTTP | 91 | 0 | 0 | 0 |
| BookingJourneyMysqlIT | MySQL/HTTP | 4 | 0 | 0 | 0 |
| BookingOrdersMysqlIT | MySQL/HTTP | 65 | 0 | 0 | 0 |
| BookingPaymentsMysqlIT | MySQL/HTTP | 40 | 0 | 0 | 0 |
| BookingSlotsMysqlIT | MySQL/HTTP | 66 | 0 | 0 | 0 |
| BusinessHoursMysqlIT | MySQL/HTTP | 58 | 0 | 0 | 0 |
| FileDownloadMysqlIT | MySQL/HTTP | 48 | 0 | 0 | 0 |
| FileUploadMysqlIT | MySQL/HTTP | 64 | 0 | 0 | 0 |
| MerchantBookingCancellationMysqlIT | MySQL/HTTP | 47 | 0 | 0 | 0 |
| MerchantBookingConfirmationMysqlIT | MySQL/HTTP | 59 | 0 | 0 | 0 |
| MerchantBookingFulfillmentMysqlIT | MySQL/HTTP | 54 | 0 | 0 | 0 |
| MerchantOnboardingMysqlIT | MySQL/HTTP | 24 | 0 | 0 | 0 |
| MerchantOperationMysqlIT | MySQL/HTTP | 50 | 0 | 0 | 0 |
| MerchantOrderListMysqlIT | MySQL/HTTP | 48 | 0 | 0 | 0 |
| MerchantPrintCancellationMysqlIT | MySQL/HTTP | 83 | 0 | 0 | 0 |
| MerchantPrintConfirmationMysqlIT | MySQL/HTTP | 87 | 0 | 0 | 0 |
| MerchantPrintDownloadMysqlIT | MySQL/HTTP | 72 | 0 | 0 | 0 |
| MerchantPrintFulfillmentMysqlIT | MySQL/HTTP | 79 | 0 | 0 | 0 |
| MerchantResubmissionMysqlIT | MySQL/HTTP | 40 | 0 | 0 | 0 |
| MockPaymentConfigurationMysqlIT | MySQL/HTTP | 1 | 0 | 0 | 0 |
| MysqlIntegrationIT | MySQL/HTTP | 11 | 0 | 0 | 0 |
| OpenApiMysqlIT | MySQL/HTTP | 3 | 0 | 0 | 0 |
| OrderEventsMysqlIT | MySQL/HTTP | 54 | 0 | 0 | 0 |
| OrderReviewsMysqlIT | MySQL/HTTP | 90 | 0 | 0 | 0 |
| PrintJourneyMysqlIT | MySQL/HTTP | 6 | 0 | 0 | 0 |
| PrintOrdersMysqlIT | MySQL/HTTP | 79 | 0 | 0 | 0 |
| PrintPaymentsMysqlIT | MySQL/HTTP | 71 | 0 | 0 | 0 |
| PrintPricesMysqlIT | MySQL/HTTP | 84 | 0 | 0 | 0 |
| PrintQuoteMysqlIT | MySQL/HTTP | 87 | 0 | 0 | 0 |
| ProfileMysqlIT | MySQL/HTTP | 16 | 0 | 0 | 0 |
| PublicBarbersMysqlIT | MySQL/HTTP | 33 | 0 | 0 | 0 |
| PublicShopCatalogMysqlIT | MySQL/HTTP | 61 | 0 | 0 | 0 |
| PublicShopReviewsMysqlIT | MySQL/HTTP | 62 | 0 | 0 | 0 |
| RegistrationMysqlIT | MySQL/HTTP | 26 | 0 | 0 | 0 |
| ServiceCatalogMysqlIT | MySQL/HTTP | 78 | 0 | 0 | 0 |
| StudentOrderListMysqlIT | MySQL/HTTP | 43 | 0 | 0 | 0 |
| StudentPrintPickupMysqlIT | MySQL/HTTP | 79 | 0 | 0 | 0 |
