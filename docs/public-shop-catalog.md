# 公开商家目录：Swagger / Apifox 验收

2026-10-26 后续公开评价/真实评分已按[ADR-0043](decisions/0043-public-shop-reviews.md)接入，见[公开验收指南](public-shop-reviews.md)。目录null/0现在仅表示确实零条公开评价，VISIBLE按真实数量/两位HALF_UP均分展示，作者当前昵称，停用作者不自动隐藏。旧统计尚未接入的阶段说明以本次为准，管理员隐藏动作后续。

2026-10-21，按 [ADR-0019](decisions/0019-public-shop-catalog.md) 实现三个公开 GET，位于“03 商家入驻”。当前运行版为 36 个操作，完整规划 62 个（26 个待开发）。无新表或迁移，保留既有 MySQL 配置。

## 准备

1. 停止旧应用，用原 VS Code 的 CampusLife (MySQL) 配置重新运行。打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。
2. 准备虚构理发/打印店，按[入驻](merchant-onboarding.md)和[审核](admin-shop-review.md)使店铺 APPROVED，经营账号和 OWNER 有效。
3. 商家登录后按[经营资料](merchant-operation.md)、[营业时间](merchant-business-hours.md)、[服务配置](merchant-service-catalog.md)准备展示资料和至少一个上架、一个下架服务。下架服务用于验证不公开。
4. 点击页面顶部 Authorize → Logout → Close，清除之前的 token。三个公开查询无需登录；其他商家维护接口仍需登录。

要用 Apifox，可更新导入[运行快照](openapi/campuslife.openapi.json)或[完整规划](openapi/campuslife.v1-design.openapi.json)，服务地址为 http://127.0.0.1:8080。公开接口选择“无需认证”，关闭从分组/项目继承旧 token；携带失效 token 会正常返回 401。

## 列表、搜索和详情

展开 GET /api/v1/shops → Try it out：

1. 先不填写筛选，执行后应为 200 / OK。data 包含 items、page、size、total、totalPages，默认 page=1、size=20；店铺按 ID 升序。
2. keyword 填写店铺名称的一部分，categoryId 从分类查询取得；同时填写时应取交集。只搜索名称，不搜索介绍或地址。
3. page=1、size=1 观察分页，再切换下一页；超出最后一页应 items=[]，total 保留实际匹配数。合法但不存在的分类 ID 也为空页。
4. 从 items 复制真实 id，执行 GET /api/v1/shops/{shopId}。应多出 description、整周 businessHours 和 campusCode，不包含审核、成员或账号信息。

keyword 中 %、_、!、反斜杠按字面匹配。可用商家资料接口把虚构店名改为“模拟100%打印”，搜索 100% 应只匹配真正含这几个字符的名称，不匹配“100元打印”。超长 keyword、非法分类 ID、page=0、size=101 应为 400 VALIDATION_FAILED。

## 上架服务与状态展示

GET /api/v1/shops/{shopId}/services：填写实际店铺 ID，serviceType 可省略或选 HAIRCUT / PRINT。应只返回本店上架服务，按创建时间和 ID 倒序；合法类型无匹配或店铺没有上架服务均为 []。

返回字段为 id、serviceType、name、description、priceAmount、durationMinutes、version。金额是整数分，理发为固定价；打印只展示参考起价，时长为 null，尚不能用于规格报价或下单。

| 人工操作 | 预期公开结果 |
|---|---|
| 商家关闭 businessEnabled，再清除授权查询 | 店铺和上架服务仍可见，openNow=false |
| 开关开启但当前不在营业时间内，或未配置营业时间 | 店铺可见，openNow=false |
| 开关开启且校园当前时刻在营业区间内 | openNow=true，不表示有预约空位 |
| 商家把一项服务下架，再清除授权查询 | 该项从公开服务数组消失 |
| 查询本人尚待审核或被拒的申请 shopId | 404 RESOURCE_NOT_FOUND，不返回原因或申请资料 |
| 查询合法但不存在 shopId | 同样 404 RESOURCE_NOT_FOUND |
| 未登录访问商家维护接口 | 401 AUTH_REQUIRED |
| 公开 GET 携带失效 token | 401，清除 token 后可匿名浏览 |

openNow 依 Asia/Shanghai 的当天星期和时刻计算，区间起点包含、终点不含；午休为 false。排班、理发师启停及服务占用不参与这个字段。

当前评价模块未实现，rating 固定 null、reviewCount 固定 0；它们不是已实现的评价统计。公开理发师按 ADR-0020 实现，见[人员公开查询](public-barbers.md)；候选按 ADR-0021/0022 计算并扣除真实占用，见[候选验收](booking-slots.md)。创建待支付预约、查看/取消及释放见[订单验收](booking-orders.md)，支付、履约及评价仍待开发。

## 自动化验证记录

2026-10-21，Java 21 + 独立临时 MySQL 8.0：本轮定向执行新增 PublicShopCatalogMysqlIT **61 个**、OpenApiMysqlIT **3 个**和普通/Web **42 个**，共 **106 个用例通过**，零失败、错误或跳过。本轮没有重新运行全部既有业务测试，不能把目录里的历史测试报告合计当成新的全回归。

覆盖三个入口统一可见性、账号/成员/审核状态、停业和分类停用边界、公开字段白名单、真实参数绑定与通配符字面搜索、Unicode/长度、分类交集、分页越界与 long offset、上架/跨店服务隔离、打印 null 时长。Clock 验证上海时区、跨日/星期、开关、起止点和午休；真实 MySQL 事务在查询中途修改账号/店铺/营业时间/服务，验证列表总数与资料、详情/营业时间、服务读取的一致快照。另验证批量加载、一次 Clock 时刻及查询不写入，匿名/多角色/失效会话与精确路由边界。

首轮 106 个用例及 Maven verify 均通过，但随后打包进程接近 30 秒才完成 HTTP 就绪，探测达到脚本固定期限。新增 smoke.ps1 和 test-mysql.ps1 可选 StartupTimeoutSeconds（1～300、默认仍 30），本轮明确用 90 秒上限重新执行相同定向验证，106 个再次通过，mysql 模式 JAR 的 HTTP 就绪、健康、UTF-8、trace 和访问边界检查通过。未修改应用启动配置，也未把日志“已启动”替代 HTTP 成功。

运行 OpenAPI 实际导出 31 个操作；完整规划 62 个（31 已实现、31 待开发）、9 分组、80 个 schemas。生成器一致性、官方 OpenAPI 3.0 schema、10 个请求示例、参数/模型引用与公开字段检查通过。日志为 .cache/public-catalog-first.log、.cache/public-catalog-final.log，选定测试报告在 target/surefire-reports / target/failsafe-reports。

验证只使用合成账号和独立临时实例，没有读取用户数据库密码或访问现有项目库；两次临时实例均已停止、目录已清理，原 MySQL80 服务保持 Running。人工点击步骤供用户复现，尚未代用户操作 Swagger 或 Apifox。公开理发师、预约空位、评价统计、报价及订单仍未实现。
