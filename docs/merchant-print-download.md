# 商家打印PDF受控下载验收：Swagger / Apifox

2026-10-26 后续管理员PRINT异常已按[ADR-0041](decisions/0041-admin-print-exception.md)接入，见[点击指南](admin-print-exception.md)。已付款未完成打印可由管理员基于原因/版本核实全额退款关闭，开始/待取件时间保留、进度ABORTED；已完成不退款，ADMIN仍无内容权限。旧阶段“管理员PRINT未开放”的描述由本次取代。

2026-10-25 后续取件已开放：[学生取件指南](student-print-pickup.md)及[ADR-0040](decisions/0040-student-print-pickup.md)使待取件打印单可由本人完成。完成后合法start/ready重试200且不改事实，完成单不再授权商家下载；全部引用终结后保留7天。下方旧阶段取件未开放或完成后拒绝重试的说明由本次取代。

2026-10-25 后续扩展：[打印开始与待取件](merchant-print-fulfillment.md)已按ADR-0039开放；商家合法IN_SERVICE/PRINTING与READY_FOR_PICKUP任务现可受控下载。旧“PRINT开始404/履约中不授权”的阶段描述以本次为准；学生取件、评价及管理员PRINT异常仍后续开发。

按[ADR-0038](decisions/0038-merchant-print-download.md)和[确认方案](merchant-print-download-design.md)实现。重启原VS Code **CampusLife (MySQL)** 配置即可；复用原下载入口，无新迁移或数据库重建，运行仍54个操作。本轮未修改本机凭据。

## 1. 准备已接单打印任务

打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，或在Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，服务地址`http://127.0.0.1:8080`。完整规划仍62个操作、8个待开发。

按[商家打印确认指南](merchant-print-confirmation.md)让学生上传本人PDF、报价、创建和付款，再由本店商家接单。GET本店详情确认ACCEPTED / PAID / QUEUED，从printing.fileId复制本次实际文件ID，不使用示例ID。

商家登录得到data.accessToken，填入Authorize，只填token本身。已付款但尚未接单的订单不能获得内容授权；接单后看到内容若无法履约，可按[商家取消指南](merchant-print-cancellation.md)全额模拟退款处理。

## 2. 商家下载

在“04 文件与打印报价”选择GET `/api/v1/files/{fileId}/content`，填写上一步fileId并执行。沿用原文件下载入口，只填fileId；无需orderId、shopId、请求体或Idempotency-Key。

预期200，响应是原PDF二进制附件，可保存后与学生上传的文件对照。Swagger响应区或Apifox保存响应文件，均须携带商家Bearer；直接在未带Bearer的新浏览器地址栏打开会401。检查application/pdf、安全UTF-8附件名、Content-Length、Cache-Control: no-store，不应是ApiResponse JSON。

再次下载仍返回相同原文件，不写下载事件、不修改打印进度、订单/文件版本或保留期，也不自动开始打印。下载不受模拟资金开关限制；商家上传和学生本人元数据接口没有随之开放，不能将内容权限等同于所有文件管理权限。

当前授权覆盖合格已接单、打印中和待取件任务；合法IN_SERVICE/PRINTING与READY_FOR_PICKUP现可继续下载，学生取件完成后权限随后续任务定稿。店铺暂停或停业、服务/价目停用、学生停用及原接单截止经过，不妨碍正常商家/有效OWNER读取已接任务。

## 3. 取消后验证权限撤销

1. 用商家token按取消指南说明原因取消该已接单任务，确认CLOSED / REFUNDED / ABORTED。
2. 若本店没有其他合格订单关联同文件，再调用商家下载：404 RESOURCE_NOT_FOUND。即使文件仍保留7天，也不继续授予商家访问权。
3. 切回学生本人token，同fileId在保留期内仍可按[本人下载规则](file-download.md)读取。学生的410到期规则保持，不因商家无权限而提前删除文件。
4. 若本店另有一笔合格已接单任务使用同一文件，商家仍可下载；取消本店最后一笔后才无授权。只有其他店有合格引用时，本店仍404；跨店引用继续保护文件存储，但不共享内容权限。

可以另建仅未付或仅已付待接单的订单验证商家下载404。另一个商家或其他学生知道fileId也不能读取；管理员没有默认PDF权限。

## 4. 并发与边界说明

下载会先定位文件，在数据库事务外等待文件互斥，取得后用新快照复核身份/成员/任务。如果等待时最后有效任务已经取消，请求404；若已获准传输后再取消，本次下载允许完成，下次重新判断。退款取消不用等待传输，清理会跳过正在传输的文件。这不能收回已经保存的本地副本。

这组时序由自动测试控制验证，不要求用户手工调时钟、删除存储目录或执行数据库修改。普通操作仍以当前订单真实状态判断；GET不关单、退款、续期或生成事件。

