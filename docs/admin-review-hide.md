# 管理员隐藏评价：Swagger / Apifox 验收

本轮已确认并实现[ADR-0044](decisions/0044-admin-review-hide.md)。新增POST /api/v1/admin/reviews/{reviewId}/hide；运行60、完整62/60/2。新增V14审核字段迁移，仍22张业务表，无新基础设施。

## 准备

沿现有VS Code启动配置重启应用，Flyway会应用V14；无需修改本机数据库账号或密钥。打开 http://127.0.0.1:8080/swagger-ui/index.html ，或在Apifox重新导入[运行快照](openapi/campuslife.openapi.json)。[完整规划](openapi/campuslife.v1-design.openapi.json)仍有2个待开发的账号管理操作，不能把它们当作已实现。

准备已有正常学生与通过受控初始化创建的ADMIN业务账号；MySQL数据库用户并不是ADMIN。沿[学生评价指南](student-order-review.md)实际完成预约或打印订单并提交评价，保存返回的data.id（reviewId）、orderId与shopId。也可以使用公开评价页的reviewId。管理员评价列表尚未提供，隐藏后需保留原ID便于重试。

## 点击流程

1. 学生登录，在Authorize填写data.accessToken本身；确认本人GET /orders/{orderId}/review为VISIBLE，订单reviewed=true。匿名GET /shops/{shopId}/reviews及店铺详情能看到该条及评分。
2. 使用管理员账号调用登录接口，把Authorize替换为管理员data.accessToken；在“08 管理员”展开“隐藏不适当评价”，填reviewId，正文如下后Execute。

```json
{"reason":"包含不适当内容，经核实隐藏"}
```

3. 应返回200、data.visibility=HIDDEN，原评分、文字、创建时间及ID保留；服务器同时保存首次管理员、原因和审核时间，但此响应仍只含原六个评价字段。
4. 重新发起匿名公开评价、商家列表/详情请求：该条不再公开或参与评分；剩余评分按原口径重算，全部隐藏后rating=null、reviewCount=0。之前已经返回或已建立旧快照的结果可以保留，需重新请求核对。
5. 换回学生token，查询本人评价仍能看到原内容/HIDDEN，订单reviewed=true；再次提交评价409 REVIEW_ALREADY_EXISTS。原订单状态、版本及事件不因隐藏变化。
6. 换回原管理员token，用同一原因重试200，首尾空白归一化相同也允许；换原因或换另一个管理员409 REVIEW_STATE_CONFLICT。此操作不提供恢复或改原因，不能借重试覆盖首次记录。

## 边界与错误

学生/商家403；未登录或失效会话401，停用管理员403。作者、店铺或OWNER停用仍可由正常管理员处理。目标不存在404。reviewId须规范正BIGINT字符串，正文仅reason字符串、strip后1～300个Unicode码点；空白、null、超长、额外expectedVersion等字段均400。内部换行保留，表情按码点计数。

首次服务器时钟早于评价创建时间409 REVIEW_TIME_INVALID；同人同原因合法重放不重判首次窗口。V13历史HIDDEN若没有审核信息仍可本人读取，但hide返回409，不补造管理员或原因。数据库暂时故障503可重试原原因；其他服务错误500不宣称成功，审核三字段与visibility一起回滚。

不新增订单事件、资金、文件期限或版本；模拟资金关闭也能合法隐藏。ADMIN没有因此取得PDF内容访问权限。

## 实际验证记录

2026-10-26：本轮各套件最终通过记录去重 **399个定向用例**（普通/Web172、MySQL/HTTP227；新增隐藏评价61），无跳过。最后针对管理员数据库故障提示重新验证Web13、回滚边界4、OpenAPI3共20个，重复执行不累加。V1～V14迁移、22表及mysql模式JAR健康、UTF-8、trace与鉴权启动检查通过；未重跑全部历史业务IT。

| MySQL / HTTP 套件 | 最终通过数 |
|---|---:|
| AdminReviewHideMysqlIT | 61 |
| PublicShopReviewsMysqlIT | 62 |
| OrderReviewsMysqlIT | 90 |
| OpenApiMysqlIT | 3 |
| MysqlIntegrationIT | 11 |

预约/打印正向用例实际完成履约、提交评价，再由管理员隐藏；验证六字段响应、首次审核记录与原评价事实、公开页/列表/详情评分、本人与reviewed/重复评价、目标停用、原因Unicode/严格JSON/ID、时钟倒退及同人/异人/异原因重试。另验证同店剩余评分重算与其他店铺隔离。

真实MySQL并发隐藏保留首次提交事实；店铺/评价锁等待后读取当前状态与时刻；管理员账号锁等待期间停用/角色变化/会话撤销/到期按当前数据库状态拒绝。写入失败、实际UPDATE后抛异常、提交前会话失效、暂时数据库故障均整体回滚visibility与审核字段，不改订单、事件、支付/退款或文件期限；持目标学生/经营账号、订单和文件行锁及文件实例锁时隐藏仍可完成，无磁盘IO。公开评价页/商家详情/列表旧RR快照在实际hide提交后仍内部一致，下一请求排除并重算。

审核三字段完整性、VISIBLE不带审核、原因长度、时间及管理员FK约束均在真实MySQL验证。另在经@@datadir核实属于同一独立测试实例的临时数据库依次执行V1～V13，保存VISIBLE/HIDDEN样本，再实际执行V14；确认旧记录及空审核字段保留、新审核合法写入，结束后仅删除该自建数据库。不将升级样本当作完整履约案例。

首轮新增测试有4处测试设置问题：未登录错误码预期应为AUTH_REQUIRED；两处时钟回退先触发JWT时间校验；一个直接SQL竞争用例因审核外键锁提前阻塞管理员账号。修正为合法JWT下的评价时间边界、遵守账号锁关系的竞争设置，并补充4个锁后资格复核用例；61个全部通过。业务规则未因此改变。最后修正管理员暂时数据库故障提示，明确用相同原因重试，而非提示管理员读取学生本人评价；相关4个回滚边界与最终包重新验证。

实际命令，均使用独立临时MySQL与自有文件根，不连接用户现有项目服务数据：

```powershell
$env:MAVEN_ARGS='-Dit.test=AdminReviewHideMysqlIT,PublicShopReviewsMysqlIT,OrderReviewsMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90

$env:MAVEN_ARGS='-Dtest=WebFoundationTest -Dit.test=AdminReviewHideMysqlIT,OpenApiMysqlIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90

$env:MAVEN_ARGS='-Dtest=WebFoundationTest -Dit.test=AdminReviewHideMysqlIT#updateAndFinalSessionFailuresRollbackVisibilityAndMetadata,OpenApiMysqlIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

日志分别保存在.cache/admin-review-mysql.log、.cache/admin-review-mysql-final.log及.cache/admin-review-message-final.log；独立升级记录为.cache/admin-review-upgrade.log，61项完整报告归档为.cache/admin-review61-report.xml。这些是本机诊断记录，交付说明不依赖其入库。失败实例停止后按精确GUID路径清理，成功实例由脚本停止并清理。

