# CampusLife V1 项目演示指南

整理日期：2026-10-27。适用于当前62个已实现接口、V1～V15迁移、23张业务表的后端。本指南组织已有能力，不新增业务规则或常驻演示账号；建议先用打印流程练习，再阅读[设计讲解与代码导读](design-walkthrough.md)。

## 1. 用一分钟说明项目

CampusLife 是面向虚构校园的 Java 后端学习项目，包含学生、商家、管理员三个角色。我选择理发预约和 PDF 打印两个场景：预约重点处理排班和时段竞争，打印重点处理计价、文件权限与生命周期。两类业务共用订单、模拟支付退款和事件记录，各自维护业务明细。当前已实现从商家入驻、经营配置到下单、履约、评价及异常处理的闭环，通过 Swagger 或 Apifox 演示。

技术上采用 Java 21、Spring Boot、MyBatis-Plus、MySQL 和 Flyway，按业务模块组织单体工程。文件使用本地私有存储，支付退款为模拟操作。现阶段没有正式业务前端，也没有真实用户运营、真实支付或压测容量结论。

先用自己的话复述，不必背诵。谈及个人贡献时，按自己实际参与的需求确认、代码阅读、验证和修改说明 AI 辅助过程；尚不能解释的部分先作为学习目标。

## 2. 演示前准备

1. 在 VS Code 选择已有 **CampusLife (MySQL)** 配置启动，沿原方式在本机填写数据库密码。不需要改用 MySQL root；数据库账号与平台业务账号不同。
2. 打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。Apifox 可导入[运行接口文件](openapi/campuslife.openapi.json)。下文接口省略统一前缀 `/api/v1`。
3. 用 `POST /auth/login` 获取响应中的 `data.accessToken`，在 Swagger **Authorize** 中只填 token 本身。切换角色后调用 `GET /me` 核对身份。携带失效 token 时，匿名接口也会拒绝请求，应先清除旧授权。
4. 准备自己掌握凭据的 STUDENT、已获批准的 PRINT 商家。管理员仅在审批或异常分支时使用；没有账号时按[受控初始化指南](admin-shop-review.md)准备。密码和 token 只在本机填写，不写进本指南或演示截图。
5. 确认商家营业开关已开启、上海当前星期和时间落在营业区间内，服务上架，A4/BW/DOUBLE价目启用。报价允许休息，创建打印订单要求当前营业。已有店铺配置不要为了演示直接整周覆盖；配置步骤见[打印完整流程](print-journey.md)。
6. 准备[五页 PDF 样本](../output/pdf/campuslife-print-sample-5-pages.pdf)，也可使用自己不含真实个人资料的无加密 PDF。新上传获得新的 fileId；不要依赖旧文件 ID 或以前的有效期。

建议分别打开学生、商家调试页，切换动作前仍核对 `GET /me`。不要假定两个标签页一定保存独立授权；每次操作前以实际身份为准。

建议先设置 A4 黑白双面20分/印刷面、上传5页样本、打印2份，方便手算：10个印刷面、6张纸、200分。现场不同配置按实际报价核对，不能照抄200分。

## 3. 主演示：打印闭环（准备充分后约10～15分钟）

时间是演示安排建议，不是性能测量。新建一笔演示订单，记录其ID；以下所有 `{orderId}` 指这一笔订单。

| 步骤 | 身份与操作 | 应看到的结果 | 可以讲解的设计 |
|---|---|---|---|
| 1 | 学生 `POST /files`，Try it out后选择PDF并Execute | 201，保存id、pageCount、expiresAt | 服务端解析页数；初始24小时有效，文件无公开地址 |
| 2 | 学生 `POST /print-quotes` | 保存priceVersion和payableAmount；五页示例为200分、6张纸 | 双面按每份向上取整纸张，费用按印刷面 |
| 3 | 学生 `POST /orders/prints`，填写新的Idempotency-Key | 首次201，PENDING_PAYMENT，printing.progress=QUEUED、version=0 | 创建时重查价目，冻结金额；不是信任客户端报价 |
| 4 | 原键、原正文再Execute一次 | 200，还是同一orderId、version=0 | 重复提交返回已有事实，不创建第二单 |
| 5 | 学生 `GET /files/{fileId}` | expiresAt=null | 未结束订单保护文件；null不是公开访问许可 |
| 6 | 学生 `POST /orders/{orderId}/payments/mock`，无正文 | PENDING_ACCEPTANCE/PAID，version=1 | 模拟资金；15分钟内付款，付款后30分钟接单 |
| 7 | 商家 `POST /merchant/orders/{orderId}/accept`，无正文 | ACCEPTED/PAID/QUEUED，version=2 | 本店有效店主且店铺获批才可首次接单 |
| 8 | 商家 `GET /files/{fileId}/content` | 200 PDF附件 | 本店已接任务授予内容权限，知道文件ID不足以下载 |
| 9 | 商家 `POST /merchant/orders/{orderId}/start`，无正文 | IN_SERVICE/PRINTING，version=3 | 打印无需等待预约时间 |
| 10 | 商家 `POST /merchant/orders/{orderId}/ready-for-pickup`，无正文 | IN_SERVICE/READY_FOR_PICKUP，version=4 | 主状态相同，打印进度进一步区分阶段 |
| 11 | 学生 `POST /orders/{orderId}/pickup`，无正文 | COMPLETED/PAID/PICKED_UP，version=5 | 由学生确认取件完成；商家complete用于预约 |
| 12 | 商家再次下载该PDF | 无其他本店合格关联时404 | 完成单不再授权；七天保留期与商家权限分开判断 |
| 13 | 学生 `GET /files/{fileId}` | 没有其他未结束引用时，最晚终结时间+7天 | 同文件多单时按全部引用判断，不能只看当前单 |
| 14 | 学生 `POST /orders/{orderId}/review` | 201；详情reviewed=true、version仍5 | 评价独立，不改订单事件或文件有效期 |
| 15 | 学生及商家分别查询订单事件；匿名查看店铺评价 | 六条事件相同，公开评价计入评分 | CREATE、PAYMENT、ACCEPT、START、READY_FOR_PICKUP、PICKUP |

