# ADR-0008：交互式接口文档与 Apifox 导入

- 日期：2026-10-15（Asia/Shanghai）
- 状态：用户要求先补齐接口调试入口；实现和独立 MySQL 验证通过
- 范围：已实现接口的 Swagger UI、OpenAPI 文件与中文点击流程

用户熟悉浏览器与 Apifox 调试，不熟悉 PowerShell 请求命令。为已有 HTTP API 增加可点击的入口，复用现有业务逻辑与鉴权，不创建绕过鉴权的测试接口。

采用此前候选的 springdoc-openapi-starter-webmvc-ui 3.1.1，与 Boot 4.1.1 配套验证。标准 Swagger UI 与 OpenAPI 3.0 让浏览器和 Apifox 共用定义，只收录当前 7 个实际操作；商家、订单等讨论稿不标为可调用。

本地 mysql 模式开放文档路径，业务鉴权保持原约定。API_DOCS_ENABLED=false 同时关闭定义、页面及文档路径匿名放行；web-only 默认关闭。Bearer 通过 Authorize 显式填写，隐藏认证身份参数，不持久保存授权，不调用远端 Swagger 校验服务。

DTO 补充字段说明与虚构示例，将 PATCH 的 JsonNode 字段准确描述为字符串，区分可省略昵称与必填版本。说明注册 201、鉴权及版本冲突，并通过真实 HTTP/MySQL 验证 schema 和示例。从运行中定义生成本地快照，仅规范化服务器端口为 8080，不含实际凭证。导入 Apifox 由用户本机完成，后续接口变化同步更新。

全套 140 个用例通过，新增文档 4 个；mysql 模式 jar 检查通过。点击流程与边界见 [接口验收](../api-testing.md)。商家业务暂缓，先让用户完成已有账号和昵称功能的人工验收。
