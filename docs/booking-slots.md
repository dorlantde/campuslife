# 预约候选时段：Swagger / Apifox 验收

2026-10-22，原 [ADR-0021](decisions/0021-booking-slot-candidates.md) 的 GET /api/v1/shops/{shopId}/booking-slots 已按 [ADR-0022](decisions/0022-booking-orders.md) 接入真实占用，位于“03 商家入驻”，摘要为“查询预约候选时段（含真实占用）”。当前运行43个操作，完整规划62个（19个待开发），订单见[预约验收](booking-orders.md)，模拟支付及待接单退款见[支付验收](booking-payments.md)，商家确认见[确认验收](merchant-booking-confirmation.md)。

当前候选从配置与排班交集中扣除 HELD/ACTIVE/CONSUMED 真实占用，到期但尚未提交关闭/退款的HELD/ACTIVE仍保守占用。**查询不预留人员/时间，不冻结价格**；创建待支付预约仍在锁内重新核对。模拟支付与待接单全额退款已按ADR-0023实现，商家确认按ADR-0024实现，接单后仍占用；开始/完成已按ADR-0026实现，完成后CONSUMED仍保留原区间。

## 准备配置

1. 用原 VS Code 的 CampusLife (MySQL) 配置重启，打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。
2. 按[目录指南](public-shop-catalog.md)准备已批准且账号/OWNER 有效的虚构理发店，记录实际 shopId。商家登录并 Authorize。
3. 按[服务指南](merchant-service-catalog.md)准备已上架的 30 分钟理发服务（例如 3000 分），记录实际 serviceId。
4. 按[人员指南](merchant-barbers.md)准备已启用且绑定该服务的人员，记录实际 barberId。按[经营指南](merchant-operation.md)开启 businessEnabled。
5. 选择 **Asia/Shanghai 明天**作为 date，先配置该星期 09:00～18:00 的营业时间，再按[排班指南](merchant-schedule.md)为该人员排当天 09:00～10:00。整周营业时间 PUT 会替换一周，已有其他排班时须保留相应覆盖，不能误清空计划。
6. 每次配置写入使用最新查询版本；排班、姓名/启停和绑定共用人员版本，营业时间使用店铺版本。

明天 09:00 距当前校园时间始终超过 2 小时，适合复现固定三个候选。实际日期及 ID 必须按本机数据替换，不能长期照抄示例。

## 查询与核对

Authorize → Logout → Close，清除旧 token。在“03 商家入驻”展开候选时段 GET，Try it out，填写四个必填字段：shopId、serviceId、barberId、date，点击 Execute。

预期 200 / OK，data 包含服务当前版本、价格、时长与三个候选：校园时间 09:00～09:30、09:15～09:45、09:30～10:00。每个开始间隔 15 分钟，不是每隔 30 分钟。以下为日期/价格均虚构的结构示例：

```json
{
  "serviceVersion": "0",
  "priceAmount": 3000,
  "durationMinutes": 30,
  "slots": [
    {"startAt": "2026-10-22T01:00:00Z", "endAt": "2026-10-22T01:30:00Z"},
    {"startAt": "2026-10-22T01:15:00Z", "endAt": "2026-10-22T01:45:00Z"},
    {"startAt": "2026-10-22T01:30:00Z", "endAt": "2026-10-22T02:00:00Z"}
  ]
}
```

响应是 UTC，校园时间需加 8 小时；序列化可省略零毫秒，均为标准带 Z 时间。slots 可以彼此重叠，因为它们是不同选择，不是同时成立的订单。当前不是营业时段，不影响查询明天的候选。

Apifox 可更新导入[运行快照](openapi/campuslife.openapi.json)或[完整规划](openapi/campuslife.v1-design.openapi.json)，服务地址 http://127.0.0.1:8080，公开接口选择“无需认证”，关闭旧 token 继承；失效凭证仍会返回 401。

## 对照验收

