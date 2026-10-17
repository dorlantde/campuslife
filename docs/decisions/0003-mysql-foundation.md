# ADR-0003：MySQL 与账号、会话迁移

- 日期：2026-10-13
- 状态：代码与隔离验证已完成；用户反馈正式本机库 Check 返回 BUILD SUCCESS、Run 启动正常，健康接口已只读确认 OK / UP
- 范围：JDBC 数据源、Flyway V1、account/auth_session 两表和真实 MySQL 验收

使用 Boot BOM 管理 JDBC、连接池、Connector/J 和 Flyway；MySQL 增加 flyway-mysql 模块。当前尚无业务 Mapper，MyBatis-Plus 留到认证持久层实现时引入，避免添加未使用的框架。本轮不引入 H2 代替 MySQL 验证约束。

默认 mysql 模式必须具备密码与有效数据源，Flyway 失败即启动失败。显式 web-only 模式继续服务 Web 基础测试，不被视为数据库就绪。普通构建运行 Web 测试；-Pmysql-check 才启用必须真实连接成功的数据库验收，不将缺密码跳过包装成通过。

用户本机已有 MySQL 8.0.45 客户端和运行中的 MySQL80 服务。迁移兼容 8.0.16+，新部署仍推荐 8.4。隔离测试使用已安装的 mysqld 创建一次性随机端口实例，不升级或重配既有服务。

本地建库由用户运行交互脚本，输入已有 root 密码与新专用账号密码。应用密码只来自进程环境或 IDE 运行配置；不写库密码模板、不传命令行、不读取用户密码文件。专用账号仅有项目库权限，未来再拆分部署迁移角色。

Flyway 是唯一结构初始化机制。不开启 baseline-on-migrate / clean，不覆写既有库；迁移前核对项目库名。首次迁移仅两张业务表，其余 V1 表按模块逐步加入。

操作与验证见 [MySQL 接入](../mysql-setup.md)。

验收：Java 21 下 13 个 Web 测试、11 个实际 MySQL 8.0.45 测试全部通过；MySQL 模式 jar 启动和临时实例完整清理通过。实际 Boot BOM 使用 Connector/J 9.7.0 与 Flyway 12.4.0，未为通过测试放宽账号权限或 SQL 约束。
