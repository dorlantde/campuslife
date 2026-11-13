# M06.1-B1 双Redis与RabbitMQ部署

本批在[基础容器](base-deployment.md)上交付显式`components`模式：Java、Nginx、MySQL、评分Redis、券Redis、RabbitMQ共六个常驻服务。该模式文件仍LOCAL、S3关闭；B2已独立交付[showcase七服务/TLS模式](storage-tls-deployment.md)。前端继续暂停，本批不增加业务路径或变更订单/券规则。

## 使用方式

在项目根目录，使用现有统一入口：

```powershell
.\scripts\deploy-base.ps1 -Action Build
.\scripts\deploy-base.ps1 -Action Init -Mode components -Port 18080 -Docs
```

Init只建立新实例身份和秘密文件；不会把原base库/卷改成components，不连接本机开发MySQL。用其输出的实际目录设置`$instance`后启动：

```powershell
$instance = '这里填Init输出的完整目录'
.\scripts\deploy-base.ps1 -Action Start -Instance $instance
.\scripts\deploy-base.ps1 -Action Status -Instance $instance
```

组件开关与服务集合一起校验。普通Init默认仍base三服务、资金与所有可选客户端关闭；components显式启用缓存、消息/消费者、券Redis，**活动仍默认SYNC，资金仍关闭**。确需虚构交易演示时，创建新components实例时额外选择`-DemoMoney`；这只是模拟支付开关，不引入真实支付。

`-Docs`显式开启本机Swagger：`http://127.0.0.1:18080/swagger-ui/index.html`。默认关闭文档。ADMIN仍通过`-Action Admin -Instance $instance`在本机交互创建，没有默认管理员密码。

只有Nginx发布回环端口。Redis、AMQP及RabbitMQ管理端均不映射宿主，也不经Nginx代理；本批不提供面向浏览器的组件管理页。需要状态时用Status/本机受控诊断，不为了面试展示开放管理端口。

## 私有身份与持久策略

| 组件 | 身份与权限 | 数据策略 |
|---|---|---|
| 评分Redis | `rating_app`专用随机密码，仅本实例评分前缀及GET/SET/DEL/连接命令；默认用户关闭 | 64MiB、allkeys-lru、无AOF/RDB、临时数据目录；失效/停机按原协议回源MySQL |
| 券Redis | `coupon_app`独立密码/实例/前缀，保留原Lua所需读写/脚本/INFO，拒绝FLUSH/CONFIG/ACL/SHUTDOWN | 128MiB、noeviction、AOF/everysec、独立卷；MySQL事实仍为恢复依据，runId变化走原代次重建 |
| RabbitMQ | `campuslife_app`非guest、无管理标签，仅本实例vhost内原六个具名交换机/队列资源；运维账号独立 | quorum主/死信拓扑保持；整个`/var/lib/rabbitmq`持久卷、固定节点名及cookie，不借此声明单节点HA |

两Redis运维身份与Broker运维身份不挂给应用；app只获DB/JWT及三个运行密码。ACL文件只含哈希，Rabbit定义用带盐SHA256哈希；这些文件仍按私有秘密保管，不上传仓库/聊天。Rabbit首次导入定义不创建guest/默认vhost，后续启动不覆盖已存在定义；cookie不符旧卷时失败，不随机重建身份。

