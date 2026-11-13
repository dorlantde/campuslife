# 学生订单列表验收：Swagger / Apifox

2026-10-24支付扩展：[ADR-0035](decisions/0035-print-mock-payment.md)已支持学生PRINT模拟付款、待接单取消/超时退款及成功资金历史。文件仍任一未终结引用NULL保护、最后实际终结后7天，支付不续期。操作见[打印支付指南](print-payments.md)；商家打印访问未开放。

2026-10-24更新：打印创建与学生PRINT详情/取消/列表/事件已按[ADR-0034](decisions/0034-print-orders.md)交付，见[打印订单点击指南](print-orders.md)。未引用文件仍上传后24小时；任一打印订单未终结则expiresAt=null，全部终结后7天；下载/报价不自行续期。商家打印访问与履约后续开发，下文原阶段记录保留。

规则按[ADR-0028](decisions/0028-student-order-list.md)确认，完整细则见[确认方案](student-order-list-design.md)。本轮新增GET /api/v1/orders；当前46个运行操作，完整62个（46已实现/16待开发），无新增迁移。

## 打开与准备

停止旧应用，用原VS Code **CampusLife (MySQL)** 配置重启，打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，服务地址设为`http://127.0.0.1:8080`；[完整规划](openapi/campuslife.v1-design.openapi.json)仍包含待开发操作。

使用虚构学生账号登录，把响应`data.accessToken`复制到Authorize，只填token本身。已有预约会直接显示；没有订单时可先验证空页，再按[预约创建指南](booking-orders.md)创建一笔。支付、取消及履约按原规则进行，无需为列表专门输入数据库密码或管理员身份。

## 点击查询

1. 展开“05 学生订单 → GET /api/v1/orders”，点击Try it out。
2. orderType与status保持未填写，page、size可不传或填1、20，点击Execute。应200，data含items、page、size、total、totalPages；只列本人预约。
3. 查看items：id、shopId为字符串，shopName/serviceName为下单名称，payableAmount为整数分，bookingStartAt/bookingEndAt为UTC预约区间，printingProgress=null。status与paymentStatus分别显示，不能把支付状态PAID填入主状态筛选。
4. 复制一项id到 **GET /api/v1/orders/{orderId}**，核对摘要与详情快照一致。后续付款、取消继续使用原接口；列表不返回完整流水、备注或订单版本。
5. status选择PENDING_PAYMENT、PENDING_ACCEPTANCE、ACCEPTED、IN_SERVICE、COMPLETED或CLOSED之一，结果只保留该状态；不选则查看全部。
6. 用page=1、size=1查询，再改page=2查看下一项；超出末页应items=[]，total/totalPages仍为匹配数据总数。没有订单时total=0、totalPages=0。
7. orderType选BOOKING应与省略类型一致；选PRINT应200空页，表示打印下单尚未接入，不是现有打印业务验收通过。

可按[模拟支付](booking-payments.md)、[商家确认](merchant-booking-confirmation.md)、[开始/完成](merchant-booking-fulfillment.md)、[管理员异常](admin-booking-exception.md)准备其他状态。需要等待实际预约开始的状态不增加调试捷径；列表本身无需等待。

## 边界与历史

| 场景 | 预期 |
|---|---|
| 默认page/size | 1/20；size最大100 |
| 非法枚举、小写、空白或显式空值 | 400 VALIDATION_FAILED |
| page=0、负数、2147483648，size=0或101，小数等 | 400 VALIDATION_FAILED |
| 合法PRINT同时搭配非法status/page/size | 400，不能因返回空页而忽略错误 |
| 商家/管理员token | 403 ROLE_FORBIDDEN |
| 学生停用、无token或会话失效 | 403 ACCOUNT_DISABLED或相应401 |
| 切换到另一学生token | 只能看该学生自己的订单，无订单则空页 |
| 店铺暂停、服务下架、经营账号停用或配置改价 | 本人历史保留，名称/金额/时间不被改写 |
| CLOSED且REFUNDED | 冻结payableAmount仍为原金额，不变为0 |
| 截止时间已到，但扫描未提交关闭 | 仍按数据库实际状态显示/筛选，GET不自动退款关单 |

列表按创建时间倒序，同一时刻按ID倒序。页码分页的不同请求之间若有新增或状态变化，下一页可能移动；单次响应的total与items来自同一个数据库快照。并发快照一致性由自动测试验证，不要求手工同时点击多个窗口制造时序。

当前不提供切换studentId/shopId或自定义SQL排序的查询能力，订单归属由登录身份固定。商家应使用已实现的[本店列表](merchant-order-list.md)，不能用本人列表代替商家管理；订单事件按[事件指南](order-events.md)查询。

## 自动验证记录

2026-10-22 独立临时MySQL定向Maven verify **197个用例全部通过**（普通/Web86、本人列表43、原预约订单65、文档3），零失败/错误/跳过。按只读列表影响范围验证，未重跑全部旧业务或全部资金写用例。

43个列表真实MySQL/HTTP用例覆盖本人数据隔离、实际创建/支付/接单/开始/完成/取消后的六种状态、类型与状态组合；默认/逐页/越界/空页、同创建时刻ID倒序、最大INT页码的long偏移、size1和100、前导零按既有数字解析；非法枚举/分页、PRINT空页完整校验、非STUDENT/停用/撤销/过期会话及无效凭证。

验证历史店铺/经营账号/OWNER停用、服务下架和名称/价格/时长变化不改写原摘要；退款后保留冻结应付金额，过期但未关闭仍显示实际状态。列表不写状态、版本、事件或资金，也不逐单查询详情/资金；合成尚未实现的PRINT记录不会被展示。

通过实际SQL总数查询后暂停请求，由另一真实HTTP事务新增订单（含原空列表）、支付或取消，再继续取页，验证四种并发情况下total/items仍来自原RR快照，下一次独立请求看到已提交变化。另一事务持有店铺/日期/订单写锁并修改未提交快照时，列表仍可读取已提交旧值，不需获取这些业务写锁。未用mock结果代替真实数据库隔离验证，也未进行性能压测。

V1～V10迁移、mysql可执行JAR真实HTTP启动、健康/UTF-8中文/鉴权边界检查通过；没有新增迁移/索引/依赖/XML，未改本机启动凭据。脚本停止并清理自有测试实例及目录，未访问用户现有服务数据或密码。

运行OpenAPI46个操作，完整62个（46已实现/16待开发）、9分组90schemas；生成器一致性、运行/规划两份官方OpenAPI3.0 schema、6个请求示例、参数/模型及引用检查通过。尚未代用户完成Swagger / Apifox人工点击验收，按上文复现。
