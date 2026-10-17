# MySQL 接入与账号、会话迁移

接入阶段日期：2026-10-13。本页记录数据源、account/auth_session 与 Flyway；注册见 [注册验收](registration.md)，2026-10-15 登录与 JWT 的本机配置见 [认证验收](authentication.md)。

## 1. 三种身份不要混淆

| 身份 | 用途 | 密码在哪里填写 |
|---|---|---|
| MySQL root | 首次创建项目数据库及数据库账号 | 本机 mysql 客户端交互提示 |
| MySQL campuslife | 应用和迁移连接 campuslife 数据库 | 本机 PowerShell 安全提示或 IDE 环境变量 DB_PASSWORD |
| 平台 STUDENT / MERCHANT / ADMIN | 登录 CampusLife 的业务账号；当前已实现学生注册 | 注册或受控创建流程，与 MySQL 账号不同 |

不要把任何一种密码发送到聊天或提交仓库。脚本不写密码文件、不把密码作为命令参数；密码只在本机交互或进程环境中临时使用。

## 2. 当前本机情况

已只读检测到 MySQL80 服务运行、MySQL 客户端 8.0.45。用户在本机完成 campuslife 库和专用账号创建；修复脚本兼容问题后，用户反馈 Check 返回 BUILD SUCCESS、Run 启动正常。agent 只读检查了正在运行的健康接口，确认 code=OK、status=UP，并复现 PowerShell 5.1 的中文解码乱码。没有读取 root 密码、没有修改现有 MySQL 服务配置，也没有替用户在现有实例执行建库。

推荐新部署使用 MySQL 8.4 LTS；当前迁移针对 MySQL 8.0.16+ / 8.4 编写。本机兼容路径使用已有 8.0 系列验证，不要求为了这两张表立即升级现有数据库。8.0.16 是 CHECK 约束实际执行的最低版本，不能以更旧版本“语法接受”当作约束生效。

## 3. 你在本机完成的步骤

在项目目录 D:\campuslife 的交互终端执行，支持 Windows PowerShell 5.1 和 PowerShell 7。输入过程由本机终端处理，密码不会通过聊天传给 agent。

```powershell
# 第一步：创建项目库与账号，只执行一次
.\scripts\setup-mysql.ps1

# 第二步：用专用账号连接，运行迁移与数据库验收
.\scripts\database.ps1 -Action Check

# 第三步：连接真实项目数据库启动应用，Ctrl+C 停止
.\scripts\database.ps1 -Action Run
```

setup-mysql.ps1 先让你设置新的 campuslife 数据库账号密码（至少 12 字符，并满足本机 MySQL 密码策略），随后 mysql 客户端要求输入已有 root 密码。新账号仅允许从 127.0.0.1 连接，项目库采用 utf8mb4。

该脚本使用 CREATE DATABASE / CREATE USER，不使用覆盖或重置命令。库或账号已存在时会停止；MySQL 建库、建账号、授权不是一个可整体回滚的事务，部分步骤失败后要先检查已创建资源，不要直接重复执行或删除已有库。应用脚本默认只使用 campuslife 账号，不将 root 作为应用账号。

database.ps1 提示输入的是刚设置的 campuslife 账号密码，不是 root 密码。已有进程环境中的 DB_PASSWORD 会直接使用；没有时通过 Read-Host -AsSecureString 输入，并仅在子进程环境中提供给 Java，脚本结束恢复原环境。

Check 首次启动 Spring 上下文时执行 Flyway V1；当前包含 11 个 SQL 约束、26 个注册、41 个认证/改密、16 个资料与 4 个文档 HTTP/MySQL 用例，另运行普通单元与 Web 测试，总计 140 个。SQL 用例事务回滚；HTTP 用例使用自己的随机测试账号与会话，结束按归属清理，不删除原有账号。AUTO_INCREMENT 可能正常增长。脚本也准备 JWT 环境，细节见 [认证验收](authentication.md)。人工接口验收推荐 [Swagger / Apifox 指南](api-testing.md)。

若已有项目库和账号，跳过 setup，直接 Check。需要使用 campuslife_dev 等项目专用库时，先自行创建，再通过 database.ps1 的 DatabaseName、DatabasePort、DatabaseUser 参数指定；支持的数据库名为 campuslife 或 campuslife_ 后接小写字母、数字、下划线。

## 4. IDE 与运行配置

默认 profile 为 mysql，缺少 DB_PASSWORD 或连接/迁移失败时应用不会伪装成已经接入数据库。

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| DB_HOST | 127.0.0.1 | 主机 |
| DB_PORT | 3306 | 端口 |
| DB_NAME | campuslife | 项目数据库名 |
| DB_USERNAME | campuslife | 专用数据库账号 |
| DB_PASSWORD | 无默认值，必填 | 在本机填写，不提交仓库 |
| JWT_SECRET_BASE64 | 应用无默认值 | dev.ps1 的 Run / DatabaseCheck 自动准备；VS Code 命名 F5 配置通过启动任务和本地文件提供，其他启动方式需自行配置 |
| SPRING_PROFILES_ACTIVE | 默认 mysql | web-only 仅供 Web 基础诊断 |

