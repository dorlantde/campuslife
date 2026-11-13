# ADR-0040：学生确认打印取件

- 日期：2026-10-25。
- 状态：用户明确“采用并实现”。精确范围见[确认方案](../student-print-pickup-design.md)，操作及实际验证见[验收指南](../student-print-pickup.md)。

## 决定与取舍

新增规划中的POST /api/v1/orders/{orderId}/pickup。仅正常STUDENT、有效会话及本人PRINT，无请求体；BOOKING、他人、不存在404，非法ID/body400。运行55→56，完整规划62/56/6，不增加表、迁移或基础设施。

首次IN_SERVICE/PAID/READY_FOR_PICKUP，acceptedAt/startedAt/readyAt齐全且按时间先后排列，completedAt/pickedUpAt/closedAt空；锁后毫秒now≥readyAt，等于允许，倒退409 PRINT_TIME_INVALID。订单COMPLETED、进度PICKED_UP、completedAt=pickedUpAt=now，版本+1及一次STUDENT PICKUP事件，IN_SERVICE→COMPLETED，原因null。匹配成功支付、没有退款、文件本人AVAILABLE/NULL保护，首次异常回滚。接口声明已取件，不增加取件码、商家代确认或自动完成。

旧单完成不要求当前商家账号/OWNER、店铺经营资格或配置有效，不反向锁商家账号。不新增资金，资金开关关闭也能取件；PRINT仍不能使用商家complete，开始后普通取消保持拒绝。

## 重试与终态

合法COMPLETED/PAID/PICKED_UP，完整有序阶段时间、completedAt=pickedUpAt及匹配支付无退款时，本人pickup重复返回当前详情200；本店正常商家/ACTIVE OWNER的start和ready也返回当前完成详情。三者不重写时间、版本、事件或保留期，不重判首次时间和文件保护，文件后续过期或清理不阻止事实重放。当前身份/归属/成员及禁止请求体仍校验；关闭和错配完成不重放。

这是ADR-0039留待本任务定稿的完成后重试边界：已经顺利完成的旧动作重试返回已有事实，不能使进度回退。完成不授予新的动作或下载权限。

## 原子性与文件权限

RC沿学生账号X→会话X→店铺S→文件X→主订单X→打印明细X→事件/文件期限，提交前复核会话；写响应使用锁后视图。无业务锁内磁盘IO或等待实例互斥，不新增日期锁。状态/版本、时间、事件及文件期限同事务，任何一步失败全回滚。

文件X内复用全部引用统计：仍有未终结引用则保持NULL；全部终结按最晚closedAt/completedAt加7天。仅实际期限改变才写文件版本；必要版本写失败或保护异常503 FILE_STORAGE_UNAVAILABLE，订单版本上限409。不需要改期限时文件版本上限不阻止取件，重试不续期。

完成单不再授权本店商家下载；另一笔本店合格未终结关联可继续授权，仅其他店关联不能授权。互斥后新RR授权快照若已见取件，按新状态判断；先前获准传输可完成，后续请求重判。取件不等待活动下载，清理仍跳过传输，学生本人按原期限下载。

## 验证与范围

真实MySQL验证阶段/权限、时间/版本边界、逐步失败回滚、全部引用保留与并发、下载快照及终态重试；命令和实际结果仅以验收指南记录为准。测试使用隔离临时实例与测试文件根，不触及用户持久数据。管理员PRINT异常退款及评价后续开发。
