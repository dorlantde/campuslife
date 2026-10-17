# ADR-0005：登录、可撤销会话与 JWT

- 日期：2026-10-15（Asia/Shanghai）
- 状态：用户已确认 V1 会话规则；实现和 PowerShell 5.1 / 7 独立 MySQL 验证通过
- 范围：login、me、logout，沿用现有 account / auth_session，不修改 Flyway V1

用户确认 JWT 有效期 2 小时，不提供 refresh token；允许多端登录，每次建立独立会话；退出只撤销当前会话，未过期凭证重复退出成功；账号停用时拒绝登录及已有凭证访问。

采用 Spring Security 7.1.1 安全链与 JOSE/Nimbus 组件，版本跟随 Boot BOM。固定 HS256，使用至少 32 字节随机密钥，校验 issuer、audience、主体 ID、UUID jti、签发时间及期限。自有时钟验证到期且无宽限；不从 JWT 读取角色权限。

选择 JWT + MySQL 会话：JWT 负责可验证凭证，数据库负责撤销、期限匹配及当前账号状态。每次请求查询数据库，是即时撤销的成本，不依赖 Redis。没有使用完整 OAuth 授权服务器，也不引入远程 JWK 服务。

BCrypt 在事务外验证；成功后在短事务内 SELECT account FOR UPDATE，再核对摘要与 ACTIVE 并插入 auth_session。密码或状态在校验后改变时拒绝创建会话。会话不保存原始 token。logout 使用原子 COALESCE 更新保留第一次撤销时间；重复退出例外仅限精确的 POST /api/v1/auth/logout。

安全链默认保护所有未公开路径；健康、注册、登录允许匿名。带无效身份访问公开接口仍拒绝。匿名未公开路径为 401，认证后的未知路径为 404。使用独立 JSON 错误写出器，保持 UTF-8、traceId、no-store 和 WWW-Authenticate；不返回库异常或原始 JWT。

本机脚本将随机密钥保存在 Git 忽略的 .cache/secrets 并注入进程环境，外部环境优先；脚本退出还原环境。测试使用临时密钥，不复用开发密钥；应用不提供固定默认密钥。重启复用本地密钥，而主动更换密钥会使全部旧 JWT 失效。

改密、会话清理、登录限流与具体角色业务权限后续逐项实现。已有 token 检查账号 ACTIVE，但管理员“停用并撤销全部会话”的管理接口尚未实现。

后续更新：改密与本人全部会话撤销已完成，见 [ADR-0006](0006-password-change.md)；本篇下述 102 个用例为登录阶段的验证记录，改密后的最新全套为 120 个。

本机操作与实际验证记录见 [认证验收](../authentication.md)。

两种 Shell 的完整流程每次 102 个用例通过，其中 JWT 17 个、认证 HTTP/MySQL 23 个；已验证精确到期边界、当前会话撤销、多端独立、账号停用、错误凭证在公开入口不被忽略、数据库状态及角色即时读取。此处为正确性验证，不是性能测试。