步骤15使用 `GET /orders/{orderId}/events`、`GET /merchant/orders/{orderId}/events` 和 `GET /shops/{shopId}/reviews`。公开统计包含店铺全部VISIBLE评价，旧评价存在时，数量和均分不一定为1和5。

报价正文，替换实际ID：

```json
{"serviceId":"实际服务ID","fileId":"实际文件ID","paperSize":"A4","colorMode":"BW","sideMode":"DOUBLE","copies":2}
```

创建正文，替换为本次报价结果；`Idempotency-Key` 填在请求头参数框，例如自行生成一个未用过的 `demo-print-日期-序号`：

```json
{"serviceId":"实际服务ID","fileId":"实际文件ID","paperSize":"A4","colorMode":"BW","sideMode":"DOUBLE","copies":2,"expectedPriceVersion":"实际priceVersion","expectedPayableAmount":200}
```

评价正文示例：

```json
{"rating":5,"content":"虚构打印演示，已确认取件"}
```

ID和版本保留响应中的字符串格式；金额为整数分。创建返回409 QUOTE_CHANGED时重新报价，确认后用新键创建；已完成订单的原键重放仍返回原订单，不能拿它再买一次。

## 4. 补一个异常分支（约3分钟）

使用**另一笔新订单**，付款后由商家拒单，展示退款闭环，不修改已完成的主演示订单。

1. 按上面的报价/创建/付款操作，使用新的Idempotency-Key，记下另一笔orderId。
2. 商家调用 `POST /merchant/orders/{orderId}/reject`，正文 `{"reason":"虚构演示：设备维护无法接单"}`。
3. 核对 CLOSED/REFUNDED/ABORTED、关闭原因与全额模拟退款记录。用相同原因再提交一次，核对退款编号和事件数量不变。
4. 说明退款、订单关闭、事件与文件引用期限在一个数据库事务中提交；同文件还有未结束订单时继续保护。

模拟资金开关未开启会阻止新增支付和退款。此时按[支付指南](print-payments.md)检查本机配置，不以管理员异常入口绕过开关。需要演示管理员核实时，另按[异常指南](admin-print-exception.md)使用原因和读取版本操作新单。

## 5. 预约如何展示

现场可展示商家配置、候选查询、下单、模拟支付和接单。至少提前2小时、15分钟起点网格、上海今天至第6天等规则照常生效；查到候选不代表已经预留。

完整浏览器履约必须等到真实预约开始窗口。若要同场展示完成结果，可以打开[四条预约隔离自动验收报告](reports/booking-immediate-2026-10-27.md)，结合 [BookingJourneyMysqlIT](../src/test/java/com/campuslife/BookingJourneyMysqlIT.java) 说明测试先验证提前开始409，再推进仅测试环境的Clock并完成服务。它是自动HTTP验收，不能称为本人已经走完浏览器人工履约。

不要修改电脑时间、真实订单时间或应用业务规则来省去等待。需要另行人工操作时，使用[预约点击指南](booking-journey.md)与[未完成的人工清单](manual-booking-acceptance.md)。

## 6. 如何出示验证证据

| 证据 | 实际结论 | 表达边界 |
|---|---|---|
| [2026-10-26全量报告](reports/v1-acceptance-2026-10-26.md) | 当次55类、2675用例，失败/错误/跳过0，真实MySQL及包启动通过 | 是该次工作区结果；不是代码覆盖率、QPS或上线保证 |
| [打印人工记录](manual-print-acceptance.md) | P00～P15共16项通过，部分操作由用户本机完成 | 是该轮虚构数据流程；不是实体打印或全部接口人工覆盖 |
| [2026-10-27预约报告](reports/booking-immediate-2026-10-27.md) | 4条预约整链，含配套检查190用例通过 | 定向自动验收，不与2675相加，不填充人工清单 |

这些是已有证据，本次整理文档未重新执行业务回归。测试报告只说明已覆盖条件下的行为，不证明没有未知缺陷。

## 7. 自己练习的完成标准

- 能在不手写HTTP脚本的情况下，用新订单走完打印正常及拒单分支，解释金额、主状态与打印进度。
- 能找到一个订单的双方事件，并说明为何重复付款/接单不会生成重复事实。
- 能指出账号角色、订单归属、文件保留期限与商家下载权限分别在哪一步判断。
- 能按[代码导读](design-walkthrough.md)打开核心方法，说明预约防重叠和文件事务边界。
- 能准确说出当前未完成的生产工作：正式业务前端、服务器部署与监控/备份演练、压测、多实例文件方案、真实支付等。

上面是下一次练习的标准，尚未因文档写成而视为你已完成独立演示。每次练习记下实际日期、所用ID、卡住步骤和错误code即可，不记录密码或token。
