# 学生侧公开商家目录：已确认方案

- 日期：2026-10-21（Asia/Shanghai）。
- 状态：用户已选择“采用并实现”；决定见 [ADR-0019](decisions/0019-public-shop-catalog.md)，交付与验证见[目录验收](public-shop-catalog.md)。
- 推荐范围：公开商家列表、详情、上架服务查询三个已有规划接口。可约查询、公开理发师、评价、打印报价及订单分别后续推进。
- 依据：[接口契约](v1-api-contract.md)、[入驻 ADR-0010](decisions/0010-merchant-onboarding.md)、[经营开关 ADR-0013](decisions/0013-merchant-operation.md)、[服务 ADR-0015](decisions/0015-merchant-service-catalog.md)。

## 1. 目标与已有决定

目前商家能维护服务、营业时间和排班，学生尚无可运行的商家浏览入口。本轮先让访客或学生查询店铺及服务，形成下一步报价/预约的入口，不把浏览成功等同于可以提交订单。

已有决定保留：APPROVED 店铺可公开展示；手动停业不撤销审核，businessEnabled 与营业时间、服务或预约空位分别表达不同条件。分类字典停用只限制新申请/重提，不自动停用已批准店铺。金额为整数分，PRINT 的 priceAmount 只是参考起价、durationMinutes 为 null。

以下公开可见性、搜索及展示字段细节均由用户本次明确确认。

## 2. 推荐可见性与权限

| 项目 | 推荐规则 | 理由与影响 |
|---|---|---|
| 访问身份 | 三个 GET 均允许匿名，也允许任意正常登录角色 | 学生可先浏览再登录，商家也可检查公开展示 |
| 有效凭证 | 携带无效、过期、撤销或停用账号的 token，仍按已有规则返回 401/403 | 不静默忽略失败身份；匿名浏览时不携带 token |
| 店铺可见 | APPROVED + 本人 OWNER 为 ACTIVE + 对应账号 ACTIVE / MERCHANT | 新增公开侧资格细化；被停用的经营主体退出公开目录，不只依赖店铺审核字段 |
| 临时停业 | businessEnabled=false 仍在列表和详情可见，上架服务仍可查 | 沿用审核与开关分离，允许学生提前了解服务 |
| 尚未配置 | 没有营业时间、服务或排班的合格店铺仍可见 | 展示信息不等于接单资格，不要求配置完成才出现 |
| 分类停用 | 不额外按 shop_category.enabled 隐藏已批准店铺 | 沿用 ADR-0015；分类筛选仍可使用该分类的合法 ID |
| 不可见资源 | 列表排除且不计入 total；详情、服务列表与不存在统一 404 RESOURCE_NOT_FOUND | 不公开待审/被拒/暂停店铺或经营主体状态原因 |
| 无上架服务 | 可见店铺返回 200 / OK、空数组 | 区分店铺不可见与店铺尚无公开服务 |