新部署固定Redis8.2.9及digest，替代旧实验起点8.2.4；同版本线后续安全修复见[官方8.2说明](https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/release-notes/redisce/redisos-8.2-release-notes/)。只适用于本批新实例，不升级旧Redis或把8.2.4测量结论移用为新结果。RabbitMQ保持已验证4.3.6，仍在[官方支持期](https://www.rabbitmq.com/release-information)。源码仅给评分配置增加可选ACL；原无密码回环配置保持兼容，新部署要求生成的独立身份。

## 既有维护入口

维护动作没有新增HTTP接口。每条命令调用同一应用镜像中原来的独立main；只把动作/ID/版本等维护输入转给一次性进程，不启动业务HTTP、消息消费者或恢复定时任务。

活动模式切换遵循[原协议](async-coupon-admission.md)，使用刚Inspect得到的`controlVersion`，不要使用商家活动业务version。下面ID和版本都是占位说明，必须替换实际值：

```powershell
.\scripts\deploy-base.ps1 -Action Coupon -Instance $instance -Operation Inspect -Id 123
.\scripts\deploy-base.ps1 -Action Coupon -Instance $instance -Operation Pause -Id 123 -Version 0
.\scripts\deploy-base.ps1 -Action Coupon -Instance $instance -Operation Inspect -Id 123
.\scripts\deploy-base.ps1 -Action Coupon -Instance $instance -Operation Async -Id 123 -Version 1 -AllInstancesUpgraded
.\scripts\deploy-base.ps1 -Action Coupon -Instance $instance -Operation Inspect -Id 123
.\scripts\deploy-base.ps1 -Action Coupon -Instance $instance -Operation Resume -Id 123 -Version 2
```

示例0/1/2不能连续照抄：恢复任务也可能推进控制版本，每个动作前重新Inspect。`-AllInstancesUpgraded`是操作者声明，不是程序远程证明；旧版本实例必须确已停止。失败后先Inspect，不假定步骤都未提交，不直接UPDATE门闩或加库存。

切回SYNC仍是Pause→检查PENDING排空→Sync→Resume，各步带最新控制版本；Sync必须先通过原Redis新代次关闭/核对，Redis故障不能自动切回同步。整套Stop/Start不会把原PAUSED意图改成OPEN；Redis运行身份变化后恢复任务按原围栏重建，保持期限与原库存事实。

消息检查沿原MessageMaintenance：

```powershell
.\scripts\deploy-base.ps1 -Action Message -Instance $instance -Operation Inspect -Id '这里填32位messageId'
.\scripts\deploy-base.ps1 -Action Message -Instance $instance -Operation Replay -Id '这里填32位messageId'
```

Replay在本机提示中填原因，原活动租约/已消费判断和原messageId保持。该旧入口的“已消费不重放”判断针对通知消费者；不要将它误当作通用优惠券消息运维协议。优惠券重建/超时处理沿原专用流程，不批量重置outbox状态。

MessageMaintenance是普通JDBC入口，原来直接读进程环境；包装器只在该维护子进程里从DB秘密文件导入密码，密码不放Docker参数或日志。Spring入口继续用configtree。维护进程结束即退出，不是后台服务。

## 点击验收与停止

Swagger切换各角色令牌，使用虚构店铺和账号：管理员审核→商家配置打印/价目/营业并发布优惠券→CLI对指定活动切ASYNC→学生POST领券（202只代表受理）并GET查终态→打印报价选本人券→创建/模拟支付/商家接单、开始、待取件→学生通知、取件和评价。若没有显式选择DemoMoney，支付应返回503，不修改默认开关来绕过。

停止/重启沿同一入口：

```powershell
.\scripts\deploy-base.ps1 -Action Stop -Instance $instance
.\scripts\deploy-base.ps1 -Action Start -Instance $instance
```

保留四个卷（MySQL、LOCAL、券Redis、RabbitMQ）及原秘密；评分缓存可丢弃。生命周期标签保留模式，现有components卷不能通过改manifest静默变base；目前不提供原地模式转换或旧数据复制入口。普通Stop不删卷、不执行prune，不退出Docker Desktop/WSL。

自动验收入口：

```powershell
.\scripts\test-deploy-components.ps1
```

All顺序执行六服务检查与默认base小回归；只复现某一批可用`-Phase Components`或`-Phase Base`。测试新建独立身份/数据，包含停评分Redis、券Redis和Broker及全套重启，不对开发环境做故障注入。结束仅清理本轮资源/秘密，保留脱敏证据。B2的TLS与[C私有Actuator](private-observability.md)已另行验证；真实Linux宿主、全栈备份恢复和容量测试仍未完成。

本批结果与失败边界见[验收报告](reports/component-deployment-2026-11-02.md)：20项相关单测、34组组件验收和7组默认模式小回归通过；运行OpenAPI仍75个业务操作。
