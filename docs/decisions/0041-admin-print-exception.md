# ADR-0041：管理员核实打印异常并全额模拟退款关闭

- 日期：2026-10-26。
- 状态：用户明确“采用并实现”。精确范围见[确认方案](../admin-print-exception-design.md)，操作及实际验证见[验收指南](../admin-print-exception.md)。

## 决定与理由

扩展原管理员详情和close-exception两入口支持PRINT，原BOOKING规则沿ADR-0027。正常ADMIN可看任意店铺所有状态PRINT，返回冻结规格、阶段时间、版本和真实资金事实；历史文件清理不隐藏订单。没有新的PDF内容权限、联系方式或存储位置，不自动退款。运行仍56、完整62/56/6，无新表、迁移、依赖或基础设施。

核实请求仍reason+expectedVersion：去首尾空白1～300 Unicode码点、规范非负BIGINT版本字符串，拒绝未知字段。不收客户端金额、时间、状态或管理员ID。首次PAID且PENDING_ACCEPTANCE/QUEUED、ACCEPTED/QUEUED、IN_SERVICE/PRINTING或IN_SERVICE/READY_FOR_PICKUP，阶段字段完整有序、未完成/取件/关闭。目标学生/经营账号或OWNER停用、店铺暂停及配置变化不阻止核实旧单，不反向锁目标账号。

锁后固定毫秒now，不早于paidAt及已有阶段时间，等于允许，倒退409 PRINT_TIME_INVALID。成功CLOSED/REFUNDED/ABORTED、ADMIN_EXCEPTION/closeNote、closedAt、公共版本+1与唯一全额退款及一次ADMIN_EXCEPTION事件同事务。事件fromStatus为真实原状态，ADMIN/实际账号，reason与closeNote/退款说明相同。保留原付款、冻结规格及acceptedAt/startedAt/readyAt，不填completedAt/pickedUpAt；已完成不允许本入口退款。实际PDF丢失或不可读不阻止核实，不在事务中读取磁盘。

## 版本、重放及并发

非CLOSED先比版本，旧版本409 CONFIG_VERSION_CONFLICT，需重新读取并核实；版本相同但非法状态或无法递增409 ORDER_STATE_CONFLICT。管理员核实期间商家开始/待取件或学生取件，不能用旧请求覆盖新事实。

CLOSED仅同管理员/归一化原因/原版本、最后事件动作/序号/操作者/原因/原状态匹配，且原支付、全额成功退款、ABORTED和保留阶段事实一致时重放200。核对退款关联支付、原因/说明、关闭/退款/事件时间一致；数据库事实不一致按内部错误处理。其他关闭请求409。合法重放不重判首次文件保护/时间/版本递增，不改时间、资金、版本、事件或文件保留期；文件清理后也可重试。当前管理员权限仍校验。

新退款仍受默认关闭的模拟资金开关保护，关闭时503整笔无写入；已有成功事实重放不依赖开关。与扫描/普通退款先提交者保留关闭原因；与学生取件竞争，已完成不退款，已核实关闭不能取件或恢复履约。

## 事务、文件及下载

RC管理员账号X→会话X→店铺S→文件X→订单X→打印明细X→资金/事件及全部引用期限，提交前复核会话；写响应使用锁后视图。PRINT不使用预约日期锁，无OWNER资格锁、反向目标账号锁、业务事务内IO或实例文件互斥等待。任一步失败全部回滚。

首次文件本人AVAILABLE/expiresAt=NULL保护元数据须正常；异常503 FILE_STORAGE_UNAVAILABLE。持文件X复用全部引用保护，有未终结引用仍NULL，全部终结最晚closedAt/completedAt+7天；仅期限实际改变才递增文件版本。必要期限写失败回滚全部资金和状态，无需改变期限时文件版本上限不阻止操作。

关闭单不再授权商家下载，本店另一合格任务仍可授权，只有他店关联不授权本店。互斥后新短RR若见关闭则重新判断；已有获准传输可结束，后续请求重判，退款不等下载，清理跳过活动传输。管理员仍无默认内容权限，学生本人沿原期限与账号规则访问。

## 交付范围

复用PrintOrderTransactions/Service、既有管理员Controller/服务分派及Mapper注解SQL；不更改普通取消和BOOKING异常规则。不增加管理员订单列表、工单、多级审批、部分退款、已完成售后或评价。实际测试、启动检查及Swagger/Apifox复现记录以验收指南为准，测试只用临时MySQL和自有文件根。
