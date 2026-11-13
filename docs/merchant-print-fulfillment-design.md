# 商家开始打印与标记待取件：已确认方案

- 日期：2026-10-25；校园业务时区Asia/Shanghai，响应UTC。
- 状态：用户明确“采用并实现”；已按[ADR-0039](decisions/0039-merchant-print-fulfillment.md)落实，实际操作与验证见[指南](merchant-print-fulfillment.md)。
- 依据：[业务基线](decisions/0001-v1-baseline.md)、[预约履约ADR-0026](decisions/0026-booking-fulfillment.md)、[文件引用ADR-0034](decisions/0034-print-orders.md)、[打印取消ADR-0037](decisions/0037-merchant-print-cancellation.md)、[商家下载ADR-0038](decisions/0038-merchant-print-download.md)。

## 1. 推荐范围与完成标准

本轮让打印从已接单进入打印中，再标记待学生取件；同步补齐履约中的商家PDF权限。打印最终由学生确认取件完成，不能误用商家预约complete提前结束。

| 接口 | 本轮推荐 |
|---|---|
| POST /api/v1/merchant/orders/{orderId}/start | 扩展既有入口支持PRINT，原BOOKING时间窗口保持 |
| POST /api/v1/merchant/orders/{orderId}/ready-for-pickup | 新增已规划入口，仅本店PRINT；BOOKING/跨店/不存在404 |
| GET /api/v1/files/{fileId}/content | 商家有效任务授权增加PRINTING与READY_FOR_PICKUP两个合法IN_SERVICE组合 |

两个动作无请求体（JSON null、空对象、其他内容同样拒绝），只填规范orderId和商家Bearer，200复用OrderDetail。不增加expectedVersion、客户端幂等键、请求时间或金额。

仅新增一个HTTP操作，运行54→55，完整规划62/已实现55/待开发7。复用现有订单、打印明细、时间及事件字段，无新表、迁移、依赖或基础设施；打印写事务继续放既有PrintOrderService/PrintOrderTransactions，不进入预约日期锁。列表、详情、事件同步展示真实进度及readyAt，OpenAPI与点击指南同步更新。

本轮不实现学生取件、评价、管理员PRINT异常或服务中退款。开始后不再允许普通商家/学生取消；本阶段PRINT异常退款入口尚未开放，后续单独接入，不能用complete或改状态绕过。待取件会保持未终结及文件保护，直到后续学生取件或获授权异常路径落实。

## 2. 权限及首次状态转换

正常MERCHANT、有效会话及本店ACTIVE OWNER才可操作。店铺由服务器所属关系固定，无店/跨店/不存在404、成员无效403；只处理本店PRINT。暂停/停业、服务或价目下架/改价、目标学生停用不阻止履行已接旧任务，不要求当前APPROVED或正在营业，不反向锁学生账号。

开始和待取件均不再判断原支付/30分钟接单截止，不引入预约startAt、预计取件时间或打印容量。两个动作无新资金，模拟资金关闭也可执行。

| 动作 | 首次前提 | 原子结果 |
|---|---|---|
| 开始打印 | ACCEPTED/PAID/QUEUED，acceptedAt非空，startedAt/completedAt/readyAt/pickedUpAt为空，锁后now≥acceptedAt | 主状态IN_SERVICE，startedAt=now，进度PRINTING；版本+1，一条START事件，ACCEPTED→IN_SERVICE |
| 标记待取件 | IN_SERVICE/PAID/PRINTING，acceptedAt及startedAt非空，completedAt/readyAt/pickedUpAt为空，锁后now≥startedAt | 主状态仍IN_SERVICE，进度READY_FOR_PICKUP，readyAt=now；版本+1，一条READY_FOR_PICKUP事件，IN_SERVICE→IN_SERVICE |

推荐不设置最短打印时长，开始后立即标记待取件允许，readyAt=startedAt也允许；不伪造真实打印机耗时。若锁后时钟早于前序动作时间，409 PRINT_TIME_INVALID，无写入。其余非法主状态/付款/进度或必要字段组合409 ORDER_STATE_CONFLICT。

首次动作校验匹配的成功支付、没有退款，以及关联文件仍属于订单学生、AVAILABLE且expiresAt=NULL。文件保护异常503 FILE_STORAGE_UNAVAILABLE，订单版本上限409，整笔无部分写入。不打开磁盘、不等待实例文件互斥，也不要求已有“下载成功”记录：下载没有事件或状态副本，不能为了前置条件增加另一套事实。接口记录商家声明的阶段，实际文件读取由受控下载验证。

保留原paidAt、acceptedAt、金额与冻结规格；标记待取件不修改startedAt，也不填写completedAt或pickedUpAt。事件操作者MERCHANT/实际账号ID，reason=null，不合成资金或完成事件。通常CREATE/PAYMENT/ACCEPT/START/READY_FOR_PICKUP序号为0/1/2/3/4。

## 3. 重复操作与关闭保护

