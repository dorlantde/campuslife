# ADR-0028：学生本人订单分页列表

- 日期：2026-10-22。
- 状态：用户已明确“采用并实现”。细则见[确认方案](../student-order-list-design.md)，操作及实际验证见[列表验收](../student-order-list.md)。

## 决定与理由

新增GET /api/v1/orders，正常STUDENT及有效会话只查本人订单，方便在Swagger / Apifox找到订单ID并调用现有详情及动作。当前只展示BOOKING；orderType允许省略、BOOKING或PRINT，PRINT合法空页，不查询尚未实现的打印订单。

status可省略或取六种精确大写主状态。省略page/size默认1/20，page最大2147483647，size为1～100，按created_at DESC、id DESC固定排序；越界空页保留真实总数，无记录totalPages=0。枚举空值/空白/未知值、分页空值/符号/小数/越界均400，PRINT仍完整校验参数。分页沿用公开目录数字解析，前导零不单独限制。

只读历史不依赖当前经营资格。名称、金额、区间使用下单快照；店铺暂停、商家/OWNER停用、服务下架/改价不隐藏或重算本人历史。退款后仍保留冻结应付金额。GET不清理超时、不生成资金或推断状态；过期未关闭仍如实显示数据库状态。

## 摘要与一致性

返回OrderPage(items/page/size/total/totalPages)，每项OrderSummary包含标识、类型、主/支付状态、店铺与服务快照、冻结应付金额/币种、创建时间和预约起止；printingProgress本阶段null。实体ID用字符串，金额整数分，时间UTC。不返回备注、核实原因、事件、人员及完整资金对象；需要完整字段或版本时读取已有详情。

RR只读事务内先查总数、再联表取当页快照，两条SQL共享归属/类型/状态条件与一致快照，不逐单调用详情或资金查询。不获取业务写锁、不修改版本，也不改变原写事务锁协议。其他事务可同时新增/支付/取消，本次响应保持原快照，下一次查询看到已提交结果。不同页请求不承诺跨请求固定快照，V1不引入游标。

## 范围与取舍

沿用order模块，新增OrderListMapper及OrderSummaryRow、OrderSummary和OrderPage，复用原Controller/Service/Transactions；当前154个生产Java文件。MyBatis注解SQL使用既有学生时间索引与LIMIT/OFFSET，无分页插件、缓存或新迁移，仍V1～V10共19张业务表。不凭本次查询声称性能提升；深分页优化随实际数据量讨论。

运行46个操作，完整62个（46已实现/16待开发）。商家列表、双方事件、搜索、日期筛选、打印、评价与导出继续分别推进，不扩大本轮查询参数。
