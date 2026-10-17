# 修改密码：实现、事务与本机验收

日期：2026-10-15。已实现 POST /api/v1/me/password，仅在 mysql 模式提供。用于已登录用户修改自己的密码；不是忘记密码或管理员重置接口。

人工验收推荐 [Swagger / Apifox 点击指南](api-testing.md)，可直接填写当前密码、新密码并发送；下文命令作为可选参考。

## 请求与结果

携带 Authorization: Bearer <accessToken>，JSON 只接受 currentPassword、newPassword，不接受 accountId、username、role 或 status。账号 ID 来自服务端认证身份。

```json
{
  "currentPassword": "DemoPass_123!",
  "newPassword": "NewDemoPass_456!"
}
```

两个密码均沿用注册规则：有效 Unicode 文本，至少 8 个 Unicode 码点、UTF-8 不超过 72 字节，不去空白、不改变大小写、不截断。新密码使用 BCrypt 随机加盐保存摘要。没有新增密码历史或“必须不同于旧密码”的规则；提交相同密码也会重新加盐并撤销会话，此时原明文仍可用于重新登录。

成功返回 HTTP 200 / OK、data=null；同一事务更新 password_hash、account.version + 1，并撤销本人所有尚未撤销的会话，包含当前请求使用的会话。已经撤销的记录保留原撤销时间。不会自动登录，也不会返回新 token。其他账号不受影响。

| 情况 | HTTP / code | 结果 |
|---|---|---|
| 未登录 | 401 / AUTH_REQUIRED | 不修改 |
| 缺字段、null、未知字段、密码格式不合法 | 400 / VALIDATION_FAILED | 不修改 |
| 当前密码不正确 | 400 / CURRENT_PASSWORD_INCORRECT | 原密码、版本和会话保持原状 |
| 当前会话已撤销或到期 | 401 / SESSION_REVOKED、SESSION_EXPIRED | 拒绝改密；事务提交前再次检查 |
| 账号已停用 | 403 / ACCOUNT_DISABLED | 拒绝改密；事务提交前再次检查 |
| 校验后摘要已被其他操作改变 | 409 / ACCOUNT_CREDENTIALS_CHANGED | 拒绝覆盖，重新读取身份并验证 |
| 不支持的 Accept / Content-Type | 406 / NOT_ACCEPTABLE、415 / UNSUPPORTED_MEDIA_TYPE | 不修改 |

成功后旧 token 访问本人资料或再次改密返回 401。原有“未过期、已撤销 token 可重复退出成功”的例外仍仅限 POST /api/v1/auth/logout。已经完成鉴权的普通在途请求不保证被取消；改密写入本身会在事务中复核当前会话。

网络中断导致未收到成功响应时，先尝试以新密码重新登录，避免无条件重复提交旧请求。该接口没有单独的幂等键；成功后原 token 已被撤销。

## 在本机验收

以下只创建虚构业务测试账号，与 MySQL 数据库账号不同。先在原运行终端按 Ctrl+C 停止旧应用，然后加载新代码：

```powershell
.\scripts\database.ps1 -Action Run
```

另开 PowerShell 终端，执行下面三组操作。随机用户名便于重复验收；演示密码仅用于这个本机虚构账号，token 只留在变量里。

第一组：创建账号，并模拟两个设备登录。

```powershell
$baseUrl = 'http://127.0.0.1:8080'
$demoUsername = 'demo_pwd_' + [guid]::NewGuid().ToString('N').Substring(0, 12)

function Invoke-DemoPost {
    param([string]$Path, [hashtable]$Payload, [hashtable]$Headers = @{})
    $json = $Payload | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri ($baseUrl + $Path) -Headers $Headers `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($json))
}

$registration = Invoke-DemoPost '/api/v1/auth/register' @{
    username = $demoUsername; password = 'DemoPass_123!'; nickname = '改密验收同学'
}
$registration.data | ConvertTo-Json -Depth 5

