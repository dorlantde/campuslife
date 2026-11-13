# 最小工程基础与本地运行

M03.2-B：75操作/V21/31表；[通知指南](notifications.md)提供点击和本机维护入口。专用MQ测试显式暂停消费者，再按用例启动，普通MySQL测试关闭传输。


2026-10-30 M03.2-A引入Boot管理的AMQP与固定digest RabbitMQ 4.3.6，V20一表至29表，73操作保持。专用本机启停和独立联测见[指南](reliable-messaging.md)/[报告](reports/outbox-publishing-2026-10-30.md)。普通MySQL测试强制关闭消息传输；仅专用自有Broker联测启用，消费与通知接口仍待B批。


2026-10-30 M08.2-C：增加V19用券状态与订单券快照，无新增组件依赖。专用MySQL验证、旧创建摘要兼容及复现说明见[订单用券指南](order-coupons.md)/[报告](reports/order-coupons-2026-10-30.md)。正式库由用户按原配置启动时Flyway升级；代理测试不访问正式数据。


2026-10-30 M08.2-B同步领券已交付，72操作/V18/27表；190普通/Web＋96真实MySQL相关检查通过，另核对文档说明、JAR启动与测试清理，见[报告](reports/coupon-claims-2026-10-30.md)。沿原VS Code配置重启迁移；前端、订单资金和Redis核心未改。下一项M08.2-C。

2026-10-29 M08.2-A新增商家活动6操作、V17一表，当前68操作/25表。74项活动专项及相关回归最终跨轮去重516项通过；MySQL可执行包、68操作Swagger/Apifox导出和临时库清理通过。首轮时钟测试设置及文档扫描/标签问题均记录修复，不把失败轮次计为通过；见[报告](reports/coupon-campaigns-2026-10-29.md)。沿原VS Code配置重启即可自动迁移；没有更改本机密钥、依赖、前端或订单/缓存核心。前端暂停，下一项M08.2-B同步领取。

2026-10-26 当前交付：账号停用/恢复按[ADR-0046](decisions/0046-admin-account-status.md)接入。禁止修改本人，双账号按ID排序主键锁后复核正常ADMIN；实际变化时状态/version+1、目标全部旧会话撤销、状态历史原子提交，恢复须重新登录。旧版本409，同版本同状态200无写入；不自动处理订单/资金或改文件期限。V15新增account_status_change，23表；运行62、完整62/62/0，当前规划入口齐全。见[Swagger / Apifox指南](admin-account-status.md)；旧阶段“账号状态待开发”以本次为准，接口齐全不等于公开部署或全部工程工作完成。

2026-10-26 当前交付：管理员账号列表按[ADR-0045](decisions/0045-admin-account-list.md)接入。正常ADMIN查询全部业务账号，角色/状态交集、分页、创建时间/ID倒序，仅六字段AccountView；身份/会话与count/items同一只读RR快照，返回前核对到期，无业务行锁或写入。运行61、完整62/61/1，仍V1～V14/22表，无新迁移；账号停用/恢复尚未实现。见[Swagger / Apifox指南](admin-account-list.md)，旧阶段“账号列表待开发”以本次交付为准。

2026-10-26 当前交付：管理员隐藏评价按[ADR-0044](decisions/0044-admin-review-hide.md)接入。正常ADMIN说明原因后原子保存首次审核记录并隐藏；同人同原因重放保留事实，其他重复409。后续公开评价/真实评分排除，本人仍可读、reviewed=true且不能重发，不改订单/资金/文件期限。V14补三个审核字段，无新表，仍22表；运行60、完整62/60/2，仅管理员账号列表/状态管理待开发。见[Swagger / Apifox指南](admin-review-hide.md)，旧阶段“隐藏后续”以本次交付为准。

日期：2026-10-13（初始阶段），2026-10-15 更新进度。工程为单 Maven Spring Boot 应用。本篇主要记录最小 Web 基础；后续已增加 MySQL 数据源、初始迁移、注册、认证与改密，最新运行方式见 [MySQL 接入](mysql-setup.md)。该段为初始基础记录；订单现已按最新ADR形成预约/打印闭环，Redis、MQ和对象存储尚未接入。

## 目录与职责

| 位置 | 职责 |
|---|---|
| pom.xml | Java 21、Spring Boot 4.1.1、Web MVC、Validation 与测试依赖 |
| src/main/java/com/campuslife/CampusLifeApplication.java | 应用入口与组件扫描根包 |
| common/api/ApiResponse.java | 成功与失败的 JSON 响应结构 |
| common/exception/ | 预期业务异常及全局 MVC 异常转换 |
| common/web/ | 每个请求生成 traceId，关联响应头与日志 |
| config/TimeConfiguration.java | 注入 UTC Clock，后续业务与测试可统一使用时钟 |
| system/controller/HealthController.java | 健康检查入口 |
| src/main/resources/application.properties | 应用名称、监听地址、严格 JSON 等配置 |
| src/test/java/com/campuslife/WebFoundationTest.java | 启动真实随机端口服务的 HTTP 集成测试 |
| scripts/dev.ps1 | 项目内 Java 21 优先、执行 Maven，并恢复脚本进程的环境变量 |
| scripts/smoke.ps1 | 用随机本地端口检查可执行 jar，finally 停止自己启动的进程 |
| .mvn/maven.config | 依赖缓存落在 .cache/maven-repository，减少构建过程对用户目录的写入 |

上表 common、config、system 路径相对 src/main/java/com/campuslife。按业务包逐步添加 Controller、Service、Mapper，不提前建立大量空目录。

2026-10-15：认证模块已按 controller、service、dto、vo、entity、mapper、security、config 分层；健康入口放入 system/controller。完整实际目录与阅读顺序见 [Java 包结构](project-structure.md)。SQL 仍为 MyBatis-Plus 和注解方式。

移包后已从 Maven clean 开始重新构建，完整 140 个用例及 mysql 模式 jar 检查通过。实际导出接口定义与整理前一致，确认没有旧包编译产物残留；验证使用独立 MySQL，不涉及用户正式库。

## 环境与启动

需要 Maven 3.6.3 及以上和完整 JDK 21。项目本地脚本首先查找 .tools/java21 下含 bin/javac.exe 的 JDK 目录；找不到时使用 JAVA_HOME 或 PATH，并验证版本为 21。脚本不修改系统环境变量。

在 D:\campuslife 中执行：

```powershell
# 编译与运行相关测试
.\scripts\dev.ps1 -Action Test

# 运行测试并打包可执行 jar
.\scripts\dev.ps1 -Action Build

# 最新默认运行需要数据库密码，在本机交互输入
.\scripts\database.ps1 -Action Run

# 对已打包 jar 做独立启动检查，结束后自动停止
.\scripts\smoke.ps1
```

如果脚本被本机执行策略拦截，可以在当前进程使用 PowerShell 的 -ExecutionPolicy Bypass -File scripts/dev.ps1 -Action Test，不修改机器级执行策略。IDE 的 Project SDK、Maven Runner JDK 同样设置为 Java 21。

已配置 JDK 21 时，可直接运行 mvn test、mvn verify；启动 mvn spring-boot:run 或可执行 jar 时，默认 mysql 模式必须具备数据库配置。jar 为 target/campuslife-0.0.1-SNAPSHOT.jar，使用所选 JDK 的 java -jar。

初次构建需要网络下载依赖。遇到 TLS 错误不得关闭证书验证；先确认网络、运行时和仓库访问。项目内 JDK 与缓存只服务本地环境，已在 .gitignore 排除，换机器需重新配置。

默认仅监听 127.0.0.1:8080。SERVER_PORT 可覆盖端口；SERVER_ADDRESS 可覆盖地址，目前仅供本地学习。认证已实现，交易功能尚未实现。

