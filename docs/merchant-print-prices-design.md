# 商家打印规格价目：已确认规则

- 日期：2026-10-20（Asia/Shanghai）。
- 状态：用户已明确选择“采用并实现”。实现取舍见 [ADR-0016](decisions/0016-merchant-print-prices.md)，实际验证见 [价目指南](merchant-print-prices.md)。
- 已确认范围：两个已规划的商家价目接口。先完成价格配置，再按后续任务开发文件上传、学生报价与下单。
- 基础：已实现的 [服务配置](merchant-service-catalog.md) 与 [ADR-0015](decisions/0015-merchant-service-catalog.md)。

## 1. 已采用规则与取舍

| 项目 | 已采用方案 | 理由与影响 |
|---|---|---|
| 权限 | ACTIVE / MERCHANT + 本人 ACTIVE OWNER + APPROVED 店铺 | 沿用服务配置权限；店铺停业、没有营业时间、服务下架时也能维护价目 |
| 服务归属 | 只能维护本店 PRINT 服务的价目 | 本店 HAIRCUT 返回 400；不存在或其他店铺的服务统一 404，不暴露对方数据 |
| 规格 | paperSize=A4；colorMode=BW / COLOR；sideMode=SINGLE / DOUBLE | 每项打印服务最多四种规格，不要求一次配齐；本轮不增加纸张尺寸、装订费或阶梯价 |
| 单价 | pricePerImpression 为严格 JSON 整数，1～100000 分/印刷面 | 与现有服务金额范围一致，拒绝小数、数字字符串、零和负数；这是项目输入上限，不是建议市场价格 |
| 启停 | 每条规则必须显式传 enabled 布尔值，支持独立停用，不物理删除 | 缺少或停用的规格以后不允许报价；其他规格不受影响；本轮只验收配置状态 |
| 批量写入 | 一次传 1～4 条不同规格，按规格新增或更新；省略的规格保留 | 沿用已有接口讨论稿的批量 upsert 语义；不是营业时间那种整表替换；空数组拒绝，全部停用需显式逐条提交 |
| 版本 | 每条价目规则独立 version，不递增 service_item.version 或 shop.version | 两个页面修改不同规格不会产生无关的版本冲突；代价是每条修改都要携带自己的版本 |
| 原子性 | 任一项参数、权限、版本或数据库操作失败，整批回滚 | 防止一个保存请求只完成部分修改；过期版本即使内容相同也拒绝 |
| 参考起价 | 不自动修改 PRINT 服务的 priceAmount | 保持已批准的参考起价含义；最终报价以后仅使用命中的启用价目，不能回退使用参考起价 |

规格枚举精确匹配，不转大小写、不自动去空白。一次请求内重复规格，无论内容是否相同均返回 400。rules、每条规则及所有字段均不允许省略或为 null；唯一例外是新增规则必须显式 expectedVersion=null。拒绝未知字段以及客户端指定 id、shopId、serviceId、version。

四种规格可设置不同单价。双面不自动半价；其总价以后按 DOUBLE 规则自己的单价计算。当前不引入免费规格、最低消费、纸张附加费或折扣。

## 2. 接口与版本行为

### 查询价目

GET /api/v1/merchant/services/{serviceId}/print-prices

返回 200 / OK，data 为数组，包含本服务已建立的所有启用及停用规则。尚未配置时返回 []。顺序固定为 BW/SINGLE、BW/DOUBLE、COLOR/SINGLE、COLOR/DOUBLE；不分页、不提供筛选，因为最多四项。

每项为 id、paperSize、colorMode、sideMode、pricePerImpression、enabled、version；id 与 version 为十进制字符串。serviceId 沿用现有服务接口的规范正十进制 BIGINT 校验，非法格式返回 400。

### 批量新增或修改

PUT /api/v1/merchant/services/{serviceId}/print-prices

请求示例（两条新规则，数据仅供学习）：

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

返回 200 / OK，data 为提交后本服务的完整价目数组，包含本次未提交但原来已存在的规格，顺序与 GET 一致。沿用统一响应结构，不新增批量部分成功格式。

- 新规格：expectedVersion 必须显式为 null；成功建立后 version="0"。如果已存在，返回 409 / CONFIG_VERSION_CONFLICT，不默认为覆盖。
- 已有规格：expectedVersion 必须是查询取得的规范非负 BIGINT 字符串。例如修改第一条价格时传 "0"，同时显式传完整规格、价格与 enabled。字符串版本对应的规格不存在也返回 409，提示重新查询。
- 实际改变单价或 enabled 才使该规则版本加一；相同内容不写入、不改变更新时间，但仍检查版本和经营资格。版本到 Long.MAX_VALUE 时允许相同内容，拒绝实际修改；任一项冲突整批回滚。
- 规格字段用于定位，不是修改规则身份。换规格需新增另一条、停用原规则，原规则 ID 保留。不存在删除接口。
- 相同请求重试不承诺自动返回原结果：新建请求已成功后继续传 null 会冲突；更新成功后继续传旧版本也会冲突。调用方应先重新查询，不自动换新版本覆盖。