IDE 的 Project SDK、Maven Runner JDK 使用 Java 21。普通 .env 文件不会被 Spring Boot 自动加载；本项目没有新增 dotenv 加载器，应使用 IDE 运行配置或提供的交互脚本。

VS Code 在“运行和调试”选择 CampusLife (MySQL)，按 F5 后在弹窗输入 campuslife 专用账号密码，JWT 密钥由启动任务准备。它复用终端启动的本地开发密钥；密码不写入文件。依赖导入修复、Java 21 设置及详细点击步骤见 [VS Code 指南](vscode-setup.md)。源码配置保持环境变量占位符，不写 root 用户或本机密码。

本地 JDBC 使用 sslMode=PREFERRED，不开启 allowPublicKeyRetrieval；Connector/J 优先协商 TLS。若本机禁用 TLS 且使用 caching_sha2_password，可能无法在首次连接时完成认证，应检查服务 TLS 配置或明确配置服务器公钥，不直接把关闭加密或任意公钥获取当作默认修复。

当前开发账号权限局限于 campuslife 库：SELECT、INSERT、UPDATE、DELETE、CREATE、ALTER、INDEX、REFERENCES，包含本地迁移所需 DDL；没有全局授权或 GRANT OPTION，也没有 DROP。未来部署可拆分迁移账号和运行账号，本轮不建立复杂权限体系。

