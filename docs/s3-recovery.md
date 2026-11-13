# M04.2-C 私有文件存储恢复与隔离迁移验收

按[ADR-0056](decisions/0056-private-object-storage.md)继续B的存储链。本指南仅针对专用测试环境；实际执行和限制见[报告](reports/s3-recovery-2026-11-02.md)。默认LOCAL/S3关闭、业务HTTP契约及前端暂停保持。

## 自动复现

需要已安装的Java21、Maven、MySQL8客户端/服务端（含mysqldump）、Docker Desktop Linux引擎。项目根目录运行：

```powershell
.\scripts\test-s3-recovery.ps1
```

Faults和Restore分别新建MySQL/S3环境。也可单独运行：

```powershell
.\scripts\test-s3-recovery.ps1 -Phase Faults
.\scripts\test-s3-recovery.ps1 -Phase Restore
```

脚本只控制带本轮随机身份的测试容器/卷/网络、临时数据库和子JVM，不连接开发项目库或持久上传根。故障代理绑定随机回环端口，转发原签名Host/头/固定字节，但不将凭据、请求头或PDF内容记录到证据。子JVM停点仅在测试类和专用profile中，生产包没有调试接口。

四个业务强杀窗口是UPLOADING提交后、PUT完成发布前、AVAILABLE提交后HTTP返回前、对象删除后DELETED回写前。恢复使用新JVM，上传期限通过隔离Clock推进，无需等待30分钟；SDK超时及代理延迟使用真实网络/时间。未知PUT测试先缓存真实已签名请求，等待客户端超时/DELETED提交，并让新进程再次删除；再释放原请求，使它们实际写到SeaweedFS，最后新进程从持久墓碑回收。不是向桶里手工补一个对象冒充晚到请求。

对象缺失/认证失效返回503但不改元数据。断流在响应提交后中断，不能在PDF尾部拼JSON。**下载不重新计算业务SHA256：外部以合法新校验和覆盖同长度内容时，下载可能200；演练会对照冻结SHA识别差异并恢复测试原件。** 上传完整回读仍会拒绝错内容。这一边界不意味着支持管理员随意改写AVAILABLE对象，也不能据校验和声称内容永不被覆盖。

## 离线备份与恢复边界

演练先关闭源应用及全部后台/连接，再取得SQL逻辑备份、停止存储并归档整个持久卷、复制完整LOCAL根（含.storage-id），另外保存私有S3认证配置。恢复到新MySQL进程和新SeaweedFS容器/卷/网络，比较全部表的规范化行摘要和文件字节，再验证旧订单/学生与商家下载。

真实部署的备份清单还必须包含独立保管的JWT签名密钥、数据库连接凭据、S3应用身份/命名空间绑定及版本配置。演练恢复使用同一隔离环境的JWT密钥，未将其写入仓库或证明密钥管理系统可用。认证配置和SQL备份包含测试凭据/摘要/会话事实，均留在Git忽略的.cache；不要上传或转发这些原始备份。

不能用重启原容器代替恢复，也不能只复制看似PDF的数据片段。恢复后先验证存储身份、数据库版本、旧内容和新写入，最后才开放业务及清理。单节点和离线一致性窗口保持；没有在线多组件一致性快照、任意时间点恢复或HA承诺。

## 隔离迁移计划格式

预演只操作恢复副本中的指定AVAILABLE LOCAL文件。先关闭恢复应用，保存migration-plan.json，再复制固定key/完整GET校验，最后以原kind/id/key/status/version条件更新定位和version；不改变原期限/订单/资金/事件。原条件重复执行必须0行，随后读取已提交事实，不据未知响应盲删目标。原LOCAL字节一直保留。

计划字段为fileId、from/to的kind/namespace/key、expectedVersion、bytes、sha256、expiresAt；计划不含密钥或公开地址。校验失败/旧版本冲突应停下该项并保留源，不能强行覆盖或改变状态掩盖缺文件。本轮没有真实库Apply入口；若将来搬迁真实文件，需要先提供具体清单、可恢复备份和维护窗口，再独立授权。源目录删除不包含在文件定位切换授权中。

## Swagger只读验收窗口

为避免占用开发数据，使用恢复后的临时应用：

```powershell
.\scripts\test-s3-recovery.ps1 -Phase Restore -ManualSeconds 900
```

启动时控制台会打印本轮.cache/s3-recovery/<随机ID>/manual.json路径。待文件生成后，在VS Code打开该文件，获取swagger地址、虚构学生/商家/管理员用户名、测试密码以及file/order ID；只在本机使用，不发到聊天。应用仅绑定127.0.0.1，窗口结束后会关闭并继续完成预演及清理。

1. 打开manifest中的swagger地址。POST /auth/login使用学生账号，复制响应data.accessToken到Authorize，然后GET /files/{fileId}和GET /files/{fileId}/content。s3File和localFile都应200，PDF可打开、2页。
2. GET /orders/{orderId}及事件：s3Order为已打印待取件，localOrder为待付款；核对冻结明细。此窗口只查看/下载，不提交取消、取件、改密或配置变更。
3. 清除授权，登录商家并Authorize；s3File内容200，localFile没有合格已接任务，应404。
4. 改用管理员登录；GET /admin/orders/{orderId}可查旧订单，但GET文件内容应403。清除授权后下载应401。

程序检查了Swagger页面可达；自动HTTP检查不能当作用户已完成人工点击。本轮若没有运行900秒窗口，人工验收仍由用户自选时机执行。可在本轮目录创建名为end-manual的空文件提前结束窗口；不要直接终止脚本跳过finally清理。默认自动验收不留常驻应用。
