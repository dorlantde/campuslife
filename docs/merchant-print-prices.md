# 打印规格价目：Swagger / Apifox 验收

已确认规则见 [ADR-0016](decisions/0016-merchant-print-prices.md)。本轮两个接口位于“06 商家经营配置”，V6 新增 print_price_rule。本节为价目阶段记录；文件上传现按ADR-0031、学生报价现按[ADR-0033](decisions/0033-print-quotes.md)交付，打印下单仍待后续。

## 准备

1. 用原 VS Code 配置重启项目，Flyway 自动执行尚未应用的 V6；不要手动修改旧迁移或重新建库。
2. 打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。使用已批准、店主有效的 PRINT 分类商家登录，将实际 data.accessToken 填入 Authorize。申请/审核步骤见 [入驻](merchant-onboarding.md)和[审核](admin-shop-review.md)。
3. 按 [服务指南](merchant-service-catalog.md) 新建或选择本店 PRINT 服务，记录其 id。创建时 durationMinutes 必须显式 null。店铺停业、未配营业时间或服务下架也能维护价目。

所有请求的 serviceId 都填此服务 ID。价目版本、服务版本与店铺版本互相独立。

## 查询与建立价目

先执行 GET /api/v1/merchant/services/{serviceId}/print-prices。新服务应为 200 / OK、data=[]；已有规则按实际结果继续，不要把已有规格当作新建覆盖。

对尚未建立以下两种规格的服务，执行 PUT 同一路径：

```json
{
  "rules": [
    {
      "paperSize": "A4",
      "colorMode": "BW",
      "sideMode": "SINGLE",
      "pricePerImpression": 20,
      "enabled": true,
      "expectedVersion": null
    },
    {
      "paperSize": "A4",
      "colorMode": "BW",
      "sideMode": "DOUBLE",
      "pricePerImpression": 20,
      "enabled": true,
      "expectedVersion": null
    }
  ]
}
```

预期 200 / OK，两条规则各有自己的 id，version="0"。20 表示每印刷面 20 分。这里都是模拟价格，不涉及支付。

GET 和 PUT 均返回本服务完整价目数组，包含停用项，按 BW/SINGLE、BW/DOUBLE、COLOR/SINGLE、COLOR/DOUBLE 排序。可以只配需要的规格，不强制凑齐四种。未建立的规格没有默认价格。

## 修改、遗漏保留与停用

先查询最新价目版本，再执行 PUT，只传需要变更的规格。例如上一步首次建立后，修改黑白单面的单价并停用它：

```json
{
  "rules": [
    {
      "paperSize": "A4",
      "colorMode": "BW",
      "sideMode": "SINGLE",
      "pricePerImpression": 25,
      "enabled": false,
      "expectedVersion": "0"
    }
  ]
}
```

预期单面版本变为 "1"，enabled=false；未提交的双面仍为 20 分、启用、version="0"。原有规则 ID 保留，店铺版本、服务版本及服务的参考起价不变。PUT 是按规格批量新增/更新，不是整表替换。

保持旧 "0" 再发送，应为 409 / CONFIG_VERSION_CONFLICT。重新查询后，用最新版本提交相同内容应成功，但不递增版本。再次启用需要完整传该规格、单价、enabled=true 和最新价目版本。没有删除接口；空 rules 不表示清空，会返回 400。

## 验证整批回滚

在一次 PUT 中提交两项：一项使用正确版本修改已存在规格，另一项使用过期版本修改另一已有规格。预期 409；重新 GET，所有单价、enabled 和版本应保持原样。也可以混合正确更新与错误参数的新规则，预期 400 且全部没有保存。

已有规格仍传 expectedVersion=null、未建立规格传字符串版本，均为 409。遇到冲突应先查询并核对，不自动替换版本覆盖。

## 边界

| 场景 | 预期 |
|---|---|
| A4 × BW/COLOR × SINGLE/DOUBLE，1～100000 整数分/面 | 接受；不同规格可以设置不同价格 |
| 20.0、"20"、0、负数、超上限或非布尔 enabled | 400 / VALIDATION_FAILED |
| rules 缺失/null/空/超过 4 项、null 项、重复规格 | 400，即使重复项内容相同 |
| 字段省略/null 或未知字段 | 400；只有新规则的 expectedVersion 必须显式 null |
| 理发服务配置价目 | 本店服务 400；他店服务仍统一 404 |
| 学生/管理员、未批准店铺、无效 OWNER | 沿用现有 403 权限错误 |
| 服务不存在或不属于本人 | 404 / RESOURCE_NOT_FOUND |
| 价目版本到上限 | 相同内容允许；实际变化 409，整批回滚 |

双面不自动半价。例如 5 页、2 份、双面每面 20 分，应为 10 个印刷面、6 张纸、200 分；这里说明计价含义，当前没有 /print-quotes 可调用。份数、文件页数与订单总额限制在对应任务定稿。

Apifox 重新导入 [运行快照](openapi/campuslife.openapi.json)，或同步本机 /v3/api-docs。当前含排班的运行版共 32 个操作，完整规划仍为 62 个（32 已实现、30 待开发）。

理发师管理与服务绑定已实现，最新交付与执行记录见 [理发师验收](merchant-barbers.md)。下方保留打印价目任务的验证记录。

## 验证记录

2026-10-20，Java 21 + 独立 MySQL 8.0：首次全回归普通 42 + 集成 451，共 493 个用例全部通过，其中新增打印价目 80 个。随后将最终写事务明确为 READ COMMITTED、把嵌套校验改到集合元素，并补充跨店首次新增及锁等待用例；定向执行最终价目 84 + 文档 3 个集成用例及普通 42 个用例，Maven verify 成功。最新各类报告合计 497、0 failures / errors / skipped，其余既有用例没有重复运行。

真实数据库验证覆盖四种规格及固定顺序、单价/枚举/空值/未知字段、缺失与过期版本、版本上限、遗漏保留、停用、跨店权限、新增/更新并发、批量预检查、实际插入/更新后故障的整批回滚、写入后会话过期、等待账号/店铺/服务/价目锁后的当前检查、唯一键/CHECK/外键。两个店铺先同时读到空价目、再各自插入的同步测试通过，且在事务内检查实际隔离级别为 READ-COMMITTED；不是仅凭 mock 推断数据库锁成立。

V1～V6 在独立临时库成功迁移；最终 mysql 模式 JAR 的启动、健康、UTF-8、trace 和访问边界检查通过。OpenApiMysqlIT 实际导出 22 个操作；完整规划 62 个（22 已实现、40 待开发）、9 分组、74 个 schemas。生成器一致性、官方 OpenAPI 3.0 schema、14 个请求示例及全部模型/参数引用校验通过，批量创建示例使用显式 expectedVersion=null。

验证只使用合成账号和独立临时实例，不读取真实密码、不访问已有业务数据。脚本结束后清理临时实例及目录；未代用户在现有业务库执行 V6、操作 Swagger / Apifox，或验证尚未实现的报价/订单。日志为 .cache/print-prices-mysql.log、.cache/print-prices-final.log；报告在 target/surefire-reports、target/failsafe-reports。

2026-10-24：学生侧[打印报价](print-quotes.md)已接入此处启用的规格价目，使用每条独立版本与实际页数，不使用参考起价。停用/改价影响后续报价；报价不锁价，未来下单仍须重算与冻结。
