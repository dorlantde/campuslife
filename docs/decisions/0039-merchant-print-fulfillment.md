# ADR-0039：商家开始打印与标记待取件

- 日期：2026-10-25。
- 状态：用户明确“采用并实现”；精确规则见[确认方案](../merchant-print-fulfillment-design.md)，操作与实际验证见[验收指南](../merchant-print-fulfillment.md)。

## 决定与取舍

原POST /merchant/orders/{orderId}/start支持PRINT，新增已规划POST /merchant/orders/{orderId}/ready-for-pickup；运行54→55，完整规划62/55/7。两动作无body，复用OrderDetail及现有字段，无新表/迁移/依赖/生产Java文件，仍V1～V12、21张表、184个生产文件。BOOKING开始窗口和complete行为保持。

正常MERCHANT/有效会话及本店ACTIVE OWNER可履行已接旧单，暂停、停业、下架、改价或学生停用不阻止；无需当前APPROVED或营业。不再判断原支付/接单截止，无预约起点或新增预计取件时间。动作不产生资金，模拟资金开关关闭也可执行。

首次开始ACCEPTED/PAID/QUEUED，acceptedAt非空且startedAt/completedAt/readyAt/pickedUpAt空；锁后now≥acceptedAt。成功IN_SERVICE/PRINTING、startedAt、版本+1和一次MERCHANT START事件。首次待取件仅IN_SERVICE/PAID/PRINTING，acceptedAt及startedAt非空、completedAt/readyAt/pickedUpAt空，now≥startedAt；进度READY_FOR_PICKUP、readyAt、版本+1及一次MERCHANT READY_FOR_PICKUP事件，主状态仍IN_SERVICE。允许立即待取件及时间相等，不伪造打印机耗时。时钟倒退409 PRINT_TIME_INVALID，非法状态组合409 ORDER_STATE_CONFLICT。

首次复核匹配支付且无退款、文件本人AVAILABLE/expiresAt=NULL、订单版本可递增；不读磁盘、不等实例互斥或要求下载历史。接口记录商家声明的阶段，实际内容读取由受控下载检查。原金额、规格、paidAt/acceptedAt保留，不填completedAt或pickedUpAt。

## 重放、原子性与保护

start在合法IN_SERVICE/PRINTING或READY_FOR_PICKUP时重放200，不覆盖startedAt或把进度退回；ready在合法待取件重放200，保留readyAt/版本/事件。每次校验当前权限和已保存阶段/支付事实；合法重放不重判首次时间/文件保护，不写入。非法body即使重放也400。关闭不恢复，本阶段COMPLETED/PICKED_UP不作为重放，学生取件实现时另行定稿终态重试。

商家RC账号X→会话X→店铺S→OWNER→文件X→主订单X→打印明细X→事件；必要锁后固定毫秒时刻，提交前复核会话。主状态、进度/时间、版本和事件原子提交，待取件主状态不变仍须递增订单版本。结果用锁后视图，保留定位缓存修复；失败全部回滚。

仍未终结，文件NULL保护，不触发7天或文件版本写入。文件版本上限不阻止合法动作，订单版本必须可递增。无反向学生账号锁、业务事务内IO或实例互斥等待。开始与取消竞争：取消先提交则start409，开始先提交则cancel409不退款。开始后普通取消不开放，商家complete仍仅BOOKING。

## 履约中下载

ADR-0038商家本店任一合格关联授权增加IN_SERVICE/PRINTING（startedAt非空、readyAt空）和IN_SERVICE/READY_FOR_PICKUP（startedAt/readyAt非空）；三种合法未终结组合均PAID、acceptedAt非空、completedAt/pickedUpAt空且文件属于订单学生。其他错配、待接单、关闭/完成不授权，跨店引用和文件保留不授予权限。

开始/待取件不使商家内容权中断；下载仍互斥后新短RR复核、普通读无业务行锁，获准传输允许完成，清理跳过活动传输。无新增下载记录或资金事实。

## 当前范围

待取件仍IN_SERVICE，文件持续保护，不自动完成/超时退款；学生确认取件、评价和管理员PRINT异常后续实现。当前开始后异常没有普通取消或管理员PRINT退款入口，不能套用BOOKING complete绕过。所有合成终态只用于边界拒绝测试，不代表取件已开放。
