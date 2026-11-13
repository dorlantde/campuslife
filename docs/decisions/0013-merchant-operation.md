# ADR-0013：商家经营资料与营业开关

- 日期：2026-10-19（Asia/Shanghai）。
- 状态：用户明确回复“采用并实现”，接受 [经营提案](../merchant-operation-design.md) 的全部规则。
- 范围：PATCH /api/v1/merchant/shop，仅本人基础经营资料与手动营业开关。

当前账号 ACTIVE / MERCHANT、店铺 APPROVED、本人 OWNER 为 ACTIVE 才能使用。name、locationText、description 直接更新，不重新审核；分类、校区、归属及审核字段不能自助修改，原审核信息与 shop_review 历史不变。这是 V1 的资格审核与日常展示资料维护取舍，不把旧审核记录解释为管理员审核过修改后的文本。

businessEnabled 独立保存商家营业意愿，可主动开启或关闭，不以前置实现营业时间/服务配置为条件。未来接单还必须同时满足营业时段、服务和场景条件。临时停业不注销账号、不撤销审核、不自动改变存量订单；当前没有订单能力，因此不声称已验证订单履约。

PATCH 使用严格白名单，四个可变字段均可省略；省略保留原值，null 和错误 JSON 类型拒绝。名称/位置/介绍沿用 Unicode 长度规则；清空介绍传空字符串，不能套用完整重提中“省略清空”的含义。businessEnabled 只接受真正的布尔值。expectedVersion 为必传的规范非负十进制字符串；实际变化版本 + 1，无变更不写 SQL，但仍检查所有权限和版本。BIGINT 上限允许无变更返回，拒绝实际更新。

同一事务按账号 → 当前会话 → 店铺 → 本人 OWNER 锁定并复核。首次无锁读仅确定归属与不在本接口改变的分类/校区；可变店铺资料、状态和版本取自锁定后的当前读，防止 MySQL REPEATABLE READ 旧快照影响补丁合并和无变更响应。写入前再次校验会话有效期，SQL 限定 APPROVED + 原版本，异常整体回滚。

复用 merchant 模块、ShopMapper 和 V2/V3，不新增数据库迁移或依赖。新增 ShopPatchRequest、MerchantOperationService 与 MerchantOperationController，后者属于“06 商家经营配置”。运行时与规划导出及 Swagger 验收同步；实际验证记录见 [经营验收](../merchant-operation.md)。营业时间、服务、平台暂停/恢复与分类变更仍按后续任务讨论。
