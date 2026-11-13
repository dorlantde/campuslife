# 预约即时隔离自动验收报告

- 日期：2026-10-27（America/New_York）。
- 用户选择：立即运行隔离自动整链验收，以测试时钟推进省去真实等待。
- 结论：**预约4条完整HTTP场景全部通过；本轮共190个用例，失败0、错误0、跳过0。**
- 本轮执行现有测试，未新增或修改生产代码、测试代码、数据库迁移、启动配置或业务规则。

## 执行范围与结果

| 验证 | 测试类 | 用例 | 结果 |
|---|---:|---:|---|
| 普通策略、令牌与Web测试 | 8 | 172 | 全部通过 |
| BookingJourneyMysqlIT真实预约整链 | 1 | 4 | 全部通过 |
| MysqlIntegrationIT迁移与数据库约束 | 1 | 11 | 全部通过，V1～V15、23张业务表 |
| OpenApiMysqlIT接口文档 | 1 | 3 | 全部通过，62个已实现操作 |
| mysql可执行JAR启动检查 | 独立检查 | 不计JUnit用例 | 健康、UTF-8、trace、访问边界通过 |
| 临时MySQL与专用目录清理 | 独立检查 | 不计JUnit用例 | 脚本成功退出，临时实例目录为空 |

本轮是定向验收，没有重跑全部47类历史MySQL集成测试。11个新生成的测试类报告合计190，不累加之前2675个全量用例或其他旧报告。为保留当前工作区的运行条件，本次未执行clean；按运行开始时间过滤报告，并核对集成测试名单恰为本轮指定的三类。

## 四条场景

每条场景均从独立学生注册、商家申请、管理员审批、营业配置、上架理发服务、理发师绑定/启用及排班开始。管理员账号使用受控AdminAccountInitializer在临时库创建；其他业务准备和动作使用真实HTTP。SQL用于断言和精确清理，不预插商家配置或订单。

| 场景 | 关键实测结果 |
|---|---|
| 正常完成与公开评价 | 候选、创建和同键重放、双方列表、他人404、支付/接单重放、停用人员409、提前开始409；推进测试时钟并重新登录，开始/完成至COMPLETED/PAID/CONSUMED、version=4；评价不改订单版本/事件，双方五条事件一致，改昵称后公开展示当前昵称，真实评分5/数量1，一笔支付且无退款 |
| 未支付取消 | NOT_PAID/RELEASED，原时段重新可选，无支付/退款，原创建键仍指向原单，新键可创建新单，原单两条事件 |
| 商家拒单退款 | CLOSED/REFUNDED/RELEASED，时段重新可选；同原因重复结果不变，一笔支付/一笔退款、三条事件 |
| 服务中管理员核实异常 | 原因和读取版本核实关闭，CLOSED/REFUNDED/CONSUMED，保留原区间；同请求重放不重复退款，最后事件为ADMIN，关闭单不能评价 |

## 测试时钟与隔离边界

测试起始Clock固定为`2026-10-26T01:00:00Z`，这是用例时间，不是本机当前时间。测试先断言提前开始返回409 BOOKING_TIME_INVALID，再将仅测试环境的Clock推进到所选startAt，重新登录后执行履约；至少提前2小时及开始窗口规则仍参与校验。

使用现有脚本创建回环随机端口的独立MySQL、随机测试凭据和专用文件根；成功结束后关闭并清理该实例。用户现有项目数据库、持久上传目录和业务账号不参与这轮测试，也没有修改电脑时间或现有应用的Clock。

本轮模拟资金在测试实例显式开启；生产默认开关及本机运行配置未修改。没有公开时间快进接口，没有为浏览器人工流程放宽时间限制。

## 实际执行与证据

```powershell
$env:MAVEN_ARGS='-Dit.test=BookingJourneyMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'
.\scripts\test-mysql.ps1 -StartupTimeoutSeconds 90
```

- Maven verify本次耗时：47.050 s，不含完整实例准备/清理等时间；不是接口性能或容量指标。
- 本轮日志：`.cache/booking-immediate-2026-10-27.log`。
- 新报告：`target/surefire-reports/TEST-*.xml`、`target/failsafe-reports/TEST-*.xml`中本轮开始后生成的上述11类。
- [脱敏计数与场景证据](booking-immediate-2026-10-27.json)：不复制JUnit系统属性、环境变量、密码、token或请求头。
- 测试源文件SHA-256：`48a865e7491516ed463c20e16093bc229317d11dbc8d1c6ea17a09abc8cdbb47`。

| 测试方法 | 结果 |
|---|---|
| completeJourneyCreatesBusinessThroughHttpAndPublishesARealReview | 通过 |
| adminClosesStartedServiceWithOneRefundAndKeepsConsumedInterval | 通过 |
| unpaidCancelReleasesWithoutMoneyAndCreationKeyKeepsTheOriginalOrder | 通过 |
| merchantRejectRefundsExactlyOnceAndOffersTheSlotAgain | 通过 |

## 与浏览器人工验收的关系

用户已选择本次即时自动方式，当前目标已完成，无需为这轮测试手动新建理发商家或等待两小时。[浏览器预约记录](../manual-booking-acceptance.md)仅保留此前的准备状态，不将上述自动结果填成人工17项通过。需要以后继续人工时，再准备用户掌握凭据的商家和真实预约时间。

本次没有用户手动等待真实预约窗口，没有实体理发、压测或部署验证；退款边界也只在上述现有场景范围内成立，不宣称覆盖了所有售后情形。
