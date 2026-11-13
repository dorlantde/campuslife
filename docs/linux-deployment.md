# M06.1-D 组合运行与Linux部署指南

当前进度：[D.6i新正式包定向验收](reports/cloud-final-remaining-2026-11-06.md)通过，维护整改收口，原失败保持；新release1cf0cb53a24a。D.7[观测与交接方案](cloud-operations-handoff-design.md)已整理、实施建议待采用：建议无常驻实例，按需私有运行可选，下一项M06.2-A综合负载方案。最近资源清理来自D.6i审计，本轮未查询实时ECS状态；保留探针/SSH观察，下面为通用指南，不代表已建立长期/公网部署，新机仍须显式compact4g。

后续[M06.2-A负载方案](comprehensive-load-design.md)已整理，尚待采用；当前建议采用后先做B.1同case监督/回执底座，不是立即运行压测或新建常驻实例。最新顺序以[路线图](roadmap.md)为准。

本批将已分别验收的基础部署、双Redis/MQ、原生S3 TLS及私有管理面组合在同一套七服务环境，按[ADR-0057](decisions/0057-single-instance-deployment.md)交付。默认base三服务和LOCAL模式保持，不转换已有实例、不搬迁开发数据。Windows入口仍是PowerShell封装；Linux使用同一套Python入口，不需要安装PowerShell。

**真实Linux宿主尚未验收。** 用户本轮确认暂无现成Linux主机/虚拟机；本轮实际平台为Windows＋Docker Desktop Linux引擎/x86_64。下面的Linux宿主步骤调用现有Python命令与参数，属于待目标宿主执行的验收清单，不把容器运行成功称为宿主部署完成。不自动采购、安装发行版或配置公网。

## 目标宿主准备

选用独立项目目录和现成Docker Engine/Compose环境；当前候选为Linux x86_64、普通rootful Engine。其它架构、Rootless、发行版特有挂载/安全策略均未验证。安装方式需在目标发行版明确后按官方文档选择，本指南不代为改系统用户组、文件权限或网络。

需要Python3、Java **JDK21**、Maven、OpenSSL、keytool、Docker CLI和Compose插件，能在同一部署用户的PATH中找到。可先检查：

```sh
python3 --version
java --version
mvn --version
openssl version
keytool -help
docker version
docker compose version
```

