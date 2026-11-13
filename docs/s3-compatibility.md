# M04.2-A 私有S3隔离探针

日期：2026-11-02。按用户已采用的[ADR-0056](decisions/0056-private-object-storage.md)/[方案](object-storage-design.md)完成A：[报告](reports/s3-compatibility-2026-11-02.md)，最终71项不同检查通过（S3协议33、既有LOCAL策略38），普通构建/web-only启动及115项生产依赖指纹通过，专用资源已回收。这是A时点的结果。B现已[接通并验证](reports/s3-file-chain-2026-11-02.md)，当前75操作/V24/32表、默认LOCAL，SDK进入生产包；C故障/恢复现已[验证](reports/s3-recovery-2026-11-02.md)，实际迁移仍未授权。

## 运行入口与隔离

在项目根目录PowerShell中运行：

```powershell
.\scripts\test-s3.ps1
```

需要项目Java21工具链、Maven及Docker Desktop Linux引擎。脚本使用随机标签创建专用SeaweedFS容器、持久卷、bridge网络和配置文件，唯一发布端口为随机`127.0.0.1:端口→8333`；无其它容器接入同一网络，不发布filer/master/volume/admin入口。WebDAV、管理UI和额外catalog监听显式关闭；其余容器内控制入口不对宿主映射。专用bridge仍允许容器出站，不应称为禁止外网的网络隔离。

本机Docker版本的`--internal`网络会使实际`NetworkSettings.Ports`为空，即使`HostConfig.PortBindings`记录了请求端口。探针使用专用bridge，并同时核对请求与生效端口、网络唯一成员和标签。启动参数通过字符串数组传给docker，再读取实际Args核对，防止PowerShell把含点号参数拆开导致忽略认证配置。

凭据仅本轮随机生成，保存在Git忽略的`.cache/s3-it/<identity>`，不使用AWS默认凭据链、本机业务账号、开发DB/JWT或持久文件根；SDK显式静态凭据及回环endpoint，禁用系统/环境代理发现。不要把该目录中的manifest/config复制到聊天或提交仓库。脱敏probe结果只含样本hash、检查名、状态与耗时。

脚本不启动Spring应用、MySQL、Redis或RabbitMQ，也不添加HTTP调试入口。A是协议基础验证，适合自动探针；学生/商家Swagger上传下载的S3业务验收由B/C提供，不能拿本探针代替。

## 版本与构建

- SeaweedFS `4.51`，镜像索引digest：`sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8`；Linux/amd64子manifest为`sha256:3a522c379599e163433a636e75022630848b63a74f08ea37bf06f8d776e9a707`。启动命令显式固定mini目录、认证配置、bind、禁自动建桶等参数，不依赖latest。
- AWS SDK for Java BOM `2.57.8`，同步`S3Client`＋Apache5客户端；A时仅有显式`s3-probe` profile的test依赖；B开始SDK作为固定版本生产依赖打包，只有后端显式启用才创建客户端。
- `src/s3-test/java`及`target/s3-probe-test-classes`只在该profile参与构建，普通`src/test/java`和`target/test-classes`路径保持。直接手写`mvn test`不会执行此探针；应使用脚本，使所有权、凭据、资源清理和环境恢复同时生效。
- 未引入传输管理器、CRT应用代码或直传/预签名；A不修改文件入口/迁移。B的实际文件适配和V24见[业务指南](s3-file-storage.md)，不能用A探针代替。

## 检查范围

专用管理员只用于准备桶、标识、受保护前缀及桶策略；模拟应用身份只获本命名空间对象前缀的读写和标识读取权限。空/目录key由测试侧精确随机key校验拒绝，桶策略再拒绝命名空间目录删除。这个测试侧校验还未成为生产业务保护；B必须在正式适配中落实并回归。

探针覆盖20MiB确定性二进制样本的PUT/HEAD/流式GET与SHA256、SDK默认校验和、显式SHA256及错误校验和、同key同字节重试、精确DELETE重复执行。样本用于协议读写，不是PDF解析或上传业务验收。

本版本实际采用path-style、`chunkedEncodingEnabled(false)`和已知长度请求体，保留SDK校验和；默认行为曾使显式SHA256小对象PUT出现400 IncompleteBody，另有小对象PUT读超时。不能仅看到400就认为服务端验证了坏checksum，负向检查明确要求`BadDigest`且没有创建对象。20MiB样本实际使用的校验和头包含CRC32，显式SHA256也经原字节与响应校验。

4.51传统Actions中的无通配符对象路径未按期望放行标识读取（其实现的无通配分支只匹配桶级动作）。测试配置用窄`Read:桶/_storage-id*`，再加桶策略显式拒绝`_storage-id?*`，实测只读原标识、不能读相似后缀对象，也不能改/删标识；不改成整桶Read。桶列表无权限时此版本返回200空数组而非403，探针对空结果作断言；对象列表、跨桶及其它管理操作仍须403。

身份覆盖匿名GET/HEAD/PUT/DELETE、错误签名、跨前缀/桶、枚举、建删桶/改策略和目录删除；拒绝后核对原对象未变。SDK客户端以4连接、2秒连接/获取、5秒读空闲、15秒尝试/30秒调用、最多2次尝试为正常初值；独立本机黑洞套接字验证缩短超时和尝试上限，不能将此称为真实S3生产故障。

停止/启动及重启只作用于本轮已核对标签的容器，核对原20MiB字节、命名空间标识、匿名拒绝和桶策略保持。静态凭据配置文件在重启期间保留；这不等于只靠数据卷自动保存凭据，也不等于已经完成备份恢复到新环境。真正的迟到PUT、业务SQL恢复、备份/迁移和多进程边界在B/C。

## 结果与清理

JUnit XML在`target/surefire-reports`，脱敏协议结果在本轮目录的`probe.json`；下次运行前保留需要的原始报告。[交付报告](reports/s3-compatibility-2026-11-02.md)及[机器汇总/SDK依赖锁定](reports/s3-compatibility-2026-11-02.json)列明实际轮次、失败与局限，不将启动失败计为兼容通过。

脚本finally核对标签后回收自身容器、卷、网络，并恢复本进程环境变量；失败保留诊断文件。禁止全局prune、清空整个.cache或删除开发持久卷。镜像和Maven缓存可以保留供复现，临时样本在测试结束精确删除。B/C前不启用开发S3或修改本机启动配置。
