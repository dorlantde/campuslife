# 订单事件验收：Swagger / Apifox

2026-10-26 后续评价已按[ADR-0042](decisions/0042-student-order-review.md)接入，见[学生评价指南](student-order-review.md)。合法完成单可本人一单一评，详情reviewed反映真实记录（含隐藏），不改订单版本/状态事件/资源；旧阶段固定false或学生评价未开放由此取代，公开评价/统计后续。

2026-10-24 后续扩展：[商家打印确认](merchant-print-confirmation.md)已开放本店PRINT列表/详情/事件及接拒单；本页旧阶段中的“商家PRINT未开放/空页”已由ADR-0036替代。打印内容访问、接单后取消及打印后续履约仍待开发。

2026-10-24支付扩展：[ADR-0035](decisions/0035-print-mock-payment.md)已支持学生PRINT模拟付款、待接单取消/超时退款及成功资金历史。文件仍任一未终结引用NULL保护、最后实际终结后7天，支付不续期。操作见[打印支付指南](print-payments.md)；商家打印访问未开放。

2026-10-24更新：打印创建与学生PRINT详情/取消/列表/事件已按[ADR-0034](decisions/0034-print-orders.md)交付，见[打印订单点击指南](print-orders.md)。未引用文件仍上传后24小时；任一打印订单未终结则expiresAt=null，全部终结后7天；下载/报价不自行续期。商家打印访问与履约后续开发，下文原阶段记录保留。

按[ADR-0030](decisions/0030-order-events.md)及[确认方案](order-events-design.md)实现学生本人、商家本店两个事件查询入口。本轮运行49个操作，完整62个（49已实现/13待开发）；无新增数据库迁移。

## 打开和准备

停止旧应用，用原VS Code **CampusLife (MySQL)** 配置重启，打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，服务地址设`http://127.0.0.1:8080`；[完整规划](openapi/campuslife.v1-design.openapi.json)继续包含待开发操作。

使用虚构学生账号登录，将data.accessToken填入Authorize，只填token本身。在[本人列表](student-order-list.md)找到预约并复制id；无订单则按[预约创建](booking-orders.md)准备。商家使用本店正常账号及ACTIVE OWNER，在[本店列表](merchant-order-list.md)取得同一预约ID。无需输入数据库密码。

## 点击查询

1. 用学生token展开“05 学生订单 → GET /api/v1/orders/{orderId}/events”，填写本人预约id，Execute应200，data直接是数组，不是分页对象。
2. 新建预约通常只有CREATE，eventSeq="0"，fromStatus=null、toStatus=PENDING_PAYMENT，actorType=STUDENT、actorId为该学生ID，reason=null。id和eventSeq都是字符串，occurredAt为原UTC时间。
3. 按[模拟支付](booking-payments.md)付款后重新查询，应新增PAYMENT；重复合法支付仍只保留原记录。根据[商家确认](merchant-booking-confirmation.md)接单，应新增ACCEPT。按[履约](merchant-booking-fulfillment.md)到实际开始时间开始及完成，可依次看到START、COMPLETE；不增加调试时间捷径。
4. 换成本店商家登录token，展开“07 商家订单履约 → GET /api/v1/merchant/orders/{orderId}/events”，输入同一id。已保存事件字段应与学生入口一致，包含真实操作者类型/ID和原原因，但不附带姓名、登录名或联系方式。
5. 其他预约可按原取消/拒单/管理员异常流程准备关闭分支。查询仅展示已有记录，不会因查看而退款关单；重复操作不追加的事件不会在查询时凭空生成。

| 动作码 | 含义及原因 |
|---|---|
| CREATE / PAYMENT | 创建/付款；reason通常null |
| ACCEPT / START / COMPLETE | 商家接单/开始/完成；reason通常null |
| USER_CANCEL | 学生取消，reason=USER_CANCEL |
| PAYMENT_TIMEOUT / ACCEPTANCE_TIMEOUT | 系统超时；reason为对应码，actorType=SYSTEM、actorId=null |
| MERCHANT_REJECT / MERCHANT_UNAVAILABLE | 商家拒单/无法履约；reason为当时保存的原因 |
| ADMIN_EXCEPTION | 管理员核实关闭；reason为当时核实原因，actorId为实际管理员ID |

