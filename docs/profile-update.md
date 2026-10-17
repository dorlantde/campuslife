# 修改昵称与版本冲突：实现和验收

日期：2026-10-15。已实现 PATCH /api/v1/me，仅在 mysql 模式提供，用于已登录用户修改本人昵称。

**推荐人工验收入口：**[Swagger / Apifox 点击指南](api-testing.md)，从注册、登录到昵称冲突逐步操作，无需编写 PowerShell 请求。下文命令作为可选参考。

## 请求与结果

先 GET /api/v1/me，原样保存 data.version；修改时把它作为 expectedVersion 提交。版本是十进制字符串，不要转成 JavaScript Number。

```json
{
  "nickname": "星海新昵称😀",
  "expectedVersion": "0"
}
```

nickname 沿用已确认的注册规则：去除首尾空白后为 1～40 个 Unicode 码点，支持中文和 emoji。只允许修改本人昵称；不接受 id、accountId、username、password、role、status 或 version 等额外字段。账号 ID 来自服务端认证身份。

expectedVersion 必须提供，格式为规范的非负十进制整数字符串，范围 0～9223372036854775807；拒绝 JSON 数字、null、负数、首尾空白、加号、前导零（单个 "0" 除外）和溢出值。实际使用时直接回传 GET 返回的字符串即可。

| 请求情况 | HTTP / code | 结果 |
|---|---|---|
| 新昵称合法、版本匹配 | 200 / OK | 昵称变更，version 加 1，data 返回最新 AccountView |
| 未传 nickname | 200 / OK | 版本匹配时返回当前资料，不修改、不增加版本 |
| 归一化后的昵称与当前相同 | 200 / OK | 版本匹配时不修改、不增加版本 |
| 版本与当前不一致 | 409 / CONFIG_VERSION_CONFLICT | 拒绝覆盖，即使昵称相同或未传也检查版本 |
| nickname 为 null、非字符串、空白或超长；版本格式错误、未知字段 | 400 / VALIDATION_FAILED | 不修改 |
| 未登录、会话已撤销或过期 | 401 | 不修改 |
| 账号停用 | 403 / ACCOUNT_DISABLED | 不修改 |

账号版本达到 BIGINT 上限时，实际变更返回 409，不允许溢出；不变更的请求仍可读取当前资料。版本冲突后应重新 GET，展示最新昵称，由用户决定是否继续修改，再携带新版本提交；不要自动循环重试覆盖。

修改昵称保留本人各设备登录状态，后续 GET /me 会读取最新昵称。密码、用户名、角色和状态不变，其他账号不受影响。改密也会增加同一账号的版本，因此改密前的旧资料版本不能在重新登录后直接使用。

## 在本机验收

可以直接从本页开始，不必先执行注册、登录或改密指南；下方代码会自行创建测试账号并登录。

复制时只选代码块内部的命令，不要包含开头的三个反引号和 powershell 标记、结尾的三个反引号，也不要复制终端的 PS 或 >> 提示符。若已显示本人资料（含 id、nickname、version），随后仅对反引号报 CommandNotFoundException，说明前面的请求已成功，可在同一终端继续下一组，不必重建账号。

先停止旧应用，加载新代码：

```powershell
.\scripts\database.ps1 -Action Run
```

另开 PowerShell。下面创建一个随机命名的虚构业务账号，方便重复尝试。它与 MySQL campuslife 账号不同；登录 token 只保留在变量里。

```powershell
$baseUrl = 'http://127.0.0.1:8080'
$demoUsername = 'demo_profile_' + [guid]::NewGuid().ToString('N').Substring(0, 12)

function Invoke-ProfileDemoJson {
    param([string]$Method, [string]$Path, [hashtable]$Payload, [hashtable]$Headers = @{})
    $json = $Payload | ConvertTo-Json
    Invoke-RestMethod -Method $Method -Uri ($baseUrl + $Path) -Headers $Headers `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($json))
}