只需检查 Web 基础时：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'web-only'
.\scripts\dev.ps1 -Action Run
# 完成诊断后清除本进程覆盖，恢复默认 mysql 行为
Remove-Item Env:SPRING_PROFILES_ACTIVE
```

web-only 排除数据源与 Flyway 自动配置，不表示数据库已经接入。普通 Web 集成测试显式使用此 profile，不需要你的数据库密码。scripts/smoke.ps1 默认也只检查 web-only；检查 mysql 模式需本机配置 DB_PASSWORD 后执行 scripts/smoke.ps1 -Profile mysql。

## 5. 迁移与表的关键规则

[V1 迁移](../src/main/resources/db/migration/V1__create_account_and_auth_session.sql)由 Flyway 自动执行。禁用 Spring 的 schema.sql/data.sql 初始化，避免两个机制重复建表。

account 的核心约束：唯一登录名、4～32 位小写 ASCII 字母/数字/下划线、三种角色、ACTIVE/DISABLED 状态、非负 version、非空昵称及摘要。username/role/status 使用明确区分大小写的 ASCII 列；昵称支持 utf8mb4。password_hash 只留摘要字段，目前没有创建演示业务账号。

auth_session 的核心约束：唯一 jti、引用真实 account 的外键、账号删除受 RESTRICT 限制、会话期限晚于创建时间、撤销时间不早于创建时间。提供 account_id 查询索引和 expires_at 清理索引。

DATETIME(3) 按约定保存 UTC，不自带时区。JDBC 使用 connectionTimeZone=UTC / forceConnectionTimeZoneToSession=true，连接池还显式设置会话 time_zone = '+00:00'，使 CURRENT_TIMESTAMP 与应用时钟约定一致。

DatabaseMigrationConfiguration 在执行迁移前校验 DB_NAME 和实际连接库名，避免连接覆盖配置将迁移写入其他库。Flyway 开启校验与命名验证，关闭 baseline-on-migrate 和 clean，既有非空库没有迁移历史时会停止，不自动接管或清空。

迁移成功后，不修改已执行 V1 的内容；结构变化新建 V2 等迁移。MySQL DDL 存在隐式提交，失败后须检查实际表与历史状态，再制定修复，不能盲目 repair 或删除表。

## 6. 独立验证方式

```powershell
# 需要本机 mysql / mysqld 和 Java 21、Maven
.\scripts\test-mysql.ps1
```

该脚本在 .cache/mysql-it/<随机目录> 初始化独立实例，使用随机回环端口，禁用 MySQL X 端口，并生成仅供本次验证的随机账号密码。它忽略现有服务的配置文件，不读取 root 密码，不接触现有服务的数据目录。

临时实例的无密码 root 仅用于最初配置此一次性夹具，不是正式部署方案。验证以有密码、库内权限受限的 campuslife 账号执行。结束停止自己启动的进程，成功后删除已核验位于夹具根目录下的随机目录；失败保留诊断资料。无论成功与否均不停止 MySQL80 服务。

验收包括：迁移与历史表、重复迁移零新增、中文/emoji、登录名唯一、非法角色和登录名拒绝、会话外键、jti 唯一、禁止删除被引用账号、UTC 毫秒精度、撤销字段和期限约束。约束拒绝按 MySQL 实际错误码与约束名断言，不能仅靠 Spring 异常包装类型判断。成功后还以 mysql profile 启动 jar，检查 HTTP 健康与测试入口隔离。

脚本源码、POM 和文档检查不等于数据库测试通过。实际状态以 Maven 的 surefire/failsafe 报告和本节后续记录为准。

## 7. 本轮验证记录

2026-10-13 已执行：

| 验证 | 结果 |
|---|---|
| Java 21 + Boot 4.1.1 构建 | Maven verify（mysql-check）成功 |
| WebFoundationTest | 13 tests，0 failures，0 errors，0 skipped |
| MysqlIntegrationIT | 11 tests，0 failures，0 errors，0 skipped，实际 MySQL 8.0.45 |
| Flyway V1 | 创建两张表与历史记录；重复 migrate 为零新增 |
| MySQL 模式 jar | 连接临时实例后正常启动，健康/trace/404 与测试入口隔离检查通过 |
| 资源退出 | SQL SHUTDOWN 停止 Windows 工作子进程，随机夹具目录清理完成，MySQL80 原服务保持运行 |

实际解析到 Connector/J 9.7.0、Flyway 12.4.0，版本由 Boot BOM 管理，并在上述组合上验证。没有用 mock 或内存数据库替代约束验证。

初次验证修正了客户端提示混入 SELECT 1 输出的探测误判、CHECK 异常包装及外键错误码兼容断言，以及 Windows mysqld 工作子进程的退出清理。未降低约束，未放宽应用账号权限来使测试通过。

用户截图暴露 Windows PowerShell 5.1 的版本检测兼容问题后，开发与冒烟脚本改用 java --version；隔离验证脚本按原生退出码处理 stderr 提示，并保留初始化进程句柄以读取退出码。已分别在 Windows PowerShell 5.1 和 PowerShell 7 执行隔离验证，每次 13 个 Web 测试、11 个 MySQL 测试与 mysql 模式 jar 检查全部通过，临时实例正常退出并清理。

随后用户执行 Run 时出现 Maven NoSuchElementException。已复现，异常堆栈指向 Maven 启动目标解析：dev.ps1 的 switch 输出在单参数 Run 分支中变为字符串，原生命令 splatting 将其拆为字符。现在用外层 @() 保证所有分支结果为数组。已在 Windows PowerShell 5.1 和 PowerShell 7 实际执行 dev.ps1 -Action Run，以 web-only 模式、随机端口启动，健康接口均返回 200 / OK / UP，traceId 与响应头一致，并停止本次验证进程。此验证覆盖 Maven 开发启动路径；不表示已经使用用户密码完成正式库应用启动。

报告分别位于 target/surefire-reports 与 target/failsafe-reports；mysql 模式冒烟日志位于 .cache/smoke.*.log。密码没有写入这些报告或日志。

用户已反馈正式库的 Check 返回 BUILD SUCCESS、Run 启动正常。agent 对运行中的健康接口只读检查得到 OK / UP。正式库启动与验收的执行记录来自用户反馈，与 agent 执行的隔离验证分开记录；agent 没有使用你的 root 或项目账号密码。

## 8. 常见失败

- Java 版本行触发 NativeCommandError：旧脚本使用 java -version，将正常版本信息写入 stderr；Windows PowerShell 5.1 在 Stop 错误策略下会中断。已改用向 stdout 输出的 java --version 并检查退出码。更新脚本后重试 database.ps1 -Action Check，已完成的 setup 不需重复执行。
- Run 在 Scanning for projects 后触发 NoSuchElementException：旧版 dev.ps1 将单个启动目标作为字符串展开，已修正为数组。重新执行 database.ps1 -Action Run，无需重建数据库。
- 健康接口返回 OK，但 message 显示以 æ 开头的乱码：Windows PowerShell 5.1 对未声明 charset 的 JSON 没有按 UTF-8 解码。成功与异常响应现已明确声明 UTF-8；在运行终端按 Ctrl+C，再执行 database.ps1 -Action Run 加载修复。查询时可加 ConvertTo-Json -Depth 5 展开 data；格式化本身不会修复乱码。
- 1045：账号、密码或允许连接的 host 不匹配，确认使用专用账号密码和 127.0.0.1。
- 1007 / 1396：库或账号已存在，检查部分初始化结果后再处理，不自动覆盖。
- 1819：新账号密码不符合本机策略，在本机重新选择密码。
- 连接拒绝：确认服务、监听端口和 DB_PORT，不能从客户端版本推断服务监听端口。
- 非空 schema / 校验和冲突：检查实际 Flyway 历史和已有表，不打开 baseline 或 clean 来绕过。
- Codex 受限终端的网络错误：真实 HTTP/MySQL 验证需要本机回环网络权限；不要修改 SQL 去绕过执行环境限制。

参考：[Spring Boot 数据库初始化](https://docs.spring.io/spring-boot/how-to/data-initialization.html)、[MySQL CHECK 约束](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html)、[Connector/J 时间处理](https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html)、[Java 21 命令选项（--version / -version 输出流）](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html#standard-options-for-java)。