退款关闭是一条关闭事件，不另造REFUND事件；reason可能是原因码或原文字段。原文作为普通文本阅读，不能当HTML执行。时间相同仍按eventSeq顺序；序号不是全库事件ID，两者不要混用。

## 权限与只读边界

| 场景 | 预期 |
|---|---|
| 他人学生/其他商家订单、不存在、尚未支持的PRINT | 404 RESOURCE_NOT_FOUND，不读取事件内容 |
| 学生调用商家入口、商家调用学生入口、ADMIN调用任一入口 | 403 ROLE_FORBIDDEN |
| 无登录、失效/撤销会话或账号停用 | 对应401或403 ACCOUNT_DISABLED |
| 商家无OWNER所属店铺 | 404；本店资源但成员PENDING / DISABLED为403 |
| orderId=0、01、带符号/空白/小数、超BIGINT | 400 VALIDATION_FAILED；与页码允许前导零的规则不同 |
| 店铺暂停/停业、服务下架 | 学生本人及有效商家仍可读旧事件 |
| 商家账号/OWNER停用 | 本人学生历史仍可读；商家入口拒绝 |
| 学生账号停用 | 有效商家仍可读本店历史 |
| 授权订单无事件 | 200 []；不自动补写记录 |
| 到期扫描尚未关闭、模拟资金开关关闭 | 正常查询已有事件，不自动退款关单或临时推断状态 |

一个请求内，归属、商家资格和事件来自同一数据库快照。查询中途若新动作提交，本次可能仍显示旧序列，下一次查询可见；与另一次详情请求不共享快照。成员在快照建立后被停用，正在执行的商家查询可能完成，下一次403。并发及写锁边界由独立MySQL自动测试验证，无需手改数据库或多窗口抢时序。

当前预约记录很少，完整返回无分页和截断；不以当前最长5条限制未来实际记录数量。打印、评价和管理员专用事件查询后续按各自任务推进。

## 自动验证记录

2026-10-23 独立临时MySQL定向Maven verify **259个用例全部通过**（普通/Web86、事件54、管理员异常57、商家确认59、文档3），零失败/错误/跳过；未重跑全部旧业务或所有资金写用例。

54个新增真实MySQL/HTTP用例覆盖完整创建→付款→接单→开始→完成及合法重复；学生未付/待接单/已接单取消、两种系统超时、商家拒单/无法履约、管理员待接单/已接单/服务中异常共十种关闭分支，核对原状态、原因及实际操作者，无合成REFUND事件。校验九字段、字符串ID/序号、UTC时间、null前状态/原因/SYSTEM操作者ID；事件数据库行与学生/商家响应逐项一致。

覆盖本人/本店隔离、角色/账号/会话/成员、非法/不存在/非BOOKING资源、暂停/下架及其他操作者停用后的历史可见性；无权查询不读事件内容。合成排序及边界数据验证序号优先于时间/插入顺序、大整数序号精确字符串、完整数组不截断、授权无事件返回[]且不修复。这些合成数据不代表新增合法业务迁移。到期读取不写状态/版本/事件/资金，资金开关关闭仍可读。

学生及商家两个入口分别在授权后暂停事件读取，让另一真实HTTP事务付款、取消或开始服务，六种并发场景均保留原RR事件快照，下次看到新增事件。商家首个归属读取后提交付款及成员PENDING / DISABLED，当前资格/事件保持旧快照、下次403；两端读取均不等待另一事务持有的店铺/日期/订单写锁，也不见未提交的事件修改。spy仅控制时序，实际结果来自数据库SQL；未进行性能压测。

V1～V10迁移、mysql可执行JAR真实HTTP启动及健康/中文UTF-8/鉴权边界检查通过；新增两个响应映射生产Java文件，当前156个；无新表/迁移/索引/依赖/XML，未改启动凭据。脚本停止并清理自有实例和目录，未访问用户现有服务数据或密码。

运行OpenAPI49个操作，完整62个（49已实现/13待开发）、9分组91schemas；生成器一致性、运行/规划两份官方OpenAPI3.0 schema、6个请求示例及参数/模型引用检查通过。尚未代用户完成Swagger / Apifox人工点击验收，按上文复现。
