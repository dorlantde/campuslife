# CampusLife 校园生活服务平台

CampusLife 是面向 Java 后端学习与实习展示的独立项目，场景为虚构的“星海大学”。所有商家、账号、订单、支付和退款均为模拟数据，不代表真实运营或资金交易。

## 当前进度

已建立 Java 21 + Spring Boot 4.1.1 工程，实现 Web 基础、MySQL 数据源、Flyway V1、学生注册、登录、本人资料读取、昵称修改、退出与修改密码。首批表为 account 和 auth_session，使用 MyBatis-Plus、BCrypt 和可撤销 JWT 会话。

Java 代码按“业务模块内分层”组织，Controller、Service、DTO、VO、Entity、Mapper 和认证安全代码分别归类；阅读入口见 [包结构与职责说明](docs/project-structure.md)。SQL 保持 MyBatis-Plus 与注解方式。

2026-10-15：当前 140 个用例全部通过，包含 17 个 JWT、41 个认证/改密、16 个资料与 4 个文档 HTTP/MySQL 用例，覆盖多端会话、改密全部撤销、版本冲突、并发修改、真实数据库回滚及文档鉴权边界。mysql 模式 jar 启动检查通过。验证使用独立 MySQL 8.0.45 实例，没有使用用户正式库密码；订单和具体角色业务权限尚未实现。

## 浏览器 / Apifox 验收（推荐）

已接入 Swagger UI，显示现有 7 个接口及中文说明、JSON 示例和 Bearer 授权。重启应用后打开 [本地接口调试页](http://127.0.0.1:8080/swagger-ui/index.html)，按 [点击验收指南](docs/api-testing.md)完成“注册 → 登录 → 读取资料 → 修改昵称 → 版本冲突”，不必编写 PowerShell 请求。

Apifox 可导入 [OpenAPI JSON 文件](docs/openapi/campuslife.openapi.json)，或读取运行中的 [接口定义](http://127.0.0.1:8080/v3/api-docs)。启动仍沿用下方 database.ps1 的本机密码输入流程。文档只收录已实现接口；业务接口继续要求登录。

要提前查看后续业务全貌，导入 [V1 完整规划文件](docs/openapi/campuslife.v1-design.openapi.json)：**61 个操作，7 已实现、54 待开发，9 个分组**。见 [完整规划导入说明](docs/api-design.md)。待开发接口包含请求、响应和权限，但仍是规则待确认的设计稿，不能直接当作已完成能力。

## 本地运行

需要 Maven 和 JDK 21。脚本优先使用项目 .tools/java21 下的 JDK；也可自行设置 JAVA_HOME 为 JDK 21，不需要更改系统默认 Java。

```powershell
# 测试并打包
.\scripts\dev.ps1 -Action Build

# 首次创建正式项目库和专用账号，由你在本机输入密码；已创建则跳过
.\scripts\setup-mysql.ps1

# 连接正式项目库，执行迁移及数据库验收
.\scripts\database.ps1 -Action Check

# 使用专用账号启动应用，Ctrl+C 停止
.\scripts\database.ps1 -Action Run

# 仅检查 Web 基础，不代表数据库就绪
.\scripts\smoke.ps1

# 隔离验证，不需要你的 root 或项目账号密码
.\scripts\test-mysql.ps1
```

默认 mysql 模式必须具备 DB_PASSWORD 且迁移成功；密码只在本机终端或 IDE 运行配置填写。默认监听 127.0.0.1:8080，健康接口为 GET /api/v1/system/health，仍表示应用 HTTP 存活；web-only 模式不检查数据库，订单功能也未实现。

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/system/health' | ConvertTo-Json -Depth 5
```

建库、密码输入及错误排查见 [MySQL 接入指南](docs/mysql-setup.md)，Web 基础见 [工程基础](docs/engineering-foundation.md)。.tools、.cache 与 target 为本地生成内容，已通过 .gitignore 排除。初次构建需要下载依赖；Codex 受限终端执行真实 HTTP/MySQL 测试时需要允许本机回环网络。

使用 VS Code 时，在“运行和调试”选择 **CampusLife (MySQL)**，按 F5，在弹窗输入 campuslife 数据库专用账号密码；启动任务自动准备本地 JWT 密钥。依赖导入、Java 21 配置和点击流程见 [VS Code 指南](docs/vscode-setup.md)。出现大量 Missing artifact / import 无法识别时，先重新导入 Maven 项目；它与应用启动时填写数据库密码属于两个阶段。

学生注册接口为 POST /api/v1/auth/register；停止旧应用、重新 Run 后可按 [注册验收指南](docs/registration.md)发送测试请求。首次 201，归一化后的重复用户名为 409；只创建学生账号，不自动登录。

登录与退出示例见 [认证验收指南](docs/authentication.md)。JWT 有效期 2 小时，允许多端登录，退出只撤销当前会话。启动脚本自动准备本地签名密钥并注入环境，不需要在聊天中输入或分享密钥。mysql 模式下，未公开路径默认要求登录。

修改密码接口为 POST /api/v1/me/password，成功后撤销本人全部会话并要求重新登录；摘要、账号版本与撤销结果一起提交或回滚。双端验证和本机操作见 [改密验收指南](docs/password-change.md)。

修改昵称接口为 PATCH /api/v1/me，携带 nickname 与 GET /me 读取的版本字符串 expectedVersion；成功返回最新资料，旧页面版本返回 409，登录状态保留。两页冲突示例见 [资料验收指南](docs/profile-update.md)。

## 设计文档

- [Agent 协作约定](AGENTS.md)
- [已确认的业务基线与待定实现细节](docs/decisions/0001-v1-baseline.md)
- [V1 数据库设计](docs/v1-database-design.md)
- [V1 状态迁移、事务与验收](docs/v1-state-transitions.md)
- [V1 接口契约](docs/v1-api-contract.md)
- [工程基础与验证记录](docs/engineering-foundation.md)
- [Java 包结构与阅读顺序](docs/project-structure.md)
- [工程初始化决策](docs/decisions/0002-engineering-foundation.md)
- [MySQL 接入与迁移](docs/mysql-setup.md)
- [MySQL 接入决策](docs/decisions/0003-mysql-foundation.md)
- [注册验收与学习路径](docs/registration.md)
- [注册决策](docs/decisions/0004-student-registration.md)
- [认证验收与密钥配置](docs/authentication.md)
- [登录会话决策](docs/decisions/0005-login-sessions-jwt.md)
- [改密验收与事务说明](docs/password-change.md)
- [改密与全部会话撤销决策](docs/decisions/0006-password-change.md)
- [昵称修改与版本冲突验收](docs/profile-update.md)
- [资料更新决策](docs/decisions/0007-profile-update.md)
- [Swagger / Apifox 点击验收](docs/api-testing.md)
- [本地接口文档决策](docs/decisions/0008-api-documentation.md)
- [V1 完整接口规划与 Apifox 导入](docs/api-design.md)
- [完整接口规划决策](docs/decisions/0009-v1-api-design-catalog.md)

## V1 范围

单校区、三类账号、商家与服务、理发预约、PDF 打印、统一订单、模拟支付与基础退款、订单评价。采用模块化单体；理发和打印分别使用业务扩展表。

优惠券、设施、收藏、完整售后、站内消息、Redis 和 MQ 在后续阶段加入。首批认证与本人资料接口、浏览器调试入口已完成；当前先由用户人工验收，再定稿商家申请规则，推进商家业务。讨论稿中的细节仍需确认。
