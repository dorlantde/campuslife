# M06.1-C 私有管理面验收

日期：2026-11-03，America/New_York。按已采用[ADR-0057](../decisions/0057-single-instance-deployment.md)完成最小Actuator/Micrometer、独立运维鉴权及按需诊断；[使用指南](../private-observability.md)、[机器记录](private-observability-2026-11-03.json)。本批不执行D真实宿主条件项、E完整备份恢复或M06.2性能测量，前端继续暂停。

## 实现与取舍

新增Boot4.1.1 BOM管理的Actuator/Micrometer，生产依赖148→156，原148项JAR字节保持；新增8项为Actuator/health/metrics/observation模块、micrometer-core/jakarta9与HdrHistogram。未升级Boot、SDK或既有组件镜像。业务操作仍75、V24/32表，不新增业务接口、DDL或指标表，不改订单/券/文件/锁/消息协议。

普通运行默认管理端口-1。新部署必须显式`Init -Observe`，独立8081端口只绑定容器内127.0.0.1，不映射宿主、不经Nginx；base/components/showcase与资金开关保持原边界。运维秘密随机生成、只读文件注入；运维鉴权直接核对独立身份，不查业务账号/数据库、不接受ADMIN JWT。最高优先级链按真实本地端口隔离，仅GET health/两探针/metrics白名单；业务端口拒绝actuator，管理端口拒绝业务路径和其它方法。管理端口误绑公网、与业务端口相同、缺失凭据或未明确启用均启动失败。

健康摘要可含标准健康组名，不返回组件/异常细节；liveness独立于外部依赖，readiness检查接受请求状态＋MySQL。可选缓存/MQ/券Redis/S3故障不聚合成进程重启条件；原业务health及Compose健康仍为HTTP存活，未实现自动摘流量/自动重启。

Snapshot仅按需执行固定聚合、一个短RR只读快照，每条SELECT限1500ms；另读容器状态和队列ready/unacked。无姓名、账号/订单ID、payload、文件定位/内容，无后台全表采样任务。消息/领券指定事实仍通过原Inspect及业务结果查询，不把SENT当消费成功。Nginx日志沿用A的脱敏配置，本批验证关联而不重复实现。

## 实际验证

| 范围 | 最终结果 | 主要证据 |
|---|---|---|
| 管理面真实HTTP测试 | 3项通过 | 匿名/错误凭据/Bearer拒绝；独立运维可读摘要与JVM；非白名单/方法拒绝 |
| 配置边界单测 | 6项通过 | 默认无监听、独立回环有效、误开端口/公网上绑定/同端口/弱或缺失凭据拒绝 |
| 原WebFoundationTest | 13项通过 | 原HTTP/trace/错误契约；web-only未增加管理HTTP |
| 隔离components实测 | 28组通过 | 真实MySQL与Broker故障、私有端口/认证、运行指标、原通知闭环、trace与标签/日志脱敏 |
| 默认base回归 | 7组通过 | 原三服务/三秘密/LOCAL、组件与资金关闭、旧清单兼容、启动及停止保留两卷 |
| showcase组合配置 | 1组通过 | 七服务TLS overlay与私有管理面可组合、24秘密、无应用端口发布；不冒充七服务运行期故障复验 |

共22项相关JUnit测试分两轮通过，均0失败/错误/跳过；运行组与JUnit分开计数，不累计重复重跑。实际执行`dev.ps1 -Action Test`，第一轮MAVEN_ARGS筛选`ManagementSecurityTest,WebFoundationTest`，第二轮筛选`ManagementBoundaryTest`；构建镜像不重复运行全量业务测试。部署入口为`test-deploy-observability.ps1`，补充showcase配置独立执行，原始记录路径及摘要见JSON。

28组实测包括：

- 同网络Nginx容器不能连接应用8081，Nginx入口actuator为404；匿名/普通Bearer和真实ADMIN JWT均不能访问管理面，业务8080上即便ADMIN JWT也返回403。
- 真停MySQL后readiness503，liveness/JVM指标200，原业务health200；运维快照只标记数据库不可用，不输出连接异常。重启MySQL后原应用不重启即readiness恢复200。
- 通过原学生/商家/ADMIN流程创建并支付打印单；停止RabbitMQ后商家置待取件，数据库保留1条READY/PUBLISHING积压及年龄，通知仍0，队列探测不可用但readiness仍200。恢复Broker后outbox SENT、通知1、消费凭据1，证明发布与实际消费分别成立。
- JVM、HTTP、Hikari计量可读取；HTTP按`/api/v1/shops/{shopId}`等路由模板记录，不含具体资源ID/查询值/token。客户端trace被替换，Nginx日志能关联应用生成trace，日志无测试密码、运维密码、Bearer值或查询标记。

## 失败与修正保留

首次镜像构建因Docker Desktop引擎未运行失败，记录`.cache/m061c-image-failure.log`；Maven打包已成功，未创建测试容器。按本机CLI位置找到用户安装的Docker Desktop并启动，随后构建通过，未更改系统/开发数据。

第一轮隔离实例`8707864d8f1d458f9bce59e57a43f1ac`的摘要断言仅接受status，未兼容标准groups字段，因此停止；该轮资源/秘密清理后保留JSON和日志。只修验收预期为允许status及组名，组件/异常仍禁止；全新`b40c49060d8f4b7e94d7904bb1ae349c`通过全部28组，真实根摘要为UP及liveness/readiness组名。未改变业务或放开额外端点来通过测试。

## 限制与下一项

本批为进程内即时诊断，不是时序监控/告警、容量或性能提升结论。固定SQL聚合仍有数据库读取成本，超时/故障时不提供部分成功的伪完整快照。未测试负载下指标开销、Linux真实宿主、全栈备份恢复、HA、自动证书维护；未定位M04-C冷恢复新写超时，运行期MySQL/Broker恢复不能称其修复。

仅清理本轮自有测试资源与秘密，保留脱敏证据；Docker Desktop/WSL不自动关闭，未访问项目库或用户上传根。下一项为M06.1-D组合运行与Linux宿主条件项；如果没有用户指定的现成Linux环境，应交付指南并明确该条件项未执行，不能用Windows的Linux容器冒充宿主验收。
