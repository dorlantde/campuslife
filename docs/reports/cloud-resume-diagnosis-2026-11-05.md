# M06.1-D.6a 重启后 Resume 专项诊断

后续D.6b已完成[两次INFO只读对照](cloud-connection-comparison-2026-11-05.md)，未调用Resume、未修改门闩，预检修复尚待确认。下文保留D.6a执行事实，不把新对照追认为本轮修复通过。

**结论：定向复现取得了具体异常，尚未修复，D.6整体仍未通过。** 本次原五卷重启后的 Resume 在第一次券Redis连接/INFO调用失败，异常链为 `RedisConnectionFailureException → RedisConnectionException → RedisCommandTimeoutException`。不是本次操作的版本冲突或30秒租约到期；不能据此追认此前两次泛化异常具有同一根因。[操作与边界](../cloud-resume-diagnosis.md)/[机器记录](cloud-resume-diagnosis-2026-11-05.json)。

## 实际执行与范围

2026-11-05 UTC 08:00:36～08:06:40（纽约03:00～03:06），新ECS同一D.5 release、JAR与镜像。外层单次执行371.011秒，未启动第四轮整链。创建自有compact4g隔离七服务，只有回环入口；不启用模拟资金，不运行打印/支付交易，不接触原项目库、上传目录或历史冷备。

为了只复现“已有发券记录＋原卷重启”，通过正常接口申请商家、创建发布活动、注册学生并同步领一张券，再以显式技术SQL设置审核资格及异步请求元数据/暂停门闩。活动库存2、已发1、持有券1、处理中0。这些SQL夹具不计为管理员审核、异步受理或订单业务验收。停止七服务并保留原五卷后重启，等待SQL与Redis的PAUSED代次/runId一致，再仅执行一次原Resume Bean。

新增两个测试工具：[安全观察类](../../scripts/diagnostics/CouponResumeTrace.java)、[隔离驱动](../../scripts/diagnostics/coupon_resume_case.py)，合计18,474字节；白名单传输并逐文件核对SHA256。观察类仅额外挂载，记录阶段、耗时、异常类型及受控错误码，不输出异常message、连接URL、参数或凭据。原事务Bean、锁、Redis配置及生产JAR未修改。

## 捕获的故障位置

| 观察点 | 实际结果 |
|---|---|
| 重启前 | ASYNC/PAUSED，generation=3，controlVersion=5；Redis同代次 |
| 原卷重启并就绪 | ASYNC/PAUSED，generation=4，controlVersion=7；Redis为新runId且同代次 |
| Resume SQL步骤 | 接受读取到的版本，提交generation=5、controlVersion=8、REBUILDING，保存OPEN恢复意图和30秒票据 |
| 第一个Redis步骤 `runId()` | 4,013 ms后失败，异常链见上；调用路径为懒连接获取后读取 `INFO server` |
| 失败后应用内快照 | REBUILDING/OPEN，leaseUntil=08:06:52.906，now=08:06:27.180900727 UTC，仍剩约25.725秒 |
| Redis侧只读复核 | 仍为generation=4/PAUSED；新generation=5的epoch未建立 |
| SQL发行计数 | 三份快照均为已发1、持有券1、处理中0 |

未出现 `prepare/initialize/seed/activate` 的调用记录，失败发生在这些重建写入之前。Redis实例runId在本次Resume前后保持一致；跨七服务重启确实改变，五卷名称保持一致。

观察类从启动Spring上下文到失败结果共53,892 ms，**不是纯Resume调用耗时**。4,013 ms是第一次懒连接/INFO入口的总耗时，不能当作Redis服务器执行INFO的延迟。源码仍是socket connect 300 ms、command 500 ms、eager initialization关闭；客户端初始化等阶段并不等同于单条命令预算。诊断外层CLI留110秒用于额外观察，原维护脚本90秒、30秒租约及所有生产超时未改；实测结果在原90秒以内返回，不依赖扩大生产预算过关。

本次已确定到客户端冷连接路径的超时，尚不能区分具体握手命令、线程调度、CPU竞争或网络因素；额外观察也可能影响时间。此前无此类追踪的两次泛化失败继续保留为未完整定位；旧空夹具的 `CONTROL_VERSION_CHANGED` 不作为它们的统一解释。没有实现自动重试、预热或延长超时。

## 制品、清理与验证

独立只读复核：全局containers/volumes、自有test networks、生成secrets均为空；现在保留15个历史夹具目录及失败证据，无长期运行实例。原D.5输入459项保持，唯一已记录差异仍为D.6的 `deploy/base/mysql-init.sh`；原测试补丁保持。云端JAR SHA256为 `2ad77758b9f8fc2bc070a19bf169ff989b978df32c66e7e04eae01eb96f7bb71`，镜像ID为 `sha256:c27d02862aebbda20b37004bff4a105551fc1e3ff538c613d81945c05efb4512`，均未改变。

测试Java在云端用原156依赖编译并实际调用原Bean；Python入口实际跑完并清理。工具退出0表示成功取得诊断，机器记录 `diagnosisCaptured=true`、`operationSucceeded=false`，不是Resume通过。未重复全量构建/业务回归、冷备、性能测量或容量验收；未启动本机Docker，未操作旧机、公网或前端。

## 下一项建议与停止条件

仅建议 **M06.1-D.6b：同维护上下文的冷/热连接只读对照**，尚未开始。验证在取得SQL重建票据之前只读检查券Redis连接是否能消除“领到票据后才冷连接失败”的窗口；最多两次只读INFO，记录各次异常/耗时与门闩未变化，不盲目重复Resume。若需要额外等待或改变生产预检/重试规则，先给出具体方案再实施，不能把两个INFO调用视为保证成功的重试策略。

完成标准是取得可解释的冷/热差异或如实保留无法复现结果，并据此提出最小修复及验证范围；不直接增加全局Redis超时，不先改租约/版本，不再跑完整交易链。修复和剩余实机断言通过前，D.6、D.7、M06.2均不标完成，前端仍暂停。
