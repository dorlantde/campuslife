# ADR-0042：学生提交与读取本人订单评价

- 日期：2026-10-26。
- 状态：用户明确“采用并实现”。精确范围见[确认方案](../student-order-review-design.md)，操作及实际验证见[验收指南](../student-order-review.md)。

## 决定与取舍

新增规划中的POST/GET /api/v1/orders/{orderId}/review，正常学生仅本人BOOKING/PRINT。首次必须合法COMPLETED/PAID且有匹配成功支付、无退款；BOOKING须CONSUMED，PRINT须PICKED_UP、完成与取件时间一致，阶段时间齐全有序。未完成/退款关闭409 REVIEW_NOT_ALLOWED；目标商家/OWNER停用、暂停/下架及文件清理不影响已完成历史资格。无评价截止期，允许完成后立即评价；锁后毫秒now不得早于completedAt。

严格JSON仅rating/content：整数1～5分，拒绝小数形式/字符串/布尔；文字strip后1～1000个Unicode码点，内部换行/文字保留。首笔201 VISIBLE，一单一次，相同或修改内容重复均409 REVIEW_ALREADY_EXISTS，隐藏同样占用唯一名额。响应丢失可GET确认；不增加客户端幂等键、编辑或重发路径。

本人GET在同一短只读RR校验当前身份/会话、订单归属及评价；未评价、不存在或他人404，隐藏后本人仍可读原内容。账号停用仍拒绝。OwnReviewView只含id/orderId/rating/content/visibility/createdAt，ID字符串、时间UTC，不附加账号资料。

## 数据与独立生命周期

V13新增order_review，避免与入驻审核shop_review混淆；id主键、order_id唯一外键、student/shop外键、评分/文字/可见性及创建时间，店铺/学生固定来自订单。数据库评分/长度/枚举检查与唯一键兜底，索引(shop_id,visibility,created_at,id)。本轮只创建VISIBLE，HIDDEN只验证保留事实的边界，管理员隐藏字段与入口后续增加。

评价不改订单状态、付款状态、version、时间、订单事件、资金、占用或文件引用。reviewed从固定false改为EXISTS评价（含隐藏），用于学生/商家/管理员详情及返回OrderDetail的原操作/重放；它是独立评价事实的派生标志，不是订单生命周期版本的一部分。订单列表不增加字段或逐单查询。

## 锁、并发与快照

RC学生账号X→会话X→店铺S→主订单X→评价唯一记录，锁后重核归属与资格，固定毫秒createdAt，提交前复核会话。必要扩展与资金只读，不在持订单后反向获取日期保护行、文件X、经营账号或实例文件互斥；无磁盘IO。评价不写资源，不需要这些资源锁，可避免与现有资源→订单写协议形成循环。

一单一评由事务和UNIQUE(order_id)保证；仅将明确的评价订单唯一键冲突在事务外、重新验证本人已存事实后转为REVIEW_ALREADY_EXISTS，不掩盖其他约束错误。竞争503可重试，已提交后用GET确认。插入或最终会话校验失败回滚评价，原订单事实不变。详情reviewed遵循原RR快照或锁后RC，不要求跨HTTP请求同快照。

## 当前边界与验证

沿order模块内分层，不新建空模块/依赖。运行56→58、完整62/58/4，V1～V13/22张业务表。公开评价分页、真实评分统计、管理员隐藏与账号管理后续推进；公开rating=null/reviewCount=0明确是统计未接入的阶段值，不代表真实评价计数。无富文本、图片、商家回复、匿名开关、敏感词或自动审核。

真实MySQL验证完整两类完成链、一单一评与隐藏边界、状态/时间/权限、约束/回滚、锁等待、完成/取件竞争、本人读取与详情快照和原生命周期回归；只使用临时实例及自有文件根。实际命令与通过范围以验收指南记录为准。
