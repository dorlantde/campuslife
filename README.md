# CampusLife 校园生活服务平台

CampusLife 让学生在校园内找到商家、预约理发或提交 PDF 打印订单，并在履约后评价店铺。商家管理排班、价目、订单与优惠券，管理员审核店铺和处理交易异常。支付与退款使用模拟资金。

## 架构与业务

后端采用 Java 21、Spring Boot、MyBatis-Plus 和 MySQL，以单个 Spring Boot 应用部署。Flyway 管理数据库迁移。Vue 3、TypeScript 和 Element Plus 提供学生、商家与管理员的部分操作页面。

| 场景 | 实现要点 |
| --- | --- |
| 理发预约 | 同一理发师同日的预约写入先锁定日期保护行，再检查时间区间重叠。创建请求可幂等重放，未支付超时释放时段。 |
| 店铺评分 | 提交或首次隐藏评价时，在同一 MySQL 事务推进评分版本。Redis 按版本缓存评分总和与数量，较早请求的回填只写入旧版本。 |
| 限量优惠券 | Redis Lua 预留库存，MySQL 在同一事务记录领取请求和待发消息。RabbitMQ 驱动异步发券，重复投递通过数据库约束及消费记录处理。客户端可查询最终领取结果。 |
| 私有打印文件 | 学生上传 PDF 后获得按印刷面计算的报价。下载权限取决于文件归属和订单履约阶段，支持本地存储与私有 S3 兼容存储。 |

实现入口和对应验证记录见[源码导读](docs/interview/source-review.md)与[技术证据索引](docs/interview/project-evidence.md)。

## 在本机启动

Windows 开发环境需要 JDK 21、Maven、MySQL 以及可用的 MySQL 客户端。在项目根目录打开 PowerShell。首次准备项目库和专用账号时运行第一条命令，已有项目库可直接运行第二条。

~~~powershell
.\scripts\setup-mysql.ps1
.\scripts\database.ps1 -Action Run
~~~

应用启动后打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。可先调用匿名健康检查或商家列表。测试学生接口时，先注册并登录，再将登录响应的 `data.accessToken` 填入 **Authorize**。Apifox 可导入[运行接口文档](docs/openapi/campuslife.openapi.json)。

- [MySQL 配置与排错](docs/mysql-setup.md) · [VS Code 启动配置](docs/vscode-setup.md)
- [Swagger 与 Apifox 使用说明](docs/api-testing.md) · [前端启动指南](docs/frontend.md)
- [Linux 部署指南](docs/linux-deployment.md) · [2 核 4 GiB 配置](docs/compact4g-deployment.md)

## 部署与验证状态

后端已在阿里云 ECS 完成私有部署。验收时应用、Nginx、MySQL、两个 Redis 实例、RabbitMQ 和 SeaweedFS 共七个服务均通过启动健康检查，并经 SSH 转发验证了 Swagger、健康接口与商家查询。云端 RabbitMQ 健康探针曾间歇失败，稳定性仍待确认，云端综合负载测试尚未完成。

隔离环境已有预约并发、评分缓存及异步领券等专项验证。例如，在 100 名学生领取 20 张券、并发为 5 的本地对照中，调整发布器后，从首次发起领取到轮询观察到成功的 p95 由约 18.96 秒降至 0.58 秒。该数字取三轮 p95 的中位数。测试条件、失败记录和指标定义见[发布器对照报告](docs/reports/outbox-publisher-comparison-2026-11-01.md)。

[项目路线图](docs/roadmap.md) · [设计决策](docs/decisions) · [验收报告](docs/reports)
