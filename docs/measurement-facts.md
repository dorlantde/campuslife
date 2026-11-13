# B.2-C.2 组件指标与发行事实观察

本模块是独立测量工具，不进入生产JAR。它读取MySQL、评分Redis、领券Redis和RabbitMQ管理API，不创建订单或发行券。真实登录、健康停止、宿主资源采样及总运行入口留给C.3；Pilot与正式测量尚未执行。

## 读取边界

宿主沿用C.1登记新case、实例/卷/容器身份和固定制品。工具只接受本轮测试permit，固定连接私有mysql、rating-redis、coupon-redis、rabbitmq端点，命名空间与vhost必须由实例ID派生；核对数据库UUID/hostname、两个不同Redis run_id和队列vhost/node/type/name，不能只凭数据库名认定归属。

MySQL使用新测试库内专用 `campuslife_measure` 账号，只授测试库与Performance Schema的SELECT，不扩大app账号权限。每次业务事实查询使用只读RR事务，另有2秒观察调用上限、网络超时及查询截止；超时取消/关闭观察连接，不让任务排队积压。观察连接默认catalog是performance_schema，业务表全部显式限定schema，避免把观察查询直接归进该业务schema的digest汇总。

Redis读取INFO选定统计/内存字段，并用EVAL_RO组合原common.lua校验和测量只读脚本，原子核对代次、持久TTL、计数守恒及受理记录/券ID证明。EVAL_RO不允许脚本写入，未加载写脚本或修改库存。原HTTP、后台、维护Redis预算不改；这里是独立观察客户端。

Broker只GET四个固定队列，逐项要求200及完整身份/ready/unacked字段，缺失或404不按0处理。不在采样循环反复启动rabbitmqctl，不跟随重定向；单个GET含完整响应体读取至多2秒且限制大小。工具的运维密码只从本轮私有文件读取，不进argv、CSV或响应日志。

## 发行和排空核对

一次采样先取SQL快照，再读Redis/队列，最后以新SQL快照复核；期间关键SQL状态变化则等待，不把跨组件顺序读取说成一个分布式事务快照。SQL核对发行计数=持有券数、请求与券/学生/活动关系、已知202的请求ID/学生/原键、outbox与消费hash、历史订单/支付/评价/事件及退款/文件数量。HTTP观察不能替代这些事实。

判定可能为UNAVAILABLE、INVALID、WAIT或OBSERVED_DRAINED。只有至少间隔5秒的两次可用观察，身份与关键事实稳定、活动OPEN、SQL受理集合已对齐、无PENDING/未发outbox/缺消费、Redis held=0/spent与SQL发行及逐条证明匹配、四队列ready/unacked都为0时，才返回观察排空授权。元数据checkpoint可以落后于已重建的代次，不能仅因checkpoint旧就忽略实际Redis重建证明。

Rabbit管理计数具有采样属性，两次观察仍不是对任意迟到发布的原子空队列证明。C.3必须在停止本段新增前台后结合已知发送任务和原幂等协议使用授权；未知POST、未明创建或关键证据缺失不能靠这个布尔值绕过。该模块不承诺消息永远不会再到达，不重复实现现有消费幂等或补偿。

## 指标与开销

记录schema digest的COUNT_STAR、timer/lock时间、扫描/返回行数，以及可用的InnoDB行锁等待/毫秒累计。摘要时间单位为皮秒，与InnoDB行锁毫秒分开；只取digest哈希/计数/首次时间，不导出SQL正文。按相同服务器/完整摘要集合计算前后增量，摘要消失、丢失、重置、计数倒退或权限缺失均标不可用，不用0编造优化收益。业务schema计数仍包含其健康检查等技术请求，不能称纯业务DML成本。

两个Redis的命中/未命中、命令数、淘汰数及内存字段单列；观测本身会增加命令和命中统计，实例命中率不是HTTP缓存命中率。观察SELECT数量、SQL耗时、Redis命令和耗时也记录。Sampler每5秒最多启动一次，不重叠、不积压；一次循环可能跨越采样间隔，不能假定所有组件在同一时刻被读取。

## 显式隔离验证入口

Linux工具目录中用新的case ID，按[监督入口](case-runner.md)准备与启动：

```json
{
  "case_id": "case-0123456789abcdef",
  "task": "M06.2-B.2-C.2",
  "total_seconds": 600,
  "cleanup_seconds": 120,
  "script": "scripts/test_measurement_facts.py",
  "args": ["job"],
  "cleanup_script": "scripts/test_measurement_facts.py",
  "cleanup_args": ["cleanup"],
  "inputs": [
    "scripts/measurement_resources.py",
    "scripts/measurement_fixture.py",
    "src/measurement/java/campuslife/measurement/MeasurementScenario.java",
    "src/measurement/java/campuslife/measurement/MeasurementDriver.java",
    "src/measurement/java/campuslife/measurement/MeasurementHttpTransport.java",
    "src/measurement/java/campuslife/measurement/MeasurementFacts.java",
    "src/measurement/java/campuslife/measurement/MeasurementFactSources.java",
    "src/measurement/java/campuslife/measurement/MeasurementFactChecks.java",
    "src/measurement/java/campuslife/measurement/V24MeasurementFixture.java",
    "src/measurement/resources/coupon-observe.lua"
  ]
}
```

检查只使用新自有组件与数据，合成两张SQL券及相应请求/outbox/消费事实，再调用一次原Resume main建立Redis代次，未通过SQL打开门闩或手改Redis库存。测试写入者与只读观察账号分开；检查还故意修改SQL事实、发布/持有/确认一条无业务消息及删除/重建空队列，证明不一致/积压/缺失不能获得授权。这些不是正常领取、发布确认或履约业务路径验收。

观察工具维持256MiB/无额外Swap、heap128MiB与CPU0/1，复用原156份依赖和固定镜像，不启动应用HTTP或S3。原维护CLI保留90秒及原预检/代次规则。测试完成后由C.1回收本轮资源/私有材料；结果与安全采样摘要保留。缺少原release/镜像/源文件时不能直接照运行；此处不是正式测量命令。

设计依据：[Redis EVAL_RO](https://redis.io/docs/latest/commands/eval_ro/)；RabbitMQ4.3.6接口形状以本轮实测为准，不以其他版本说明冒充兼容通过。实际范围与结论见[报告](reports/measurement-facts-2026-11-07.md)。
