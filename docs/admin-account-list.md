# 管理员账号列表：Swagger / Apifox 验收

2026-10-26 后续扩展：账号状态PATCH已按[ADR-0046](decisions/0046-admin-account-status.md)实现，见[操作指南](admin-account-status.md)。当前运行62/完整62/62/0，原文账号状态“待开发”属于上一阶段；本页原验证记录保留原任务范围。

已按[ADR-0045](decisions/0045-admin-account-list.md)实现GET /api/v1/admin/accounts。运行61、完整62/61/1；本轮无迁移，仍V1～V14/22表。账号停用/恢复PATCH尚未实现，管理员仍使用既有受控初始化，不开放管理员注册。

## 首次创建平台管理员

项目没有内置管理员用户名或默认密码，普通启动也不会自动创建ADMIN。自动化测试的管理员只存在于独立临时测试库，测试结束后清理，不会写入你日常使用的campuslife数据库。

如果campuslife.account中没有role=ADMIN的账号，先停止当前应用，在VS Code终端确认位于D:\campuslife，执行一次：

```powershell
.\scripts\init-admin.ps1
```

按提示设置新的平台管理员用户名、昵称、密码并确认密码；用户名4～32位字母/数字/下划线，昵称1～40码点，密码至少8码点且UTF-8最多72字节。如果随后提示数据库密码，填写原有MySQL campuslife数据库账号密码，与刚设置的管理员登录密码分别处理；密码只在本机输入，不需要发送到聊天。

看到`Created ACTIVE ADMIN business account`表示创建成功。默认连接127.0.0.1:3306/campuslife；若平时启动使用其他库或端口，需要按[管理员初始化指南](admin-shop-review.md)显式指定同一连接。账号存于account表，role为ADMIN；密码列password_hash只保存加盐摘要，不能查看或还原原密码。用户名已存在时脚本会拒绝，不覆盖原账号、密码或角色。

创建后按原VS Code启动配置启动应用，再使用刚设置的管理员用户名与密码完成下面的登录流程。

## 点击流程

1. 沿原VS Code启动配置重启应用，无需更改数据库用户、密码或密钥。打开 http://127.0.0.1:8080/swagger-ui/index.html ，或在Apifox重新导入[运行接口文件](openapi/campuslife.openapi.json)。[完整规划](openapi/campuslife.v1-design.openapi.json)中的账号状态PATCH仍标记待开发。
2. 在“01 账号认证”使用已有平台ADMIN账号登录，把响应data.accessToken本身填到Authorize，不添加Bearer前缀。MySQL的campuslife/root用户不是业务ADMIN。
3. 在“08 管理员”展开“分页查询平台业务账号”，四个参数都不填，Execute。应返回200，data为items、page、size、total、totalPages；默认page=1、size=20。
4. 每条items只有id、username、nickname、role、status、version六字段；id和version为字符串。能看到本人和其他管理员，也包含停用账号；没有登录密码、摘要、JWT、会话或联系方式。
5. role选STUDENT、status选ACTIVE查询两者交集，再分别只填角色或状态。筛选DISABLED若暂无账号，应返回空页，不需要为了验收去数据库修改真实账号。
6. page=1、size=1看第一页，再page=2看下一页；created_at倒序、同毫秒按id倒序。page=2147483647、size=100应返回空items但保留真实total/totalPages。page=0001、size=001等价于1/1；Apifox可保留原查询字符串测试。
7. 若需新样本，可用原学生注册或商家申请接口创建虚构账号，之后仍使用管理员token重新查询。注册不自动登录；商家即使待审/无订单也出现在账号列表。不要使用未实现的状态PATCH创建停用样本。
8. 将Authorize换成学生/商家token，本接口应403；退出管理员后以旧token查询应401。普通登录流程不会自动创建ADMIN。

## 数据边界与错误

role仅STUDENT/MERCHANT/ADMIN，status仅ACTIVE/DISABLED，精确大写；未知值、空串或首尾空白400。只省略参数取默认值，分页只接受数字，page1～2147483647、size1～100；0、负数、正号、小数、指数、溢出及显式空串400。Swagger输入框空白可能等同于省略；要测试“显式空串”，在Apifox发送?page=等原始查询参数。

