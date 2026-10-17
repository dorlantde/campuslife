# 浏览器与 Apifox 接口验收

人工验收推荐使用本页流程，在浏览器填写 JSON 并点击发送。原有 PowerShell 请求示例保留供参考。

## 启动和打开页面

在原运行窗口按 Ctrl+C 停止旧应用，再用原来的方式启动新代码。数据库密码仍只在本机输入：

```powershell
.\scripts\database.ps1 -Action Run
```

启动成功后打开 [Swagger 接口调试页](http://127.0.0.1:8080/swagger-ui/index.html)，也支持 [swagger-ui.html 入口](http://127.0.0.1:8080/swagger-ui.html)。调整过端口时，把链接中的 8080 一并替换。

页面按“系统、账号认证、本人资料”分组，显示已实现的 7 个接口。展开接口，点击 **Try it out**，填写请求，再点击 **Execute**。实际结果看 **Server response** 下的状态和 Response body；下方 Responses 列表是说明与示例。

请求会读写本机数据库中的业务账号。使用下文虚构测试账号即可，平台登录密码与启动时的 MySQL 密码是两回事。

## 注册并登录

打开“01 账号认证 → POST /api/v1/auth/register”，点击 Try it out，填写：

```json
{
  "username": "demo_api_01",
  "password": "DemoPass_123!",
  "nickname": "接口验收同学"
}
```

点击 Execute，首次应为 HTTP 201 / OK。若返回 409 / USERNAME_EXISTS，换一个用户名，或使用你已知密码的现有虚构账号登录。注册不会自动登录。

再打开“POST /api/v1/auth/login”，填写同一账号：

```json
{
  "username": "demo_api_01",
  "password": "DemoPass_123!"
}
```

成功应为 HTTP 200。从实际响应中复制 **data.accessToken 的值**，不含两侧双引号。

点击页面上方 **Authorize**，在 bearerAuth 输入框粘贴 token 本身，点击弹窗中的 Authorize，再 Close。不要自己添加 `Bearer ` 前缀，页面会自动添加。刷新页面后授权不会持久保存，需要重新填写。

注册、登录、健康检查可匿名使用；本人资料、改密、退出需要这个凭证。不要把 token 或完整登录响应发到聊天。

## 读取资料和修改昵称

打开“02 本人资料 → GET /api/v1/me”，点击 Try it out、Execute。记录实际响应 data.version，例如字符串 **"0"**；已有账号可能是其他版本，以刚读取的响应为准。

打开“PATCH /api/v1/me”，把读取到的版本填入 expectedVersion：

```json
{
  "nickname": "星海新昵称😀",
  "expectedVersion": "0"
}
```

版本带双引号，是字符串。点击 Execute，应返回 HTTP 200 / OK，昵称改变，version 从 "0" 增加为 "1"。如果本来就是这个昵称，版本不会增加，换一个新昵称再验证。

## 验证旧页面不能覆盖

保持旧 expectedVersion 不变，只修改 nickname，再点击 Execute：

```json
{
  "nickname": "旧页面尝试覆盖",
  "expectedVersion": "0"
}
```

若上一轮发生了实际修改，这次应返回 **HTTP 409 / CONFIG_VERSION_CONFLICT**，这是预期的保护结果。再次 GET /me，应仍是第一次成功的昵称，版本没再次增加。

如果决定继续修改，先读取最新版本，再填入 PATCH 请求。不要自动重试覆盖，也不要随意猜版本。

## 其他验收

| 验收项 | 操作 | 预期 |
|---|---|---|
| 未登录保护 | 在 Authorize 弹窗中 Logout，执行 GET /me | 401 / AUTH_REQUIRED |
| 非法昵称 | 授权后，把 PATCH 的 nickname 填为空字符串或 null | 400 / VALIDATION_FAILED，资料不变 |
| 退出当前设备 | POST /auth/logout，无请求体 | 200；再 GET /me 为 401 |
| 修改密码 | POST /me/password，填写当前密码、新密码 | 200、data=null；全部设备旧 token 失效 |

改密成功后，用新密码登录取得新 token，再通过 Authorize 的 Logout 清除旧值并重新 Authorize。退出接口不会自动清除输入框中的 token，因此退出后资料调用返回 401 是正常结果。

## 导入 Apifox

**想一次查看包含待开发业务的完整目录：**导入 [campuslife.v1-design.openapi.json](openapi/campuslife.v1-design.openapi.json)，共 61 个操作（7 已实现、54 待开发），详见 [完整规划说明](api-design.md)。下面的运行快照仅有 7 个已实现操作，两者用途不同。

可导入文件：[campuslife.openapi.json](openapi/campuslife.openapi.json)，磁盘位置为 D:\campuslife\docs\openapi\campuslife.openapi.json。文件从实际运行应用生成，仅把随机测试端口规范化为默认 8080，不含实际账号、密码或 token。

推荐使用 Apifox 桌面客户端：

1. 团队项目页面选择“导入项目”，格式选 **OpenAPI / Swagger**。
2. 选择上面的 JSON 文件；维护方式可选“以可视化界面维护接口”，按预览完成导入。
3. 检查本机环境服务地址为 **http://127.0.0.1:8080**，不要用 Mock 地址。启动 CampusLife 后才能调试。
4. 发送注册、登录请求，复制实际响应的 data.accessToken。
5. 在 GET /me、PATCH /me 等受保护接口的 **Auth / 认证** 中选择 **Bearer Token**，粘贴 token 本身。导入时若支持“导入鉴权组件”，可一并勾选；注册、登录无需配置登录凭证。
6. 按上文填写 JSON 并点击发送，验证成功和版本冲突。

也可尝试 URL 导入 [运行中的 OpenAPI 地址](http://127.0.0.1:8080/v3/api-docs)。工具无法访问本机地址时使用文件导入；通过远端服务抓取时无法访问你电脑的 127.0.0.1。导入文档不会自动启动应用。

后续接口变化后，运行中的 /v3/api-docs 是最新来源，重新导入更新定义。本地文件是本次已验证的快照；新增接口时同步更新注解、文件和验收说明。

## 常见情况

- 连接失败：检查应用是否启动、端口是否正确。
- Swagger 入口 401 / 404：确认停止旧进程并加载新代码；默认 mysql 模式提供文档，web-only 诊断模式关闭文档。
- Failed to load API definition：打开 /v3/api-docs 检查响应，确认没有设置 API_DOCS_ENABLED=false。
- GET /me 返回 401：先登录，将实际 token 填入 Authorize，检查是否已退出、改密或超过 2 小时。
- PATCH 返回 409：重新 GET /me 获取最新版本，由你决定是否继续修改。

## 配置与验证记录

使用 springdoc-openapi 3.1.1、OpenAPI 3.0 与 Swagger UI。默认本地 mysql 模式可匿名读取页面和定义，业务接口保留原鉴权。API_DOCS_ENABLED=false 同时关闭定义和页面，web-only 默认关闭。密钥、数据库密码和服务端认证参数不进入定义，页面不持久保存授权，并关闭远程 Swagger 校验。

2026-10-15：Windows PowerShell 5.1、Java 21、独立 MySQL 8.0.45 全流程 140 个用例全部通过，0 failures / errors / skipped，mysql 模式 jar 检查通过。新增 OpenApiMysqlIT 3 个，检查实际定义、字段类型、Bearer 标记、页面资源和示例的注册→登录→读取→修改→409；ApiDocsDisabledMysqlIT 1 个检查关闭后匿名访问 401、已登录访问 404。

未代用户登录或上传到 Apifox，未声称完成 Apifox 客户端导入测试。开发者验证仍使用 scripts/test-mysql.ps1，人工验收不要求手写 PowerShell 请求。

导出检查发现 springdoc 会裁剪最初未被引用的自定义错误模型，已调整为在补充错误响应引用时一并注册。增加所有 schema 引用可解析的断言，并校准 data=null 和版本正则。完整 140 个用例通过后，这些最终文档调整又运行了 42 个常规测试、4 个文档专项 MySQL 用例与 jar 检查，全部通过；业务用例没有因纯文档模型调整重复执行。最终快照与最新实际导出一致。

参考：[springdoc 官方文档](https://springdoc.org/)、[Apifox 新建与导入项目](https://docs.apifox.com/create-api-project)、[Apifox 导入设置](https://docs.apifox.com/import-settings)。