$registration = Invoke-ProfileDemoJson 'Post' '/api/v1/auth/register' @{
    username = $demoUsername; password = 'DemoPass_123!'; nickname = '资料验收同学'
}
$login = Invoke-ProfileDemoJson 'Post' '/api/v1/auth/login' @{
    username = $demoUsername; password = 'DemoPass_123!'
}
$authHeaders = @{ Authorization = 'Bearer ' + $login.data.accessToken }
$before = Invoke-RestMethod ($baseUrl + '/api/v1/me') -Headers $authHeaders
$before.data | ConvertTo-Json -Depth 5
```

模拟两个页面都读取到了初始版本：

```powershell
$pageAVersion = $before.data.version
$pageBVersion = $before.data.version

# 页面 A 保存成功：昵称去掉首尾空白，version 从字符串 "0" 变为 "1"。
$updated = Invoke-ProfileDemoJson 'Patch' '/api/v1/me' @{
    nickname = '  星海新昵称😀  '; expectedVersion = $pageAVersion
} $authHeaders
$updated.data | ConvertTo-Json -Depth 5

# 页面 B 还拿着旧版本，应得到 HTTP 409 / CONFIG_VERSION_CONFLICT。
# PowerShell 对非成功 HTTP 状态显示错误属于预期。
Invoke-ProfileDemoJson 'Patch' '/api/v1/me' @{
    nickname = '旧页面覆盖'; expectedVersion = $pageBVersion
} $authHeaders
```

重新读取应仍为“星海新昵称😀”；用最新版本再修改可成功。读取和修改沿用同一个 token，不需要重新登录：

```powershell
$current = Invoke-RestMethod ($baseUrl + '/api/v1/me') -Headers $authHeaders
$current.data | ConvertTo-Json -Depth 5

Invoke-ProfileDemoJson 'Patch' '/api/v1/me' @{
    nickname = '重新读取后修改'; expectedVersion = $current.data.version
} $authHeaders | ConvertTo-Json -Depth 5
```

不要将完整登录响应或 token 发到聊天。示例账号留在本机库中；自动测试使用的账号会自行清理。

## 版本与行锁各解决什么

版本检查保护的是用户读取到的旧页面：两个页面都拿着 "0"，第一次修改把版本增加到 "1" 后，第二个页面必须重新读取，不能直接覆盖。只使用行锁会把请求排成先后执行，仍可能允许旧页面覆盖新昵称；所以必须核对 expectedVersion。

本接口使用短事务，按既有顺序先锁 account、再锁当前会话，复核账号 ACTIVE、会话未撤销且未到期，再比对版本。实际 SQL 也包含 id、version 与 ACTIVE 条件，只更新 nickname 并递增版本；影响行数必须为 1。返回值来自锁内当前账号，避免响应混入另一个并发写入的结果。

账号锁还用于与登录、改密协调；同账号的写入提交串行，BCrypt 计算不在本接口事务中。事务失败时昵称和版本一起回滚。未引入 MyBatis-Plus 乐观锁插件或新表，Flyway V1 保持原样。决策见 [ADR-0007](decisions/0007-profile-update.md)。

## 验证方式

```powershell
.\scripts\test-mysql.ps1
```

ProfileMysqlIT 使用真实 HTTP 和独立 MySQL，覆盖昵称边界、严格字段类型、无变化请求、旧版本冲突、双请求竞争、大于 JavaScript 安全整数的版本、版本上限、本人范围、双端登录保持、锁内身份复核和真实 CHECK 异常后的事务回滚。

执行结果以 target/surefire-reports 与 target/failsafe-reports 为准；本机手动示例与自动化数据库验证分开记录。

2026-10-15 已在 Windows PowerShell 5.1、Java 21、独立 MySQL 8.0.45 执行完整流程：136 个用例全部通过，0 failures / errors / skipped。其中新增 ProfileMysqlIT 16 个，原 120 个回归用例全部通过；mysql 模式 jar 启动与访问边界检查通过。真实回滚用例先调用完整资料更新，再在同一事务触发 MySQL version CHECK 失败，确认昵称与版本一起恢复；没有使用 mock 替代。验证未使用用户正式库密码或数据。
