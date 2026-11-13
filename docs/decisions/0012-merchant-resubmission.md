# ADR-0012：被拒商家修改资料并重新提交

- 日期：2026-10-19（Asia/Shanghai）。
- 状态：落实 ADR-0010 中已确认的重提规则，用户授权“进行下一项”。
- 范围：POST /api/v1/merchant/shop/resubmit，不扩展经营配置或公开商家查询。

仅本人 MERCHANT 的 REJECTED 店铺、PENDING OWNER 可以重提。完整提交 categoryId、shopName、locationText 和 expectedVersion；description 可选，省略/null/空白统一清空为字符串。分类、文本沿用首次申请的校验，抽取 MerchantShopPolicy 复用。expectedVersion 必须为规范的非负十进制字符串，不接受数字、前导零或超过 BIGINT 的值。

身份与店铺归属来自当前会话，不接收 shopId、accountId、角色或审批字段。重提不改账号、密码、会话或成员，不新建店铺。版本匹配并可递增后，同事务更新申请资料、REJECTED → PENDING、版本 + 1、保持停业，清空当前 approval_reason/reviewed_by/reviewed_at；shop_review 历史行不变。无需新迁移或基础设施。

锁顺序为商家账号 → 当前会话 → 新分类共享锁 → 店铺 → 本人 OWNER。先无锁读取归属取得 shopId，持有店铺锁后再次以账号 ID 锁定本人 OWNER 校验归属与状态，避免先锁成员再锁店铺而与审核反序。锁内复查账号 ACTIVE、MERCHANT 与会话有效性，写入前再检查会话到期。分类共享锁持有到提交，防止校验启用后、提交前分类被并发停用；提交后仍允许后续停用。

店铺锁与版本条件 UPDATE 共同裁决并发。旧版本或版本上限返回 CONFIG_VERSION_CONFLICT；当前版本下店铺/成员状态不符返回 SHOP_STATE_CONFLICT。相同请求成功后重发返回 409，不返回成功回放。管理员须重新读取重提后的版本才能审核，原审核请求不能覆盖新申请。

接口返回 200 和最新 MerchantShopView。请求是完整替换申请资料，省略 description 会清空旧介绍；账号资料仍由现有认证/本人资料接口管理。Swagger / Apifox 点击步骤和真实验证结果见 [重提验收](../merchant-resubmission.md)。