$firstLogin = Invoke-DemoPost '/api/v1/auth/login' @{
    username = $demoUsername; password = 'DemoPass_123!'
}
$secondLogin = Invoke-DemoPost '/api/v1/auth/login' @{
    username = $demoUsername; password = 'DemoPass_123!'
}
$firstHeaders = @{ Authorization = 'Bearer ' + $firstLogin.data.accessToken }
$secondHeaders = @{ Authorization = 'Bearer ' + $secondLogin.data.accessToken }
```

第二组：用第一个设备改密。应得到 OK、data=null。

```powershell
Invoke-DemoPost '/api/v1/me/password' @{
    currentPassword = 'DemoPass_123!'; newPassword = 'NewDemoPass_456!'
} $firstHeaders | ConvertTo-Json -Depth 5
```

第三组：分别执行以下三个请求，应全部返回 HTTP 401。PowerShell 对非成功 HTTP 状态显示错误是预期结果，前两个错误码是 SESSION_REVOKED，第三个是 LOGIN_FAILED。

```powershell
Invoke-RestMethod ($baseUrl + '/api/v1/me') -Headers $firstHeaders
Invoke-RestMethod ($baseUrl + '/api/v1/me') -Headers $secondHeaders
Invoke-DemoPost '/api/v1/auth/login' @{
    username = $demoUsername; password = 'DemoPass_123!'
}
```

最后用新密码登录，应成功，读取本人资料时 version 为字符串 "1"，昵称与角色不变。

```powershell
$freshLogin = Invoke-DemoPost '/api/v1/auth/login' @{
    username = $demoUsername; password = 'NewDemoPass_456!'
}
$freshHeaders = @{ Authorization = 'Bearer ' + $freshLogin.data.accessToken }
Invoke-RestMethod ($baseUrl + '/api/v1/me') -Headers $freshHeaders |
    ConvertTo-Json -Depth 5
```

不要把完整登录响应或 token 发到聊天。示例账号留在本机库中，自动测试使用的临时账号则会自行清理。

## 为什么需要事务与锁

只先验证旧密码、再直接写新摘要存在竞争：两个请求都可能通过旧密码校验，后写入者覆盖先写入者；另一个登录请求也可能拿着已验证的旧摘要，在改密后创建会话。

本次把 BCrypt 匹配和新摘要计算放在事务外，减少持有连接和行锁的时间。随后通过独立的 PasswordChangeTransactions bean 进入 Spring 事务，避免同类内部调用绕过事务代理：

1. SELECT account FOR UPDATE，与登录创建会话使用相同的首个账号锁。
2. 锁定当前账号与 jti 对应的会话，复核 ACTIVE、当前会话有效且未到期，以及摘要仍与刚才验证的一致。
3. 更新摘要、递增版本、批量撤销本账号未撤销会话，一起提交；异常则一起回滚。

先提交的登录会话会被后续改密撤销；后提交的旧密码登录会在锁内发现摘要已变而失败。并发改密只有一个成功：另一个可能在外层读到新摘要而返回当前密码不正确，也可能在事务内读到已撤销会话而返回 401。不会覆盖第一次成功结果。

锁顺序固定为账号再会话。退出只更新自己的会话，不再反向取得账号锁。已有两张表与索引足够，本轮没有修改已执行的 Flyway V1 或增加基础设施。决策见 [ADR-0006](decisions/0006-password-change.md)。

## 实际验证

```powershell
.\scripts\test-mysql.ps1
```

2026-10-15：Java 21 + MySQL 8.0.45 独立临时实例上，120 个用例全部通过，0 failures / errors / skipped；其中 AuthenticationMysqlIT 从 23 增至 41 个，包含改密 HTTP、Unicode/字节边界、两个设备撤销、其他账号隔离、相同密码、并发改密、锁内状态复核及旧摘要登录拒绝。

回滚用例在同一个真实数据库事务中先执行完整改密，再主动触发 account.version 的 MySQL CHECK 失败，断言旧摘要、version 与两个原会话全部恢复。没有用 mock 代替回滚或并发验证。mysql 模式可执行 jar 启动检查通过；临时实例与目录已清理，未访问用户正式库。

报告见 target/surefire-reports 与 target/failsafe-reports。限流、过期会话清理、忘记密码和管理员重置不在本次实现范围。