| 情况 | 推荐结果 |
|---|---|
| 已合法IN_SERVICE/PRINTING，再start | 200当前详情，原startedAt/版本/事件不重写 |
| 已合法IN_SERVICE/READY_FOR_PICKUP，再start | 200当前待取件详情，不把进度改回PRINTING，不重写时间 |
| 已合法IN_SERVICE/READY_FOR_PICKUP，再ready-for-pickup | 200原readyAt及当前详情，不重写版本/事件 |
| 未开始就标待取件，或未付款/待接单/已关闭等其他状态 | 409 ORDER_STATE_CONFLICT，无写入，不代跑超时或退款 |
| 非法请求体，即使目标已成功操作 | 400 VALIDATION_FAILED，不因重放忽略输入错误 |
| 合成COMPLETED/PICKED_UP | 本阶段不作为合法重放，409；学生取件实现时另行定稿终态重试行为 |

每次仍校验当前身份、归属和成员，重复不是权限例外。合法重放核对已保存的阶段/支付事实，不重新执行首次动作、文件保护校验或时间判定，不重置任何保留期。CLOSED不能恢复，取消先提交后start不能重新开始；start先提交后普通cancel409，不产生退款。

原商家complete仍仅BOOKING，PRINT保持404；待取件不是完成，也不因长时间未取自动结束。管理员PRINT异常仍未开放，不据既有枚举或合成测试宣称已实现。

## 4. 文件保护与履约中下载

开始/待取件始终是未终结引用，文件继续NULL保护，不启动7天倒计时、不更新文件版本。即使文件版本已达上限，只要元数据仍合法且本次无需改期限，不应因此禁止动作；订单版本仍必须允许递增。

商家内容授权延续ADR-0038的本店任一合格关联、文件学生归属及当前账号/会话/ACTIVE OWNER检查，允许以下合法组合：

- ACCEPTED/PAID/QUEUED，acceptedAt非空、startedAt/readyAt/pickedUpAt空。
- IN_SERVICE/PAID/PRINTING，acceptedAt和startedAt非空、readyAt/pickedUpAt空。
- IN_SERVICE/PAID/READY_FOR_PICKUP，acceptedAt/startedAt/readyAt非空、pickedUpAt空。

以上未终结组合completedAt须为空。未付款、待接单、关闭、完成、错配进度或必要字段缺失不授权；其他店引用和历史7天保留仍不授予本店权限。开始/待取件的合法状态切换不造成商家下载权中断，已获准传输和清理互斥仍沿ADR-0038，不延长内容访问至关闭或未来完成后。

## 5. 事务、并发与验收

沿商家RC账号X→会话X→店铺S→OWNER资格→文件X→主订单X→打印明细X→事件的顺序。必要锁后固定一次毫秒动作时间，提交前复核会话。主状态/进度/时间、版本和事件同事务；待取件即使主状态仍IN_SERVICE，也递增公共订单版本，避免事件和明细变化没有版本事实。

用锁后主订单/明细判断和构造返回，不复用定位旧缓存。开始与取消、开始与待取件、重复请求和同文件关联变化协调；先提交状态决定后续动作，SQL条件写/唯一事件约束兜底。文件行锁范围内始终无磁盘IO，不持有业务行锁等待下载；内容授权读取仍RR普通一致性读，不改为长事务或逐字节撤销。

验收范围（实际执行记录见指南）：

1. Swagger/Apifox：学生上传/报价/创建/付款→商家接单/下载→start→ready-for-pickup→学生/商家详情、列表、事件核对真实阶段/时间；各合法阶段可受控下载，主状态待取件仍IN_SERVICE，文件NULL保护。
2. 真实MySQL/HTTP验证角色、会话、OWNER、跨店、BOOKING ready404、暂停/停业/学生停用、截止经过和资金关闭；严格ID/无请求体、未付/待接单/关闭/错配组合，未开始不可标待取件。
3. 固定锁后毫秒时间、now等于/早于前序动作、允许立即待取件；start在PRINTING/READY重试与ready重试不覆盖时间/版本/事件，不回退进度。
4. 主订单、打印进度/时间、事件及最终会话失效故障回滚，订单版本上限、文件保护异常与文件版本上限无需期限写入的边界。
5. 真实开始/取消、双开始/双待取件、开始/待取件竞争和锁等待后的复核；无反向学生锁、无实例互斥或磁盘IO，两个阶段继续被文件清理保护。
6. 下载在开始/待取件切换前后的真实RR快照与有效组合，跨店和错配状态仍拒绝；回归原预约start/complete/取消窗口、打印取消/支付/历史/事件、学生与商家下载。
7. 同步注解、两份OpenAPI与待开发计数、ADR和人工点击指南；只用独立临时MySQL与自有测试文件根。

本次已确认立即待取件与时间倒退处理、阶段重复操作、开始后普通取消限制、IN_SERVICE下载组合，以及学生取件/管理员异常仍后续开发的范围。授权范围已落实；验证执行范围及结果以指南记录为准。
