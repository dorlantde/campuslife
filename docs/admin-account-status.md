# 管理员停用与恢复账号：Swagger / Apifox 验收

已按[ADR-0046](decisions/0046-admin-account-status.md)实现PATCH /api/v1/admin/accounts/{accountId}/status。新增V15状态历史表，V1～V15/23表；运行62、完整62/62/0。当前规划的接口全部可调用，后续工程验收、前端或其他业务仍可独立推进。

## 准备与点击流程

1. 沿原VS Code配置重启，Flyway应用V15；不需要修改本机数据库账号或密钥。打开 http://127.0.0.1:8080/swagger-ui/index.html ，或重新导入[运行接口文件](openapi/campuslife.openapi.json)到Apifox；[完整规划](openapi/campuslife.v1-design.openapi.json)已无待开发操作。
2. 使用已有正常ADMIN登录，把data.accessToken本身填入Authorize。先选一个虚构STUDENT或MERCHANT验收；无需增加第二名管理员。平台ADMIN与MySQL用户不同，管理员仍只经[受控初始化](../scripts/init-admin.ps1)创建。
3. 调用GET /admin/accounts取得目标id和version；在“停用或恢复其他账号”填accountId，正文只填status和expectedVersion，版本使用刚读取的字符串。例如：

```json
{"status":"DISABLED","expectedVersion":"0"}
```

4. 成功200，status=DISABLED、version加1，其余账号资料保留；服务器同时撤销目标全部旧会话并追加一次状态历史。不返回密码摘要、会话或历史记录，也不自动退款/取消订单。
5. 若此前保存了目标的登录token，换入该token访问本人接口应被拒绝；再用目标用户名/原密码登录也被拒绝。停用本身不修改原密码。
6. 换回原管理员token，刷新列表取得最新version，以ACTIVE恢复。例如首次0→1后：

```json
{"status":"ACTIVE","expectedVersion":"1"}
```

7. 成功后version再加1，旧token仍失效，目标需重新登录取得新token。恢复不自动批准店铺、激活OWNER或处理旧单。
8. 重复原旧版本请求409 CONFIG_VERSION_CONFLICT；刷新后用当前版本提交相同状态200，但版本不变、会话不再撤销、历史不再追加。响应丢失时先刷新列表确认。

## 本人与管理员保护

任何本人状态请求（含ACTIVE不变）409 ADMIN_SELF_MODIFICATION_FORBIDDEN。仅一名正常管理员时不能停用自己；若需管理另一名ADMIN，可用已有另一账号，或通过原受控初始化显式创建。两名管理员互停由排序锁和锁后当前身份决定，失效操作者拒绝；不用人工发并发请求验收，自动MySQL测试覆盖。

## 存量业务与错误

停用不会修改订单、退款、占用、文件期限、评价、店铺或成员记录。商家账号停用后新资格读取不再公开该店铺，已有订单仍在；正常管理员可按既有异常范围核实，新增退款仍需模拟资金开关。恢复仍需原经营资格。学生停用不阻止正常商家处理既有任务或系统按原期限扫描；VISIBLE历史评价不会自动隐藏。

旧RR快照、已获准文件传输、其他账号已完成资格复核的在途操作沿原边界完成，后续请求重新判断；本操作不提供全局中断。所有新状态实际变化（含恢复）均撤销旧会话，保证不复活旧token。

ID/状态/版本/未知字段错误400；未知目标404；非正常ADMIN403，无效/撤销/过期凭据401；旧版本、版本上限实际变化或本人操作409；暂时数据库故障503，内部失败500。版本匹配且无变化时，即便版本Long最大仍200。任一写入失败、提交前会话到期，状态/版本/会话撤销/历史一起回滚。

## 实际验证记录

2026-10-26：本轮 **384个定向用例通过**（普通/Web172、真实MySQL/HTTP212，新增状态68＋存量业务7），零失败/错误/跳过。V1～V15迁移、23张业务表及mysql模式可执行JAR健康、UTF-8、trace和鉴权启动检查通过。未重跑全部历史业务IT。

| MySQL / HTTP套件 | 通过数 |
|---|---:|
| AdminAccountStatusMysqlIT | 68 |
| AdminAccountStatusDomainMysqlIT | 7 |
| AdminAccountListMysqlIT | 66 |
| AuthenticationMysqlIT | 41 |
| ProfileMysqlIT | 16 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |

学生、商家和另一管理员的停用/恢复正向通过HTTP登录与状态接口验收；多设备/过期未撤销记录全部撤销、恢复必须重登、历史DISABLED残留会话恢复时撤销、0会话合法、同版本同状态不写、旧版本409、MAX边界和严格JSON/ID/权限均覆盖。核心异常/并发样本用独立测试账号并通过SessionService创建真实JWT/会话，节省重复密码校验；没有用mock代替实际账号、会话及行锁。

真实MySQL验证双方账号按ID逐条加锁且先于会话锁；双方均已通过HTTP鉴权后互停，恰一成功、一失效403且保留一个有效管理员；三人交叉停用保留至少一个有效管理员。并发同版本仅一变更。锁等待期间管理员停用/角色变化/撤销/到期按当前状态拒绝。登录先提交时新会话被随后停用撤销；停用先提交则登录拒绝，恢复先提交则可产生新的有效会话。并发昵称/改密提交后，管理员旧版本409；退出与停用不反转账号/会话锁。

状态UPDATE、会话撤销、历史插入0行/抛异常、实际插入后失败、暂时竞争、提交前会话到期均验证状态/版本/会话/历史一起回滚。历史操作者/目标FK、禁止本人、合法且不同状态、版本差及唯一键使用真实MySQL约束验证。

存量业务通过实际预约/打印创建和履约：停用学生不改已有订单事实，商家仍能继续履行；停用/恢复商家不改店铺或成员配置、已付订单/文件保护，公开资格按APPROVED或SUSPENDED重新判断，已付支付重放保留原事实；作者停用保留VISIBLE评价及已完成文件期限。商家停用后的已付异常仍能由正常ADMIN核实，模拟资金关闭先拒绝且无变化，开启后仅一次退款；学生停用不阻止原未付扫描到期关闭。没有宣称本轮新增全局中断或重跑全部文件传输历史用例，既有RR/获准传输边界保持。

另在经@@datadir核实属于本轮独立MySQL的临时数据库执行V1～V14并保存ACTIVE/DISABLED账号和未撤销会话，再实际执行V15；确认账号与会话原值不变、历史表为空，没有虚构迁移前记录。随后写入一笔恢复及对应撤销/历史，验证新表可接受合法事实；结束后仅删除该自建临时数据库。

实际命令（独立临时MySQL及自有测试文件根）：

```powershell
$env:MAVEN_ARGS='-Dit.test=AdminAccountStatusMysqlIT,AdminAccountStatusDomainMysqlIT,AdminAccountListMysqlIT,AuthenticationMysqlIT,ProfileMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

初次测试编译发现OpenAPI用例局部变量重名，修正后完整定向运行全部通过，业务规则未改变。编译诊断保存在.cache/account-status-compile.log；实际成功记录为.cache/account-status-mysql.log，独立升级记录为.cache/account-status-upgrade.log。测试结束临时实例已停止，失败实例按精确GUID路径清理、成功实例由脚本清理；未访问用户现有项目服务数据、上传目录或启动密钥。

