# 学生注册：实现与验收

日期：2026-10-14。已实现 POST /api/v1/auth/register，运行于 mysql 模式。

人工验收推荐 [Swagger / Apifox 点击指南](api-testing.md)，在浏览器填写注册 JSON 并发送；下文命令作为可选参考。

## 请求与返回

请求字段为 username、password、nickname。客户端不能传 role、status、id、version；这些未知字段返回 400 / VALIDATION_FAILED。

- username：去首尾空白、转小写，4～32 位 ASCII 字母、数字及下划线。
- nickname：去首尾空白，1～40 个 Unicode 码点，支持中文和 emoji。
- password：至少 8 个 Unicode 码点、UTF-8 不超过 72 字节；保留空白、大小写和原始内容，不截断。

成功为 HTTP 201：

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "101",
    "username": "demo_student_01",
    "nickname": "星海同学",
    "role": "STUDENT",
    "status": "ACTIVE",
    "version": "0"
  },
  "traceId": "由服务端生成的 UUID"
}
```

响应不含 password、passwordHash 或 accessToken，不创建登录会话。归一化后用户名重复返回 409 / USERNAME_EXISTS，原账号的昵称和摘要保持不变；非法或缺失字段返回 400 / VALIDATION_FAILED。成功与失败响应都使用 UTF-8、no-store 和 traceId。不支持的 Accept / Content-Type 返回 406 / 415，不创建账号。

## 在本机尝试

先停止旧应用，再启动新代码；数据库已经创建，不需重复 setup。

```powershell
.\scripts\database.ps1 -Action Run
```

另开终端执行以下示例。示例密码仅用于虚构测试账号，不是 MySQL 账号密码，不要换成真实账号使用的密码。

```powershell
$registrationBody = @{
    username = 'demo_student_01'
    password = 'DemoPass_123!'
    nickname = '星海同学'
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
    -Uri 'http://127.0.0.1:8080/api/v1/auth/register' `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([Text.Encoding]::UTF8.GetBytes($registrationBody)) |
    ConvertTo-Json -Depth 5
```

首次应返回学生账号；用同一请求再执行会收到 HTTP 409，PowerShell 报错显示非成功 HTTP 状态属于预期结果。若该演示登录名已有账号，可换一个测试登录名。不要手工删除正式账号或修改 Flyway V1 来重置演示。

## 验证与学习重点

```powershell
# 不需要数据库密码：策略、摘要和 Web 基础测试
.\scripts\dev.ps1 -Action Test

# 独立临时 MySQL，覆盖注册、并发、迁移与 jar 启动
.\scripts\test-mysql.ps1
```

2026-10-14 已执行：共 62 个用例全部通过，其中 RegistrationMysqlIT 为 26 个真实 HTTP + MySQL 用例。测试覆盖账号落库、随机盐、密码内容保留、72 字节边界、40 个 emoji 昵称、未知字段拒绝、重复用户名、并发仲裁、响应字段隔离、不自动登录及 406/415 无副作用。

关键实现位置：RegisterRequest → AuthController → RegistrationService → AccountMapper → account；RegistrationPolicy 负责归一化与输入校验，RegistrationConfiguration 配置密码编码器。实体不直接作为响应 DTO。

HTTP 请求在服务端线程提交，不能靠测试线程的 @Transactional 回滚。注册测试使用随机 rg_ 前缀登录名，并先确认该登录名不存在，结束仅清理本用例生成的测试账号；独立实例验收通过后整个夹具删除。database.ps1 -Action Check 也会执行这些 HTTP 注册用例，因此会暂时写入并清理自己的测试账号，不删除原有账号。已有 SQL 约束测试仍使用事务回滚；自增序号可能正常增长。

注册阶段未执行用户正式库中的注册请求，没有用用户密码连接数据库。web-only 不提供注册入口。2026-10-15 已实现登录、JWT 和退出，见 [认证验收](authentication.md)；商家/管理员创建和公开部署限流仍未实现。
