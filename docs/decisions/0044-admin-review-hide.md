# ADR-0044：管理员隐藏评价与首次审核记录

- 日期：2026-10-26。
- 状态：用户明确“采用并实现”。精确范围见[确认方案](../admin-review-hide-design.md)，点击流程和实际验证见[指南](../admin-review-hide.md)。

新增POST /api/v1/admin/reviews/{reviewId}/hide，仅当前ACTIVE/ADMIN与有效会话；规范正BIGINT ID，严格JSON仅reason字符串，strip后1～300个Unicode码点，内部换行保留。首次VISIBLE→HIDDEN，原子保存服务器取得的管理员、归一化原因及锁后UTC毫秒时间；首次时间早于createdAt为409 REVIEW_TIME_INVALID。成功200原六字段OwnReviewView，审核信息不加入公开/本人响应。

同管理员同归一化原因且审核记录完整的HIDDEN重放200，保留首次时间与记录；异人、异原因或没有审核记录的历史HIDDEN为409 REVIEW_STATE_CONFLICT，不覆盖。内容不可修改、动作单向，因此不增加expectedVersion或多次审核历史表；未来恢复/改原因需另行设计，不把此次决定外推为可反复审核。

V14在order_review新增moderated_by FK account、moderation_reason VARCHAR(300)、moderated_at DATETIME(3)。数据库约束三字段全空或全有，完整记录须HIDDEN、原因1～300字符且时间不早于创建。兼容旧HIDDEN全空，不伪造处理人或填补原因；新接口首次隐藏必须补齐三字段。原内容/评分/归属/创建时间、一单唯一及全部V1～V13迁移不变，仍22张业务表。

写RC锁序：管理员账号X→当前会话X→所属店铺S→评价X；锁后复核身份/会话、归属和当前状态，写后再校验会话期限。定位只找店铺，返回取锁后视图；同事务提交visibility与审核记录，异常全部回滚。未取订单/日期/文件锁或磁盘互斥，不反向锁原作者或经营账号，不访问磁盘。目标账号/店铺/OWNER停用不阻止处理，模拟资金开关无关。

隐藏不改订单状态/版本/事件、账号/店铺/文件版本、占用/资金/文件期限；首次审核记录属于评价，不合成订单事件。本人仍读HIDDEN、reviewed=true且不能重发；公开分页/评分继续按VISIBLE实时读取。已建立的短RR快照可以完成，后续新快照排除并按剩余记录重算，零条null/0，不承诺收回已返回内容。

运行59→60、完整62/60/2；沿order评价服务/Mapper扩展，增加管理员Controller及原因DTO，不引入基础设施。不含恢复/编辑/删除、自动审核、批量隐藏、管理员评价检索或账号管理。真实MySQL权限/并发/回滚、存量升级、公开快照及生命周期不变量验证以指南实际记录为准。