错误沿用现有协议：参数、非法规格或理发服务为 400 / VALIDATION_FAILED；认证无效为 401；账号、角色、店铺和成员不满足权限为既有 403；不可见服务为 404 / RESOURCE_NOT_FOUND；版本冲突为 409 / CONFIG_VERSION_CONFLICT。数据库故障走既有统一异常处理，不返回 SQL 细节。

## 3. 计价含义与后续边界

以下公式按整份 PDF 的逻辑页数计算印刷面数，不含页码选取、拼版或空白页剔除；这些文件处理边界留到上传/报价任务定稿：

- 印刷面数 = pageCount × copies。
- 金额（分）= 印刷面数 × 命中规格的 pricePerImpression。
- 单面纸张数 = pageCount × copies。
- 双面纸张数 = 每份向上取整(pageCount / 2) × copies，不能先把所有份数页数合并后取整。

例如 5 页、2 份、双面每面 20 分：10 个印刷面、6 张纸、200 分。服务端以后从已验证的 PDF 元数据取得页数，不信任客户端传单价或页数。

本轮不新增 /print-quotes、上传、订单或模拟支付接口，不将手工示例当作这些接口已实现。文件页数/份数上限、订单总金额上限、报价过期和下单重校验将在对应任务确认；本次单价上限不能替代订单总额限制。

后续报价/下单只允许启用的规则，并重新核对店铺/服务是否可用；旧版本或金额变化要求学生重新确认。已创建订单使用价格与规格快照，商家改价/停用不能重算其金额；需在订单模块落地验证。

## 4. 数据与并发方案

V6 新增 print_price_rule，不改写 V1～V5。沿用 MyBatis-Plus、注解 SQL 与当前 merchant 分层，不增加依赖或基础设施。

原数据库讨论稿同时保存 shop_id 和 service_id。本次确认**只保存 service_id**，通过 service_item.shop_id 确定归属：服务归属已经不可修改，重复保存店铺 ID 容易形成两个归属字段不一致的问题。查询/写入先验证本店服务，再操作该服务的规则；这是对未实现数据库讨论稿的明确调整，已随本方案确认。

字段：id、service_id、paper_size、color_mode、side_mode、price_per_impression、enabled、version、created_at、updated_at。唯一键为 (service_id, paper_size, color_mode, side_mode)，service_id 外键限制悬空引用和误删；CHECK 约束枚举、1～100000 整数分、布尔值和非负版本。PRINT 类型由应用在服务行锁内复核，不声称单列外键能验证服务类型。

写锁顺序为本人账号 → 当前会话 → 店铺 → 本人 OWNER → 目标服务 → 价目规则，规则按固定规格顺序处理。写事务采用 READ COMMITTED，父服务锁保护不存在的规格，避免不同店铺的空范围间隙锁相互阻塞。拿到锁后重新检查经营资格、服务类型、会话有效期、规则当前内容及版本；先检查全部请求项，再执行必要写入，写入后再次核对会话到期，异常整批回滚。

不能只对不存在的价目规则执行 SELECT FOR UPDATE 并假设获得了可靠的父级保护。服务行锁协调新增/更新，唯一键同时兜底同规格重复创建；有锁等待时使用当前读，避免用旧快照合并。保留店铺锁会串行化同店经营写入，这是当前项目规模下的简单方案，不承诺不同规格物理并行执行；独立版本只消除无关版本冲突。

查询沿用只读 RR 事务，校验本人经营资格与 PRINT 服务，返回同一快照下的规则。后续报价/订单锁协议须与此协调，不能把本次配置测试当作下单锁正确性证据。

## 5. 完成标准

1. 实现 GET / PUT 两个接口及 V6 迁移，不扩展报价与订单功能；已实现操作由 20 增至 22，完整规划仍为 62。
2. 真实 MySQL 验证四种规格、默认无价目、增改停用、单价/枚举/null/未知字段、跨店权限、版本上限、重复规格、遗漏保留、同规格并发、不同规格版本独立，以及任一项失败整批回滚。
3. 验证实际 SQL 后异常与等待锁后的角色/会话/资格复查，并检查唯一键、外键和 CHECK 约束；不得仅凭 mock 宣称数据库并发成立。
4. 同步注解、运行 OpenAPI、完整规划文件、数据库/接口文档及 Swagger / Apifox 点击指南；清楚记录已执行、未执行和环境限制。

人工验收流程为：打印商家登录 → 选择本店 PRINT 服务 → 查询空价目 → 一次建立两种规格 → 仅修改一种并确认另一种保留 → 旧版本冲突 → 停用一种 → 混入冲突项确认整批没有落库。以上是计划验收，不代表现已执行。
