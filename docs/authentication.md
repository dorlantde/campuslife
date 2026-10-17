# 登录、会话与 JWT：实现和验收

日期：2026-10-15。已实现登录、读取本人资料和退出；本页示例仅用于本机虚构账号。

人工验收推荐 [Swagger / Apifox 点击指南](api-testing.md)，支持浏览器填写 JSON、登录授权与发送请求；下文命令可选。

## 当前行为

| 接口 | 行为 |
|---|---|
| POST /api/v1/auth/login | username、password；成功 200，data 含 accessToken、tokenType=Bearer、expiresAt、account |
| GET /api/v1/me | Bearer token；返回当前数据库中的本人 AccountView |
| PATCH /api/v1/me | nickname、expectedVersion；修改本人昵称并检查版本，返回最新 AccountView |
| POST /api/v1/auth/logout | Bearer token；撤销当前会话，200，data=null |
| POST /api/v1/me/password | 当前密码、新密码；原子更新摘要并撤销本人全部会话，200，data=null |

用户已确认：有效期 2 小时，不做 refresh token；允许多端登录，每次建立独立会话。退出只撤销当前会话；同一未过期凭证重复退出返回成功。停用账号不能登录，已有凭证也不能访问。

改密已在后续任务实现，成功后当前及其他设备的会话均撤销，需要重新登录；本机双端验收与事务解释见 [改密指南](password-change.md)。

昵称修改与版本冲突也已实现；成功后保留各设备会话，旧页面不能覆盖新版本，操作见 [资料指南](profile-update.md)。

登录名沿用注册归一化规则，密码不去空白、不改大小写。输入格式不合法为 400；不存在的用户和错误密码统一为 401 / LOGIN_FAILED。停用账号只有密码正确时返回 403 / ACCOUNT_DISABLED，密码错误仍为 LOGIN_FAILED。

| 情况 | HTTP / code |
|---|---|
| 受保护接口未带凭证 | 401 / AUTH_REQUIRED |
| 伪造签名、格式错误、算法或 claims 不符合约定、会话不存在 | 401 / TOKEN_INVALID |
| JWT 或数据库会话到期 | 401 / SESSION_EXPIRED |
| 已撤销会话访问普通接口 | 401 / SESSION_REVOKED |
| 账号停用 | 403 / ACCOUNT_DISABLED |

所有 401 带 WWW-Authenticate: Bearer。成功和失败都保留 UTF-8、no-store、traceId。公开接口如果带了无效 Authorization，同样拒绝，不忽略错误身份。

mysql 模式仅健康、注册、登录可匿名访问，其余路径默认要求登录。因此匿名访问未公开路径先返回 401；带有效身份访问不存在的路径返回统一 404。web-only 是诊断模式，不提供认证业务，仍保留原有诊断响应。当前角色来自数据库并映射到 Spring Security authority，但商家、管理员的业务权限入口尚未实现。

## 在本机尝试

先按 [注册指南](registration.md)创建示例账号 demo_student_01 / DemoPass_123!。这是虚构业务账号，与 MySQL campuslife 账号不同。不要将真实账号密码或 token 粘贴到聊天。

停止旧应用，再启动新代码：

```powershell
.\scripts\database.ps1 -Action Run
```

另开 PowerShell 终端。下面把 token 保存在变量里，仅展示账号资料：

```powershell
$loginBody = @{
    username = 'demo_student_01'
    password = 'DemoPass_123!'
} | ConvertTo-Json

$login = Invoke-RestMethod -Method Post `
    -Uri 'http://127.0.0.1:8080/api/v1/auth/login' `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([Text.Encoding]::UTF8.GetBytes($loginBody))

$login.data.account | ConvertTo-Json -Depth 5
$login.data.expiresAt
$authHeaders = @{ Authorization = 'Bearer ' + $login.data.accessToken }

Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/me' -Headers $authHeaders |
    ConvertTo-Json -Depth 5

Invoke-RestMethod -Method Post 'http://127.0.0.1:8080/api/v1/auth/logout' -Headers $authHeaders |
    ConvertTo-Json -Depth 5

# 同一个未过期 token 重复退出，仍返回 OK。
Invoke-RestMethod -Method Post 'http://127.0.0.1:8080/api/v1/auth/logout' -Headers $authHeaders |
    ConvertTo-Json -Depth 5

# 退出后读取本人资料应为 401；PowerShell 对非成功 HTTP 状态报错属于预期。
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/me' -Headers $authHeaders
```

只支持 Authorization 头，不从查询参数、Cookie 或请求体读取 token。服务端不建立用于认证的 JSESSIONID。

## 密钥与环境

