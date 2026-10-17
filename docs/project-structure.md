# Java 包结构与阅读顺序

2026-10-15：按“业务模块 → 模块内职责”整理。auth、common、config、system 保留原职责，认证模块内部增加分层；SQL 沿用 MyBatis-Plus 和 MyBatis 注解。

## 当前实际结构

下面路径相对 src/main/java/com/campuslife：

```text
com.campuslife/
├── CampusLifeApplication.java
├── auth/
│   ├── controller/
│   │   ├── AuthController.java
│   │   └── MeController.java
│   ├── service/
│   │   ├── RegistrationService.java
│   │   ├── RegistrationPolicy.java
│   │   ├── LoginService.java
│   │   ├── SessionService.java
│   │   ├── PasswordChangeService.java
│   │   ├── PasswordChangeTransactions.java
│   │   └── ProfileUpdateService.java
│   ├── dto/
│   │   ├── RegisterRequest.java
│   │   ├── LoginRequest.java
│   │   ├── PasswordChangeRequest.java
│   │   └── ProfileUpdateRequest.java
│   ├── vo/
│   │   ├── AccountView.java
│   │   └── LoginView.java
│   ├── entity/
│   │   └── AccountEntity.java
│   ├── mapper/
│   │   ├── AccountMapper.java
│   │   ├── AuthSessionMapper.java
│   │   └── model/
│   │       ├── SessionAccount.java
│   │       └── AuthSessionState.java
│   ├── security/
│   │   ├── TokenService.java
│   │   ├── AuthPrincipal.java
│   │   ├── SessionAuthenticationFilter.java
│   │   └── AuthHttpErrors.java
│   └── config/
│       ├── ApiSecurityConfiguration.java
│       └── RegistrationConfiguration.java
├── common/
│   ├── api/ApiResponse.java
│   ├── exception/
│   │   ├── BusinessException.java
│   │   └── GlobalExceptionHandler.java
│   └── web/
│       ├── TraceContext.java
│       └── TraceIdFilter.java
├── config/
│   ├── TimeConfiguration.java
│   ├── DatabaseMigrationConfiguration.java
│   └── OpenApiConfiguration.java
└── system/
    └── controller/HealthController.java
```

共 36 个生产 Java 文件。本次移动其中 27 个文件，测试仍在 src/test/java/com/campuslife，通过更新 import 跟随新包。

## 各层负责什么

| 包 | 责任 | 典型内容 |
|---|---|---|
| auth.controller | HTTP 路径、请求接收、响应返回 | 注册、登录、本人资料入口 |
| auth.service | 业务规则、流程与事务 | 密码校验、版本冲突、会话创建和撤销 |
| auth.dto | 客户端提交的数据 | LoginRequest、ProfileUpdateRequest |
| auth.vo | 返回给客户端的数据 | AccountView、LoginView |
| auth.entity | 数据表映射实体 | AccountEntity 对应 account，用于 MyBatis-Plus |
| auth.mapper | 访问数据库 | BaseMapper 常规 CRUD、自定义注解 SQL |
| auth.mapper.model | 数据库查询结果的内部载体 | 会话与账号联查、会话状态记录，不直接作为接口返回 |
| auth.security | 登录凭证与请求鉴权 | JWT、当前身份、过滤器、认证错误输出 |
| auth.config | 认证模块专用组件装配 | 安全链、密码编码器、Mapper 扫描 |
| common | 跨业务模块复用的基础代码 | 响应结构、异常、请求追踪 |
| 顶层 config | 应用级配置 | 时钟、数据库迁移、接口文档 |
| system.controller | 系统接口 | 健康检查 |

DTO、VO、Entity、数据库查询结果分开，避免把数据库实体直接返回给客户端。当前 Service 使用具体类，没有为每个单一实现额外建立接口与 impl 包。

后续商家、订单等模块沿用同样的组织方式，等有实际文件时再建立对应目录。

## 以修改昵称为例阅读代码

1. [MeController](../src/main/java/com/campuslife/auth/controller/MeController.java) 接收 PATCH /api/v1/me。
2. [ProfileUpdateRequest](../src/main/java/com/campuslife/auth/dto/ProfileUpdateRequest.java) 表达 nickname、expectedVersion。
3. [ProfileUpdateService](../src/main/java/com/campuslife/auth/service/ProfileUpdateService.java) 校验参数，在事务内复核身份和版本。
4. [AccountMapper](../src/main/java/com/campuslife/auth/mapper/AccountMapper.java) 与 [AuthSessionMapper](../src/main/java/com/campuslife/auth/mapper/AuthSessionMapper.java) 查询或更新数据库。
5. [AccountView](../src/main/java/com/campuslife/auth/vo/AccountView.java) 表达对外资料，[ApiResponse](../src/main/java/com/campuslife/common/api/ApiResponse.java) 提供统一响应外层。

请求进入 Controller 前，还会经过 [SessionAuthenticationFilter](../src/main/java/com/campuslife/auth/security/SessionAuthenticationFilter.java) 校验登录凭证。

## Mapper 与 SQL 的位置

AccountMapper 继承 BaseMapper<AccountEntity>，常规 insert、selectById 等由 MyBatis-Plus 提供；需要明确条件、行锁或专用更新的 SQL 继续放在 @Select、@Update、@Insert 等 MyBatis 注解中。AuthSessionMapper 使用自定义注解 SQL。

本次没有新增 Mapper XML，没有更改现有 SQL、业务规则或数据库迁移。Mapper 扫描更新为 com.campuslife.auth.mapper，Spring 组件扫描仍从 CampusLifeApplication 的 com.campuslife 根包开始。OpenAPI 扫描的 auth / system 根包会继续包含这些子包。

重启旧应用时继续使用原启动方式，浏览器和 Apifox 的接口地址无需修改。目录整理属于代码组织调整，不需要重新建库或重新注册账号。

## 本轮验证

2026-10-15 已执行 Maven clean 后的完整 Java 21 构建、独立 MySQL 8.0.45 验证及 mysql 模式 jar 启动检查。140 个用例全部通过，0 failures / errors / skipped，涵盖注册、JWT、认证会话、改密、昵称并发和回滚、文档与扫描边界。

对整理前后的 45 个生产/测试源文件进行对比，除 package、import 与 MapperScan 路径外，代码正文一致。重新导出的 OpenAPI 与原运行快照解析后完全一致，完整 61 接口设计稿校验通过。包内含全部 27 个移动后的类，没有旧 auth 根包、auth.persistence 或旧健康 Controller 的残留编译文件。

验证使用独立临时数据库，未访问用户正式库；测试实例和目录结束后清理。详细测试报告在 target/surefire-reports 与 target/failsafe-reports。
