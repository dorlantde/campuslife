# 商家服务配置：已确认规则

- 日期：2026-10-19（Asia/Shanghai）。
- 状态：2026-10-20 用户明确要求“开始实现”，采用本方案。实现取舍见 [ADR-0015](decisions/0015-merchant-service-catalog.md)，实际验证见 [服务验收](merchant-service-catalog.md)。
- 已确认范围：本店服务查询、新增、修改三个已规划接口；通过修改完成上下架，不增加删除接口。

## 1. 基础与已确认边界

V1 已确认只做理发、打印两种交易，金额采用整数分，订单以后保存服务与价格快照。本方案将原设计稿中的 HAIRCUT / PRINT 服务、理发固定价格和 15 分钟倍数时长细化为已确认规则；打印按规格报价留到后续任务。

ADR-0010 在入驻阶段没有自动绑定分类与服务类型。本次**已确认新增规则**：HAIRCUT 分类只创建 HAIRCUT 服务，PRINT 分类只创建 PRINT 服务；不支持同一家店混合经营。理由是与已审核分类一致，便于 V1 权限和后续场景验证；代价是以后支持混合经营时需扩展支持类型模型，不能只增加下拉选项。分类仍不是订单状态或订单处理器。

## 2. 已采用业务规则

| 项目 | 已采用方案 | 理由与影响 |
|---|---|---|
| 权限 | 当前账号 ACTIVE / MERCHANT，本人 ACTIVE OWNER，店铺 APPROVED | 沿用经营配置权限；手动停业或尚未配置营业时间仍可管理服务 |
| 服务类型 | HAIRCUT / PRINT，创建时与店铺分类匹配；创建后不可更改 | 防止把理发服务改成另一套计价与履约流程；类型填错时下架后重新创建 |
| 名称与介绍 | 名称去首尾空白后 1～80 Unicode 码点；介绍 0～500 | 拒绝非法 Unicode；同店允许同名，以服务 ID 区分，不新增名称唯一约束 |
| 金额 | priceAmount 为严格 JSON 整数，单位分，1～100000，即 0.01～1000 元 | V1 不支持免费服务；设置明确业务上限，避免把 JSON 安全整数上限当作合理服务价格 |
| 理发 | 固定价格；durationMinutes 为严格整数 15～240，且为 15 的倍数 | 为后续预约保留明确时长；本次不自动创建理发师、排班或时段资源 |
| 打印 | durationMinutes 创建时必须显式为 null；priceAmount 只作商家维护的参考起价 | 不作为最终报价，也不声称它已经与尚未实现的规格价目表校验一致；规格计价后续单独开发 |
| 上下架 | 新增时 enabled 可省略，默认 false；可显式传 true；之后通过 PATCH 切换 | 允许先准备服务再上架；上架不自动开启店铺、不保证当前可接单 |
| 下架与删除 | 下架阻止后续新交易，不删除服务或自动取消存量订单 | 保留历史关联；没有订单实现前，仅验证配置状态，不声称已验证存量履约 |
| 审核 | 正常配置直接生效，不重新审核店铺或单独审核服务 | 沿用 V1 简化模式；不能修改店铺分类、归属或审核信息 |

类别字典 enabled 的现有用途仍是控制新申请/重提时可选分类；本项不另加“分类停用自动禁止已审核店铺经营”的规则。未来分类或混合经营管理需要单独定稿。

## 3. 三个接口

| 方法 | 路径（均以 /api/v1 开头） | 行为 |
|---|---|---|
| GET | /merchant/services | 返回本店 MerchantServiceView 数组，含上架/下架服务；可按 serviceType、enabled 筛选 |
| POST | /merchant/services | 创建服务，201 / OK，返回 MerchantServiceView，服务版本为 "0" |
| PATCH | /merchant/services/{serviceId} | 修改提供的字段，200 / OK，返回最新 MerchantServiceView |

查询沿用现有数组契约，不新增分页或关键词搜索；按 created_at DESC、id DESC 固定排序，空结果返回 []。serviceType 筛选只能为 HAIRCUT / PRINT，enabled 筛选只能为 true / false；合法但与本店类型不同的筛选返回空数组，不改变服务归属。

返回字段：id、serviceType、name、description、priceAmount、durationMinutes、enabled、version。ID 和版本为十进制字符串，金额和理发时长为整数，打印时长为 null。不返回商家密码、会话或其他店铺服务。

