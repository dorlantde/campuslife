# M06.1-A0 MySQL 8.4 新部署兼容报告

日期：2026-11-02，America/New_York。状态：**分轮通过，A0收口**。用户在[部署提案](../deployment-observability-design.md)交付后指示推进，采用[ADR-0057](../decisions/0057-single-instance-deployment.md)，本轮只执行数据库兼容门槛；[机器记录](mysql84-compatibility-2026-11-02.json)、[复现入口](../mysql84-compatibility.md)。

## 结果与范围

原业务实现可在本轮固定的MySQL8.4.13候选上通过精选迁移、约束、并发、资金及读取语义检查。**80项不同检查分轮通过**，另有16项启动相关重复检查，不累计成96项。正式数据卷重启、重启后mysql模式可执行JAR启动通过。没有修改生产代码、SQL、历史迁移、依赖或开发启动配置；75业务操作/V24/32张应用表及默认开关保持。

独立Windows宿主Java21/Maven客户端连接Docker Desktop Linux引擎29.8.0内的新MySQL；不是Linux应用镜像或独立Linux宿主部署。每轮新容器/卷/专用网络和随机凭据，3306仅宿主回环随机端口；既有异步夹具要求库名`campuslife_it`，保留该保护，不放宽成任意库。

固定官方镜像：

- `mysql:8.4.13@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902`。
- Linux/amd64清单：`sha256:72c368d898861f3b3a07af0e6d690400f44f048799ed46822783d9224cc2c86d`；实际拉取版本与SQL `VERSION()`相符。候选补丁依据[官方8.4.13记录](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/news-8-4-13.html)，非浮动`latest`。
- 严格模式保留；`foreign_key_checks=1`、`restrict_fk_on_non_standard_key=1`，未禁用约束或启用旧认证来制造通过。后者默认限制见[官方系统变量说明](https://dev.mysql.com/doc/refman/8.4/en/server-system-variables.html#sysvar_restrict_fk_on_non_standard_key)。
- 项目账号`caching_sha2_password`，仅本项目库DDL/DML；测试核对不能读`mysql.user`。保留应用启动Flyway需要DDL权限的已知取舍，不声称迁移/运行账号分权已经实现。

## 检查构成

| 既有/新增检查集 | 不同用例数 | 核心证据 |
|---|---:|---|
| 新增Mysql84CompatibilityIT | 4 | 精确版本、库/账号/UTC/严格模式；24迁移校验和及重复迁移0；32表/InnoDB/字符集；标准外键/启用CHECK；账号权限 |
| MysqlIntegrationIT | 12 | 唯一/FK/CHECK拒绝、中文及Emoji往返、毫秒UTC、会话期限、迁移可重复执行 |
| BookingOrdersMysqlIT精选 | 6 | 多人争抢同一区间、同创建键竞争、真实MySQL锁超时、金额/事件约束 |
| BookingPaymentsMysqlIT精选 | 12 | 支付/退款事实重放、故障全回滚、重复支付及付款/取消/扫描竞争、RR详情快照 |
| CouponOrdersMysqlIT精选 | 6 | 预约及打印用券/释放/复用/实付退款、写失败回滚、同券两次创建唯一赢家 |
| CouponClaimsMysqlIT精选 | 2 | 多学生精确库存竞争、同学生不同键/同键仅一次效果 |
| CouponAsyncMysqlIT精选 | 3 | 异步落库重复消费不超发、超时与消费唯一终态、请求与outbox原子回滚；不借此声明MQ/Redis本轮已联测 |
| OutboxMysqlIT精选 | 4 | 原动作与outbox回滚、租约接管/迟到回写、SKIP LOCKED；计数包含原参数化变体 |
| PublicShopCatalogMysqlIT精选 | 15 | 优化路径/原路径筛选与排序、包含搜索的特殊字符字面语义、并发资格/营业/服务写入不撕裂RR读取 |
| PublicShopReviewsMysqlIT精选 | 16 | 真实评分精度、评价资格/count/记录/昵称及目录评分快照一致；包含参数化变体 |
| **合计** | **80** | 最终这些检查0失败、0错误、0跳过 |

关键读语义由原确定性数据与独立预期/结果断言验证，包含原M01采用的列表查询分支；没有重新做8.0与8.4的千/万店成对性能测量，不推导计划相同、性能相同或有所提升。

80项通过后，重启与JAR环节发现测试端口问题，按风险仅重复元数据/迁移16项及该启动链。最终正式服务重启后哨兵值保持、哨兵表删除，随后JAR通过原mysql启动检查；不是把容器进程存在当应用可用。

## 失败、修复和原始证据

| 轮次身份（`.cache/mysql84-it/`下） | 事实 | 处理与限制 |
|---|---|---|
| `94bd5b9ae11e4dc1977ff7e4d9c1bf3c` | 就绪超时，MySQL客户端忽略Windows挂载的world-writable配置文件 | 复制到临时容器内0600后读取；秘密不进入命令参数。尚未运行业务检查 |
| `2b4ee51e05774793b5a9abe0164bf70e` | 探针看到初始化socket临时服务，正式连接尚未就绪 | 改正式TCP就绪探针，拒绝临时socket假阳性；尚未运行业务检查 |
| `623b42757a214c8eb037f39b58a3d0f0` | 80项中77通过，3项在原异步夹具库名保护处失败 | 不改原夹具保护，在新容器内沿用`campuslife_it`。此轮仍沿用应用原optional导入，未手工读取其内容；后续轮显式替换导入、隔离所有Spring环境覆盖项，组件均关闭且数据库/文件根始终自有 |
| `5fb244eb515a46cfa6eb932c19af201b` | 80项全通过；卷内重启读写通过，JAR连接旧端口被拒绝，因此整轮不是全部通过 | 保留测试及启动日志，修复重启后读取实际回环端口，不改业务连接配置 |
| `79c5c5de5ce74744849cd43b3db77f62` | Startup小批：16项全通过，卷重启保持，JAR启动通过 | 证实随机映射从63529变63533，重新发现端口后连接成功；16项包含于80，不重复统计 |

前次失败轮没有保存重启后端口，不能把该具体端口值补写进历史。最后一轮实际观察证明本环境会重分配端口，修正后该链通过；后续Compose应用连接服务名内部3306，不依赖本测试的宿主随机端口。

## 制品、清理及尚未执行

527个既有源码/脚本/前端/pom文件指纹全部保持，只新增兼容测试、隔离运行脚本与文档。生产JAR SHA-256 `5b16a6b6de985acf258547fdd447ccc515da3f171e3177b79332019cbef3ba05`与M04-B/C相同；148项嵌套运行依赖逐项SHA一致。新测试类未进入生产JAR。

各轮自有容器/卷/网络清理完成，最终按本任务标签复核均0；生成的秘密文件均删除。保留镜像缓存、失败/成功日志及脱敏清单，不执行全局prune，也未退出Docker Desktop或关闭WSL。没有连接开发项目库、访问真实上传根、升级本机MySQL或搬迁用户数据。

未执行：原地8.0→8.4升级/存量迁移、全项目回归、新8.4性能容量基线、Compose/Linux应用镜像/Nginx、S3 TLS、Actuator、全栈备份恢复、前端或公网部署。A0只是当前固定源码/候选的兼容门槛通过；后续变更仍需相关检查。

下一项：**M06.1-A基础容器交付**，仍是独立小批，按已采用方案实现Java镜像/base Compose/Nginx/秘密与LOCAL卷，不同时接入全部可选组件。
