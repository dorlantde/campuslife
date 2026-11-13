# 学生订单评价：已确认方案

- 日期：2026-10-26；状态：用户明确“采用并实现”；落实见[ADR-0042](decisions/0042-student-order-review.md)，实际验证见[指南](student-order-review.md)。
- 依据：[业务基线](decisions/0001-v1-baseline.md)、[接口草案](v1-api-contract.md)、[数据库草案](v1-database-design.md)、[订单状态规则](v1-state-transitions.md)、[预约完成 ADR-0026](decisions/0026-booking-fulfillment.md)、[打印取件 ADR-0040](decisions/0040-student-print-pickup.md)、[管理员打印异常 ADR-0041](decisions/0041-admin-print-exception.md)。草案中的评价细节由本次确认，不能因已有路径就视为已批准。

## 1. 推荐范围与理由

本轮实现两个已规划入口：

| 方法与路径 | 推荐行为 |
|---|---|
| POST /api/v1/orders/{orderId}/review | 正常学生对本人已完成订单提交一次评分和文字，首次201 |
| GET /api/v1/orders/{orderId}/review | 正常学生读取本人订单的已有评价，200；尚未评价404 |

同时把现有BOOKING/PRINT详情中的reviewed从固定false改为真实EXISTS判断，学生/商家/管理员详情及返回OrderDetail的原有操作保持一致。OrderSummary当前没有reviewed，本轮不增加列表字段或筛选。

新增V13迁移建立order_review表，名称与商家申请审核历史shop_review区分。沿现有order模块内分层，仅增加本轮实际使用的评价Controller、Service/事务、Mapper、DTO/VO和实体；不增加依赖、缓存、消息或空模块。运行56→58个操作，完整规划仍62，已实现58、待开发4；业务表21→22张，迁移V1～V13。

公开评价分页、真实商家评分统计和管理员隐藏入口在后续任务接入。本轮新建记录标为VISIBLE，但尚无公开评价接口；公开目录rating=null/reviewCount=0仍明确是“统计未接入”的阶段值，不能解读为真实评价计数。届时一起定稿公开昵称、分页、聚合精度和隐藏后的统计行为，避免把未实现的公开能力算作本轮交付。

## 2. 资格与归属

仅正常STUDENT与有效会话，订单学生固定本人。规范正BIGINT字符串orderId；非法400，订单不存在、他人或不支持类型统一404。学生账号停用、角色或会话无效时，提交和本人读取均拒绝。

首次提交须本人BOOKING/PRINT、COMPLETED且PAID、completedAt非空，匹配原成功支付且没有退款；BOOKING应为已完成且CONSUMED，PRINT应为合法PICKED_UP、pickedUpAt=completedAt、阶段时间齐全有序。未付、待接单、已接单、履约中、待取件和异常关闭均不可评价，409 REVIEW_NOT_ALLOWED。

锁后创建时间now≥completedAt，等于允许；时钟倒退同样409 REVIEW_NOT_ALLOWED，不写评价。推荐不设置评价截止天数、不要求等待；完成后立即评价、打印文件过期或被清理后评价均允许。

店铺暂停/停业、经营账号或OWNER停用、服务/人员/价目变化不影响已完成历史单的评价资格。不要求店铺当前公开可见，不反向锁经营账号或成员，不打开打印文件、不等待下载、不修改文件保留期。

本人GET先校验订单归属，再查评价；未评价返回404 RESOURCE_NOT_FOUND，任何状态的本人合法订单均可查询，不以GET替代创建资格判定。以后即使评价被隐藏，本人仍可读原内容和visibility；账号停用仍不能读。

## 3. 请求、响应与重复提交

严格JSON只含两个必填字段，缺失/null/未知字段或错误类型均400 VALIDATION_FAILED：

```json
{
  "rating": 5,
  "content": "服务顺利完成，沟通清楚。"
}
```

- rating为严格JSON整数1～5，字符串、布尔、1.0等小数形式均拒绝。
- content为字符串，按Java strip去首尾空白后1～1000个Unicode码点；不改内部文字、空白或换行，不接收图片、商家回复、客户端studentId/shopId/orderId、visibility、时间或版本。
- 只存普通文字，不增加敏感词、自动审核、富文本、匿名开关、编辑、删除、商家回复或图片。未来展示端按文字安全展示，不把内容当HTML执行。

首次201，返回OwnReviewView：id、orderId为字符串，rating为整数、content为归一化文字、visibility=VISIBLE、createdAt为UTC。本人GET返回同一结构，不附加姓名、登录名、联系方式或存储信息。

