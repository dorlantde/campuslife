# 最小工程基础与本地运行

日期：2026-10-13（初始阶段），2026-10-15 更新进度。工程为单 Maven Spring Boot 应用。本篇主要记录最小 Web 基础；后续已增加 MySQL 数据源、初始迁移、注册、认证与改密，最新运行方式见 [MySQL 接入](mysql-setup.md)。订单、Redis、MQ 和对象存储尚未实现。

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

已补充 [完整 V1 接口规划](api-design.md)，可在 Apifox 预览 61 个操作与待开发模块。此次仅生成文档和文档工具，没有新增 Java 业务接口，不以格式校验代替业务测试。

2026-10-15：首批认证与本人资料接口、Swagger UI / OpenAPI 已完成。当前全套 140 个用例，包括 17 个 JWT、41 个认证/改密、16 个资料与 4 个文档 HTTP/MySQL 用例；独立 MySQL、并发修改、事务回滚、文档权限及 mysql 模式 jar 检查通过。人工验收优先按 [浏览器 / Apifox 指南](api-testing.md)，现阶段先由用户验收，再继续商家规则。web-only 保留 Web 诊断并关闭文档。
