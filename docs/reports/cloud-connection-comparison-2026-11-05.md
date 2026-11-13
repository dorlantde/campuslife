# M06.1-D.6b 维护上下文冷/热连接只读对照

后续D.6c已按用户推进[实现并定向验证预检](maintenance-preflight-2026-11-05.md)；正式云端新JAR/镜像尚待核验，D.6仍未通过。下文为本轮只读对照当时事实。

**结论：本次首次连接超时、同上下文第二次调用成功，且没有取得SQL重建票据。** 对照已完成；没有实施预检/重试修复，D.6整体仍未通过。[机器记录](cloud-connection-comparison-2026-11-05.json)/[待确认修复方案](../coupon-maintenance-preflight-design.md)。此前D.6两次泛化异常仍不能据此追认为同因。

## 执行与结果

2026-11-05 UTC 08:24:40～08:31:12（纽约03:24～03:31），单次包装耗时398.865秒。沿用新ECS、compact4g、D.5原JAR/镜像及原Redis配置；新建自有技术夹具，保留原五卷重启一次。虚构商家/学生正常建活动并同步领券后，技术SQL构造已发券异步元数据和暂停门闩；与D.6a相同，技术资格及元数据播种不算业务验收，不运行订单、资金或打印链。

新增[观察类](../../scripts/diagnostics/CouponConnectionProbe.java)和[驱动](../../scripts/diagnostics/coupon_connection_case.py)，合计16,424字节，白名单上传且逐文件SHA核验。使用原 `CouponMaintenanceBootstrap` 的Spring上下文、原 `CouponRedisStore` 和连接工厂，无代理包装、不替换生产类；只在应用内读门闩，然后连续调用两次 `runId()`（INFO server），每次后再读门闩。**不调用Resume、不请求票据、不执行Lua重建写入。**

| 观察 | 实际结果 |
|---|---|
| 重启后、调用前 | ASYNC/PAUSED，generation=5，controlVersion=8，ticket=null，lease=null |
| 第1次 `runId()` | 3,619 ms失败：RedisConnectionFailureException → RedisConnectionException → RedisCommandTimeoutException |
| 第1次后 | 完整SQL门闩记录相等，仍无票据 |
| 第2次 `runId()` | 311 ms成功，runId与门闩中记录一致 |
| 第2次后 | 完整SQL门闩记录相等，仍无票据 |
| 外部快照复核 | 门闩、Redis control、epoch计数及SQL发行计数均保持；已发1、持有券1、处理中0 |

两次应用内调用合计约3.930秒；从启动Spring到完成观察共58.646秒，两者不能混为一项。第二次311 ms是客户端完整调用时间，不是纯Redis服务器执行时间；同一上下文/工厂不代表复用了同一条物理TCP连接。冷失败后初始化进度、类加载、调度等因素尚未拆解，具体握手或底层资源原因未知。单次对照不代表必然第二次成功，也不建立新的成功率或延迟SLA。

本轮未改变socket connect 300 ms、command 500 ms、30秒重建租约及原维护CLI90秒预算。诊断CLI包装110秒用于观察，实际58.646秒在原90秒以内结束；外层隔离包装780秒没有触发。没有暂停/清空Redis、改DNS/TLS/镜像源或引入组件来影响结果。

## 清理与制品

驱动完成后清理自身资源，独立SSH只读审计确认containers、volumes、test networks、生成secrets均为空。历史夹具目录现在16个，留存日志/证据而非长期实例。本机Docker未启动，旧ECS/开发数据/冷备/公网/前端均未操作。

原D.5输入459项保持，唯一既有差异仍为 `deploy/base/mysql-init.sh`；四份已记录D.6补丁及本次两份上传源码摘要核验通过。云端JAR仍为 `2ad77758b9f8fc2bc070a19bf169ff989b978df32c66e7e04eae01eb96f7bb71`，镜像仍为 `sha256:c27d02862aebbda20b37004bff4a105551fc1e3ff538c613d81945c05efb4512`。没有业务Java/DDL/pom/前端改动或重新构建生产制品。

观察类用原156项依赖在云端编译并实际执行；Python语法、两份源码摘要、前后门闩与计数断言均通过。退出0仅表示只读对照及清理完成，不表示Resume或D.6通过。没有重跑第四轮整链、容量测试或全量业务回归。

## 证据与复现入口

本地原始证据为 `.cache/cloud-connection/case-074e7929437f/{manifest.json,runner-result.json,final-audit.json}`，文件摘要见机器记录。远端源码目录 `/home/campusdeploy/.cache/campuslife-connection/case-074e7929437f`，输出目录在原release `.cache/diagnostics/case-074e7929437f`。私有完整日志留在自有夹具目录；报告只收录异常类型，不收录异常message或凭据。

本次目标机实际执行如下，留作复现记录，**不要求现在重跑**；输出目录须为新目录，旧目录不可覆盖：

```sh
python3 -u /home/campusdeploy/.cache/campuslife-connection/case-074e7929437f/coupon_connection_case.py --release /home/campusdeploy/campuslife/releases/110d141b50c2 --output-dir /home/campusdeploy/campuslife/releases/110d141b50c2/.cache/diagnostics/case-074e7929437f
```

## 下一项

建议按[预检方案](../coupon-maintenance-preflight-design.md)定稿D.6c：仅维护入口在取得票据前预检连接，对本次匹配的连接超时最多额外尝试一次；失败不改门闩，成功仍由原事务重新校验版本和资格。生产预检/重试尚未实施，先确认本次具体行为变化，再做实现与定向验证；D.7、综合压测和前端仍等待。
