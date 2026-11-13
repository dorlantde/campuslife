# PDF上传与本人文件信息验收：Swagger / Apifox

2026-10-25 后续扩展：[商家PDF受控下载](merchant-print-download.md)已按ADR-0038开放，仅凭本店合格已接单未开始任务授权。旧“商家内容未开放/一律403”阶段说明由本次扩展替代；上传/本人元数据仍仅学生，打印中授权随履约后续推进。

2026-10-24支付扩展：[ADR-0035](decisions/0035-print-mock-payment.md)已支持学生PRINT模拟付款、待接单取消/超时退款及成功资金历史。文件仍任一未终结引用NULL保护、最后实际终结后7天，支付不续期。操作见[打印支付指南](print-payments.md)；商家打印访问未开放。

2026-10-24更新：打印创建与学生PRINT详情/取消/列表/事件已按[ADR-0034](decisions/0034-print-orders.md)交付，见[打印订单点击指南](print-orders.md)。未引用文件仍上传后24小时；任一打印订单未终结则expiresAt=null，全部终结后7天；下载/报价不自行续期。商家打印访问与履约后续开发，下文原阶段记录保留。

按[ADR-0031](decisions/0031-print-file-upload.md)及[确认方案](print-file-upload-design.md)实现上传、本人元数据和未下单文件清理；本轮运行51个操作，完整62个（51已实现/11待开发）。V11新建file_asset，当前20张业务表，重启你的mysql应用时由Flyway迁移。

## 准备与选择文件

停止旧应用，用原VS Code **CampusLife (MySQL)** 配置重启，打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，服务地址设`http://127.0.0.1:8080`；[完整规划](openapi/campuslife.v1-design.openapi.json)仍含未开发能力。

用虚构学生账号登录，将data.accessToken填到Authorize，只填token本身。可使用[两页虚构验收PDF](samples/campuslife-print-demo.pdf)，或你自己准备的无加密测试PDF；不必上传真实个人材料。样例只用于校验上传和页数，不表示已连接打印机。

## 点击上传与查询

1. 展开“04 文件与打印报价 → POST /api/v1/files”，Try it out，点击file旁的选择文件，选PDF，Execute。
2. 首次应201，data含id、originalName、byteSize、mediaType、pageCount、status、expiresAt。样例pageCount=2，status=AVAILABLE，有效期从发布完成起24小时，UTC时间；id是字符串。
3. 复制id到同分组 **GET /api/v1/files/{fileId}**，应200且元数据一致。刷新不会续期，不解析/删除文件。
4. 再次上传同一个PDF，会得到新文件ID；服务器没有上传去重或幂等键。FileView不返回哈希、存储路径、key或下载URL。
5. Apifox选择Body → form-data，只有file一项，类型选File后选择本地PDF；不要选择JSON或添加ownerId/pageCount等字段。它们由服务器确定。

本人下载现按[ADR-0032](decisions/0032-file-download.md)交付，见[下载验收](file-download.md)；打印报价现按[报价验收](print-quotes.md)开放；商家文件访问和打印下单尚未实现。完整规划中可见相应接口不代表当前可用；当前两个接口仅供学生本人使用，管理员也不能默认读取这些元数据。

## 主要边界

| 场景 | 预期 |
|---|---|
| 单个非空、无加密PDF，1～500页、≤20 MiB | 201；大小、页数由服务端确认 |
| 缺少/空file、重复file、额外字段 | 400 VALIDATION_FAILED |
| 非PDF后缀/部件类型，或非multipart上传 | 415 FILE_TYPE_UNSUPPORTED或UNSUPPORTED_MEDIA_TYPE |
| 假PDF、无法解析页树、0页 | 422 PDF_INVALID |
| 加密PDF，含空密码可打开的加密文档 | 422 PDF_ENCRYPTED；先导出无加密副本 |
| 超500页 | 422 PDF_PAGE_LIMIT_EXCEEDED |
| 超20 MiB；表单总大小超22 MiB | 413 FILE_TOO_LARGE；20 MiB=20971520字节 |
| 已有两个PDF正在处理 | 429 FILE_PROCESSING_BUSY，可稍后重试 |
| 商家/管理员token | 403 ROLE_FORBIDDEN |
| 无登录、无效/退出/过期会话、停用账号 | 对应401或403 ACCOUNT_DISABLED |
| 另一学生或不存在ID | 404 RESOURCE_NOT_FOUND，即使该文件已到期 |
| fileId为0、01、带符号/空白/小数或溢出 | 400 VALIDATION_FAILED |
| 本人文件达到24小时或已进入删除流程 | 410 FILE_EXPIRED，物理清理尚未完成也不可使用 |
| 本人文件已删除 | 410，保留元数据，不保留PDF内容 |

