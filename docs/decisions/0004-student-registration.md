# ADR-0004：学生注册与密码摘要

- 日期：2026-10-14（Asia/Shanghai）
- 状态：用户已确认规则和 MyBatis-Plus；实现及隔离验证完成
- 范围：POST /api/v1/auth/register，不包含登录、JWT、会话或商家申请

公开注册固定创建 STUDENT / ACTIVE，version 为 0，成功返回 HTTP 201 和 AccountView。请求只接收 username、password、nickname，未知字段拒绝；不自动登录，不创建 auth_session。

用户名去首尾空白、以 Locale.ROOT 转小写，原字符限定 ASCII 字母、数字和下划线，长度 4～32。昵称去首尾空白，长度按 Unicode 码点计为 1～40，与 MySQL utf8mb4 字符计数保持一致。无效 Unicode 文本拒绝。

密码至少 8 个 Unicode 码点，UTF-8 编码不超过 72 字节；不去空白、不改大小写、不做 Unicode 归一化，不静默截断。使用 Spring Security crypto 模块的 BCrypt $2b$，工作因子 12，每次随机加盐；DelegatingPasswordEncoder 保存 {bcrypt} 前缀，便于以后识别摘要算法。因子 12 是当前实现参数，尚未做部署机器的性能标定，不声称达到特定耗时或吞吐。只启用 bcrypt 编码器，不提供明文回退。

按用户确认引入 MyBatis-Plus 3.5.17 的 Boot 4 starter。Mapper、服务和注册 Controller 仅在 mysql profile 中启用；web-only 继续用于 Web 诊断。仅引入密码摘要模块，尚未启用 Spring Security Web 鉴权或 JWT。

注册只有一条账号 INSERT，利用数据库语句原子性；不先查询后插入，不建立覆盖摘要计算的大事务。uk_account_username 唯一索引负责并发仲裁；只有此索引的重复键转为 409 / USERNAME_EXISTS，其他持久层异常保留为内部错误处理。BCrypt 在调用 Mapper 前计算，避免摘要计算占用数据库事务连接。

AccountView 与 AccountEntity 分开，ID 和 version 为十进制字符串。返回值不含原始密码或摘要。RegisterRequest 的 toString 隐藏密码；关闭 MyBatis SQL 参数输出。请求映射声明 consumes / produces，406 和 415 在 Controller 执行前被拒绝，不创建账号。V1 已有字段满足需求，本轮没有修改已执行的迁移或添加新迁移。

验证：独立 MySQL 8.0.45 实例上，12 个策略/密码测试、13 个 Web 测试、11 个 SQL 迁移约束测试、26 个注册 HTTP/MySQL 测试全部通过，0 失败、0 错误、0 跳过。4 个同名并发请求得到 1 个 201、3 个 409，数据库只有 1 行。mysql 模式 jar 启动和 HTTP/UTF-8 检查通过，临时实例及目录清理完成。这里不将正确性用例描述为压测。

操作与测试边界见 [注册验收](../registration.md)。参考：[MyBatis-Plus Boot 4 安装](https://baomidou.com/getting-started/install/)、[Spring Security 密码摘要](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)。