当前Python入口使用标准库；Java版本不符或组件不可用时停止，不靠跳过检查继续。Docker权限由宿主操作者管理，Docker用户组具有很高的宿主权限，不能把容器内非root与宿主权限等同。[Docker官方说明](https://docs.docker.com/engine/install/linux-postinstall/)

只准备源码、构建描述和部署/脚本文件；不复制Windows的`.local`、`.cache`、`.tools`、`.vscode`或旧实例秘密。目标主机重新构建镜像、生成自己的清单/身份；Windows的绝对路径、镜像ID和生成目录不是可直接搬用的部署包。历史真实数据迁移不在本批范围。

## 新建与启动（目标宿主待执行）

在项目根目录执行构建。Python入口在当前验证中已被Windows封装调用；Linux会从PATH选择`mvn`、`java`和`keytool`：

```sh
python3 scripts/deploy_base.py build
python3 scripts/deploy_base.py init --mode showcase --port 18080 --docs --observe
```

Init不启动服务，只在独立私有目录创建清单、证书和秘密。将实际输出的完整目录填入变量：

```sh
instance='这里填写Init实际输出的实例目录'
python3 scripts/deploy_base.py storageinit --instance "$instance"
python3 scripts/deploy_base.py start --instance "$instance"
python3 scripts/deploy_base.py status --instance "$instance"
python3 scripts/deploy_base.py observe --instance "$instance" --operation Health
python3 scripts/deploy_base.py observe --instance "$instance" --operation Snapshot
```

showcase明确启用评分Redis、券Redis、MQ和私有S3；`--observe`另外启用仅容器回环的管理面。`--docs`只为本机人工验收开放Swagger。资金仍默认关闭；隔离虚构交易演示需要在新建实例Init时**另行显式**增加`--demo-money`。活动仍默认SYNC，不能据服务启动自动认定已开放ASYNC。

ADMIN仍需显式本机创建，没有默认管理员或密码：

```sh
python3 scripts/deploy_base.py admin --instance "$instance"
```

用户名、昵称和新密码在本机提示中填写，密码不作为命令参数。异步活动仍沿[原Inspect/Pause/Async/Resume流程](component-deployment.md)处理；不要手改门闩或直接编辑Redis代替维护协议。管理Health/指标使用独立运维身份，详见[私有管理面](private-observability.md)。

只在Nginx映射宿主`127.0.0.1:18080`；MySQL、Redis、MQ、S3及管理端口均不映射。宿主本机可打开`http://127.0.0.1:18080/swagger-ui/index.html`使用原注册/登录/Authorize/上传/订单操作。远端访问方式需在主机明确后另配受控回环转发，本批不改为公网监听。

## 持久状态与启停

七个常驻服务为app、nginx、mysql、rating-redis、coupon-redis、rabbitmq、seaweed。五个持久卷保存MySQL、LOCAL根/暂存、券Redis AOF、RabbitMQ和SeaweedFS；评分Redis是可丢弃缓存，不另建持久卷。服务分布在三个网络，身份与卷标签由入口核对，一个业务Java实例持有原实例文件互斥。

```sh
python3 scripts/deploy_base.py stop --instance "$instance"
python3 scripts/deploy_base.py start --instance "$instance"
python3 scripts/deploy_base.py observe --instance "$instance" --operation Snapshot
```

Stop按依赖关系停止服务，保留卷、秘密与清单；Start复核本地镜像ID、服务/端口集合、卷归属、TLS指纹/标识，不重新初始化密钥或认领其它数据。Compose的healthy只辅助启动顺序，不保证业务恢复、自动摘流量或运行期自动重启。[Compose官方说明](https://docs.docker.com/compose/how-tos/startup-order/)

重启后的券Redis具有新runId，原协调器可能先重建代次；若活动停止前PAUSED，应保持暂停并核对已发券事实，之后使用最新Inspect版本显式Resume。不能看到HTTP health为UP就跳过门闩核对，不能改成同步模式“修复”故障。

秘密由Compose逐文件挂载，私有目录0700、指定文件0444便于容器读取；它们不是加密秘密服务。CA私钥留宿主，应用只拿S3应用身份及公共信任库；数据库root、组件维护凭据不会全部交给应用。实例目录及未来备份需要操作者按私有材料保管，不通过放宽目录或关闭证书验证解决权限错误。[Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/)

## 验收方法与停止条件

当前Windows实际执行：

```powershell
.\scripts\test-deploy-showcase.ps1
```

目标Linux宿主补验收时，直接运行同一测试入口：

```sh
python3 scripts/test_deploy_showcase.py
```

测试使用新test身份、自有卷/秘密/文件，显式开启模拟资金；全程不连接项目开发库或用户上传根。流程是异步领券→S3打印用券/模拟付款→商家待取件通知→学生取件评价→真实评分缓存；暂停活动后整套Stop/Start，核对五卷、身份、事实与文件，最后显式Resume并验证新领券、新上传。仅停止/启动当前卷，**不等于备份恢复**。

自动测试结束只销毁自身test身份资源、删除生成秘密，保留脱敏日志。普通实例的Stop不删卷；不使用全局prune，不自动退出Docker Desktop或关闭WSL。生产单实例边界保持，不通过scale或复制卷让多个应用共用文件根。

实际组合结果与源/制品摘要见[本批报告](reports/showcase-lifecycle-2026-11-03.md)。真实宿主补验收必须记录目标系统/工具版本、实际启动/权限/端口、原卷重启和新业务写入；脚本记录宿主系统名，独立宿主资格仍需结合环境资料核对，不能仅由系统名自动认定。任一项失败保留原卷/日志，不能用重建空库或删卷制造通过。当前不承诺固定启动/退出时间、RTO、HA或容量。

本轮完成组合运行与指南后进入M06.1-E全持久组件备份恢复。M04-C冷恢复新写超时原因仍未知，普通重启通过不能称其已修复；前端继续暂停，公网及真实数据搬迁仍需独立授权。

后续进度：E隔离冷备/新卷恢复已[验证](full-stack-recovery.md)。用户现已补充有阿里云Linux服务器，当前尚未核对或部署；早期“暂无环境”是当时验收回答。下一项先只读核对云端条件，M06.2固定负载仍待做，前端暂停。