只放行这三个已实现 GET 的准确路由，不提前匿名放行 /shops/**，避免把其他待开发接口一起开放。没有新的管理员操作或账号停用接口。

## 3. 三个接口

统一前缀 /api/v1，成功沿用 ApiResponse，失败沿用已有错误协议。

| 方法 | 路径 | 参数 | data |
|---|---|---|---|
| GET | /shops | keyword、categoryId、page、size 可选 | ShopSummary 分页 |
| GET | /shops/{shopId} | shopId 必传 | ShopDetail |
| GET | /shops/{shopId}/services | shopId 必传，serviceType 可选 | ServiceView 数组，仅 enabled=true |

shopId、categoryId 沿用规范正十进制 BIGINT 字符串，不接受 0、负数、前导零、空白或溢出值。格式错误 400 VALIDATION_FAILED；合法但不存在的 shopId 为 404。合法但不存在的 categoryId 筛选返回空分页，不把零匹配当错误。

### 列表与搜索

- page 默认 1，正整数且不超过 Java int 上限；size 默认 20、范围 1～100。非法/溢出值 400，不静默截断。offset 用 long 计算，防止乘法溢出。
- keyword 只匹配店铺名称，去首尾空白后最多 80 个 Unicode 码点；省略、空白或空字符串表示不筛选，非法 Unicode 或超长为 400。使用名称包含搜索，遵循当前 MySQL 字段排序规则；不增加拼音、全文或模糊纠错。
- `%`、`_` 等字符按字面内容搜索，不让用户输入变成 LIKE 通配符。使用绑定参数并转义通配字符，不拼接输入为 SQL。
- categoryId 为精确分类筛选，与 keyword 同时传入时取交集。
- 固定 id ASC，沿用公开目录草案；不接受客户端自定义排序。超出最后一页返回空 items，仍提供实际 total / totalPages。
- data 为 items、page、size、total、totalPages；total 只计符合可见性与筛选的店铺。不承诺不同请求间分页快照不变。

### 上架服务

serviceType 可省略，或严格为 HAIRCUT / PRINT，其他值 400；合法类型在该店没有匹配项目则返回 []。只返回该可见店铺的 enabled=true 服务，固定 created_at DESC、id DESC，与现有列表约定一致；本轮沿用数组，不新增分页或服务详情接口。

ServiceView 返回 id、serviceType、name、description、priceAmount、durationMinutes、version。金额单位分；PRINT 时长 null，参考起价不代替规格报价。店铺暂时停业仍可看服务；服务版本仅供后续重新核对，不预留价格或资源。

## 4. 公开字段与 openNow

ShopSummary：id、name、category（id/code/name）、locationText、businessEnabled、openNow、rating、reviewCount。

ShopDetail 在上述字段外增加 description、businessHours、campusCode；营业时间沿用 weekday/startTime/endTime 的整周分段形式。列表不返回整周时段，但批量读取所需营业时间，避免逐店查询。

不复用 MerchantShopView，不返回账号 ID/用户名、成员状态、审核原因/审核人、商家店铺版本、会话或密码信息。

openNow 推荐精确定义为：在公开可见资格成立后，businessEnabled=true，且服务端 Clock 的 Asia/Shanghai 当前星期/时刻位于某一营业区间 [startTime,endTime)。起点包含、终点不含；午休、休息日、未配置营业时间或开关关闭均为 false。一次响应只取一个当前时刻，跨午夜测试使用注入 Clock。它不检查理发师排班/服务绑定/占用，也不等于有预约空位或可以下单。

评价模块尚未建立。为延续既有规划，推荐本轮保留 rating=null、reviewCount=0，并在 API 注解说明为当前无评价能力的阶段值，不生成虚构评分、不引入评价表或统计任务；评价实现时再接入真实 VISIBLE 评价统计。本次已确认保留这些阶段字段以稳定展示结构。

## 5. 实现与一致性取舍

继续在 merchant 模块内分层组织，只新增当前接口需要的 Controller / Service / DTO 或 VO / 注解 Mapper 等代码，不创建空模块。不增加依赖、缓存、搜索服务、XML 或数据库迁移，复用现有八轮迁移及十三张业务表。

查询采用只读 REPEATABLE READ，让可见资格、列表总数、当前页、营业时间及服务来自同一请求内一致快照。并发审核/停用/修改不导致同一响应拼接两个版本；已开始查询可能返回其快照时的有效资料，下次请求看到新状态。读取不加写锁、不修改店铺/人员/服务版本，不以展示查询阻塞配置写入。

可见资格的 SQL 条件在列表、详情和服务入口一致。营业时间批量加载，避免列表 N+1；服务查询在同一快照先核对店铺可见性。所有接单条件仍须在未来创建订单的锁内重新验证，公开查询无法保证后续写入成功。

## 6. 本次完成标准

1. 实现三个 GET，运行版由 28 增至 31 个操作；完整规划仍 62 个，剩余 31 个待开发。原有预约/评价/公开理发师接口不提前开放。
2. 真实 MySQL + HTTP 验证匿名/有效与无效 token、审核/账号/成员状态可见性、停业可见、分类停用边界及私有字段不泄露。
3. 验证分页、分类与名称交集、空白/超长关键字、通配符字面匹配、非法 ID/参数、无匹配/越界页与固定排序；验证仅展示本店上架服务及筛选。
4. 注入 Clock 验证上海时区、跨日、起止点、午休、无时段、手动关店；用真实数据库并发事务验证读快照一致性和批量加载。没有写锁变更，不声称测试了预约并发。
5. 同步 OpenAPI 注解、运行导出、完整规划、决策记录及 Swagger / Apifox 点击指南；上述标准不代表已经执行或通过。

人工验收：准备已批准理发/打印商家 → 配置资料/营业时间及上架服务 → 退出 Swagger 授权 → GET /shops → 筛选名称/分类 → 查看店铺详情和服务 → 商家登录下架一个服务/关闭营业开关 → 清除授权再查询，对照公开展示变化。
