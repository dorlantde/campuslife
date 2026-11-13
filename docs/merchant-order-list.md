# 商家订单列表验收：Swagger / Apifox

2026-10-24 后续扩展：[商家打印确认](merchant-print-confirmation.md)已开放本店PRINT列表/详情/事件及接拒单；本页旧阶段中的“商家PRINT未开放/空页”已由ADR-0036替代。打印内容访问、接单后取消及打印后续履约仍待开发。

规则按[ADR-0029](decisions/0029-merchant-order-list.md)确认，细则见[确认方案](merchant-order-list-design.md)。本轮新增GET /api/v1/merchant/orders；运行47个操作，完整62个（47已实现/15待开发），无新增迁移。

## 打开与准备

停止旧应用，用原VS Code **CampusLife (MySQL)** 配置重启，打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，服务地址设为`http://127.0.0.1:8080`；[完整规划](openapi/campuslife.v1-design.openapi.json)仍含待开发操作。

使用本店虚构商家账号登录，把响应`data.accessToken`复制到Authorize，只填token本身。应已完成入驻审核、OWNER为ACTIVE。有店铺但尚无预约时可直接验证空页；需要准备订单时，按[预约创建](booking-orders.md)用学生账号创建本店预约，再切回商家token。列表不要求填写学生或店铺ID，也不需要数据库密码。

## 点击查询与履约

1. 展开“07 商家订单履约 → GET /api/v1/merchant/orders”，点击Try it out。
2. orderType和status不填写，page、size可不传或填1、20，Execute应200。data含items、page、size、total、totalPages，只展示本店预约，可以包含不同学生的订单。
3. status选择PENDING_ACCEPTANCE查询待接单；复制一项id打开 **GET /api/v1/merchant/orders/{orderId}** 核对。按[商家确认](merchant-booking-confirmation.md)接单/拒单，按[履约指南](merchant-booking-fulfillment.md)开始/完成；按[事件指南](order-events.md)查询操作记录；操作后重新查列表，应显示数据库已提交的新状态。
4. 也可选择PENDING_PAYMENT、ACCEPTED、IN_SERVICE、COMPLETED或CLOSED。PAID是支付状态，不可填入主状态筛选。
5. 用page=1、size=1，再改page=2翻页。超出末页items=[]但保留真实total/totalPages；无匹配时total=0、totalPages=0。
6. orderType省略与BOOKING结果一致；PRINT返回空页，表示打印下单暂未接入。不能据此认为打印业务已可验收。
7. 查看摘要：id/shopId为字符串，shopName/serviceName取下单名称，payableAmount为整数分，预约起止是UTC时间，printingProgress=null。摘要不含学生个人信息、备注、版本或完整资金流水；需要履约细节时读已有详情。

同创建时刻按ID倒序；不同请求间新增或状态变化可能使页码移动。本次资格、总数及页内数据来自同一个数据库快照；并发时序由自动测试验证，无需手工同时点击多个窗口。

## 边界与历史

| 场景 | 预期 |
|---|---|
| 默认page/size | 1/20；size最大100 |
| 非法类型/主状态、显式空白或空值 | 400 VALIDATION_FAILED |
| page=0、2147483648，size=0或101，小数/符号等 | 400 VALIDATION_FAILED；前导零允许 |
| PRINT同时搭配非法status/page/size | 400，完整校验 |
| 学生或管理员token | 403 ROLE_FORBIDDEN |
| 商家账号停用、无token、会话失效 | 403 ACCOUNT_DISABLED或相应401 |
| MERCHANT账号没有OWNER所属店铺 | 404 RESOURCE_NOT_FOUND；PRINT也不例外 |
| OWNER为PENDING或DISABLED | 403 ROLE_FORBIDDEN；空页/PRINT也不例外 |
| 切换另一有效商家token | 仅显示另一商家的本店订单 |
| 本店暂停/停业、服务下架、学生账号停用 | 旧单仍可读，不改变接单原有资格要求 |
| 店铺/服务改名改价 | 摘要名称/金额/起止仍为下单冻结值 |
| 已退款 | payableAmount保留原应付金额，paymentStatus=REFUNDED |
| 到期但扫描尚未提交关闭 | 按数据库实际状态显示/筛选，GET不自动退款关单 |

查询不依赖模拟资金开关。刚建立快照后OWNER被停用，正在执行的请求可能完成，下一次请求403；普通读取不等待商家配置和订单的业务写锁。这里不提供手改数据库的人工测试步骤，自动测试使用独立临时实例验证这些边界。

## 自动验证记录

2026-10-23 独立临时MySQL定向Maven verify **239个用例全部通过**（普通/Web86、商家列表48、学生列表43、商家确认59、文档3），零失败/错误/跳过。按只读列表及共享参数解析的影响范围执行，未重跑全部旧业务或全部资金写用例。

48个新增真实MySQL/HTTP用例覆盖多店隔离、本店不同学生、实际创建/支付/接单/开始/完成/取消后的六种状态、默认/逐页/空页/越界/最大偏移、固定排序、非法筛选和分页、前导零、PRINT空页及权限参数完整校验、账号/会话/成员边界。验证暂停/停业、学生停用、服务/人员停用和改名改价仍保留冻结历史；退款后保留原应付金额，到期但未关闭仍返回实际状态，GET不写版本/事件/资金、不逐单查询详情或资金。

真实SQL在count后暂停请求，另一个真实HTTP事务创建（含原空列表）、支付或取消，再继续page：四种并发场景的total/items保持旧RR快照，后续请求可见提交变化。另在首个本人店铺读取后暂停，提交新增订单和成员PENDING / DISABLED，再继续读取，验证资格与订单均沿用原快照、下一请求403。另一事务持有店铺/日期/订单写锁及未提交修改时，列表仍可读取原已提交数据。spy仅控制时序，结果由实际数据库SQL产生；未进行性能压测。

V1～V10迁移、mysql可执行JAR真实HTTP启动、健康/中文UTF-8/鉴权边界检查通过；无新迁移、索引、依赖、XML、分页插件或生产Java文件（仍154个），未改本机启动凭据。测试脚本停止并清理自有实例与目录，未访问用户现有服务数据或密码。

运行OpenAPI47个操作，完整62个（47已实现/15待开发）、9分组90schemas；生成器一致性、两份官方OpenAPI3.0 schema、6个请求示例及参数/模型引用检查通过。尚未代用户完成Swagger / Apifox人工点击验收，按上文复现。
