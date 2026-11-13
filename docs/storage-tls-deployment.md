# M06.1-B2 私有S3 TLS部署

本批在[六服务模式](component-deployment.md)上增加SeaweedFS，形成显式`showcase`七服务模式；按[ADR-0057](decisions/0057-single-instance-deployment.md)验收。默认`base`仍是三服务/LOCAL，各可选组件与模拟资金关闭；`components`仍是六服务/LOCAL。各模式只创建新实例，不原地转换已有卷、不搬迁开发文件。

## 初始化与启动

已验证环境为Windows、Docker Desktop Linux引擎、项目Java21/Maven、Python标准库、OpenSSL与Java keytool。证书生成只使用实例内OpenSSL配置，不改系统配置或系统信任库。Linux真实宿主尚未验收。

在项目根目录执行：

```powershell
.\scripts\deploy-base.ps1 -Action Build
.\scripts\deploy-base.ps1 -Action Init -Mode showcase -Port 18080 -Docs
```

Init只生成新实例配置、随机秘密和私有证书，不启动服务。将实际输出的目录填入变量，再显式初始化该实例的存储：

```powershell
$instance = '这里填写Init实际输出的实例目录'
.\scripts\deploy-base.ps1 -Action StorageInit -Instance $instance
.\scripts\deploy-base.ps1 -Action Start -Instance $instance
.\scripts\deploy-base.ps1 -Action Status -Instance $instance
```

StorageInit只先启动SeaweedFS，以独立初始化身份新建专属桶、`_storage-id`标识和原有窄权限策略，最后验证应用身份可以读取精确标识。已有桶缺失标识、标识不匹配、结果不确定均拒绝接管；不要靠改写标识绕过错误。初始化身份不授予Java应用。正常Start只校验初始化回执、证书指纹和原标识，不重建桶、不更新密钥或认领已有数据。

只有Nginx映射`127.0.0.1:18080`；S3和其它组件均不映射宿主端口。`-Docs`显式打开本机Swagger，地址为`http://127.0.0.1:18080/swagger-ui/index.html`。需要模拟付款时，在新建测试实例的Init命令中明确增加`-DemoMoney`；缺省关闭。活动仍默认SYNC，异步切换沿[原维护协议](component-deployment.md)，不会自动开启旧活动。

平台ADMIN仍由`-Action Admin -Instance $instance`本机显式创建，无默认管理员。Swagger使用原注册/登录/Authorize和`POST /files`、`GET /files/{fileId}/content`流程；业务路径和PDF限制不变。S3上传仍完整回读验证，下载仍经后端鉴权，不向用户返回对象直链。

## TLS、权限与秘密边界

七个常驻服务使用三个私有网络、五个持久卷。SeaweedFS沿用固定4.51 digest；S3主端口8333配置证书和私钥，并设置`s3.port.https=0`，使主端口本身只接受TLS。不能另开HTTPS端口后仍保留HTTP主监听。固定版本[启动逻辑](https://raw.githubusercontent.com/seaweedfs/seaweedfs/4.51/weed/command/s3.go)和真实明文请求400均已核对。

私有CA有效期365天，服务证书90天，SAN仅`seaweed`。Java只信任本实例证书库，并保持证书链和主机名校验；错CA/错主机名已通过真实SDK上传请求验证为503且不回落LOCAL。测试别名`seaweed-wrong`没有对应SAN。没有跳过TLS校验或放宽原跨容器明文限制。

| 材料 | 可见范围 |
|---|---|
| CA私钥 | 宿主实例秘密目录；不挂载任何服务 |
| 服务证书/私钥 | SeaweedFS；应用不持有服务私钥 |
| 初始化身份 | 宿主初始化过程及SeaweedFS认证配置；不挂载应用 |
| 应用S3身份 | Java应用及SeaweedFS认证配置；只有本桶、本命名空间对象权限及精确标识读取 |
| 公共CA/JKS | 应用只读挂载，记录SHA256；不修改JRE全局信任 |

JKS只有公共信任证书，不含私钥。生成密码经临时文件交给keytool，随后删除；JVM参数不包含密码。JSSE以空密码加载公共证书条目，不执行JKS密码完整性MAC检查，完整性边界由实例私有目录、只读挂载及Start前指纹核对承担；TLS链和主机名验证仍执行。没有实现自动续期或密钥轮换；到期应安排独立证书维护任务，不能临时关闭验证。

显式初始化使用标准库SigV4和镜像内curl，仅向本实例白名单服务发送，签名通过标准输入传递；业务仍使用原AWS SDK2.57.8/Apache5，没有引入新的生产依赖。应用不能写/删标识、修改桶策略、读写其它前缀或删除命名空间目录；桶列表保留原空列表语义。

SeaweedFS其它控制面仍在专属内部网络的信任边界内；本批不声称所有内部控制协议均已TLS/认证，也不提供外部访问。秘密目录、CA私钥、桶/命名空间身份应一并按私有材料保管；不上传仓库或分享实例目录。

## 停止、重启与验证

```powershell
.\scripts\deploy-base.ps1 -Action Stop -Instance $instance
.\scripts\deploy-base.ps1 -Action Start -Instance $instance
```

Stop保留五个卷及实例秘密。Start不会重新初始化标识；SeaweedFS的健康检查只证明TLS监听可用，不能代替已初始化、鉴权和新写验证。初始化中断产生的失败记录保留在实例目录，已生成秘密会被清理；不要把缺失回执的半成品当作可运行实例。

自动验收新建独立测试库、身份和文件，不访问用户项目库或上传根。实际已分批执行：

```powershell
.\scripts\test-deploy-storage.ps1 -Phase Native
.\scripts\test-deploy-storage.ps1 -Phase Business
.\scripts\test-deploy-storage.ps1 -Phase Regression
```

省略Phase默认All，依次执行完整Business及Regression；Business已包含Native的12组，不重复累计。最终28组存储检查、7组默认base回归及1组六服务配置回归通过，另16项原S3策略单测通过，详见[报告](reports/storage-tls-deployment-2026-11-02.md)。包含真实Java错CA/主机名、20MiB、仅测试夹具LOCAL共存、原清理器精确删除、整套重启后新上传与下载字节一致性。

测试只清理自有容器/卷/网络和生成秘密，保留脱敏日志；不自动退出Docker Desktop或关闭WSL。B2验收时生产JAR/148依赖与B1相同，仍75业务操作/V24/32表；后续C增加的观测依赖见[C指南](private-observability.md)。没有验证全栈备份恢复、Linux真实宿主、HA或固定RTO；M04-C冷恢复新写超时原因仍未定位，本次普通重启成功不能称已修复。C私有管理面现已另行交付，D容器组合与指南已交付（真实Linux宿主未执行），当前下一项为M06.1-E全栈备份恢复，前端继续暂停。

后续进度：E隔离冷备/新卷恢复已[验证](full-stack-recovery.md)。用户现已补充有阿里云Linux服务器，当前尚未核对或部署；早期“暂无环境”是当时验收回答。下一项先只读核对云端条件，M06.2固定负载仍待做，前端暂停。
