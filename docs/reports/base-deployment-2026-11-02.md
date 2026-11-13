# M06.1-A 基础容器交付与验收

日期：2026-11-02，America/New_York。状态：**A基础批次已交付，33项部署验收检查组通过**。依据[ADR-0057](../decisions/0057-single-instance-deployment.md)，本轮仅LOCAL基础模式；[使用与Swagger指南](../base-deployment.md)、[机器记录与完整检查标签](base-deployment-2026-11-02.json)。不将本批结果称为全栈部署或独立Linux宿主验收。

## 交付内容

- `deploy/base/Dockerfile`及两个应用脚本：Java21、UID10001、生产JAR、独立AdminBootstrap入口；构建上下文由根`.dockerignore`白名单限制。根`.gitattributes`固定容器文件LF，Docker构建赋予脚本执行权限。
- `deploy/base/compose.yaml`：一个Java应用、Nginx、独立MySQL；两个具身份标签的持久卷（DB、LOCAL）及入口/内部两个网络。只有Nginx回环端口可达宿主，应用/数据库不发布；固定应用容器名阻止同项目scale，不是分布式锁。
- 秘密文件/configtree、新卷项目账号初始化、普通启动不建ADMIN、默认资金及可选组件关闭；临时Admin秘密仅挂一次性非HTTP进程。镜像不含开发配置、测试类、秘密或前端。
- Nginx 22MiB总请求上限、PDF双向不缓冲、写请求不重试、独立入口错误/请求ID；应用原trace不变。访问日志去查询字符串且不记录认证/请求体；原生请求错误日志会附完整请求行，故不保存，保留脱敏访问日志与启动失败记录。
- `scripts/deploy-base.ps1`/`deploy_base.py`：Build、Init、Start、Status、Stop、Admin、仅自有test可用的DestroyTest。普通Stop保留数据和秘密；不关闭全局Docker/WSL。
- `scripts/test-deploy-base.ps1`/`test_deploy_base.py`：真实基础环境验收，自动生成虚构数据并按身份清理；不是mock控制器或只验Compose语法。

## 固定版本与制品

Docker Desktop Linux引擎29.8.0、Compose v5.5.1，当前由Windows运行部署入口。实际容器内版本：Temurin21.0.13（+9）、MySQL8.4.13、Nginx1.30.6。Java21主版本与Boot4.1.1保持，未改Maven依赖。

| 镜像 | 固定索引摘要 |
|---|---|
| `eclipse-temurin:21.0.13_9-jre-noble` | `sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f` |
| `mysql:8.4.13` | `sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902` |
| `nginx:1.30.6-alpine` | `sha256:0e52bd054126cdc040b1276c0af347aeb02a41b9cfe305d6094767ac30e97949` |

