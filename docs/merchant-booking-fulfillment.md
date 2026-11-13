# 预约开始与完成验收：Swagger / Apifox

2026-10-26 后续评价已按[ADR-0042](decisions/0042-student-order-review.md)接入，见[学生评价指南](student-order-review.md)。合法完成单可本人一单一评，详情reviewed反映真实记录（含隐藏），不改订单版本/状态事件/资源；旧阶段固定false或学生评价未开放由此取代，公开评价/统计后续。

2026-10-25 后续扩展：[打印开始与待取件](merchant-print-fulfillment.md)已按ADR-0039开放；商家合法IN_SERVICE/PRINTING与READY_FOR_PICKUP任务现可受控下载。旧“PRINT开始404/履约中不授权”的阶段描述以本次为准；学生取件、评价及管理员PRINT异常仍后续开发。

规则按 [ADR-0026](decisions/0026-booking-fulfillment.md) 确认，细则见[确认方案](merchant-booking-fulfillment-design.md)。本阶段新增开始与完成两个接口；后续管理员异常按ADR-0027交付后，当前45个运行操作，完整62个（45已实现/17待开发）；无新迁移。

## 准备

停止旧应用，使用原VS Code **CampusLife (MySQL)** 配置重启，打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，服务地址设为 `http://127.0.0.1:8080`；[完整规划](openapi/campuslife.v1-design.openapi.json)包含待开发操作。

按[预约](booking-orders.md)、[付款](booking-payments.md)、[接单](merchant-booking-confirmation.md)流程准备虚构订单，记录ID，确认ACCEPTED / PAID / ACTIVE，version="2"。本轮还没有商家订单列表，可直接填写测试订单ID。

人工验收需等待实际预约开始时刻，仍遵守至少提前2小时创建规则。没有绕过时间的HTTP调试接口，无需手工修改系统时间；精确边界、锁等待及提前完成保护由受控Clock自动验证。

## 开始与完成

1. 以本店商家登录，复制 `data.accessToken` 到Authorize，仅填token本身。在“07 商家订单履约”打开 **POST /merchant/orders/{orderId}/start**，填订单ID，**不填请求体**。
2. 在预约开始时刻及之后、结束时刻之前执行，应200、IN_SERVICE / PAID / ACTIVE，startedAt为服务器时间，version="3"。提前执行或等于/超过结束时执行，应409 BOOKING_TIME_INVALID，订单不变。
3. 再次开始，应返回同一startedAt、版本和事件事实，即使重试已经超过原预约结束也不覆盖首次开始。
4. 打开 **POST /merchant/orders/{orderId}/complete**，同样不填请求体。已开始即可完成，无须等待原预约结束；应200、COMPLETED / PAID / CONSUMED，completedAt有值，version="4"，原startAt/endAt不变。
5. 核对原payment、金额、人员和时长快照不变，refund=null，reviewed=false。切换为下单学生token查本人详情，应看到相同履约结果。
6. 切回商家token重复完成，或对已完成订单重复开始，应返回原当前完成详情，不重写时间/版本。完成后学生取消、商家拒单或无法履约取消均409 ORDER_STATE_CONFLICT，不产生退款。

完成后的CONSUMED表示原区间已经售出并使用，不表示重新可售。短预约的原开始时间可能已经过去，人工查不到它并不能独立证明防重复售卖；自动测试使用长预约及未来剩余区间，核对候选和实际新建均受保护。

## 规则速查

| 情况 | 预期 |
|---|---|
| 开始时startAt<=now<endAt，状态ACCEPTED | 200，首次开始 |
| 早于开始或等于/超过结束，尚未开始 | 409 BOOKING_TIME_INVALID，无写入 |
| IN_SERVICE完成，now>=startedAt | 200；可立即、提前或超过原结束再完成 |
| 完成时间早于已记startedAt | 409 BOOKING_TIME_INVALID，无写入 |
| ACCEPTED直接完成，未付款/待接单或已关闭操作 | 409 ORDER_STATE_CONFLICT |
| 学生/管理员使用商家接口 | 403 ROLE_FORBIDDEN |
| 账号/OWNER停用 | 403，不能重放旧事实 |
| 不存在、跨店、非BOOKING | 404 RESOURCE_NOT_FOUND |
| 正常账号及有效店主，店铺暂停/停业/服务下架 | 允许按原已接订单开始与完成，不重新报价 |
| 模拟资金关闭，但已有合法已付订单 | 两动作可用，不生成新支付或退款 |
| 携带{}、JSON null、客户端时间/金额等业务请求体 | 400 VALIDATION_FAILED |

没有自动开始或自动完成。预约结束仍未开始、服务中发生异常可按[管理员核实指南](admin-booking-exception.md)全额模拟退款关闭，不把异常单伪造为完成。晚于预约结束才完成不自动延长原占用，评价及文件/打印能力也尚未开放。

## 自动验证记录

2026-10-22 首轮相关回归246个用例全部通过（普通/Web86、商家取消47、商家确认59、履约51、文档3），随后补充完成/新建竞争、账号停用重放及开始写入跨结束时间场景，并更新旧注解，最终定向 **143个用例全部通过**（普通/Web86、履约54、文档3），零失败/错误/跳过。两轮最新相关报告覆盖249个用例，不代表单次全回归249个；本轮未重跑全部旧业务。

54个真实MySQL/HTTP履约用例覆盖完整正常流程及原资金/时间/版本/事件；开始前/等于开始/结束前/等于结束/结束后，真实日期锁等待跨开始/结束，以及开始判定时间在实际写入跨结束后仍固定；完成立即/提前/超过原结束、倒退时钟拒绝；重复/多端操作、开始与完成竞争、开始与取消时间/状态竞争；角色/本店/类型/账号/OWNER，暂停/停业/分类停用/下架不改旧单快照，资金关闭仍可合法履约；无请求体及非法状态约束、完成后不可普通退款；状态/占用/事件故障与提交前会话过期整笔回滚、版本上限、真实InnoDB锁超时503及可重试。

使用4小时原预约在开始时提前完成，并把新服务时长改为15分钟，证明未来剩余区间仍从公开候选排除、实际新建409，而原区间结束后的相邻预约可创建。完成提交暂停时，候选看到旧ACTIVE仍排除；并发创建等待后看到CONSUMED仍409，无可售空档。原区间的排班/营业时间保护、已完成人员停用规则及待接单扫描不误退款也有检查。

独立临时MySQL的V1～V10迁移与mysql可执行JAR真实HTTP启动/健康/中文/鉴权边界检查通过，无新迁移。两轮自有实例及目录由脚本停止并清理，未访问用户现有项目库或密码。

实际运行OpenAPI43个操作、完整62个（43已实现/19待开发）、9分组、88个schemas；生成器一致性、官方OpenAPI3.0 schema、7个请求示例及参数/模型引用和本地链接检查通过。尚未代用户进行Swagger/Apifox人工点击验收，按上文复现。
