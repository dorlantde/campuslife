# ADR-0019：公开商家列表、详情及上架服务

- 日期：2026-10-21（Asia/Shanghai）。
- 状态：用户已对[完整提案](../public-shop-catalog-design.md)选择“采用并实现”。
- 范围：三个匿名 GET /shops、/shops/{shopId}、/shops/{shopId}/services；人工与自动化验证见[目录验收](../public-shop-catalog.md)。

## 规则

公开可见条件统一为 shop.APPROVED + ACTIVE OWNER + 对应账号 ACTIVE / MERCHANT。列表只统计可见店铺；详情与服务入口对不可见和不存在统一返回 404。businessEnabled=false、未配置营业时间/排班/服务不隐藏店铺；分类字典停用仍只限制申请/重提，不隐藏已批准店铺。这细化 ADR-0010 的 APPROVED 公开规则，沿用 ADR-0013 的营业开关独立含义及 ADR-0015 的分类停用边界。

允许匿名或任意有效登录角色。携带无效/过期/撤销或停用账号的凭证仍按原认证规则失败；只准确放行三个 GET，不放行 /shops/** 或其他方法。

列表按 id ASC，page 默认 1、正整数且不超过 int 上限，size 默认 20、1～100，offset 使用 long；越界页返回空 items，total/totalPages 仍按实际结果。categoryId 为合法正 BIGINT 字符串，合法但不存在分类返回空页。keyword 仅名称包含搜索，去首尾空白后最多 80 个 Unicode 码点，空白等于不筛选；绑定参数并使用显式 LIKE ESCAPE，使 %、_、转义字符按字面匹配。名称比较沿数据库现有排序规则。

ServiceView 仅含上架服务，创建时间/ID 倒序，可选严格 HAIRCUT / PRINT，合法类型无匹配返回 []。停业可看服务；PRINT 金额为参考起价、时长 null，服务版本不预留价格。

ShopSummary/ShopDetail 使用独立公开 VO，不公开账号/成员/审核信息、店铺版本或会话。openNow 为开关开启且校园时区当前星期/时刻落在营业区间 [start,end)，一次响应取一个 Clock 时刻；不检查服务、人员排班或订单占用，不表示可下单或有空位。评价模块尚未实现，按已确认方案保留 rating=null、reviewCount=0 的阶段值并在 API 说明，不生成虚构评分；以后接入真实 VISIBLE 评价统计。

## 技术与取舍

沿用 merchant 分层、MyBatis-Plus/注解 SQL，无新增依赖、XML、迁移、缓存或搜索引擎。只读 RR 事务把资格、总数、列表/详情、营业时间及服务绑定到同一快照；不加写锁、不递增版本。已开始查询可返回其快照时的资料，下次请求看到新状态；未来订单仍须锁内重新校验。

列表批量加载营业时间，避免逐店查询。shop/category/owner/account 资格条件统一放在 PublicShopMapper 的 SQL 常量，防止三个入口分叉。ServiceItemMapper 复用已有按本店、类型和启用状态查询，固定传 enabled=true；对外使用 ServiceView 白名单映射。

公开理发师、可约时段、评价、报价及订单留到对应任务。当前接口不构成价格承诺或预约资源预留，实际测试结果以[验收指南](../public-shop-catalog.md)记录为准。