版本先核对官方维护记录与实际registry摘要，再实际运行。来源：[Nginx发布记录](https://nginx.org/news.html)、[Temurin官方镜像](https://hub.docker.com/_/eclipse-temurin/tags?name=21.0.13_9-jre-noble)。CLI利用已包含的PropertiesLauncher指定`loader.main`并实际创建管理员，依据[Boot启动器说明](https://docs.spring.io/spring-boot/specification/executable-jar/launching.html)，没有修改JAR默认启动类。

本地镜像`campuslife-base:m061a-5b16a6b6de98-0849cd7e6d6a`的实际ID、构建输入指纹见机器记录；实例Start再次核对image ID。生产JAR SHA-256 `5b16a6b6de985acf258547fdd447ccc515da3f171e3177b79332019cbef3ba05`，与M04/A0完全一致。148个嵌套运行依赖逐项SHA相同，530个既有源码/脚本/前端/pom文件指纹保持；本批只新增部署/验收文件与文档，仍75业务操作/V24/32应用表。

## 33项检查如何覆盖场景

机器记录按唯一标签保存33组检查，重复轮次不累计；这是部署验收组数，不是新增JUnit用例、覆盖率或容量数字。

| 场景 | 实际验证 |
|---|---|
| 基础启动和隔离 | 新库完成24迁移/32表，三个常驻服务健康，只有网关实际回环映射；默认0个ADMIN，文档默认关闭，匿名订单拒绝 |
| 非root和秘密 | 容器UID10001能写LOCAL卷、不能改JAR；只读根和权限实际运行；应用未挂MySQL root秘密，镜像环境没有DB/JWT密码，开发目录不在运行目录 |
| 注册/登录/代理 | 学生注册登录成功，configtree密钥能签验JWT；中文/Emoji和UTF-8正常；外部伪造trace被替换，应用trace与入口ID独立 |
| 私有PDF与边界 | 中文/Emoji原名、本人原字节下载和附件名/no-store、匿名拒绝；实际20MiB上传和下载SHA相等；20MiB+1触发应用413，22MiB+1触发入口413，来源与关联ID清楚 |
| 管理员和打印基础链 | 独立非HTTP入口显式建ADMIN，登录审核虚构店铺；商家营业/服务/价目，学生报价20分、待付下单/同键原单重放、文件保护、关闭模拟支付503、未付取消和保留期 |
| 文档与业务操作数 | 显式Docs开启后Swagger资源可访问，实际OpenAPI75操作；没有增加业务路径或前端页面 |
| 退出和恢复 | 普通Stop后两个卷保留；全套Start后存储标识、账号会话/JWT、已关闭订单、PDF不变，新上传成功；不靠删除卷重新注册来证明重启成功 |
| 故障边界 | 真实无监听TCP拒绝502、暂停上游后真实60秒读取超时504；入口错误不伪造应用trace。真实PDF传输时KILL自有Java容器，收到内容是原PDF的严格前缀、长度不足，没有JSON尾接；重新启动后旧文件可读 |
| 日志与重试 | 实际访问日志含服务端trace、不含密码/token/Authorization；配置与运行保持请求/响应不缓冲、无上游自动重试；不据此宣称端到端exactly-once |

502实验短暂替换已停止/移除的测试应用：使用同一已构建镜像运行仅sleep的网络诊断容器，不运行Java、不挂秘密/数据卷、不开放端口。请求证明TCP拒绝后立即清理，再创建正常应用。它不是新增常驻组件，也不与正常Java应用并行访问文件。504则暂停真实自有应用，获超时响应后解除暂停。Docker health状态不自动重启或摘除流量，结果未冒充HA。

## 失败保留与修复

- 首轮`83690e16671d45b7a9bc448119e62d54`：营业时间测试数据填写`24:00`，原规则只接受`00:00～23:59`，返回400。保留原业务校验，修正虚构测试数据；此前上传/权限/ADMIN检查已执行，不把它们重复累计。
- 第二轮`6909b2968d4d49329eebffbec73252d5`：应用停掉后的Docker端点表现为连接超时504，断言错误预设必为502。实际日志同时保留过渡期间的502/504；分离“有网络但无监听”和“有监听但进程暂停”实验，不改应用状态机、不把504改成502。
- 完成轮`57156532b7614947b0c95bc032f628c7`：33项全部通过，真实故障后恢复及清理通过，完整证据在`.cache/deploy-base/<身份>/acceptance.json`。源文件/脚本静态检查明确使用UTF-8；编写期一次未指定编码的AST读取命令失败未作为应用缺陷计数。

每轮保留服务/构建/CLI及失败记录，删除生成的秘密文件；最终按本任务test标签再次核对容器/卷/网络均0（含短时诊断容器）。只保留本机镜像/构建缓存和脱敏证据；没有全局prune，也未退出Docker Desktop/WSL。未读开发密码、接入开发MySQL或访问真实上传根。

## 边界与下一项

当前是单机可信工作负载边界：MySQL沿官方入口初始化/降权，Java和Nginx为非root；秘密挂载文件不等于加密秘密系统，Windows chmod不保证ACL隔离；SQL账号保留Flyway DDL权限。数据库连接沿已有PREFERRED设置，没有新增数据库CA身份校验；基础实例关闭binlog，不提供PITR承诺。网关仅回环HTTP，无公网TLS发布。

没有运行本批无关的全项目回归或容量测试；此前A0/各模块报告保留原范围。独立Linux宿主、Redis/MQ、S3 TLS、Actuator/私有指标、全栈恢复、前端与公网均未执行，不将M06.1整体标为完成。

下一项仅 **M06.1-B1：两个Redis与RabbitMQ接入、维护入口及显式演示开关**。按已采用方案推进，保留缓存回源、独立券Redis、消息/券原幂等与暂停恢复协议，不重做V1业务或同时实施B2/C/D/E。