### 新增示例

已批准的理发店创建服务：

```json
{
  "serviceType": "HAIRCUT",
  "name": "模拟标准理发",
  "description": "仅用于校园项目学习验收",
  "priceAmount": 3000,
  "durationMinutes": 30
}
```

预期默认下架，版本 "0"；3000 分为 30 元。POST 必传 serviceType、name、priceAmount、durationMinutes；description 省略为空字符串，enabled 省略为 false。若传入上述可选字段，也必须是正确类型，null 不作为默认值。

已批准的打印店示例：

```json
{
  "serviceType": "PRINT",
  "name": "模拟 PDF 打印",
  "priceAmount": 20,
  "durationMinutes": null,
  "enabled": true
}
```

20 分只作参考起价，不能据此认定任何具体文件/份数/单双面组合的最终费用。PRINT 必须显式 durationMinutes=null，不能填写 0 或预计打印耗时。

### 部分修改

PATCH 必传从服务查询取得的 expectedVersion；它是**服务版本**，不是 GET /merchant/shop 的店铺版本。可变字段为 name、description、priceAmount、durationMinutes、enabled。

省略字段保留原值。清空介绍传空字符串或空白，description=null 拒绝；只有 PRINT 的 durationMinutes 可显式为 null，HAIRCUT 不可为空。金额/时长拒绝数字字符串、浮点和小数形式（例如 3000.0 / 30.0）；enabled 严格为 JSON 布尔值。拒绝未知字段，包括 shopId、accountId、serviceType、role 及客户端版本覆盖字段 version。

规范化后实际变化时只增加一次 service_item.version；没有变化或只传 expectedVersion 时，在通过权限与版本检查后返回当前服务，不增加版本。旧版本即使无变化也返回 409 / CONFIG_VERSION_CONFLICT。BIGINT 上限允许无变化，拒绝实际更新。修改服务不增加 shop.version，避免价格编辑与营业时间产生无关冲突。

POST 不需要已有服务版本，不提供创建幂等键；相同请求重复提交会创建不同 ID 的服务，不承诺自动去重。PATCH 中的 serviceId 须为规范的正十进制 BIGINT 字符串；不存在或属于其他店铺统一返回 404，不暴露对方服务内容。

## 4. 错误、事务与数据

身份、店铺归属和成员权限取自服务端会话与数据库。认证无效 401，非商家/停用账号/店铺未获经营资格/成员无效 403，无本人店铺或不可见服务 404。非法字段、越界金额/时长、类型与分类不匹配、无效筛选为 400 / VALIDATION_FAILED；服务版本冲突为 409 / CONFIG_VERSION_CONFLICT。

通过 V5 增加 service_item，使用现有 MyBatis-Plus 与注解 SQL，不新增依赖。基础字段、shop 外键、类型、金额、时长组合、布尔值和非负版本通过数据库约束兜底；不改写 V1～V4。

写事务沿用本人账号 → 当前会话 → 店铺 → 本人 OWNER → 服务。锁内复查角色、资格、归属、会话及类型/版本，使用当前读合并字段，写入前复查有效期；失败整体回滚。店铺锁用于协调经营资格和并发，不代表每次都要增加店铺版本。查询也检查经营资格，只返回当前本人店铺的数据。

服务下架、修改价格和时长在后续交易中只影响新请求，已有订单应使用冻结快照；预约保护、打印报价版本与金额重算在对应模块实现前单独落实。本项不声称已经实现这些模块。

## 5. 完成标准

本轮交付三个接口，运行操作由 17 增至 20，完整规划仍为 62。同步 Java 注解、运行 OpenAPI、完整规划和 Swagger / Apifox 点击指南。人工验收为：商家登录 → 新增默认下架服务 → 查询 → 修改价格/时长并上架 → 旧版本冲突 → 下架；分别验证理发和打印类型及跨店铺拒绝。

独立真实 MySQL 计划覆盖分类/类型边界、严格金额/时长、默认值与 null、权限与归属、无变更语义、独立服务版本、并发修改、状态/会话锁后复查、异常回滚和数据库约束。以上为验收要求，实际结果以服务验收指南为准，不将要求本身视作已执行结果。

打印规格价目、公开服务查询、理发师与排班按后续任务推进，不在本项一次性展开。