扩展名和MIME不是内容真实性证明；服务端会检查签名和页树。能够解析不等于完成内容净化或纸面效果验证，实际打印和预览不在本轮范围。

## 文件放在哪里与清理方式

默认私有目录为项目根下`.local/files/`，Git已忽略，普通构建/打包不会清理或打入JAR。对象在objects子目录，临时文件使用专用目录，文件名是随机key。浏览器不能通过静态URL访问，也不要手改文件名、`.storage-id`标识或清空目录来模拟接口操作。

如需另设目录，可配置FILE_STORAGE_ROOT；当前单机开发可以保持默认。只支持一个应用实例使用同一目录；切换目录不会自动迁移原文件，数据库与目录应一起备份。`.cache`是工具缓存，与持久上传目录不同。

集成测试默认使用target/file-it的独立测试目录，自动扫描关闭；隔离脚本通过专用FILE_TEST_STORAGE_ROOT注入自有fixture目录，覆盖该测试路径。应用的FILE_STORAGE_ROOT配置不会自动成为集成测试目录。

未下单文件发布24小时后逻辑过期。清理启动执行一次、每60秒扫描，最多100条；先在短事务标记DELETING，再删除对象，最后标记DELETED。删除失败保留DELETING重试，对象已不存在可重复完成。磁盘暂不可用或数据库提交失败会触发补偿/恢复；响应丢失可能已经提交成功，不保证重传沿用同一ID。

内部UPLOADING占位用于找回进程中断的未发布文件，不能查询为可用文件，30分钟未完成进入恢复清理。上传时退出、停用或过期会在发布前复查。当前请求临时文件会回收，遗留临时文件只按限定路径和命名清理；不会递归清空存储根。

之后打印下单接入时，须实现所有未终结订单保护文件、全部终结后保留7天；当前尚未实现订单引用，不能通过本轮验收宣称该保护已可用。无需手改数据库或等待一天来制造异常，精确时间、故障和恢复由独立测试验证。

## 自动验证记录

2026-10-23 独立MySQL定向Maven verify **242个用例全部通过**（普通/Web123，含新增文件策略/真实文件系统37；文件MySQL64、认证41、迁移11、文档3），零失败/错误/跳过。首轮通过后补强手动验收的文件目录隔离及查询范围，最终同范围复测242个通过；没有重跑全部旧业务或全部订单/资金用例。

37个新增策略/文件系统用例覆盖规范ID、文件名/MIME、1与500页、0/501页、真加密及空口令加密PDF、伪装签名、流式20MiB限制、临时文件活动保护/遗留回收、当前存储根标识和真实Windows目录联接逃逸拒绝。64个真实MySQL/HTTP文件用例覆盖上传元数据/原字节/hash、重复独立上传、真实20MiB及超1字节HTTP大小边界、单部件表单约束、角色/会话/本人隔离、先归属后410、只读不续期/碰磁盘。

实际SQL与IO故障验证接收/移动前后/占位插入/发布更新失败，处理过程中停用/退出/过期，失败不形成半发布AVAILABLE。删除失败或删除后提交失败保留DELETING，后续扫描重复完成；到期前/等于/之后、未发布/无对象/删除中恢复、其他根标识不处理、合成NULL有效期不清理、每批最多100条和并发清理通过。两个并发处理槽拒绝第三个、同文件发布与清理互斥均有实际HTTP及数据库验证；未用mock结果代替事务/锁正确性。

恢复测试先合成已提交的中断UPLOADING/DELETING和遗留临时文件，再启动新的实际可执行JAR进程，确认启动扫描读取持久根标识、回收对象与临时文件、保留未知文件，并停止该进程。此验证不是在任意CPU指令点强杀上传进程，也不宣称跨实例文件协调已实现。测试使用自有临时MySQL和文件根，不访问用户现有数据库、持久上传目录或密码。

V1～V11迁移与约束、mysql可执行JAR健康/UTF-8/鉴权边界检查通过，所有自有fixture已停止清理。新增11个生产Java文件，当前167个；PDFBox3.0.8、私有本地存储，无新XML/Redis/MQ。本页两页PDF样例另经生产PdfInspector实际解析确认pageCount=2。

运行OpenAPI51个操作，完整62个（51已实现/11待开发）、9分组92schemas；生成器一致性、两份官方OpenAPI3.0 schema、6个请求示例及参数/模型引用检查通过。尚未代用户完成Swagger / Apifox人工点击验收；下载、报价、打印订单保护仍未交付，按上文验证当前范围。