一单只能评价一次。相同内容重复也409 REVIEW_ALREADY_EXISTS，不覆盖、不追加、不再次返回201；隐藏记录同样占用唯一约束，不能重发。首次响应丢失时客户端可GET本人评价确认已有事实；不增加客户端幂等键或请求摘要。

重复请求仍校验当前身份、归属与输入格式。对正常归属且输入合法的请求，已有评价优先返回REVIEW_ALREADY_EXISTS，再判断首次完成资格；不能用重复请求编辑原评分/内容。并发重复由事务和UNIQUE(order_id)共同兜底，不把约束错误直接暴露给客户端。

## 4. 评价表与订单关系

order_review字段：id BIGINT主键、order_id BIGINT唯一且外键、student_id/shop_id BIGINT外键、rating TINYINT、content VARCHAR(1000)、visibility（VISIBLE/HIDDEN）、created_at DATETIME(3)。student/shop来自已锁订单，不由客户端覆盖；唯一键包含全部记录，不按visibility排除隐藏项。

数据库校验rating 1～5、content长度1～1000、visibility枚举；为后续按店铺/可见性/时间分页建立(shop_id, visibility, created_at, id)索引。moderated_by/reason/at等隐藏操作字段在管理员隐藏任务增加，不在本轮虚构审核事实。visibility本轮只创建VISIBLE，HIDDEN用于既有契约与保留事实的约束边界，不代表已有隐藏接口。

评价是独立事实：不改订单主状态、paymentStatus、version、completedAt、资金、占用、打印进度、文件版本/期限，也不向订单状态事件追加REVIEW。reviewed是评价表派生的“是否已有评价”，不是订单生命周期版本的一部分；存在HIDDEN记录也true。

详情读取、创建/取消/支付/履约等返回OrderDetail的路径同步使用真实评价存在性，不把成功重放的历史详情固定为false。沿各路径原RR快照或锁后RC视图读取，不要求与另一个HTTP请求同一快照，不逐单为订单列表增加查询。

## 5. 事务与锁取舍

写事务RC：学生账号X→会话X→店铺S→主订单X→评价唯一记录；锁后重新核对归属、当前状态、支付/完成事实，固定一次毫秒createdAt，提交前复核会话。使用锁后当前订单，避免定位查询缓存混读；评价插入和资格/会话校验同事务，失败回滚。

不改占用或文件引用，因此不取预约日期保护行、文件X或实例文件互斥，也不在锁订单后再补这些资源锁。完成事实必要扩展/资金用普通读校验，不能反向取得资源锁；这样不会形成“评价拿订单等文件，而履约拿文件等订单”的锁循环。

UNIQUE(order_id)兜底并发；重复冲突在事务外映射明确业务错误，不能吞掉不相关数据库错误。真实数据库暂时竞争503 SERVICE_TEMPORARILY_UNAVAILABLE，可重试原输入；重试若已提交则409，并可用GET确认。

本人评价GET采用短只读RR，在同一快照核对当前账号/会话、订单归属和评价，不取业务行锁、不写入、不自动关单或退款。详情reviewed遵守其原事务快照，当前资格变化由后续请求重新判断。

## 6. 完成标准与验收范围（实际执行记录见指南）

1. Swagger/Apifox分别真实完成BOOKING与PRINT，未评价GET404/详情false；POST201后GET一致，详情reviewed=true，重复同内容/改内容均409，原记录和订单版本/事件/资金/文件期限不变。
2. 权限、会话、归属与规范ID，严格评分整数、1/5边界、Unicode文字1/1000/1001码点、空白与未知字段；未完成/异常关闭拒绝，目标店铺/经营账号失效和文件清理后仍可对合法完成单评价。
3. now等于/早于completedAt、无评价期限、匹配原支付与合法完成事实；人工构造HIDDEN验证本人可见、reviewed=true且不能重发，不宣称隐藏入口已交付。
4. 真实MySQL双提交只成功一次，唯一约束和外键/长度检查；插入/提交前会话失败回滚、真实锁等待后的当前状态与时刻、完成/取件与评价竞争；持日期/文件/经营账号锁或实例互斥不阻止合法完成单评价。
5. 学生/商家/管理员BOOKING/PRINT详情及返回OrderDetail的原重放使用真实reviewed；原订单状态、版本、事件、资金、占用和文件引用回归。本人GET与详情快照边界验证，不为公开目录虚构统计。
6. V13迁移、表数/约束、注解、运行58/完整62/58/4、两份OpenAPI、模型引用及可执行包启动；测试只用独立临时MySQL与自有文件根，补充实际执行命令和点击验收指南。

本次已确认评分/文字、完成资格与无截止期、重复冲突、评价独立于订单版本/事件，以及本轮仅本人读写、公开统计后续的范围。已确认范围按ADR-0042落实，实际执行结果仅以验收指南为准。
