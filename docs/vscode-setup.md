# VS Code 导入与启动排查

## 本次依赖错误

2026-10-16 用户上传的完整诊断中，最前面的下载失败为：

```text
Could not transfer artifact ... from/to central
Remote host terminated the handshake
javax.net.ssl.SSLHandshakeException
```

这是 Java 扩展导入 Maven 依赖时的 HTTPS 握手失败。随后出现的 `Missing artifact`、`The import io.swagger cannot be resolved`、`Tag cannot be resolved to a type` 等会连带发生，不能逐项当成源码缺陷修复。日志本身不能确定握手失败由代理、网络还是证书环境造成。

命令行通过 `.mvn/maven.config` 使用项目的 `.cache/maven-repository`，其中已有所需依赖。原来的 VS Code 工作区设置没有指定 Maven settings；现已通过 `java.configuration.maven.userSettings` 指向 `.mvn/vscode-settings.xml`，让 Java 扩展使用同一个缓存，避免再次下载已有依赖。保持正常 HTTPS 校验与在线下载能力。

`.vscode/settings.json` 同时把项目的 JavaSE-21 映射到本机 `.tools/java21` 中的 JDK。Java 扩展自身继续使用它附带的 JRE；本机扩展 1.57.0 要求启动语言服务的 JDK 至少为 25，不能把 `java.jdt.ls.java.home` 强行设成 21。项目编译与运行仍使用 Java 21。

## 重新导入

1. 用 VS Code 的“打开文件夹”打开 `D:\campuslife`，让根目录的 `.vscode/settings.json` 生效。
2. 按 `Ctrl+Shift+P`，执行 `Java: Clean Java Language Server Workspace`，确认清理并重启 Java 语言服务。清理的是编辑器索引，不会删除项目源码或数据库。
3. 等待 Java 项目导入、构建完成，再查看“问题”面板。不要在仍提示导入时直接运行应用。
4. 若仍有依赖问题，可执行 `Java: Update Project Configuration`，再查看“输出”中的 `Language Support for Java` 和最新的第一条 Maven 错误。

不要为了移除这批错误删除注解、删掉 POM 中的依赖，或关闭 SSL 校验。以后新增依赖若再次下载失败，应按最新的 Maven 下载错误处理实际网络问题。

当前工作区的 Maven settings 路径、XML 中的 localRepository 和 JDK 路径使用本机绝对路径。搬动项目或更新本地 JDK 后，需要同步修改这些路径。这份 XML 只配置 Maven 缓存，与 Mapper SQL 无关。

## F5 启动与断点调试

已提供 `.vscode/launch.json` 和 `.vscode/tasks.json`。用“运行和调试”面板选择 **CampusLife (MySQL)**，按 F5；无需先执行 database.ps1。

1. 先停止之前占用 8080 的应用，例如在原脚本终端按 Ctrl+C。
2. 按 F5，在密码弹窗中输入此前创建的 **MySQL campuslife 专用账号密码**，不是 root 密码，也不是平台业务登录密码。密码框隐藏输入，配置不会保存该密码。
3. VS Code 自动执行 `scripts/prepare-vscode.ps1`，复用 `.cache/secrets/jwt-secret.base64`；该文件不存在时才生成随机密钥。脚本将密钥写入已被 Git 忽略的 `.cache/vscode/runtime.properties`，不写入数据库密码，也不输出密钥。
4. Java 21 启动主类，通过 `--spring.config.additional-location=file:./.cache/vscode/runtime.properties` 读取本地签名配置，并连接 mysql。这个文件只用于该启动配置，普通构建不会将它打包进应用。已有的 JWT 环境变量仍按 Spring 的优先级生效。
5. 在 VS Code 的集成“终端”看到 `Started CampusLifeApplication` 后，打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。停止调试用 Shift+F5。可在 Controller / Service 行号旁点击设置断点，再通过 Swagger 发出请求。启动配置的 console 使用 integratedTerminal；调试面板仍可显示变量和断点。

保持 `application-mysql.properties` 中的凭据引用：

```properties
spring.datasource.username=${DB_USERNAME:campuslife}
spring.datasource.password=${DB_PASSWORD}
campuslife.jwt.secret-base64=${JWT_SECRET_BASE64}
```

不要把 root 用户或本机密码直接写进这个源码配置。此前已创建的 campuslife 专用账号足够运行与迁移。F5 的数据库地址、端口、库名、用户名在 launch.json 的非敏感 env 配置中设置；本机默认值为 127.0.0.1:3306 / campuslife。更新本地 JDK 后，还需同步修改 launch.json 的 javaExec。

这里的 JWT 密钥用于签发和验证业务登录令牌，与 MySQL 密码作用不同。用户后续上传的启动日志最末层为 `Could not resolve placeholder 'JWT_SECRET_BASE64'`，因此仅填写数据库密码仍无法启动；Tomcat 和安全过滤器创建失败是上层包装异常。该日志尚未证明数据库连接成功。

直接点击主类的通用 Run、其他 IDE 或直接 Maven 启动时，要确认实际使用上述命名配置，否则仍需自行准备必填环境。终端交互启动也可继续使用 `scripts/database.ps1 -Action Run`，两条路径复用同一份本地开发签名密钥。具体变量见 [MySQL 运行配置](mysql-setup.md#4-ide-与运行配置)和[认证配置](authentication.md)。

## 验证记录

2026-10-16：在 Java 21 下执行离线 `mvn -o -B test-compile` 返回 BUILD SUCCESS；使用新配置执行 `mvn -o -B -s .mvn/vscode-settings.xml test-compile` 也通过。已有主代码和测试代码编译结果为最新，本次没有触发重新编译。此次未修改业务源码、依赖版本或数据库配置，未重跑单元和 MySQL 集成测试。VS Code 问题面板是否清空，需要用户重新导入后确认。

同日补齐 F5 配置后再次执行离线 test-compile，恢复了源文件和 target/classes 中的凭据占位符。Windows PowerShell 5.1 下验证启动准备脚本重复执行复用同一密钥、配置文件不含数据库密码；随后以 Java 主类、外部本地密钥文件、独立临时 MySQL 实际启动，健康、注册、登录和携带 JWT 读取本人资料均通过。结束停止应用及临时 MySQL，清理本次夹具，没有使用用户现有数据库或密码。该检查覆盖配置和主类启动路径；没有操作 VS Code 的 F5 按钮或验证交互密码框，也未重跑完整的 140 用例。

用户随后上传的 10:53:26 启动日志包含 HikariPool Start completed、Started CampusLifeApplication、ReadinessState ACCEPTING_TRAFFIC，说明该次本机 MySQL 启动已成功。条件匹配报告中的 Positive / Negative matches 是自动配置诊断，不是失败列表。此结论来自用户日志，未据此声称应用目前仍在运行或已经完成浏览器接口验收。根据用户偏好将输出从 internalConsole 改回 integratedTerminal，重新启动后生效。

参考：[Java 扩展设置](https://github.com/redhat-developer/vscode-java#supported-vs-code-settings)、[VS Code Java 项目管理与语言服务清理](https://code.visualstudio.com/docs/java/java-project)、[Java 调试配置](https://code.visualstudio.com/docs/java/java-debugging)、[Spring Boot 外部配置](https://docs.spring.io/spring-boot/reference/features/external-config.html)。
