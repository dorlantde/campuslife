# M06.1-D.6i 新正式包剩余定向实机验收

状态：**2026-11-06单次执行通过，32项定向断言＋5组配置，D.6缺陷整改及剩余断言收口**；[报告](reports/cloud-final-remaining-2026-11-06.md)/[机器记录](reports/cloud-final-remaining-2026-11-06.json)。841.056秒，首次及重启后Resume/实际发券通过，资源已清理。保留RabbitMQ探针超时和SSH重置观察；未新跑完整打印交易，不追认旧失败轮通过。前置[D.6h正式包](reports/cloud-initialization-build-2026-11-06.md)。下文为本轮已核对范围。

使用release `/home/campusdeploy/campuslife/releases/1cf0cb53a24a`，JAR SHA256 `6311c16a12b2a756353150cefd0ac9a2e7074194a8012204326c85df3df3bd12`，镜像ID `sha256:7656904023ca692349cf4606576c06d271ea9cd9588ec7c0000c3fbcfae291d6`。[原定向入口](../scripts/test_deploy_remaining.py)仅更新制品指纹、任务标识及失败时的受控SQL状态证据；七份辅助输入保持，八份白名单共136216字节。没有修改业务类、JAR、配置、依赖或DDL，不挂载覆盖业务class。

先检查真实宿主、空容器/卷、五组既有配置，再创建一个自有showcase/compact4g/observe实例。随机回环Nginx入口，其他组件/管理面私有；模拟资金关闭。正常注册学生、显式AdminBootstrap创建虚构管理员、商家申请审核、发布库存2张活动；无SQL播种业务资格或门闩。

同一执行覆盖原main的Pause/Async/Resume、首次真实202及发行、Pause后保留原五卷/24份凭据重启、旧会话/幂等/发行事实、重启后Resume及第二次真实发行；最后执行既有资源、管理面与日志凭据钩子。总量不得超发或重复，日志不得含13类生成凭据及本轮密码/token。不创建订单、资金记录或业务文件，不重复冷备/压测。

外层900秒、超时先中断并给90秒清理，维护CLI90秒、固定命令expiry500ms/TCP300ms、维护初始化5秒、两次预检和租约30秒均保持。一次失败保存日志与允许的状态证据后停止，不自动重复。仅清理本任务自有资源与生成秘密，不全局prune或停止Docker服务。

最终按本轮实际通过项及独立审计报告，不累计旧失败轮次；采样不能保证捕获全部短命CLI或瞬时峰值，不作容量/HA/RTO结论。不默认公网或长期实例，前端暂停。