| 操作 | 预期 |
|---|---|
| 关闭营业开关 | 200，slots=[]，版本/价格/时长仍返回 |
| 合格组合没有营业时间/排班，或服务比连续可用区间长 | 200，slots=[] |
| 服务下架、人员停用或解绑 | 404 RESOURCE_NOT_FOUND；停业也不能绕过资格检查 |
| 服务或人员来自其他店、查询 PRINT 店 | 404 RESOURCE_NOT_FOUND |
| 缺少字段、非法 ID、日期格式错误、昨天或今天后第 7 天 | 400 VALIDATION_FAILED |
| 查询今天但所有开始时间不足提前 2 小时 | 200，slots=[] |
| 营业/排班有午休或断档 | 不产生跨断档候选 |
| 修改理发价格或时长后重新查询 | 返回新版本、价格、时长和相应数量的候选 |
| 公开查询携带失效 token | 401；清除 token 后可匿名浏览 |

精确的 2 小时/1 毫秒、上海跨日和并发快照不要求手动调整电脑时钟，由自动化注入 Clock 验证。服务器以一次请求取得的 now 判断，开始时刻等于 now+2小时允许，差 1 毫秒则跳到下一个符合条件的 15 分钟网格。

查询不会创建日期保护行或改变任何配置版本。真实占用排除、下单冲突与存量保护已按 ADR-0022 接入：创建后重叠候选消失，取消/超时提交后恢复，步骤见[预约验收](booking-orders.md)。

## 2026-10-21 配置候选阶段验证记录

2026-10-21：使用项目 Java 21 和独立临时 MySQL，执行 `scripts/test-mysql.ps1 -StartupTimeoutSeconds 90`，本次通过进程内 Maven 参数定向选择 BookingSlotsMysqlIT、PublicBarbersMysqlIT、PublicShopCatalogMysqlIT、OpenApiMysqlIT。此命令不访问现有项目库；不需要填写用户数据库密码。

- 本轮执行 **227 个用例全部通过**，零失败/错误/跳过：普通/Web 64 个（含新增时间策略 22 个）；真实 MySQL/HTTP 163 个（新增候选 66、公开人员 33、公开目录 61、接口文档 3）。没有重新运行全部旧业务集成用例。
- 覆盖资格失败优先于停业、非法/缺失参数、跨店绑定、各角色与失效凭证、元数据/UTC 往返、2 小时 ±1 毫秒、日期窗口、跨午夜、午休/断档、服务时长和查询不写入。四个读取关口分别暂停，在真实数据库提交资格/价格/时长/绑定/营业时间/排班修改后恢复查询，验证整次响应仍为旧 RR 快照，下一请求读取新状态；这不是订单占用或抢位测试。
- 首轮发现测试夹具初始化被误算为接口写入，以及并发用例拦截 Mapper 默认方法的内部查询而未触发。清除夹具阶段调用记录、改为拦截实际入口 views 后，以上 227 个用例重新执行通过。
- V1～V8 迁移、mysql 模式可执行 JAR 的 HTTP 启动/UTF-8/身份边界检查通过；运行 OpenAPI 33 个操作，完整规划 62 个（33 已实现、29 待开发）、9 个分组、82 个 schemas。生成器一致性、官方 OpenAPI 3.0 schema、10 个请求示例、模型/参数引用及本指南 JSON/本地链接检查通过。

使用合成数据，未访问用户现有项目库、密码或本机启动配置。成功及首轮失败的自有临时实例/目录均已停止并清理，现有 MySQL80 为 Running。人工步骤供用户复现，尚未代用户操作浏览器/Apifox。

2026-10-22 真实占用升级已按 ADR-0022 验证：HELD/ACTIVE/CONSUMED 排除、释放恢复、过期保守显示及创建锁内重查均由真实MySQL/HTTP用例覆盖，实际结果见[预约验证记录](booking-orders.md)。此补充不改写上面2026-10-21配置候选阶段的历史测试记录。