正式 mysql 运行需要 DB_PASSWORD 和 JWT_SECRET_BASE64。database.ps1 仍在本机提示数据库密码；dev.ps1 的 Run / DatabaseCheck 在未提供 JWT_SECRET_BASE64 时，首次生成 32 个随机字节并保存到 .cache/secrets/jwt-secret.base64，再注入子进程环境。后续复用此文件，重启应用不随意更换密钥；外部提供的环境变量优先使用。脚本结束恢复原进程环境。

该文件已被 .cache/ 的 Git 忽略规则覆盖。不要提交、共享或打印内容。删除或更换密钥会使旧签名失效，相当于所有旧 JWT 需要重新登录；损坏文件不会被脚本静默覆盖。VS Code 选择 CampusLife (MySQL) 按 F5 时，通过启动任务将同一密钥提供给本地配置文件，并弹窗输入数据库密码，见 [VS Code 指南](vscode-setup.md)。其他 IDE / 直接 Maven 启动需自行提供 DB_PASSWORD 和 JWT_SECRET_BASE64；普通 .env 不会自动加载。应用缺少或收到无效、过短密钥时停止启动，不使用内置默认密钥。

密钥文件只是本机开发配置；部署时通过环境配置提供足够随机的 Base64 密钥。独立 MySQL 测试使用单次随机密钥，不读取正式开发密钥文件。独立执行 smoke.ps1 -Profile mysql 时，同样需在本机进程环境提供数据库密码与 JWT_SECRET_BASE64。

## 实现取舍

JWT 用 Spring Security JOSE/Nimbus 做 HS256 签名和验签，固定算法、issuer、audience。这里只把 https://campuslife.local 作为 issuer 标识，不请求该地址。claims 含 sub、jti、iat、exp，不含密码或角色；边界为 now 严格小于 expiresAt，未设过期宽限。

auth_session 仅记录会话标识与期限，不保存原始 token。鉴权在验签后读取会话和账号，核对 account_id / jti / expires_at，检查撤销、到期和 ACTIVE，角色及资料来自当前数据库。因而 JWT 不是本项目的纯无状态登录方案；数据库读取是支持即时撤销的明确取舍。退出后的新请求会被拒绝，不承诺取消已完成鉴权的在途请求。

登录先验证 BCrypt，再在短事务中锁定账号，复核摘要及状态并插入会话。避免 BCrypt 长时间占用事务连接，也防止校验后账号状态或密码已变却仍创建会话。未知用户仍执行一次摘要匹配，减少明显的分支耗时差异，不声称达到恒定时间。

安全链只使用头部 Bearer，不使用 Cookie/Basic/表单登录，关闭 CSRF 与 HTTP session 认证。以后若加入 Cookie 认证，需要重新评估 CSRF。签名校验通过后，允许已撤销凭证的唯一例外是未过期的 POST logout；其他接口不能借此复用它。

## 验证

```powershell
# 普通单元和 Web 测试，不需要真实数据库密码
.\scripts\dev.ps1 -Action Test

# 独立临时 MySQL：完整 HTTP、会话、JWT 和包启动验证
.\scripts\test-mysql.ps1
```

JWT 测试使用固定时钟验证到期边界，不等待两小时；HTTP/MySQL 测试使用可控应用时钟及真实会话记录，不用 mock 代替数据库。测试创建随机登录名和自己的会话，按 ID 先删会话再删账号；不修改用户原有账号。Check 也会运行这些 HTTP 用例，自增序号可能正常增长。

2026-10-15 已分别在 PowerShell 7 和 Windows PowerShell 5.1 执行独立 MySQL 全流程，每次 102 个用例全部通过：12 个注册策略、17 个 JWT、13 个 Web、11 个 SQL 约束、26 个注册 HTTP/MySQL、23 个认证 HTTP/MySQL。mysql 模式 jar 启动及访问边界检查通过。另在 Windows PowerShell 5.1 验证本地密钥生成、复用和损坏文件拒绝；测试没有输出密钥值。

报告在 target/surefire-reports 和 target/failsafe-reports；HTTP 测试只进入测试类路径，未打包到应用 jar。

随后改密任务在独立 MySQL 全流程验证通过，当前共 120 个用例：认证 HTTP/MySQL 从 23 增至 41 个，其余数量不变；包含并发改密、全部会话撤销与真实 CHECK 异常回滚。mysql 模式 jar 检查通过，详见 [改密验证](password-change.md)。此次未修改脚本，没有重复两种 Shell 的兼容性全流程。

本轮没有使用用户正式库密码或正式账号登录。过期会话清理、登录限流、商家及管理员权限接口留到后续任务。

再后续的昵称任务新增 ProfileMysqlIT 16 个用例；当前全套为 136 个，已在 Windows PowerShell 5.1 的独立 MySQL 完整流程验证，mysql 模式 jar 检查通过。上文 102 / 120 为各阶段历史记录。

参考：[Spring Security 安全链](https://docs.spring.io/spring-security/reference/servlet/architecture.html)、[JWT 验签支持](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)。