## 当前可用接口

GET /api/v1/system/health，匿名读取，只表示当前应用 HTTP 处理可运行，不表示数据库或其他依赖已经就绪。

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/system/health' | ConvertTo-Json -Depth 5
```

响应 code = OK，data 包含 application = CampusLife、status = UP、timestamp（UTC ISO 时间）。顶层 traceId 与 X-Trace-Id 响应头相同，所有当前响应设置 Cache-Control: no-store。

成功和统一异常 JSON 响应明确声明 Content-Type: application/json;charset=UTF-8，兼容 Windows PowerShell 5.1 的响应解码。使用 Spring Boot 4 的 spring.servlet.encoding.force-response=true；异常处理器显式保留 UTF-8 媒体类型。ConvertTo-Json 仅用于展开嵌套对象，不承担编码修复。

普通 API 响应由 Controller 显式返回 ApiResponse，不用全局响应切面自动包装。这样以后文件流、下载与其他特殊响应可以保持自己的语义。

GlobalExceptionHandler 保留真实 HTTP 状态：参数和 JSON 错误为 400、不存在为 404、方法不支持为 405、请求类型不支持为 415。BusinessException 携带业务 code、对外安全消息及错误 HTTP 状态；未预期异常为 500，内部细节只记录服务端日志。

traceId 是每次请求的关联标识，不是授权凭证，也不是分布式追踪系统。当前同步请求使用 MDC 并在 finally 恢复上下文；未来异步线程池需要单独设计上下文传递。

## 验证方法与边界

WebFoundationTest 使用真实嵌入式服务器、随机端口和 Java HttpClient；测试用探针与故障 Controller 只在测试上下文导入，不打包到应用 jar。测试范围包括：

- 成功响应、UTC 时间、响应头和 traceId 一致性。
- 每个请求独立生成 traceId，不信任客户端传入的追踪值。
- 合法 JSON 正常处理；Bean Validation、未知字段和损坏 JSON 返回 400。
- 参数类型错误、404、405（保留 Allow 头）、406、415 的统一失败结构。
- 业务冲突保留 409 和业务错误码；500 响应不暴露异常细节。

测试结果以实际 Maven 输出及 target/surefire-reports 为准；包启动还需独立 HTTP 冒烟验证。此处未涉及密码认证、订单状态、金额、数据库事务或并发正确性，不得据此声称业务测试通过。

## 当前验证记录

2026-10-21 公开理发师阶段：公开理发师按 [ADR-0020](decisions/0020-public-barbers.md) 交付，见[点击验收](public-barbers.md)。新增 3 个生产 Java 文件，共 120 个；运行 32 个操作，无新依赖、XML 或迁移，仍 V1～V8、十三张表。复用目录只读 RR，联表/EXISTS 保留完整公开绑定并限定同店与类型。定向新增 33 + 目录 61 + 文档 3 + 普通/Web 42，共 139 全部通过，零失败/错误/跳过；未重跑全部旧业务。mysql JAR（启动探测上限 90 秒）、实际导出和完整规划/schema/示例检查通过，临时库已清理。当时下一项为候选时段，已在本篇后续记录交付；目录查询不能代替预约保护。

2026-10-21 公开目录阶段：公开商家列表/详情/上架服务按 [ADR-0019](decisions/0019-public-shop-catalog.md) 交付，见[点击验收](public-shop-catalog.md)。新增 8 个生产 Java 文件，共 117 个；当前 31 个操作，无新增依赖、XML 或迁移，仍 V1～V8、十三张表。只读 RR 和独立公开 VO，批量营业时间、精确匿名 GET 放行。定向验证 61 个新目录 + 3 个文档 + 42 个普通/Web，共 106 全部通过，未重跑全部旧业务。mysql JAR、实际导出、完整规划及 schema/示例检查通过。

首轮 Maven verify 已通过，后续 JAR HTTP 就绪探测在固定 30 秒期限临界超时。smoke.ps1 / test-mysql.ps1 新增可选 -StartupTimeoutSeconds（1～300，默认 30），本轮明确设 90 秒后同样的定向验证及 JAR HTTP 检查通过。实际调用为 scripts/test-mysql.ps1 -StartupTimeoutSeconds 90，MAVEN_ARGS 选择 PublicShopCatalogMysqlIT,OpenApiMysqlIT；不把历史报告合计冒充本轮全回归。原有应用/设备启动配置未改，详细日志和边界见目录指南。

2026-10-21 排班阶段记录：按日排班与营业时间保护按 [ADR-0018](decisions/0018-merchant-schedule.md) 交付，见[点击验收](merchant-schedule.md)。当前 109 个生产 Java 文件、28 个已实现操作，V8 增加两表（共十三张），无新依赖或 XML。新增 91 个真实 MySQL 用例；首轮修正一处外键错误码兼容断言并补充锁等待/快照验证后，完整回归普通 42 + 集成 654 = 696 全部通过，零失败/错误/跳过。mysql JAR、实际导出及完整规划/schema/示例检查通过。下一项建议定稿学生侧公开商家目录；有效预约的存量保护仍待订单接入。

2026-10-13 实际结果：

| 检查 | 结果与证据 |
|---|---|
| Java 21 本地运行时 | Azul Zulu OpenJDK 21.0.12.1；下载包 SHA-256 与 Azul 官方元数据一致 |
| 构建与测试 | scripts/dev.ps1 -Action Build 成功，Maven verify 完成 |
| HTTP 集成 | WebFoundationTest：13 tests，0 failures，0 errors，0 skipped；Java 21 + 实际嵌入式服务器 |
| 可执行包 | 生成 target/campuslife-0.0.1-SNAPSHOT.jar；入口 class 的 major version 为 65（Java 21） |
| 测试隔离 | jar 内没有 WebFoundationTest、FixtureController、ProbeRequest |
| jar 启动 | scripts/smoke.ps1 通过；实际健康接口 200、traceId 一致，不存在路径与测试故障入口均为统一 404 |
| 中文响应兼容 | 修复 UTF-8 声明后，13 个 HTTP 用例通过；Windows PowerShell 5.1 和 PowerShell 7 的 smoke 均通过，实际 Invoke-RestMethod 正确读取“成功”；使用 web-only 随机端口，与用户现有进程分开验证 |
| 环境边界 | 受限终端最初禁止本机回环连接；允许本地网络后同一 jar 检查成功，不修改应用来绕过限制 |

原始测试报告在 target/surefire-reports/com.campuslife.WebFoundationTest.txt 与对应 XML；冒烟日志在 .cache/smoke.stdout.log、.cache/smoke.stderr.log，均为本地生成文件。

初次依赖下载遇到 TLS 握手失败；使用仍验证证书的 TLS 1.2 完成依赖预下载，最终 Java 21 的开发脚本未增加跳过证书验证或永久 TLS 降级参数。Temurin 的 GitHub 下载链路握手失败后改用 Azul 官方发行包。最终编译、测试和 jar 启动都使用 Java 21；未改变系统默认 Java 24。

500 场景主动抛出测试异常，因此测试日志中的对应 ERROR 是预期故障注入；客户端未收到内部异常内容。Spring 测试依赖的 Mockito inline 机制会显示动态 agent 警告，当前 13 个用例未使用 mock，后续确有 mock 需求时再配置显式 agent，不通过隐藏警告冒充修复。

本节最小工程阶段当时未创建数据库或执行 DDL；后续 MySQL 迁移和隔离验证已完成，详见 MySQL 接入文档，不将历史 Web 测试结果充当数据库证据。

## 下一项任务

2026-10-21 理发师阶段记录：理发师与服务绑定按 [ADR-0017](decisions/0017-merchant-barbers.md) 交付，见 [点击验收](merchant-barbers.md)。V7 建立两表、四个操作，当前 99 个生产 Java 文件、26 个可调用操作，无新增依赖或 XML。新增 108 个真实 MySQL 用例；首次全回归覆盖 605 个，修正一个辅助事务反向外键取锁的测试错误后，全部新增用例及普通 42 个用例定向复测通过。最新报告共 605、零失败/错误/跳过；mysql JAR、实际导出、生成器、官方 schema/示例校验通过。下一项建议定稿理发师排班，预约接入前补齐停用/解绑存量保护及日期锁协调。

打印价目阶段：按 [ADR-0016](decisions/0016-merchant-print-prices.md) 交付，见 [点击验收](merchant-print-prices.md)。V6 建立 print_price_rule，两个操作，当时 88 个生产 Java 文件、22 个可调用操作，无新增依赖。首次全回归 493 个用例通过；最终明确 RC 写事务并补充跨店新增、锁等待验证后，价目 84 + 文档 3 及普通 42 个用例定向复测通过。当时报告共 497、零失败/错误/跳过；mysql JAR、实际导出、生成器、官方 schema/示例校验通过。

服务目录阶段：按 [ADR-0015](decisions/0015-merchant-service-catalog.md) 交付，见 [点击验收](merchant-service-catalog.md)。V5 新增 service_item、三个操作，当时 80 个生产 Java 文件、20 个已实现操作。新增 78 个真实 MySQL 用例通过；修正历史入驻测试的未实现接口断言后，定向重跑入驻 24 + 服务 78 及普通 42 个用例，Maven verify 成功。当时报告共 413、零失败/错误/跳过；mysql JAR、实际导出、生成器、官方 schema 与示例校验通过。

营业时间阶段：每周营业时间按 [ADR-0014](decisions/0014-merchant-business-hours.md) 已确认并交付，见 [点击验收](merchant-business-hours.md)。V4 新增一张时段表、一个 PUT，当时 72 个生产 Java 文件、17 个已实现接口。新增 58 个真实 MySQL 用例；当时既有 277 个回归通过，修正新增约束测试的编号兼容及数组断言后定向复测通过，报告共 335、零失败/错误/跳过。mysql JAR、真实导出与规划/官方 schema/示例校验通过。

经营阶段：经营资料与营业开关按 [ADR-0013](decisions/0013-merchant-operation.md) 已确认并交付，见 [点击验收](merchant-operation.md)。当时新增一个 PATCH，共 66 个生产 Java 文件、16 个已实现接口；复用现有表，不新增迁移或依赖。新增 50 个真实 MySQL 用例，当时全回归 Maven verify 共 277 个（普通 42、集成 235）全部通过；mysql JAR、实际接口导出与完整规划校验通过。

重提阶段记录：被拒后修改重提已交付，见 [ADR-0012](decisions/0012-merchant-resubmission.md)及[点击验收](merchant-resubmission.md)。当时 63 个生产 Java 文件、15 个已实现接口，重提复用 V2/V3，不新增迁移。新增 40 个真实 MySQL 用例；首次回归发现文本类型与测试编码问题并修正后，定向重跑 109 个相关用例通过。当时报告合计 227、零失败/错误/跳过；mysql JAR、运行接口导出与完整规划校验通过。

前一项：管理员初始化与审核已交付，见 [ADR-0011](decisions/0011-admin-shop-review.md)及[点击验收](admin-shop-review.md)。V3 审核历史、4 个 HTTP 操作、独立无 HTTP/JWT 的初始化工具；当时 60 个生产 Java 文件、14 个已实现接口。报告覆盖 187 个测试且无失败/错误（全回归后修正清理夹具并定向复测）；mysql JAR 和真实 PowerShell 初始化路由检查通过。

2026-10-19 当前交付：新增 merchant 模块（13 个 Java 文件），分类、提交申请与本人待审店铺三个接口，V2 建立三张商家表。运行时 10 个操作，完整规划 62 个；入驻审批规则按 ADR-0010 已确认。Maven verify 共 164 用例全部通过，其中新增商家 24 个。mysql / web-only JAR 的 HTTP 就绪与访问检查单独通过。此前日志就绪探测误判已改为随机回环端口上的 HTTP 探测，默认最多等待 30 秒；现有密码及本机开发配置未更改。详情见 [商家入驻验收](merchant-onboarding.md)。下一项为受控创建平台管理员及审批。

以下为前一阶段记录：

已补充 [完整 V1 接口规划](api-design.md)，可在 Apifox 预览 61 个操作与待开发模块。此次仅生成文档和文档工具，没有新增 Java 业务接口，不以格式校验代替业务测试。

2026-10-15：首批认证与本人资料接口、Swagger UI / OpenAPI 已完成。当前全套 140 个用例，包括 17 个 JWT、41 个认证/改密、16 个资料与 4 个文档 HTTP/MySQL 用例；独立 MySQL、并发修改、事务回滚、文档权限及 mysql 模式 jar 检查通过。人工验收优先按 [浏览器 / Apifox 指南](api-testing.md)，现阶段先由用户验收，再继续商家规则。web-only 保留 Web 诊断并关闭文档。

2026-10-21 候选时段最新交付：按 [ADR-0021](decisions/0021-booking-slot-candidates.md) 实现一个匿名 GET，新增 4 个生产 Java 文件，当前共 124 个、33 个运行操作；无新依赖或迁移。配置/排班只读 RR、每请求一次 Clock，暂不扣订单占用。定向 227 个用例（普通/Web 64、MySQL/HTTP 163，含新增时间 22、候选 66）重新执行全部通过，零失败/错误/跳过；首轮测试夹具与拦截点问题已修正，未重跑全部旧业务。独立 MySQL 迁移及 mysql JAR（启动探测上限 90 秒）、实际 OpenAPI 导出、生成器、官方 schema/示例校验通过；两轮自有临时目录均已清理。详情与人工步骤见[候选验收](booking-slots.md)，下一项建议定稿预约订单、占用和配置变更存量保护。

2026-10-22 待支付预约按 [ADR-0022](decisions/0022-booking-orders.md) 交付：新增16个生产Java文件，当前140个、36个运行操作；V9四表，当前17张业务表，无新依赖/XML。先全回归，修正Swagger扫描及测试构造/约束包装断言并补充竞争边界后定向复测；最终154个（普通/Web86、预约MySQL65、文档3）全部通过。各阶段最新报告合计965个、零失败/错误/跳过，不代表最后单次全回归965个；相关人员/排班/营业时间/候选/目录及迁移已复测。MySQL V1～V9与mysql可执行JAR、实际OpenAPI36、完整规划62/36/26、85 schemas及9个请求示例校验通过。扫描测试通过重建扫描器读取数据库历史deadline验证，不声称已执行带存量订单完整进程重启；人工验收见[预约指南](booking-orders.md)。下一项建议定稿模拟支付与商家接单。

2026-10-22 模拟支付与待接单退款按 [ADR-0023](decisions/0023-booking-mock-payment.md) 交付：新增5个生产Java文件，当前145个、37个运行操作，V10两张流水表使业务表总数19；无新依赖/XML。默认模拟资金关闭，本机VS Code开发项显式设置MOCK_PAYMENT_ENABLED=true，未更改原数据库凭据。最终独立MySQL定向Maven verify **272个用例通过**（普通/Web86，MySQL/HTTP186，其中支付退款40及开关配置1），零失败/错误/跳过，未重跑全部旧业务。V1～V10、mysql可执行JAR真实启动、运行导出37、完整规划62/37/25、86schemas、官方schema及9个请求示例校验通过；实际范围和首轮修正见[支付验收](booking-payments.md)。带存量订单恢复使用新扫描器验证，未执行完整进程重启；用户项目库迁移以本机重启为准。下一项建议定稿商家接单/拒单。

2026-10-22 商家预约确认按 [ADR-0024](decisions/0024-merchant-booking-confirmation.md) 交付：新增2个生产Java文件，当前147个、40个运行操作；复用V9/V10共19表，无新依赖/XML/迁移，未修改本机启动凭据。首轮定向250个通过，补充已接单保护与资格/截止竞争后最终Maven verify **330个用例通过**（普通/Web86、MySQL/HTTP244，新增商家确认59），零失败/错误/跳过，未重跑全部旧业务。独立MySQL V1～V10、mysql可执行JAR真实启动、运行导出40、完整规划62/40/22、87schemas、官方schema及8个请求示例/参数/引用校验通过。测试实例及目录由脚本清理，实际范围见[商家确认验收](merchant-booking-confirmation.md)。下一项建议定稿商家已接单后无法履约取消。

2026-10-22 商家无法履约取消按 [ADR-0025](decisions/0025-merchant-booking-cancellation.md) 交付：新增BookingCancellation一个生产Java文件，当前148个、41个运行操作；复用V9/V10共19表，无新依赖/XML/迁移，未更改启动凭据。商家三个写路径复用私有锁入口，拒单/取消共用严格原因归一化及原子退款关闭。独立临时MySQL定向Maven verify **235个用例通过**（普通/Web86、MySQL/HTTP149，新增取消47），零失败/错误/跳过；未重跑全部旧业务。V1～V10、mysql可执行JAR真实启动、运行导出41、完整规划62/41/21、88schemas、官方schema及7个请求示例/引用检查通过。脚本停止并清理测试实例，实际范围见[取消验收](merchant-booking-cancellation.md)。下一项建议定稿预约开始/完成履约。

2026-10-22 预约开始/完成按 [ADR-0026](decisions/0026-booking-fulfillment.md) 交付：无新生产Java文件，仍148个、43个运行操作，V1～V10共19表；无新迁移/依赖/XML，未更改启动凭据。首轮相关回归246个通过，补充3个场景及旧注解后最终定向143个通过（普通/Web86、履约54、文档3），零失败/错误/跳过；最新相关报告跨两轮覆盖249个，不是最后单次全回归。独立MySQL迁移、mysql可执行JAR真实启动、实际导出43、完整规划62/43/19、88schemas及官方schema/7个请求示例/引用检查通过。实例和目录由脚本清理，验证范围及人工等待实际开始的流程见[履约验收](merchant-booking-fulfillment.md)。下一项建议定稿管理员异常核实与退款关闭。

2026-10-22 管理员预约异常按[ADR-0027](decisions/0027-admin-booking-exception.md)交付：新增2个生产Java文件，当前150个、45个运行操作；V1～V10仍19表，无新迁移/依赖/XML，未改启动凭据。初次受限终端Web检查被回环网络权限阻止；允许本地网络后首轮相关Maven verify 280个全部通过。补充7个场景后最终定向205个全部通过（普通/Web86、管理员异常57、商家确认59、文档3），零失败/错误/跳过；没有单次重跑全部旧业务。独立MySQL真实锁/并发/资金回滚、mysql可执行JAR启动、运行导出45、完整62/45/17、9分组89schemas及官方schema/6个请求示例/引用检查通过；两个自有实例及目录由脚本清理。操作与实际范围见[异常验收](admin-booking-exception.md)。下一项建议定稿学生订单分页列表。

2026-10-22 学生本人订单列表按[ADR-0028](decisions/0028-student-order-list.md)交付：新增OrderListMapper、OrderSummaryRow、OrderSummary与OrderPage四个生产Java文件，当前154个、46个运行操作，V1～V10仍19表。RR只读count/单页联表使用已有学生索引与参数化LIMIT/OFFSET，无新迁移/依赖/XML/分页插件，未改启动凭据。独立MySQL定向Maven verify 197个全部通过（普通/Web86、列表43、原预约订单65、文档3），零失败/错误/跳过；未重跑全部旧业务。四种真实并发写入与快照读取、本人隔离/历史/边界、mysql可执行JAR启动、实际运行导出46、完整62/46/16、9分组90schemas、两份官方schema/6个请求示例/引用检查通过。自有实例及目录由脚本清理，实际范围见[列表验收](student-order-list.md)。下一项建议定稿商家订单列表。

2026-10-23 商家订单列表按[ADR-0029](decisions/0029-merchant-order-list.md)交付：无新生产Java文件，仍154个、47个运行操作，V1～V10仍19表。共享摘要与分页参数解析，RR从本人店铺读取固定资格及count/page快照；复用已有商家索引，无新迁移/依赖/XML/分页插件，未改启动凭据。独立MySQL定向Maven verify 239个全部通过（普通/Web86、商家列表48、学生列表43、商家确认59、文档3），零失败/错误/跳过；未重跑全部旧业务。多店隔离/权限/历史、四种count/page并发写入、两种快照后成员变化、读取不等待业务写锁、mysql可执行JAR启动、运行导出47及完整62/47/15、9分组90schemas、两份官方schema/6个请求示例/引用检查通过；自有实例与目录已清理。范围见[商家列表验收](merchant-order-list.md)。下一项建议定稿订单事件查询。

2026-10-23 学生/商家订单事件查询按[ADR-0030](decisions/0030-order-events.md)交付：新增OrderEventView、OrderEventRow两个生产Java文件，当前156个、49个运行操作，V1～V10仍19表。RR内先授权后按原序号读取完整事件，原latest/append语义不变，无新迁移/依赖/XML/基础设施，未改启动凭据。独立MySQL定向Maven verify 259个全部通过（普通/Web86、事件54、管理员异常57、商家确认59、文档3），零失败/错误/跳过；未重跑全部旧业务。真实生命周期及十种关闭分支、权限/历史/序号边界、两端六种并发读写、两种成员快照变化及读取不等待业务写锁通过；mysql可执行JAR启动、运行导出49/完整62/49/13、9分组91schemas、两份官方schema/6个请求示例/引用检查通过。自有临时实例与目录已清理，范围见[事件验收](order-events.md)。下一项建议定稿打印文件上传及受控文件生命周期。

2026-10-23 私有PDF上传按[ADR-0031](decisions/0031-print-file-upload.md)交付：新增11个生产Java文件，当前167个、51个运行操作，V11新增file_asset，20张业务表；PDFBox3.0.8及单实例本地私有存储，无XML/Redis/MQ，未改启动凭据。两轮相关定向Maven verify各242个全部通过（普通/Web123含新增文件策略37、文件MySQL64、认证41、迁移11、文档3）；第二轮补强所有Failsafe测试的文件目录隔离和按根标识查询。真实PDF大小/页数/加密、Windows目录联接拒绝、SQL/IO失败补偿、身份变化、清理重试/互斥/批次和新JAR进程恢复持久中断数据通过；未重跑全部旧业务。V1～V11及mysql JAR健康/UTF-8/鉴权检查、样例生产解析、运行导出51/完整62/51/11、9分组92schemas、两份官方schema/6个请求示例/引用检查通过；自有临时实例/目录已清理。存储目录独立于缓存与构建，实际范围和点击流程见[上传验收](print-file-upload.md)。下一项建议定稿受控文件下载。

2026-10-23 本人PDF下载按[ADR-0032](decisions/0032-file-download.md)交付。实际执行 `$env:MAVEN_ARGS='-Dit.test=FileDownloadMysqlIT,FileUploadMysqlIT,AuthenticationMysqlIT,MysqlIntegrationIT,OpenApiMysqlIT'; .\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90`：最终291个定向用例通过（普通/Web124、真实MySQL167），含下载48；V1～V11与mysql JAR检查通过。过程中的故障已修正并重跑，最终日志`.cache/file-download-mysql-final.log`；测试使用独立数据库/存储根，未访问现有服务数据。没有执行全部历史订单/资金IT或代用户点击Apifox，当前运行52、完整62/52/10。

2026-10-24 打印报价按[ADR-0033](decisions/0033-print-quotes.md)交付。实际执行 `$env:MAVEN_ARGS='-Dit.test=PrintQuoteMysqlIT,PrintPricesMysqlIT,FileUploadMysqlIT,FileDownloadMysqlIT,AuthenticationMysqlIT,MysqlIntegrationIT,OpenApiMysqlIT'; .\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90`，最终480个定向用例通过（普通/Web142、MySQL338，含报价87与计算18）；V1～V11迁移及mysql JAR启动检查通过。首轮测试同步问题修正后重跑，最终日志`.cache/print-quote-mysql-final.log`。使用独立临时MySQL/文件根，不访问现有项目数据；未运行全部历史订单/资金IT、未代用户点击Swagger/Apifox。

2026-10-24：打印待支付按[ADR-0034](decisions/0034-print-orders.md)实现，V12新增print_detail、21张业务表，54个运行操作；11个新增生产Java文件，无新依赖/XML。学生订单历史类型扩展和文件引用串行/保留期由真实MySQL验证，结果及执行范围见[打印订单指南](print-orders.md)。本轮未改动VS Code本机启动凭据。

2026-10-24：最新相关报告合计 **798个用例通过**（普通/Web172、真实MySQL/HTTP626；本轮新增打印策略30及打印订单79），零失败/错误/跳过。该数字合并本轮相关套件的最新通过结果，不是一次全项目回归。先执行12套相关IT，修正营业边界测试的登录时间设置后，最终定向复测打印订单、迁移和OpenAPI共265个用例通过；V1～V12迁移及mysql模式JAR检查通过。最终执行与各套件数量见[打印订单验收](print-orders.md)；最终日志`.cache/print-orders-mysql-verified.log`。

2026-10-24 打印支付按[ADR-0035](decisions/0035-print-mock-payment.md)扩展现有入口与V10资金表，无新迁移/依赖/模块；184个生产Java文件、V1～V12和21张表、54个运行操作保持。真实MySQL发现并修复写返回中定位selectById的一级缓存陈旧问题；补确定时序的取消/创建重放对扫描用例。实际执行结果和范围见[打印支付验收](print-payments.md)，测试使用独立临时数据库/文件根，未改用户VS Code启动凭据。

2026-10-24：最终定向 **586个用例通过**（普通/Web172、真实MySQL/HTTP414，含新增打印支付71），零失败/错误/跳过；V1～V12迁移及mysql模式JAR健康、UTF-8、trace和鉴权启动检查通过。运行仍54个操作，完整62/54/8，未运行全部历史业务IT。最终命令及套件数量见[打印支付验收](print-payments.md)，最终日志`.cache/print-payment-mysql-final.log`；使用自有临时实例和存储根。

2026-10-24 商家打印确认按[ADR-0036](decisions/0036-merchant-print-confirmation.md)扩展五个既有入口，生产Java184、运行操作54、完整62/54/8、V1～V12与21表保持；未修改VS Code本机凭据。验证使用独立临时MySQL及测试文件根，实际执行范围和结果见[商家打印验收](merchant-print-confirmation.md)。

商家打印确认最终验证：2026-10-24：最终定向 **676个用例通过**（普通/Web172、真实MySQL/HTTP504，其中新增商家打印确认87），零失败/错误/跳过；V1～V12迁移及mysql模式JAR健康、UTF-8、trace和鉴权检查通过。运行仍54个操作、完整62/54/8；未重跑全部历史业务IT。范围与可复现步骤见[商家打印验收](merchant-print-confirmation.md)。

2026-10-25 商家打印取消按[ADR-0037](decisions/0037-merchant-print-cancellation.md)扩展既有入口；生产Java184、运行54、完整62/54/8、V1～V12/21表保持。本机启动凭据未修改；测试使用独立临时MySQL/文件根，实际验证结果与命令见[打印取消验收](merchant-print-cancellation.md)。

打印取消最终验证：2026-10-25：最终定向 **607个用例通过**（普通/Web172、真实MySQL/HTTP435，其中新增打印取消83），零失败/错误/跳过。V1～V12迁移及mysql模式JAR健康、UTF-8、trace和鉴权检查通过；运行仍54、完整62/54/8，未重跑全部历史业务IT。实际范围及复现见[验收指南](merchant-print-cancellation.md)。

2026-10-25 商家受控下载按[ADR-0038](decisions/0038-merchant-print-download.md)复用既有入口/存储/互斥，无新增部署要求或迁移。本机启动凭据未修改；实际用例/命令与范围见[商家下载指南](merchant-print-download.md)，使用独立临时MySQL和测试文件根。

商家下载验证：2026-10-25：本轮相关套件的最新通过报告合计 **532个用例**（普通/Web172、真实MySQL/HTTP360，含新增商家下载72），各套件当前报告零失败/错误/跳过；最后业务定向复验258个通过，随后接口分组修复的Web/API16个通过（不重复计入合计）。V1～V12迁移与mysql模式JAR健康、UTF-8、trace、鉴权检查通过；运行54、完整62/54/8，未重跑全部历史业务IT。命令与实际范围见[验收指南](merchant-print-download.md)。

2026-10-25 打印开始/待取件按[ADR-0039](decisions/0039-merchant-print-fulfillment.md)交付，运行55、完整62/55/7，184个生产Java文件，V1～V12/21表保持。测试使用独立临时MySQL及测试文件根，本机VS Code凭据未修改；实际命令和结果见[验收指南](merchant-print-fulfillment.md)。

打印履约验证：2026-10-25：最终定向 **687个用例通过**（普通/Web172、真实MySQL/HTTP515，其中新增打印履约79），零失败/错误/跳过。V1～V12迁移及mysql模式JAR健康、UTF-8、trace和鉴权检查通过；运行55、完整62/55/7，未重跑全部历史业务IT。实际范围及复现见[验收指南](merchant-print-fulfillment.md)。

2026-10-25 学生打印取件按[ADR-0040](decisions/0040-student-print-pickup.md)接入，运行56、完整62/56/6，V1～V12/21表、184个生产Java文件保持。无需新增迁移或改本机启动凭据，实际验证命令与结果见[验收指南](student-print-pickup.md)。

学生打印取件验证：2026-10-25：本轮去重定向 **790个用例通过**（普通/Web172、真实MySQL/HTTP618，其中新增学生取件79），零失败/错误/跳过。V1～V12迁移及mysql模式JAR健康、UTF-8、trace和鉴权检查通过；运行56、完整62/56/6。未重跑全部历史业务IT。实际范围与复现见[验收指南](student-print-pickup.md)。

2026-10-26 管理员PRINT异常按[ADR-0041](decisions/0041-admin-print-exception.md)扩展，运行56、完整62/56/6，V1～V12/21表、184个生产Java文件保持；无需改本机凭据或重建数据库。实际测试命令、范围和结果见[验收指南](admin-print-exception.md)。

管理员打印异常验证：2026-10-26：本轮各套件最终报告去重 **814个定向用例通过**（普通/Web172、真实MySQL/HTTP642，新增管理员打印异常108）；最终报告零失败/错误/跳过。V1～V12迁移与mysql模式JAR健康、UTF-8、trace及鉴权启动检查通过，运行56、完整62/56/6。未重跑全部历史业务IT。实际范围与复现见[验收指南](admin-print-exception.md)。

2026-10-26 学生评价按[ADR-0042](decisions/0042-student-order-review.md)接入，运行58/完整62/58/4，V1～V13/22表、191个生产Java文件。沿原配置启动自动迁移，不改本机凭据；真实MySQL验证及实际命令见[指南](student-order-review.md)。

学生订单评价验证：2026-10-26：本轮各套件最终报告去重 **714个定向用例通过**（普通/Web172、真实MySQL/HTTP542，其中新增评价90），零失败/错误/跳过。V1～V13迁移、22张业务表及mysql模式JAR健康、UTF-8、trace和鉴权启动检查通过；运行58、完整62/58/4。未重跑全部历史业务IT。实际范围与复现见[验收指南](student-order-review.md)。

2026-10-26 公开评价及真实评分按[ADR-0043](decisions/0043-public-shop-reviews.md)扩展，运行59/完整62/59/3、194个生产Java文件，V1～V13/22表保持，无需新迁移或修改启动凭据。实际验证命令、范围和结果见[验收指南](public-shop-reviews.md)。

公开评价验证：2026-10-26：本轮各套件最终报告去重 **399个定向用例通过**（普通/Web172、真实MySQL/HTTP227，新增公开评价62）；最终报告零失败/错误/跳过。V1～V13迁移及mysql模式JAR健康、UTF-8、trace和鉴权启动检查通过；运行59、完整62/59/3，无新迁移。未重跑全部历史业务IT。实际范围与复现见[验收指南](public-shop-reviews.md)。

2026-10-26 管理员隐藏评价：本轮最终399个定向用例去重通过，含新增真实MySQL/HTTP61及原公开/本人评价回归。最后暂时故障提示复验20个通过，不累加；V13存量升级V14、22表与mysql JAR启动检查通过，临时实例清理。实际命令/范围见[验收指南](admin-review-hide.md)，未重跑全部历史业务IT。

2026-10-26 管理员账号列表：本轮最终393个定向用例去重通过，含新增66（首轮65、修正只读事务测试设置后1复验）。最后17个复验与mysql JAR启动检查通过；无新迁移，仍V1～V14/22表。实际范围、命令和记录见[指南](admin-account-list.md)，未重跑全部历史业务IT。

2026-10-26 账号状态：384个定向用例通过（普通172/MySQL212，新增68＋7），V15/23表及mysql JAR启动检查通过。独立存量V14→V15验证不改账号/会话且不虚构记录；运行62/完整62/62/0，临时实例清理。实际命令/范围见[指南](admin-account-status.md)，未重跑全部历史业务IT。

2026-10-26 预约整链验收：本轮190个用例通过（普通172/MySQL18，新增BookingJourneyMysqlIT4），完整准备与业务动作使用HTTP，SQL仅断言/清理，ADMIN走受控初始化。V15/23表及mysql JAR启动通过，独立实例清理；未重跑全部历史IT，未替用户完成需等待的浏览器人工验收。命令/范围见[指南](booking-journey.md)。

2026-10-26 打印整链：196个用例通过（普通172/MySQL24，打印旅程6、预约旅程4、数据库11、OpenAPI3）；V15/23表及mysql JAR检查通过，测试文件根和实例清理。实际命令/边界见[指南](print-journey.md)，未重跑全部历史IT，未宣称真实打印或等待七天清理。

## 2026-10-26 V1全量回归

55类、2675用例全量通过，未筛选或跳过；从Maven clean开始构建，独立MySQL及专用文件根执行完毕清理，mysql/web-only可执行包与存量V13→V14/V14→V15补充验证通过。首轮2675用例仅1条旧断言失败：PublicBarbersMysqlIT仍将已公开的评价GET当作受保护待开发路径；改为仍受保护的/reviews/1后重新全量复验通过。本轮无生产业务代码或规则修改。[完整报告](reports/v1-acceptance-2026-10-26.md)包含真实执行命令、逐类计数、模块范围和验收限制；不是压测或公开部署结论。

## 2026-10-27 打印界面验收

[打印验收记录](manual-print-acceptance.md)P00～P15共16/16项通过。Chrome/Swagger走完新店配置、5页PDF上传、2份双面6张/200分报价、幂等创建、模拟支付、商家接单/打印/待取件、学生取件与公开评价；双方六条事件一致、下载副本字节一致，跨学生404及角色403符合预期。订单1最终COMPLETED/PAID/PICKED_UP、version=5；文件2期限为完成+7天。

管理员审核、首次文件选择及P12最后的本人登录/下载由用户在本机操作；最后一次下载的200及完成结果据用户明确反馈，未采集该次traceId。保留浏览器工具限制及身份切换修正记录，不当作应用缺陷。本轮只更新验收文档，未修改Java或迁移，也未重跑或累加自动测试；未进行实体打印、真实七天清理或部署验收。预约人工履约另行安排。

## 2026-10-27 预约即时隔离自动验收

用户希望无需真实等待走完预约链，明确选择现有测试时钟的隔离方式。实际运行BookingJourneyMysqlIT四条旅程、MysqlIntegrationIT11、OpenApiMysqlIT3及普通/Web172，共190用例，失败/错误/跳过均0；V1～V15/23表、62操作及mysql JAR启动检查通过，临时MySQL及目录已清理。

按本轮开始时间只采集新生成的11类报告，排除旧XML；未执行clean，未重跑全部历史IT，也未累加之前用例。Clock仅在测试中固定并推进到候选startAt，先验证提前开始409，再重新登录并履约；现有项目库和应用Clock未改变。无生产/测试代码或迁移修改。[本轮报告](reports/booking-immediate-2026-10-27.md)记录四条场景、实际命令和脱敏计数；[浏览器人工记录](manual-booking-acceptance.md)保留未执行项，不由自动结果替代。

## 2026-10-28 商家查询性能基线

新增显式运行的PublicShopQueryBaseline和`scripts/benchmark-public-shops.ps1`，复用原临时MySQL与JAR检查流程，无新依赖。两轮独立实例均通过；最终一键复验执行普通/Web172用例与基线1用例，共173，零失败/错误/跳过。测量类不匹配默认Test/IT扫描，还需专用环境标记；写入前核对空库和实际随机datadir，避免污染已有项目数据。

两档1,000/10,000虚构店铺、15,000/105,000关联完成预约与评价，11种查询场景；实际HTTP捕获SQL并验证只读RR与4/3/2条数量，保留76组EXPLAIN/ANALYZE、660个正式HTTP样本和2,280个单SQL样本。最终大档列表首页p50/p95=61.06/68.11 ms，发现联表后排序及热门评分/评价深分页候选；该结果不是并发容量或优化收益。生产SQL/索引/规则/接口/依赖均未修改，Redis未接入，未重跑全部业务IT。两次自建实例均清理；命令、原始数据、首轮差异和下一步建议见[方法](public-shop-query-baseline.md)与[本轮报告](reports/public-shop-query-baseline-2026-10-28.md)。

## 2026-10-28 M01.1 SQL等价对照实验

新增显式运行的PublicShopSqlExperiment、实验/汇总脚本及方法文档；原造数SQL抽为PublicShopQueryFixture由C06/M01.1共用，不改生产查询、索引、迁移或业务规则。原版从真实MyBatis MappedStatement渲染并核对Mapper；两个候选为EXISTS＋ID派生分页、以及加NO_SEMIJOIN查询块提示的版本。

两个独立MySQL实例各运行两档、7场景、3版、3轮×20样本，共5,040个count/page只读RR事务样本；全部返回列、顺序、count及每档18项额外语义检查通过，提示无Warning。每实例普通/Web172＋实验1，共173，零失败/错误/跳过及mysql JAR检查通过。另独立复验原HTTP基线入口173通过，确认夹具抽取兼容；三个自建实例已清理。未重跑全部历史业务IT，计时样本不当作测试用例。

分类首屏提示候选有可解释收益，大档两个实例的SQL事务p50为46.07→12.97ms、25.91→11.91ms，分页仅扫描27个候选取得20项；count仍处理全部匹配。无分类/小数据部分路径退化，不能全局加提示或把SQL事务数字当HTTP收益。路线图标M01.1已验证、下一项M01.2按场景补验证再采纳或保留；[报告](reports/public-shop-sql-experiment-2026-10-28.md)保存完整分轮结果与建议，[方法](public-shop-sql-experiment.md)提供复现。

## 2026-10-28 M01.2 分类首页采纳与完整HTTP复测

仅在指定分类、规范化后无名称关键词且第1页时，Service调用新增静态@Select分类分页：完整EXISTS公开资格检查后LIMIT，使用既有分类复合索引及局部NO_SEMIJOIN提示，再加载展示列。count、未分类、搜索、后续页和详情保留原查询；原RR、排序、营业状态、评分和资格规则保持，无新索引/迁移/接口/依赖。取舍见[ADR-0047](decisions/0047-public-shop-list-query.md)。

先进行双分支原型预验，再收窄到实际分类分支独立复测；各实例1,000/10,000店、60%/90%/10%公开分布、9场景、3轮×12次，各3,888正式HTTP样本，整个data响应及额外语义/RR验证通过。正式阶段大档原60%分布理发首页20条p50/p95=29.09/31.40→17.45/18.79 ms，打印25.19/27.14→13.10/14.62 ms；小档稀疏size100几乎无收益，保留报告。不是并发容量验证，两个阶段不同源码不跨阶段算提升。

实际命令为`scripts/verify-public-shop-list.ps1 -Phase validation`及`-Phase adopted`；两阶段各普通/Web172＋实验1通过。另用MAVEN_ARGS指定PublicShopCatalogMysqlIT、PublicShopReviewsMysqlIT、BookingJourneyMysqlIT、PrintJourneyMysqlIT、OpenApiMysqlIT、MysqlIntegrationIT运行原test-mysql脚本：普通/Web172＋MySQL153＝325，14类，零失败/错误/跳过；新增6个目录回归。三次mysql JAR检查与隔离实例清理通过，未访问用户项目数据/文件根，未重跑全部历史IT。62操作、V1～V15/23表保持，OpenAPI契约不变。

[报告](reports/public-shop-list-adoption-2026-10-28.md)及JSON归档保留原始样本、执行计划、源码摘要和逐类回归计数；[方法](public-shop-list-adoption.md)提供脚本和Swagger/Apifox人工核对路径。汇总脚本检查正式源码摘要、样本及分支SQL、新XML计数并归档；M01已完成，路线图下一项M02.1方案定稿，Redis未实现。

## 2026-10-28 M02.1 缓存方案草案（待确认）

已核对PublicShopCatalogService/PublicReviewMapper的详情聚合、OrderReviewTransactions的创建/隐藏及原锁序、经营资料与时段版本规则，提出[详情评分统计缓存方案](redis-rating-cache-design.md)。建议仅此一个GET场景缓存SUM/COUNT，增加V16技术版本元数据，同原评价写事务推进，以版本键使旧缓存逻辑失效，保留原RR、资格和现场营业状态；无改变已验收业务的授权推定。

本机Boot4.1.1的BOM管理Spring Data Redis4.1.1及Lettuce7.5.2.RELEASE，已读取核对；POM尚无Redis starter，不能称为兼容测试通过。发现docker/wsl命令，Docker服务版本检查返回Linux Engine命名管道不存在，运行条件未验收。本轮未启动/安装组件，未读取启动密钥或操作用户库，未修改Java/POM/迁移/接口，未运行构建/缓存测试；仍62操作、V1～V15/23表。

交付为待确认方案、源码依据、替代方案及写成本、失效/回填/降级/有界合并流程和真实联测矩阵。路线图标M02.1草案待确认、M02.2尚未开始；确认新增基础设施和技术元数据后再实施，不能把规划当已实现或把历史基线当缓存收益。

## 2026-10-28 M02.2 详情评分缓存

用户明确采用并实现后，加入Boot管理的Redis starter、条件化Lettuce连接/显式Jackson3统计JSON、详情事务外编排/独立RR读取、版本键/TTL、有界在途合并和5秒故障绕过。V16新增统计版本技术表，同原评价提交/隐藏事务最后更新，回滚/重放/缓存关闭边界保持；当前62操作、V1～V16、24张应用表，列表与其它查询无缓存。

实际执行`test-rating-cache.ps1 -Phase All`，后补充离线新应用启动及真实HTTP在途复用，重跑`-Phase Cache`：514个不同用例（190普通/Web、304定向MySQL、19缓存联测、1真实V15升级），19类，失败/错误/跳过均0；普通190在每阶段重复运行而不累加独立数量。四个成功临时MySQL的mysql JAR启动与清理通过，自有测试Redis已删除；三次失败试跑的自有MySQL残留核对无进程后清理，诊断日志保留，fixture父目录为空。未重跑全部历史IT。

真实捕获匿名详情未命中3/命中2条业务SQL、全部RR/readOnly，data一致；事务回滚/版本并发/溢出、旧RR及晚回填、资格/坏缓存/零统计和实际Redis停机恢复通过。V15存量5,100条评价由Flyway执行V16后逐行不变。初次验证发现并修正Docker桥接protected-mode、恢复探针异常类型和动态端口重启变化；最后使用固定回环端点验证。

本机Start/Status/Stop/Start已验证，留下项目自有开发Redis，专用配置/身份文件经Git忽略检查；仓库默认关闭，用户正式应用未代重启/迁移、MySQL/JWT/VS Code密钥未读改。[指南](redis-rating-cache.md)、[ADR-0048](decisions/0048-public-rating-cache.md)和[报告/JSON](reports/redis-rating-cache-2026-10-28.md)提供复现、源码摘要与逐类证据。下一项M02.3测系统故障与同条件性能/版本写成本，本次不声称接口速度或QPS收益。

## 2026-10-28 M02.3 故障与性能对照

新增显式RatingCacheAcceptance与运行/汇总工具，仅测试及文档改动。生产Java、POM/资源摘要与M02.2归档核对一致；运行`benchmark-rating-cache.ps1 -Runs 2`，两个独立临时MySQL/Redis各执行190普通/Web＋专项1，失败/错误/跳过0；两次mysql JAR及清理通过，失败试跑唯一自有MySQL残留核对无进程后清理，当前fixture目录与测试Redis均0。正式应用/库、上传目录、开发Redis与启动密钥未操作。

同构建两个真实配置应用、同数据库、1,000/10,000店、普通/热门/零评价、1/4/16线程；两轮8,640个预热HTTP＋672个冷/到期HTTP，响应正确，RR/SQL计数成立。稳定开启的4,320请求实际全命中、聚合0；关闭4,320请求各一次聚合。1万店热门单线程p50两轮6.54→2.88 ms、6.16→2.71 ms，16线程30.75→6.78 ms、33.87→7.13 ms；普通/零评价中位更慢，不能泛化提升。

真实暂停Redis超时、拒绝SET、停机期间隐藏并恢复，以及六次隐藏/96并发GET均验证通过；恢复以实际命中/回填判断。写阶段仅在测试拦截器省略版本更新作原事务对照，1/8线程、两轮1,536个真实事务样本，额外SQL和行锁等待存在；保留全部尾部异常与原样本，不把事务计时称作HTTP写延迟。

首遍试跑因测试构造JSON整数节点类型差异误判，规范化独立期望的传输形式后重新两轮通过，未改生产行为。无新接口/迁移/组件，仍62操作、V16/24表。方法、限定条件、原始数据/源码摘要见[测量方法](redis-rating-cache-measurement.md)和[完整报告](reports/redis-rating-cache-measurement-2026-10-28.md)；未重跑全部历史业务IT，不承诺线上QPS/SLA。M02结束，下一项M05.1最小前端页面与接口清单定稿。

## 2026-10-29：M05.2批次A前端

用户已确认M05.1，按[ADR-0049](decisions/0049-minimal-frontend.md)建立独立`frontend/`。实际交付注册/统一登录/本人资料/改密表单、公开商家/服务/评价浏览与角色导航壳。Node24.22.2/npm11.19.1位于`.tools/node`，官方ZIP校验通过，版本固定于package-lock.json；Java仍单Maven工程，后端src/main/POM的233个文件摘要保持，62操作/V16/24表不变。

实际可用命令：`scripts/setup-frontend.ps1`、`scripts/frontend.ps1 -Action Install|Test|Build|Run`。Run仅回环5173并代理8080，先启动原后端；不修改VS Code密码/JWT或放开CORS。31项客户端测试、类型检查/生产构建、锁文件离线重装及21个agent浏览器检查点通过；用户另亲自确认改密及新密码登录成功。浏览器覆盖真实临时MySQL正常流程、昵称并发版本冲突、会话隔离、代理故障恢复与桌面/窄屏。首次包装器原生stderr警告误终止后检查，改为操作系统级独立日志捕获；独立补跑190项普通/Web＋1项夹具测试、mysql JAR后检查及自动清理通过，旧失败轮次数据核对路径/进程后清理并保留诊断日志，不是历史MySQL全量重跑。

操作与后续状态见[前端指南](frontend.md)和[批次A记录](reports/frontend-batch-a-2026-10-29.md)。`preview-frontend.ps1`配合仅环境标识启用的测试夹具，在随机生成datadir和文件根中准备合成数据；与原项目库隔离。B入驻/审核/经营配置、C打印、D预约及管理尚未实现，后续按已确认批次推进。

## 2026-10-29：M05.2批次B商家管理前端

沿用ADR-0049和既有前端依赖，新增申请/拒绝重提/审核、分类经营配置页面，接入20个已有业务操作。经营表单保留各自字符串版本、PATCH变更字段、价目遗漏保留与时段/绑定完整替换；写入不自动重试。页面内草稿选择替代会阻塞in-app浏览器的原生confirm，最终版本重新验证。后端src/main与POM共233个文件摘要保持，无迁移/规则/接口变化。

本轮3类57项客户端测试（含A31/B26）、vue-tsc/Vite构建、28个真实浏览器检查点通过；独立MySQL后端流程9类190项普通/Web＋1类1项浏览器夹具通过，mysql JAR后检查成功。Stop自动清理后再次核对所属数据库进程0、Vite进程不存在、5173及随机后端端口无监听、自有datadir不存在；未操作原服务数据或开发Redis。不是历史2675项全量重跑或新增行锁证明。操作与范围见[商家指南](frontend-merchant.md)、[本批报告](reports/frontend-batch-b-2026-10-29.md)；前段A记录保留当时状态，当前A/B已完成，下一批C打印整链尚未开始。

## 2026-10-29：M05.2批次C打印前端

接通上传/元数据/受控下载、规格报价、原键创建恢复、学生订单与商家打印任务、模拟支付/取消退款、打印/取件/评价及真实事件。客户端保留原冻结请求，错误后不自动换键；成功后响应丢失由仅临时预览可启用的开发代理注入，记录原键和实际后端订单再断开响应。普通Run/Build默认无故障注入，原后端规则/接口/迁移未变，生产src/main/POM共233文件摘要保持。

最终4类91项客户端测试（A/B57＋C34）、类型/生产构建和31个桌面浏览器功能检查点通过，含5笔真实打印订单、退款/跨账号隔离、响应丢失刷新恢复与下载SHA-256一致。会话清空重建打印页导致空白已修复并实际复验退出/换账号；修复后重跑91项及构建。隔离后端9类190项普通/Web＋1项夹具、mysql JAR与自动清理通过，随后独立核对所属MySQL/Vite进程、端口和datadir均无残留。未测历史MySQL全量/容量，390px覆盖未生效、实际1280，不计窄屏通过。见[使用指南](frontend-print.md)、[定向报告](reports/frontend-batch-c-2026-10-29.md)；下一批D预约与管理，并补C窄屏复核。

## 2026-10-29：M05.2批次D预约与管理员前端

接通预约候选/创建恢复、两类订单与预约动作、管理员按ID核实退款、账号查询/状态及评价隐藏，A～D覆盖61业务操作；没有新增后端API或业务规则。两类恢复各自保存原键/正文，管理动作使用对应原版本，公开评价带入时重新读取真实摘要，管理员无PDF内容入口。测试夹具只在已校验随机datadir准备真实校园今天起7天排班和虚构可登录账号，无改时钟后门；生产src/main/POM233文件摘要及前端依赖保持。

最终5个测试文件114项客户端测试、类型构建、28功能＋4实际390px布局浏览器检查点通过，3预约＋1打印均关闭，包含版本竞争、模拟退款、隐藏评分变化、停用/恢复会话边界及共享打印回归。in-app视口无效后改Chrome；截图发现页头挤压后修复nav换行，在第二独立环境目视复核四页及链接边界（实际390、文档375～390px），补齐C待办，覆盖已reset，本轮标签已关闭。两轮各9类190普通/Web＋1夹具、mysql JAR及清理通过，不累计为新增覆盖；独立进程/端口/目录核对归档。主轮停止首次自动审核超时，允许的一次重试成功，未停用户服务。未等新预约真实开始/完成/新评价，不以合成历史完成单或旧时钟报告冒充。见[指南](frontend-booking-admin.md)、[D报告](reports/frontend-batch-d-2026-10-29.md)；下一项M08.1只做优惠券业务定稿，人工预约末段另保留。

M08.3-B沿Java21/原MySQL启动方式升级V22；75操作/32应用表，未增加依赖或前端任务。coupon超时调度默认启用，隔离测试显式关闭并按需手动调用。新的独立券消息队列与原通知链复用Broker连接/发布租约，仓库消息传输默认关闭。复现与实际证据见[底座指南](async-coupon-foundation.md)，不需要改用户机器的数据库/JWT凭据。

2026-10-31 M08.3-C：75操作/V23/32表。单独的ACL/AOF/noeviction券Redis与M02隔离，仓库默认关闭，本机Start/Status/Stop已验证后停止并保留自有卷。私有单工作线程避免长恢复阻塞公共定时调度，不替换Spring默认Executor/TaskScheduler；恢复入口不持SQL事务做IO。普通test-mysql默认关闭未由专用runner拥有的Redis连接；真实三组件/双Redis及升级证据见[C报告](reports/coupon-redis-recovery-2026-10-31.md)。

## M08.3-D（2026-10-31）

147项不同相关检查分轮最终通过，无新依赖或DDL，75操作/V23/32表。三阶段公开受理、模式维护非Web子JVM与MySQL JAR启动已验证；脚本为`scripts/coupon-mode.ps1`，只接受活动ID/控制版本/动作/升级声明，密码本机输入。默认SYNC/依赖关闭、不改现有开发活动；实际命令、范围和首轮夹具修复见[指南](async-coupon-admission.md)/[报告](reports/async-coupon-admission-2026-10-31.md)。
