# M06.1-A 基础容器环境

本文的base只包含一个Java应用、Nginx、独立MySQL，上传走LOCAL。使用已采用的[ADR-0057](decisions/0057-single-instance-deployment.md)，Redis/MQ以B1可选模式交付，S3 TLS与最小私有观测分别由B2/C交付；没有公网部署、用户数据迁移或前端改动。不要将本环境接入原有项目数据库、真实上传根或其它环境的数据卷。

B1现已补充显式[components六服务模式](component-deployment.md)，B2已补[showcase七服务/TLS模式](storage-tls-deployment.md)，本文仍说明默认base三服务；[私有观测](private-observability.md)须在新实例显式-Observe。新模式不会自动迁移或转换本文的已有实例。

## 构建与启动

当前验证平台是Windows＋Docker Desktop Linux引擎。需要项目已有Java21、Maven、Python和Docker Compose；Python只使用标准库。Linux直接使用`python3 scripts/deploy_base.py`对应操作，真正Linux宿主尚未验收。

在项目根目录执行：

```powershell
.\scripts\deploy-base.ps1 -Action Build
.\scripts\deploy-base.ps1 -Action Init -Port 18080 -Docs
```

Build先用现有Maven工程打包，再从白名单上下文构建镜像；不会借此重复全套业务回归。Init只生成新身份、随机数据库/JWT秘密和清单，不启动服务，不创建ADMIN。`-Docs`是明确的本机Swagger开启选项，省略时默认关闭；模拟支付始终关闭，评分Redis/券Redis/MQ/S3均关闭，活动默认SYNC。

Init输出本机新目录，例如`D:\campuslife\.local\deploy\base\<32位身份>`。将实际路径填入下列`$instance`，不要把尖括号示例原样执行：

```powershell
$instance = '这里填Init输出的完整目录'
.\scripts\deploy-base.ps1 -Action Start -Instance $instance
.\scripts\deploy-base.ps1 -Action Status -Instance $instance
```

三个服务健康后，打开`http://127.0.0.1:18080/swagger-ui/index.html`（须已选Docs）；使用现有接口注册、登录、Authorize和上传PDF。只有Nginx映射宿主回环端口；应用/数据库不直接发布端口，数据库在独立内部网络。冲突时Init明确指定其它端口，不静默抢占已有8080/3306服务。

首次需要业务管理员时显式执行：

```powershell
.\scripts\deploy-base.ps1 -Action Admin -Instance $instance
```

在本机提示中填写管理员用户名、昵称、新密码。密码不回显、不作为命令参数；临时文件只挂载给一次性无HTTP的AdminBootstrap进程，结束后删除。它复用同一镜像中的PropertiesLauncher明确选择已有main类；不是给`java -jar`传一个业务参数来切main，也不新增公开管理员接口。根数据库账号只用于新卷初始化，不是平台ADMIN，应用只持有项目库账号。

## 停止和重启

```powershell
.\scripts\deploy-base.ps1 -Action Stop -Instance $instance
.\scripts\deploy-base.ps1 -Action Start -Instance $instance
```

普通Stop保留容器、两个持久卷以及部署秘密：账号/会话/数据库事实和LOCAL `.storage-id`/文件随卷保留。Start校验本地镜像ID、固定服务集合、身份标签与回环端口；镜像被换掉时要求显式处理，不用随机生成新密钥“修复”旧卷。新卷初始化失败时保留错误日志，不删卷掩盖问题。停止有界，依赖顺序为Nginx→应用→MySQL；未能停止会返回失败，不能把组件8秒预算理解为整个环境保证8秒退出。

`DestroyTest`仅允许自动验收生成的test身份；普通环境不提供自动删卷入口。不要手动对本项目或全局使用prune、删卷或scale。固定应用容器身份拒绝Compose扩副本；这不是分布式锁，跨项目复制数据卷/绕过入口多实例仍不支持。

脚本不会退出Docker Desktop或执行`wsl --shutdown`。需要释放WSL占用时，先处理其它依赖Docker/WSL的工作，再由你退出Docker Desktop并自行关闭WSL。

## 配置、秘密与权限

固定版本和摘要见`deploy/base/Dockerfile`/`compose.yaml`：Temurin21.0.13+9、MySQL8.4.13、Nginx1.30.6-alpine。A验收时Java源码和148项运行依赖保持，C观测加入后当前为156项；镜像只含生产JAR和两个入口脚本，仓库的`.dockerignore`使用白名单，不发送`.local`/`.cache`/`.tools`/源码/测试/前端。运行镜像Tag包含JAR与构建输入摘要，实例清单再核对实际本地image ID。

部署根与秘密目录由Init新建，位于Git忽略目录。MySQL独占root秘密；应用只挂DB_PASSWORD/JWT_SECRET_BASE64，Spring使用configtree并替换开发可选配置导入。秘密不放镜像或Compose环境变量；这些是本地文件挂载，不是加密秘密服务。Linux目录0700、挂载文件0444使非root容器可读指定文件，临时管理员目录在私有实例根内；Windows的chmod不等于完整ACL保护，宿主账号/磁盘与秘密备份仍由操作者保管。

应用和Nginx为UID10001，只读根文件系统、移除能力并禁止提权；应用仅文件卷和临时目录可写。MySQL官方镜像仍按其入口初始化/降权流程运行，不声称三个容器都以同一非root身份启动。项目SQL账号沿现有Flyway所需DDL/DML权限，不是root，也尚未分离迁移账号和运行账号。

Nginx不缓存/缓冲PDF请求与响应，不自动重试写请求。总请求上限22MiB，原后端单PDF20MiB限制保持。入口错误用`EDGE_*`及`X-Edge-Request-Id`，应用响应保留服务端`X-Trace-Id`；入口自身413/502/504不伪造应用trace。访问日志记录方法、无查询字符串的路径、状态、耗时和两个关联ID，不记录密码、token或请求体。

Nginx原生请求错误日志会自动附带完整请求行，故本配置不保存该日志，避免查询参数旁路进入日志；保留脱敏访问日志和启动命令失败记录。这会减少底层连接错误文本，基础阶段以状态/耗时定位，后续观测不应重新引入敏感请求日志。

业务health只表示HTTP可响应，Compose health/依赖顺序辅助启动，不是自动运行期重启/流量摘除；当前重启策略为`no`，失败明确暴露。私有Actuator/readiness已由[M06.1-C](private-observability.md)独立交付，不改变本文base的HTTP存活语义。

## 自动验收和人工入口

已构建镜像后执行：

```powershell
.\scripts\test-deploy-base.ps1
```

测试新建test身份、回环随机端口、独立数据库/LOCAL卷和虚构账号；真实20MiB/超限、管理员入口、打印报价/未付取消、权限、重启持久化、网关故障与PDF断流。它会在自有应用上制造暂停/强杀，不影响你原来的开发实例。结束只清理该轮标签资源和生成秘密，保留`.cache/deploy-base/<身份>/`脱敏结果/失败日志。

人工验收优先Swagger：注册学生并登录Authorize→上传本人PDF并下载；使用显式管理员审核虚构打印店→商家配置营业/PRINT服务/价目→学生报价/下单/取消。默认资金关闭，支付应拒绝；需要完整模拟支付/异步组件时采用B1的[新实例显式配置](component-deployment.md)，不改本文默认开关。完整[33项验收报告](reports/base-deployment-2026-11-02.md)保留A阶段失败与运行边界；B1对新JAR另做7项base小回归，没有宣称重新执行A的全部33组。