| 返回 | 含义 |
|---|---|
| 400 VALIDATION_FAILED | fileId不是规范正BIGINT字符串 |
| 401 | 未登录、会话到期/撤销或token无效 |
| 403 ROLE_FORBIDDEN / ACCOUNT_DISABLED | 角色/账号/OWNER无效，或等待中角色改变 |
| 404 RESOURCE_NOT_FOUND | 学生不可见，或商家无所属店铺/本店合格关联 |
| 410 FILE_EXPIRED | 学生本人文件已到期或删除中/已删除；商家没有保留期读取权 |
| 503 FILE_STORAGE_UNAVAILABLE | 已授权文件保护元数据、实际对象、根或长度异常；不修改订单/资金 |
| 503 SERVICE_TEMPORARILY_UNAVAILABLE | 数据库暂不可用，稍后重试相同下载 |

成功只做完整200，不启用Range断点续传或条件缓存。已开始写出PDF后若传输失败，会终止连接，客户端可能得到不完整附件，应重新发起请求；不会在PDF尾部追加JSON错误。

## 自动验证记录

2026-10-25：本轮相关套件的最新通过报告合计 **532个用例**（普通/Web172、真实MySQL/HTTP360，含新增商家下载72），各套件当前报告零失败/错误/跳过；最后业务定向复验258个通过，随后接口分组修复的Web/API16个通过（不重复计入合计）。V1～V12迁移与mysql模式JAR健康、UTF-8、trace、鉴权检查通过；运行54、完整62/54/8，未重跑全部历史业务IT。

首次相关回归执行：

```powershell
$env:MAVEN_ARGS='-Dit.test=MerchantPrintDownloadMysqlIT,FileDownloadMysqlIT,FileUploadMysqlIT,PrintOrdersMysqlIT,MerchantPrintCancellationMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

首次日志`.cache/merchant-print-download-mysql-first.log`。学生下载48、上传64、打印创建79、商家打印取消83以及接口/迁移回归通过；新下载一条夹具把已受保护文件设为UPLOADING却未填到期时间，违反已有ck_file_upload_expiry约束，测试准备阶段失败。已在夹具同时设置上传到期时间，不放宽数据库约束；业务授权/流传输实现无需修改。补强商家断流用例对原PDF前缀的检查，并同步已有文件保护能力的注解文字。

修正后只复验新增商家下载、实际接口导出与数据库/JAR检查；其他本轮通过套件不重复运行：

```powershell
$env:MAVEN_ARGS='-Dit.test=MerchantPrintDownloadMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

最后日志`.cache/merchant-print-download-mysql-final.log`；合计使用各套件最新通过报告，同一普通用例不重复计数。

| 套件 | 通过数 |
|---|---:|
| MerchantPrintDownloadMysqlIT | 72 |
| FileDownloadMysqlIT | 48 |
| FileUploadMysqlIT | 64 |
| PrintOrdersMysqlIT | 79 |
| MerchantPrintCancellationMysqlIT | 83 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |

新增用例实际走上传、创建、付款、接单后下载，验证原始字节、UTF-8附件/no-store等响应头、完整200/Range与条件头、重复下载无订单/文件/事件/资金变化；角色/会话/OWNER/本店与跨店归属、无关联/未付/待接单/已取消/完成/未来合成进度拒绝，文件归属一致性、保护元数据异常、暂停/停业/配置失效/学生停用、上传原24小时经过及资金关闭的合法旧任务。商家上传/元数据仍403、角色改变后不能仅凭拥有文件绕过任务授权。

真实MySQL门闩安排互斥等待中的取消、退出/角色/账号/成员变化及文件元数据变化，验证新RR复核；在互斥后资格快照建立后取消/成员停用，验证同一RR资格与关联一致，当前获准下载完成、下一次拒绝。实际持有账户、会话、店铺、文件、订单和打印明细写锁时下载仍能完成，探针确认磁盘读取不处于数据库事务。受控长传输时退款取消在5秒内提交，推进测试Clock至真实保留期后清理仍跳过活动文件，传输结束再清理；同文件本店多单及跨店引用不混淆存储保护和下载权。

覆盖对象缺失/长度/根/目录与首读失败的JSON、提交后中断仅原PDF前缀且无JSON尾接、句柄/互斥释放，以及事务前/取得互斥后数据库暂时错误503与重试。未来进度只用于拒绝测试，不代表打印履约或IN_SERVICE内容权限已实现。

测试使用独立临时MySQL及自有文件根，不访问用户正式服务数据或持久上传目录；成功实例自动清理，首次失败实例进程结束后定向清理。未修改本机VS Code凭据。上述人工流程供用户复现，未代用户操作Swagger/Apifox。

文档终检还发现文件Controller更新分组描述后，报价Controller的同名分组描述未同步，导致导出两个同名tag。已统一说明，在OpenApiMysqlIT增加9个分组且名称不重复断言；仅复验Web基础13例、OpenAPI3例与JAR启动，不重跑下载业务用例：

```powershell
$env:MAVEN_ARGS='-Dtest=WebFoundationTest -Dit.test=OpenApiMysqlIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

接口终检日志`.cache/merchant-print-download-openapi-final.log`；两份导入文件最终均9个唯一分组、运行81/完整94个schema。
