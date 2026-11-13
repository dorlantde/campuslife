# 本人PDF下载验收：Swagger / Apifox

2026-10-25 后续扩展：[商家PDF受控下载](merchant-print-download.md)已按ADR-0038开放，仅凭本店合格已接单未开始任务授权。旧“商家内容未开放/一律403”阶段说明由本次扩展替代；上传/本人元数据仍仅学生，打印中授权随履约后续推进。

2026-10-24支付扩展：[ADR-0035](decisions/0035-print-mock-payment.md)已支持学生PRINT模拟付款、待接单取消/超时退款及成功资金历史。文件仍任一未终结引用NULL保护、最后实际终结后7天，支付不续期。操作见[打印支付指南](print-payments.md)；商家打印访问未开放。

2026-10-24更新：打印创建与学生PRINT详情/取消/列表/事件已按[ADR-0034](decisions/0034-print-orders.md)交付，见[打印订单点击指南](print-orders.md)。未引用文件仍上传后24小时；任一打印订单未终结则expiresAt=null，全部终结后7天；下载/报价不自行续期。商家打印访问与履约后续开发，下文原阶段记录保留。

本轮按[ADR-0032](decisions/0032-file-download.md)新增GET `/api/v1/files/{fileId}/content`。仅正常学生下载本人有效PDF，成功返回原文件附件，不是JSON。规则见[确认方案](file-download-design.md)；上传仍按[原指南](print-file-upload.md)。本轮没有新数据库迁移。

## 浏览器点击流程

1. 停止旧应用，用原VS Code **CampusLife (MySQL)** 配置重启，打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。
2. 使用虚构学生账号登录，复制响应的`data.accessToken`，点 **Authorize**，只填token本身。
3. 在“04 文件与打印报价”调用 **POST /api/v1/files**，选择[两页测试PDF](samples/campuslife-print-demo.pdf)，应201，复制`data.id`。也可使用本人尚未到期的已上传ID。
4. 打开 **GET /api/v1/files/{fileId}/content**，Try it out，填fileId，Execute。成功200，响应类型为application/pdf；使用响应中的下载链接（Download file）保存文件。如果浏览器选择预览，也可以另存为PDF。
5. 打开保存的PDF，核对仍为原两页内容；可再调用元数据接口确认expiresAt未因下载延长。重复下载不修改状态或版本。

下载需要Authorization请求头。把路径直接粘贴到浏览器地址栏不会自动携带Swagger里的token，返回401是正常鉴权结果；不要把token放进URL。服务器到期删除不能收回已保存到电脑的副本。

## Apifox流程

重新导入[运行接口快照](openapi/campuslife.openapi.json)，服务地址为`http://127.0.0.1:8080`。登录后设置Bearer Token，上传PDF并复制id；选择下载接口，填路径参数，发送，使用响应区的保存/下载功能保存二进制内容。此GET没有JSON请求体。

[完整规划文件](openapi/campuslife.v1-design.openapi.json)仍含待开发能力，打印报价现按[报价验收](print-quotes.md)开放；商家文件访问和打印下单尚未开放；商家或管理员身份不能用于本轮下载。

## 主要结果

| 情况 | 预期 |
|---|---|
| 正常学生本人、有效AVAILABLE文件 | 200原PDF附件，中文原名编码在响应头 |
| 下载后再次读取元数据 | 有效期、状态和版本不变 |
| 另一个学生，或不存在/尚未发布的ID | 404 RESOURCE_NOT_FOUND，不读取内容 |
| 本人达到期限、删除中或已删除 | 410 FILE_EXPIRED，即使磁盘暂未删除 |
| 无登录、退出、过期或无效token | 401，对应认证错误 |
| 账号停用；商家/ADMIN | 403 ACCOUNT_DISABLED或ROLE_FORBIDDEN |
| ID为0、01、符号、小数、空白或超过BIGINT | 400 VALIDATION_FAILED |
| 本人有效记录，但对象丢失、大小不符、存储根不匹配或路径不安全 | 503 FILE_STORAGE_UNAVAILABLE，不改元数据 |
| 请求Range或条件缓存 | 仍返回完整200；当前不支持断点续传或304 |

成功响应包含Content-Type、Content-Length、Content-Disposition、Cache-Control: no-store和X-Content-Type-Options: nosniff。错误在写流前返回JSON；已开始的传输若中断，可能留下不完整PDF，应重新请求，不会在PDF尾部追加JSON。

## 到期与下载的关系

获得文件互斥后，服务器用新的只读快照重新检查账号、会话、归属和有效期。等待期间发生退出/停用/到期则拒绝；通过这次复核后的传输允许完成，后续请求重新校验。清理遇到正在传输的文件会跳过，释放后由后续扫描回收；新请求达到期限仍为410。

磁盘IO和传输不放进数据库事务，不锁账号行等待客户端。当前单实例协调可能让同一互斥桶内的请求串行，慢传输也可能延后物理删除。本轮不含分布式部署、商家打印订单授权或订单引用保护。

## 自动验证记录

2026-10-23 最终定向291个用例通过，0失败/错误/跳过：普通及Web测试124（文件策略38，含新增真实目录链接读取拒绝1个）；真实MySQL/HTTP下载48、上传64、认证41、基础迁移11、OpenAPI3。V1～V11迁移、mysql模式JAR的健康/UTF-8/追踪及鉴权检查通过。

下载覆盖原字节及中文/特殊字符附件名、二进制头、完整Range/条件请求、身份/归属/生命周期、毫秒到期边界、存储缺失/长度/命名空间及读取故障。并发使用真实MySQL和受控传输时序，验证等待后的身份/文件复核、已授权传输继续、到期清理跳过及释放后恢复；数据库更新可在阻塞传输期间提交，IO中无活动事务。实际HTTP读取故障验证部分PDF不混入JSON并在5秒内结束；另用模拟Servlet输出写失败验证客户端断开分支及句柄/互斥回收，不将其称为真实网络断开压力测试。

首轮测试发现PDF响应携带全局UTF-8字符集、未提交/已提交的故障用例边界及已提交中断连接收尾问题，修正后重跑上述定向集合通过。验证使用自有临时MySQL和临时目录，未访问用户开发库及持久上传目录；未代用户点击浏览器下载或操作Apifox。单实例慢客户端、磁盘外部篡改、订单引用保护等限制仍按ADR。

当前运行52个操作、完整62/52/10，9分组及92个规划schema；导入文件与实际运行快照同步。打印报价现按ADR-0033交付。
