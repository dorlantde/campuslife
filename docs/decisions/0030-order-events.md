# ADR-0030：学生与商家订单事件查询

- 日期：2026-10-23。
- 状态：用户已明确“采用并实现”。细则见[确认方案](../order-events-design.md)，交付和实际验证见[点击验收](../order-events.md)。

## 决定与理由

新增GET /orders/{orderId}/events及GET /merchant/orders/{orderId}/events（均以/api/v1为前缀），分别供正常STUDENT查询本人BOOKING、正常MERCHANT及ACTIVE OWNER查询本店BOOKING，避免通过当前状态猜测历史动作。沿用原详情的历史权限：学生不依赖当前经营资格，商家暂停/停业仍可读旧单，目标学生停用不隐藏；跨归属、不存在、非BOOKING统一404，本店无效成员403，无OWNER所属店铺404。格式错误ID400，沿用规范正BIGINT字符串解析。

200统一包装，data为完整OrderEventView数组；固定eventSeq ASC，不按时间或记录ID排序、不分页、不截断、不合成REFUND或补写CREATE。当前预约最长链路仅5条，未来扩展再评估分页，不设数据库硬上限。授权无事件返回[]。

复用规划九字段：id、eventSeq、action、fromStatus、toStatus、actorType、actorId、reason、occurredAt。ID/序号为字符串（CREATE序号0），创建前状态和未填原因可null，SYSTEM的actorId=null，时刻UTC。学生及商家均可见原业务原因，包括管理员核实原因及实际操作者ID；不附加姓名、登录名、联系方式或凭证，不提供账号反查。原因作为文本返回，不作为HTML执行。原事件及幂等重复规则保持不变。

## 一致性与取舍

RR只读事务内先读订单归属/商家资格，再带已授权order_id查询事件，均普通一致性读；权限失败不读事件内容。快照后提交的付款、关闭、履约由下次查询可见，不保证与另一次详情请求共享快照。商家资格变化沿用ADR-0029：快照后成员停用本次可能完成，下次拒绝。

不取业务写锁，不触发超时清理、状态/占用/版本变化、新事件或资金，不依赖模拟资金开关。仍使用V1～V10十九张表、order模块及注解SQL，复用uk_order_event_seq，无新迁移/索引/依赖/XML/消息机制。保留OrderEventMapper.latest的管理员重试用途；新增独立列表查询与两个响应映射文件。运行49个操作、完整62/49/13；打印、评价和管理员专用事件入口不在本轮范围。实际结果见验收指南。
