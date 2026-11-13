# M04.2-B 文件存储路由与验收

日期：2026-11-02。方案已按 [ADR-0056](decisions/0056-private-object-storage.md) 接入；本轮验证结果见[报告](reports/s3-file-chain-2026-11-02.md)。不迁移实际历史文件，开发默认仍为 LOCAL；C 的真实迟到 IO、备份恢复及隔离迁移预演现已[验证](reports/s3-recovery-2026-11-02.md)，具体Swagger窗口见[恢复指南](s3-recovery.md)。

## 使用边界

原接口、上传限制和文件期限不变。新文件先在本地受控暂存目录完成 PDFBox 校验，再按配置存入 LOCAL 或 S3。数据库记录中的 `storageKind/storageId/storageKey` 决定后续位置，改变新上传目标不会改写旧记录。S3 故障没有自动切回本地另存。

S3 上传在 UPLOADING 占位提交后进行 PUT，并完整 GET 校验 SHA-256/长度，再以短事务发布。完整回读有额外带宽/耗时成本。原学生本人、商家本店合法打印任务的下载授权保持；管理员无内容权限。下载继续由后端代理，无公开 URL 或预签名；第一次创建打印订单会在事务外检查对象，创建重放、报价、付款、退款和取件沿用数据库事实。

清理将到期记录标为 DELETING，事务外精确删除，再提交 DELETED。S3 的 DELETED 行不物理清除，按游标永久轮转精确删除，以回收可能晚到的未知 PUT。不会列出整桶并删除未知对象。单实例共享 FileGuards；墓碑不能据此视为跨实例协调。

自动扫描使用一个私有线程和零容量队列，60秒唤醒；同轮到期/墓碑各最多100条，共享1秒继续处理预算，已开始 IO 可收尾。命名空间/扫描类别轮转，失败和忙文件也推进游标，尾部再从头复核。关闭仅有界等待8秒后发中断；不承诺远端写入取消或应用整体8秒退出。

## 配置含义

| 环境变量 | 默认/含义 |
|---|---|
| FILE_UPLOAD_TARGET | LOCAL；仅新上传目标，可显式设 S3 |
| FILE_S3_ENABLED | false；是否登记 S3 后端，关闭后已有 S3 文件访问为503 |
| FILE_S3_ENDPOINT | 显式 S3 endpoint；本机 HTTP 只允许127.0.0.1，其余须 HTTPS，无凭据/查询/路径 |
| FILE_S3_REGION | us-east-1；必须与服务端签名区域一致 |
| FILE_S3_BUCKET | 显式私有桶，应用不创建桶 |
| FILE_S3_NAMESPACE | 显式初始化的UUID，必须区别于 LOCAL 的 .storage-id |
| FILE_S3_ACCESS_KEY / FILE_S3_SECRET_KEY | 独立应用凭据，仅本机环境或部署秘密配置，不提交仓库 |

`FILE_UPLOAD_TARGET=LOCAL` 且 S3 后端仍启用时，两类存量继续可读。关闭 S3 不是数据回滚。SDK 已进入正常可执行包（固定2.57.8/Apache5），但默认不创建 S3 客户端或访问网络；配置错误早失败，远端不可达不参与启动探测，LOCAL/其它业务仍可启动。

S3 namespace 使用 `<UUID>/objects/<32位随机值>.pdf`；每次对象操作先核对桶内 `_storage-id` 的UUID。必须按[A实测权限](s3-compatibility.md)同时配置窄 Actions 和 Deny 后缀策略：应用仅受管对象前缀读写删除、精确标识读取，无整桶权限。应用不使用管理员身份初始化，不自动写标识。服务端应关闭自动建桶、匿名读取和绕过数据库期限的生命周期清理。

本轮只创建并回收隔离实例，未启用你的开发 S3 或生成开发凭据。实际部署初始化/备份操作在 C/M06 给出对应环境的可用步骤，不把测试 manifest 当成开发配置。

## 自动复现

需要 Java21、Maven、MySQL8客户端/服务端程序、已启动的 Docker Desktop Linux 引擎。在项目根目录：

```powershell
.\scripts\test-s3-business.ps1
```

此脚本生成专用 SeaweedFS 容器/卷/网络/凭据，并调用现有独立临时 MySQL。S3 测试上下文显式启用后端，原回归上下文及 mysql JAR 启动仍默认 LOCAL。结束后仅回收本轮自有资源，失败保留日志；`.cache/s3-it` 的 manifest/config 含临时凭据，不要上传、提交或发到聊天。

独立检查真实 V23→V24 升级（必须新临时库，不能混在普通回归同库运行）：

```powershell
.\scripts\test-s3-business.ps1 -Cases S3StorageUpgradeCheck -UnitCases FileCleanupWorkerTest
```

普通测试无需 S3 运行。`scripts/test-s3.ps1` 仍可单独运行原33项协议探针；该探针源码仍隔离，但SDK从B开始为生产依赖，A报告中“无生产SDK”的结论仅适用于A时点。

## 浏览器验收入口

业务路径保持：学生在 Swagger 的 POST /files 选 PDF，返回201后 GET /files/{fileId} 查元数据、GET /files/{fileId}/content 下载。未过期本人下载200，其他学生404、管理员403。仅在显式配置好 S3 的隔离/开发应用中，这些路径才使用 S3；本轮无待用户手动操作的常驻测试应用。

沿[打印人工流程](manual-print-acceptance.md)下单付款、商家接单后下载200，开始/待取件仍200；学生取件后，该完成订单不再给商家授权，本人仍可在保留期内下载。还有其它本店合格订单时仍可授权。C已提供具体验收环境与故障/恢复点击清单；本轮自动HTTP及真实数据库证据不能冒充人工点击完成。