未登录/无效/撤销/过期凭据401，错误角色/停用管理员403。数据库暂时故障503，其余内部错误500，不能把故障当作空账号页。无匹配或越界页200，不新增404/409。

一个响应的管理员资格、会话、总数和items来自同一只读数据库快照；请求期间并发新增/改名/变更状态或退出会话，已建立的旧快照可以完成，下一请求反映新事实。返回前自然到期仍401。不同页或不同HTTP请求不保证永久同一时刻。

查询不修改账号版本或会话，不停用/恢复账号，也不加载密码摘要、写订单/资金或访问磁盘。列表里的version只是读取值，未来状态修改仍须重新校验。

## 实际验证记录

2026-10-26：本轮各用例最终结果去重 **393个定向用例通过**（普通/Web172、真实MySQL/HTTP221；新增账号列表66）。首轮新增65个通过，修正测试设置后剩余1个复验通过；重复执行不重复累加。最后复验Web13、该新增用例1、OpenAPI3共17个通过，mysql模式JAR健康、UTF-8、trace、鉴权与V1～V14迁移启动检查通过。无新迁移，仍22张业务表，未重跑全部历史业务IT。

| MySQL / HTTP套件 | 本轮最终通过数 |
|---|---:|
| AdminAccountListMysqlIT | 66 |
| AdminShopReviewMysqlIT | 23 |
| AdminReviewHideMysqlIT | 61 |
| AuthenticationMysqlIT | 41 |
| ProfileMysqlIT | 16 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |

新增正向用例实际调用学生注册、商家申请及受控AdminAccountInitializer，再由ADMIN登录查询，验证三种角色、本人/其他管理员/停用样本均可见，与店铺审核状态无关。停用边界使用独立测试库显式样本，未把尚未实现的状态PATCH当作可用功能。

已验证角色/状态交集、默认与数字解析边界、显式空串/符号/小数/指数/溢出、分页前导零、最大页long偏移、空页/越界保留total、同毫秒ID倒序和多页；ID超过JavaScript安全整数、version为Long最大值时保持字符串精度。响应严格六字段，不包含摘要/会话；查询不修改账号或会话事实，不获取账号/会话业务行锁。未暴露创建/详情或状态修改路由。

真实MySQL只读RR验证并发新增账号、改昵称、角色/状态变化，资格/count/items保持旧快照，后续请求反映新事实；管理员停用、角色变化或会话撤销在旧快照建立后允许该响应完成、下一请求拒绝。已认证principal在进入服务前资格变化仍按数据库拒绝；分页过程中自然到期在返回前拒绝。持管理员/目标账号和会话X锁时普通读可完成，不等待业务写锁。暂时故障503、内部故障500，均data=null，不伪造空页，不改账号或会话。

首轮一个“鉴权后、服务快照前撤销会话”用例的旧测试代码把模拟撤销写进只读事务，触发数据库只读保护，实际503而预期401；已将该模拟写改为独立REQUIRES_NEW写事务。一次复验因本地增量编译仍使用旧测试字节码，再次触发同一只读保护；核对字节码和文件时间后重新编译。最终复验从已通过HTTP鉴权到服务快照之间实际提交撤销，401 SESSION_REVOKED，业务查询代码无需改变。其余65个新增用例首轮通过，本次最终按用例名合并通过记录，不把失败计为通过，也不重复累加复验数量。

实际命令，均使用独立临时MySQL及测试自有文件根：

```powershell
$env:MAVEN_ARGS='-Dit.test=AdminAccountListMysqlIT,AdminShopReviewMysqlIT,AdminReviewHideMysqlIT,AuthenticationMysqlIT,ProfileMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90

$env:MAVEN_ARGS='-Dtest=WebFoundationTest -Dit.test=AdminAccountListMysqlIT#sessionChangeBeforeServiceSnapshotIsNotHiddenByTransportAuthentication,OpenApiMysqlIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

日志为.cache/admin-account-list-mysql.log与.cache/admin-account-list-mysql-final.log；旧测试字节码的复验诊断保存在.cache/admin-account-list-stale-bytecode.log，首轮新增完整报告归档为.cache/admin-account-list-first-report.xml。最后报告保存于target/failsafe-reports，按上述用例名覆盖合并可核对66个新增最终结果。失败实例停止后按精确GUID路径清理，最后成功实例由脚本停止并清理；不访问用户当前项目服务数据或上传目录。

