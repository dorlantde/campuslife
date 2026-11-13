# D.7.1-B 单次探针诊断入口

状态：B.2[原观察](reports/rabbit-probe-observation-2026-11-07.md)保持，C提案已采用；[C.1固定镜像关卡](reports/rabbit-health-compatibility-2026-11-07.md)缺curl/wget按规则停止，候选不兼容，未起Broker或生成秘密。下一项仅最小客户端依赖变更方案定稿，C.2未开始，不重跑旧诊断或自动改探针。[C.1入口](rabbit-health-compatibility.md)/[C提案](rabbit-health-treatment-design.md)。C.3/B.2未通过，前端暂停。以下为已有诊断入口及历史执行边界。

实现依据[已采用方案](rabbit-probe-diagnosis-design.md)。这是固定ECS上的隔离诊断，不是应用启动或正式测量入口。原探针/640MiB/CPU0、1/秘密挂载保持；只启动Rabbit，一个128MiB/heap32MiB/CPU0且最多1核、无网络的临时工作者只用于第二窗口，60秒自停。

## 入口与归属

由原`scripts/case_runner.py`准备新case，task标注具体轮次D.7.1-B/B.1/B.2，总480秒、清理120秒；job和cleanup均用`scripts/run_rabbit_probe_diagnosis.py`及对应role。必须有原固定release、镜像与C.1账本，不允许直接对现有服务执行或重新启动未知case。

白名单7项：原case_runner、measurement_resources、measurement_runtime；新增rabbit_probe_diagnostics、test_rabbit_probe_diagnostics、run_rabbit_probe_diagnosis三个Python文件；独立`src/diagnostics/java/campuslife/diagnostics/CpuWindow.java`。Java只编译诊断类，不改业务JAR/依赖。

准备阶段在目标Python执行受控用例，编译工作者并验证1秒自截止，再登记停止状态的工作者。原Init可以生成原showcase全部私有配置，但只启动Rabbit；没有数据库、业务账号、券或消息交易。运维HTTP由宿主读取本case私有密码，通过已验证的核心网络IP访问；不给Rabbit或工作者新增秘密挂载，不公开管理端口。

## 边界与输出

- 原Docker探针按容器ID/Start/End去重，输出只保留长度/安全分类/通过安全检查的指纹；没有任意诊断原文。
- HTTP请求在独立、可终止的短命宿主Python子进程中执行，完整调用截止2秒，响应体至多256KiB。凭据通过本机stdin管道传入，不能出现在argv/报告；只返回允许的状态、版本或身份匹配布尔值。
- 手动CLI包含固定阶段标记，宿主派发至返回最多5秒；其中包含exec/传输开销。杀掉docker客户端不能证明容器内CLI已退，超时后停止后续样本，最多5秒只读取证并结束专项。
- 宿主每秒最多一次inspect、每5秒读取已核对cgroup资源及进程身份。进程名/PID/启动ticks/父PID/命名空间PID可用于观察；不读取完整argv/环境或按进程名pkill。
- 两个窗口每个60秒，不为凑齐原探针前5个结果延长。首5个只是预定子集，实际所有可见探针和跨窗口/不足/读取失败均保留。
- CPU工作者的60秒指其有界计算循环。进程启动、最终JSON和退出观察有开销，退出核对可在候选阶段内最多等3秒；不延长计算窗口或候选总45秒。

主要证据文件：`diagnosis.json`（状态/窗口/候选权限）、`slots.json`（执行与未执行原因）、`native-probes.json`、`host-samples.json`、`observer-errors.json`、`protocol-ready.json`、`worker-config.json`、`worker-short-check.json`、`secret-checks.json`。修正版增加每次原子保存`protocol-attempts.json`；原失败轮没有该明细，不追补。B.1已实测67次准备尝试落盘，原A/B仍未执行。归属对照现用`/api/vhosts?disable_stats=true`，要求合法列表含本case精确名称一次，空列表不通过；B.2已验证正常凭据列表路径200及精确名称，认证负面对照仍未执行。上述证据不含原始HTTP正文或CLI输出。HTTP ready无/错误凭据仍成功时，不能称其提供认证证明。

`FIXED_WINDOWS_COMPLETE`只说明本次固定观察完成；`TRUNCATED_ON_MANUAL_TIMEOUT`表示按停止规则提前结束，余下时槽未执行；`TOOL_OR_PRECONDITION_FAILED`表示辅助或前置失败。监督回执成功与“探针正常/根因已定位”分开判断，清理失败不得绿色交付。原case结果未知时只查询，不重新派发。

最新诊断、截断和未执行项以[B.2报告](reports/rabbit-probe-observation-2026-11-07.md)为准，前两次失败保持。C[最小处理提案](rabbit-health-treatment-design.md)已采用，C.1因缺客户端结束；下一项仅依赖变更方案定稿，未自动第四轮诊断、改探针、重跑C.3或开始Pilot。
