# ADR-0043：公开评价分页与真实商家评分统计

- 日期：2026-10-26。
- 状态：用户明确“采用并实现”。精确范围见[确认方案](../public-shop-reviews-design.md)，操作及实际验证见[验收指南](../public-shop-reviews.md)。

## 决定与理由

新增匿名GET /api/v1/shops/{shopId}/reviews；既有公开商家列表与详情的rating/reviewCount改为真实VISIBLE评价统计。沿ADR-0019店铺资格：APPROVED+ACTIVE OWNER+ACTIVE/MERCHANT经营账号；停业、休息、无服务或分类字典停用不隐藏，资格失效与不存在统一404。精确放行GET路径，错误token仍拒绝，其他方法/子路径和本人评价权限保持。

评价页默认page1/size20、size1～100、page至INT最大，沿公开目录十进制解析、offset用long；参数格式先校验，createdAt DESC/id DESC，越界空items但保留total。只返回reviewId/rating/content/reviewerNickname/createdAt五字段，ID字符串、时间UTC；不公开学生/订单ID、登录名、联系方式、可见性或审核信息。包含预约/打印历史评价，不重新检查原订单/配置当前资格。

昵称使用作者当前昵称，修改影响后续快照中的历史展示，不加昵称快照列。作者停用或角色变化不自动隐藏VISIBLE评价、不扣统计；登录权限与内容下架独立。HIDDEN不在公开分页/count/平均分中，本人仍可读、reviewed=true且不能重发；隐藏动作后续接入，不以本次过滤宣称隐藏入口已实现。

## 精确统计与查询

reviewCount=本店全部VISIBLE COUNT；rating=SUM(rating)/COUNT，十进制计算、两位小数HALF_UP，无公开记录null/0，不补虚构5分。JSON数值不保证末尾补零，继续使用原字段/数值类型；例如5/4/4为4.33，5/5/4为4.67，1.125为1.13。SUM用BigDecimal承载，COUNT为long，最终才转现有输出数值，不逐条浮点累加。

商家列表按当前页店铺ID一次批量GROUP BY，不逐店查评价或加载全部记录，空页不查统计。详情查询单店聚合，评价页资格/count/page/昵称普通读；使用V13既有(shop_id,visibility,created_at,id)索引，无新迁移、缓存、冗余平均分或依赖。

## 快照和范围

三个响应各自短只读RR，保证资格、数量、页内记录/昵称或评分在同一响应快照。快照后新增/隐藏/改名或资格变化下次请求可见；不承诺跨HTTP/跨页同快照，普通页码分页期间记录可移动。无业务行锁、事务内IO、实例互斥或业务写入，不改评价、订单/账号/店铺版本、事件、资金或文件保留。数据库错误不能静默当作无评价返回null/0。

沿merchant模块既有公开Controller/服务，新增PublicReviewMapper及两个公开VO；评价写入仍order模块。运行58→59、完整62/59/3，仍V1～V13/22表，无新增基础设施。管理员隐藏、账号管理、图片/回复/编辑/匿名开关、自动审核和评分排序不在本轮。

真实MySQL验证公开资格/隐私、精度/隐藏/改名/停用作者、批量查询、分页/偏移及并发RR快照，回归公开目录和本人评价；只使用临时实例与测试文件根，实际命令/结果仅以验收指南记录为准。
