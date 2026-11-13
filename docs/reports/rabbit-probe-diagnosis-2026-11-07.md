# D.7.1-B 单次探针诊断记录

日期：2026-11-07。**辅助已实现，唯一一次诊断因辅助就绪错误失败，固定观察/候选检查未完成；修正已通过相关短检查，没有第二次诊断。** D.7.1-B和C.3/B.2仍未收口，原Rabbit问题未定位/未修，未改探针或启动Pilot。[入口](../rabbit-probe-diagnosis.md) / [已采用范围](../rabbit-probe-diagnosis-design.md) / [结构化证据](rabbit-probe-diagnosis-2026-11-07.json) / [路线图](../roadmap.md)。

## 唯一一次实际执行

case `case-b9b27f8f0da94279`，manifest `bb38831cb7540a0d2e14296e9351318657a30baa1f01d9df023d1a624dffa8cd`，原B.1监督/原C.1账本，总480秒含最后120秒清理；实际 **101.072秒**，原回执FAILED且cleanup_verified=true。job自身98.213秒，失败码BROKER_PROTOCOL_READY_DEADLINE；未重新派发此case或另起诊断补样本。

白名单7文件，新增三个诊断Python和一个独立CpuWindow Java；原case_runner、measurement_resources和measurement_runtime复用。固定三正式release/JAR/镜像保持；只启动原4.3.6 Rabbit，原组合healthcheck、5秒/5秒/30/15秒与640MiB/no-swap/CPU0、1不改。原Init产生配置但未启动其余六服务，未建业务数据库或操作订单/券/文件/消息。

目标Linux原版相关用例 **14项全部通过**，包括实际POSIX短路；工作者Java编译和1秒自截止检查通过。128MiB/heap32MiB/CPU0且最多1核、无网络的工作者容器已登记并创建，**始终未启动**，没有60秒竞争工作量。主业务JAR未重建或覆盖class。

## 观察到了什么

准备阶段已持续保留原探针Start/End/ExitCode与Output安全分类。共10个不同完成结果：第一条exit69，分类APPLICATION_NOT_RUNNING；后面**9条exit0**，宿主按Docker Start/End计算的完成时长为 **3.246～3.529秒**，最终Docker healthy/失败计数0。没有本轮明确的DOCKER_TIMEOUT分类。

这说明本次单Broker启动观察期间原探针可以成功，**不能证明七服务C.3条件下问题已修或排除资源争用，更不能将未知旧根因直接归为探针误报**。没有第二窗口或分段CLI数据，不能给出两条CLI各自耗时结论。

保留90份宿主样本，其中18份包含cgroup详细资源；采样到的Broker内存最大约206.234MiB，观测OOM/Swap0、宿主oom_kill未增加。无工作者运行、无业务流量，不是完整峰值/容量/性能证据。所有原始探针输出均未原样保存，只有安全分类/长度/允许的指纹。

## 为什么没有进入窗口

诊断辅助导入`http.client`后又定义同名`http`函数，遮蔽了模块；子进程在调用HTTPConnection构造器前发生AttributeError。原代码将它安全归类为HTTP_UNAVAILABLE，准备循环因此直到90秒退出。

对照实际派发helper的SHA256 `96ec42fb623343028add81b7af60367e358fbc8389ef68091ac8d0184a8a657d`，在无服务条件下用同字节源码重现了相同AttributeError，且全局http确实解析为函数。因此这是**辅助代码缺陷，不能据就绪失败认定Broker HTTP不可用**。另有记录缺口：原版只在就绪成功后保存轮询明细，本轮失败明细没有落盘，不能虚构逐次HTTP状态或说已拿到实际认证响应。启动观察的原探针/资源样本仍完整留存。

原FAILED回执和原7份派发输入保持，没有热改运行文件。修正为独立导入HTTPConnection；准备循环每次尝试即原子保存`protocol-attempts.json`。后者的实际Broker失败落盘效果仍待新一轮验证，不给旧case补写原本没有的证据。

## 修正后的定向验证

在独立私有短检查目录 `/home/campusdeploy/.cache/campuslife-rabbit-probe-checks/92e5605d8b32` 上传三份修正源码并核对摘要；未运行case_runner、Init或Docker服务。目标Python3.10下 **16项全部通过、无跳过**，用时1.370秒；覆盖HTTP连接实际构造/关闭和真实127.0.0.1受控服务器的有界HTTP子进程，也保留敏感输出、探针去重、JSON/权限分类、子进程截止及POSIX短路检查。

该回环服务器返回预设JSON，不是Rabbit；它证明修正后的HTTP路径能运行，**不证明4.3.6 ready权限/语义或真实vhost兼容已验证**。运行入口仅语法/摘要核对，没有用修正入口再次启动Broker。本机最终16项中14通过、两项Linux专属跳过，不能与目标16项相加。工作者本机另验证非法0秒拒绝及1秒自停，不是60秒运行证据。

## 未执行、清理与下一项

两个60秒窗口、6次手动CLI、6组窗口HTTP/TCP、6次候选正常/无/错误凭据检查和容器内客户端盘点**全部未执行**。12个窗口时槽均保留NOT_EXECUTED及准备截止原因；候选HTTP是否认证、是否适合替换原探针仍未知。没有正常业务请求、C.3/Pilot或生产探针修改。

独立只读审计：原scope CLEANED，job/cleanup/监督进程结束，work与生成秘密空；已采集的34个容器进程PID/启动标识均不再运行，云端容器/卷空、仅三个默认网络。三正式release有效输入/JAR/镜像及历史17/9/5保持，本机463正式输入、23份既有辅助源和原JAR保持，本机Docker未启动。新修正三文件与目标短检查摘要相同，原7文件的云端快照和失败回执不变；不是一次修正后真实Broker验收通过。

**建议下一项D.7.1-B.1：取得新单次执行授权后，只用已短检的修正版再执行原480秒范围。** 不加预算、样本或组件，不自动把本轮当完成，也不直接提换探针的结论。本轮遵守“一次诊断、不自动补跑”边界停止于此；后续拿到固定窗口/分段与候选证据后，再决定必要的D.7.1-C最小处理或回C.3。前端暂停，无公网/常驻或旧SSH操作。
