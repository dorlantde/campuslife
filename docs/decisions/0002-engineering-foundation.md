# ADR-0002：最小工程初始化

- 日期：2026-10-13
- 状态：工程基础已实施并验证（2026-10-13）
- 范围：一个可启动的 Web 应用、统一响应、全局异常与基础 HTTP 集成测试

采用 Java 21、Spring Boot 4.1.1 的 Maven 单应用，根包 com.campuslife。仅加入 spring-boot-starter-webmvc、spring-boot-starter-validation、spring-boot-starter-webmvc-test。测试与 JSON 组件版本随 Boot BOM，不混入旧 Boot 的 Jackson 或测试自动配置包。

实际解析到的 Jupiter 为 JUnit 6.0.3，沿用 org.junit.jupiter 的测试 API；因此原技术草案中的“JUnit 5”调整为 Boot 管理的 Jupiter 版本。使用 Jackson 3 的 tools.jackson 包，验证库间兼容性后再增加其他组件，不单独降级依赖凑旧教程配置。

数据库连接、MyBatis-Plus、Flyway、Spring Security、JWT 和 springdoc 在对应模块接入时验证并添加，避免尚无业务的工程因缺少数据库配置或安全配置而启动失败。此决定只调整依赖引入时机，不取消已讨论的技术方向。

Controller 显式返回 ApiResponse；MVC 异常统一转换，保留 HTTP 状态与 Allow 等协议头；日志保存内部异常，客户端获得稳定错误码和安全提示。测试故障入口只存在于测试类路径。

本机原环境缺少 Java 21。项目内 JDK 及 Maven 缓存放在已忽略的 .tools 和 .cache 下，不修改全局 Java 配置。JDK 从官方发行源获取并校验其 SHA-256，不关闭 TLS 或证书验证。

默认绑定本机回环地址，当前只开放无敏感内容的健康检查，不部署对外可交易平台。最小健康检查无需引入 Actuator；监控功能在有实际需求后添加。

实现说明和验证记录见 [工程基础](../engineering-foundation.md)。

验收结果：Java 21 下 Maven verify 成功，13 个真实 HTTP 测试全部通过，可执行 jar 启动及测试入口隔离检查通过。数据库和认证尚未实现。
