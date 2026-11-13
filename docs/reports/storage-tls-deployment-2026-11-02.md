# M06.1-B2 私有S3 TLS部署验收

日期：2026-11-02，America/New_York；原始UTC日志可能显示11月3日。状态：本批已交付并验证，下一项仅M06.1-C。依据[ADR-0057](../decisions/0057-single-instance-deployment.md)，[使用与复现](../storage-tls-deployment.md)，[机器记录](storage-tls-deployment-2026-11-02.json)。

## 结果与改动

新增显式showcase七服务模式，在B1的六服务上增加固定SeaweedFS4.51原生TLS、实例私有CA和独立S3身份。默认base三服务与components六服务保持LOCAL/S3关闭；showcase显式选择S3。只改部署入口/配置并新增存储初始化和验收脚本，未改Java源码、依赖、业务协议、迁移、前端或开发配置。

Java加载本实例公共信任库，证书链与主机名校验保持。S3主端口本身为TLS-only，无宿主端口发布；初始化独立且不接管无标识或标识不符的已有桶。普通Start只读核对身份；原应用前缀权限、标识保护、SDK完整上传回读与受控下载保持。CA私钥仅留宿主，服务私钥不授予Java应用。

初始化签名工具只处理运维请求，凭据/签名通过标准输入发送，不写Docker命令参数；生产上传仍由原SDK处理。JKS只有公共证书，以空密码读取条目，文件完整性靠实例目录、只读挂载及指纹核对，不执行JKS密码MAC校验；没有禁用TLS验证。CA365天/服务证书90天，自动续期和轮换尚未实现。固定4.51[源代码](https://raw.githubusercontent.com/seaweedfs/seaweedfs/4.51/weed/command/s3.go)显示：提供证书且独立HTTPS端口为0时，主端口使用ServeTLS；另开非零HTTPS端口会保留HTTP监听，本批未使用后一配置。

## 实际验证

| 范围 | 最终结果 | 证据与边界 |
|---|---|---|
| 原S3StoragePolicyTest | 16项通过，0失败/错误/跳过 | 单测检查原endpoint/LOCAL策略，不代替真实TLS |
| Native预验收 | 12组通过 | 正确CA、错误SAN、拒绝明文/匿名/错误签名、精确前缀/标识/目录权限、空桶列表语义、初始化拒绝标识不符 |
| Business最终验收 | 28组通过，包含上述12组 | 另16组覆盖实际七服务/秘密隔离、真实SDK、20MiB、鉴权、LOCAL共存、清理和重启；不能将Native再加一次 |
| 默认base回归 | 7组通过 | 真实三服务启动，可选组件/资金关闭、原三秘密、旧manifest兼容、停止保留两卷 |
| components配置回归 | 1组通过 | 六服务/LOCAL/S3关闭、原13秘密；只做配置回归，未重复B1整套六服务业务验收 |

Business实际观测包括：

- 正确私有CA下PDF上传201、完整回读与受控下载成功，数据库保存S3定位和自有命名空间；匿名下载401；运行OpenAPI仍75业务操作。
- 在唯一应用进程上分别替换错误公共信任库和使用错误主机名，真实Java SDK上传均503 `FILE_STORAGE_UNAVAILABLE`，不新增AVAILABLE文件、不回落LOCAL。恢复正确信任后旧内容可读。
- 在测试夹具内临时使用LOCAL上传，再恢复S3；每次只重建同一个应用实例，没有两个文件生命周期进程并行。新旧定位共存、原LOCAL行不迁移；20MiB PDF经Nginx和真实SDK链上传，下载SHA与原样本相同。
- 停止整个七服务保留五卷；重启后原LOCAL/S3内容及标识仍正确，原Java清理器将测试到期文件标记DELETED且对应对象HEAD404；新PDF上传201并下载字节相同。

实际执行`test-deploy-storage.ps1 -Phase Native`、`-Phase Business`、`-Phase Regression`；单测通过`MAVEN_ARGS=-Dtest=S3StoragePolicyTest`配合`dev.ps1 -Action Test`。没有运行不相关全量回归或新性能测试。

原始夹具记录均位于Git忽略的`.cache/deploy-base/`：Native为`d4e55beb33f04f3189ac828a4ef501db`，Business为`8426fd9d3b7c41f596eabe6bd5525e4f`，base回归为`6229f05c54a7455199e315d0adba574e`，components配置回归为`f397557b44f748ad8ae3f243dc36ffd8`。检查名称、公共证书指纹、JAR及交付文件摘要见机器记录。

## 失败与修正

首个Init在调用本机OpenSSL时失败：该安装缺少其默认配置路径。失败发生在Docker资源创建之前；只删除已核实属于该测试实例的生成秘密，保留`1c6bc91be26b465a8f352f21810a2365/initialization-failure.json`。随后改为实例内显式OpenSSL配置，并为Init半成品增加精确清理。未修改系统OpenSSL/JRE配置；之后独立Native和Business轮均通过。失败不计为通过，也未删除失败记录。

## 指纹、清理及限制

生产JAR SHA256为`041fd9a692f133ee53cd540c10de9a593ac1336b3a8a7c3091d8573c79c411ea`，与B1完全相同，148项运行依赖保持；重建的容器镜像因启动脚本变化而不同。开工549个既有源/配置/脚本文件中，只有`deploy-base.ps1`、`deploy_base.py`、`app-entrypoint.sh`、`components.yaml`四个部署文件变化，其余545个保持。新增文件不混入既有文件不变统计。

每轮最终资源清理均为0容器/0卷/0网络，生成秘密已删除；最终按测试标签全局核对亦为0。错误CA测试私钥/临时密码亦已删除。未访问项目库、用户持久上传根或真实迁移；Docker Desktop/WSL保持运行，未执行全局清理或关闭。

本批是单实例、本机Docker Desktop Linux引擎验证，不能声称Linux真实宿主、容量、多节点HA、自动证书维护、全栈备份恢复或固定RTO通过。SeaweedFS内部控制协议仍在独立内部网络的信任边界内，不宣称全控制面TLS/认证。M04-C冷恢复新写超时原因未定位，本次正常整套重启后新写成功不能替代冷恢复故障修复结论。C观测、D宿主条件项、E全栈恢复仍待独立任务，前端继续暂停。
