# ADR-0015：商家服务查询、新增与修改

- 日期：2026-10-20（Asia/Shanghai）。
- 状态：用户在 [服务配置提案](../merchant-service-design.md) 后明确要求“开始实现”，按该方案落实。
- 范围：GET /merchant/services、POST /merchant/services、PATCH /merchant/services/{serviceId}；不含规格价目、公开服务查询、理发师或订单。

经营资格沿用 ACTIVE / MERCHANT + APPROVED + ACTIVE OWNER，店铺手动停业、没有营业时间也可以管理服务。新增服务类型与分类代码绑定：HAIRCUT → HAIRCUT、PRINT → PRINT；这项新决策补充 ADR-0010 入驻阶段尚未定义的类型限制。分类字典停用仍只限制新申请/重提选择，不自动停用已审核店铺。不支持混合经营，未来扩展时单独讨论支持类型模型。

名称和介绍分别为去首尾空白后 1～80、0～500 Unicode 码点，同名允许。金额严格 JSON 整数分 1～100000。理发时长为严格整数 15～240 且为 15 的倍数；打印创建时必须显式 durationMinutes=null，金额仅商家维护的参考起价，不代表规格报价。新增 description 默认空字符串、enabled 默认 false；显式上架允许，仍不代表可以接单。

服务类型、归属创建后不可变，修改只支持白名单字段及必传字符串服务版本。省略保持、介绍空白清空、null 拒绝（仅 PRINT 时长例外）。实际变化才递增 service_item.version，无变更仍校验版本和资格；BIGINT 上限允许无变更，拒绝实际修改。服务版本独立于 shop.version，新增/修改不更改店铺版本。无硬删除或创建幂等回放，同名/重复创建会获得不同 ID。

V5 新增 service_item，以外键及 CHECK 兜底类型、名称、金额、时长组合、布尔值与版本，不改写 V1～V4。MyBatis-Plus 负责实体插入，读取/更新沿用注解 SQL；未引入依赖。写锁顺序为本人账号 → 当前会话 → 店铺 → 本人 OWNER → 服务，锁内复核资格与归属，基于当前读合并服务字段，并在写入前/后复核会话到期以回滚等待过久的写入。SQL 更新同时限定服务 ID、本人店铺及版本。

查询使用只读 RR 事务，在一致快照中核对本人经营资格、查询本店数据；HTTP 认证过滤器按已有规则核对账号和会话。查询不分页，固定 created_at DESC、id DESC，可筛选 serviceType 与 enabled。合法的其他类型筛选返回空数组；跨店铺服务修改统一 404。返回 MerchantServiceView，包含上下架与服务版本，不暴露账号/会话信息。

实际验证与 Swagger / Apifox 点击流程见 [服务验收](../merchant-service-catalog.md)。规格价目、预约与订单快照保护仍须在对应模块落实；本任务仅验证服务配置，不宣称已完成交易或存量履约。
