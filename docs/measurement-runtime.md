# B.2-C.3 七服务测量入口接线

状态：**代码已实现，最后一轮因原Rabbit探针连续失败而提前停止，C.3/B.2验收未通过。** 正常登录、首次详情、八前台和真实领券/重放尚未执行。以下描述实现与约束，不能作为这些路径已验证的证据；先定稿D.7.1限定探针专项，不重复云端试跑或开始Pilot。[实际记录](reports/measurement-runtime-2026-11-07.md)。

这是综合测量前的接线工具。它复用[资源账本](measurement-resources.md)、[V24夹具](measurement-fixture.md)、[请求驱动](measurement-driver.md)和[只读事实观察](measurement-facts.md)，不进入生产JAR。当前入口只接受C.3检查：FORMAL历史种子、一个库存20活动恢复、两个正常学生登录、六个首次详情访问、八个前台请求（七读一领）、结果轮询及一次原键重放。没有运行Pilot的60请求/库存2，也没有正式600请求批次。

## 准备和生命周期

原case监督器总900秒含最后120秒清理。每轮先以C.1登记独立ROOT和资源身份，再启动MySQL、两个Redis、Rabbit和Seaweed；调用原StorageInit设置本轮桶/marker/策略，它同时创建原edge/core/storage网络。工具容器只挂测量类/原156依赖、迁移/只读Lua和本轮私有资料。工具起初挂核心网络，已按原ID登记后再接同实例edge网络；HTTP固定经`nginx:8080`，组件端口不公开。Nginx沿原配置只绑定宿主回环，未新增公网入口。

夹具迁移与SQL合成历史在空库保护下完成并关闭写入连接；没有合成新券、受理或消费记录。本轮只恢复第一个活动，另两个仍PAUSED。原Resume main只调用一次，90秒及原业务预算保持。应用和Nginx沿固定正式制品启动，没有业务class覆盖或第二个Web实例。

登录通过正常auth接口，读取/me核对两个身份，并验证匿名访问订单401。密码只从生成文件读取，token只存在Java内存，不导出到宿主、argv、JSON或CSV。只读账号仍SELECT-only，app权限不扩张；不挂JWT、root、S3管理密钥给工具。

## 健康和资源

准备过程有界等待七服务healthy，随后固定60秒基线，不自动另选一个更好的窗口。整个基线保留健康与资源记录，要求七服务持续healthy、末尾三个本窗口已完成Rabbit探针成功且失败计数归零。按探针End去重；重复采到同一个失败不会算两次。

运行中Broker unhealthy或连续两次不同的已完成探针失败、组件/工具OOM、宿主oom_kill增加、容器/PID启动身份或服务集合变化都会停止新增前台。原Compose探针及其超时/重试不改。健康失败不得关掉探针继续跑；按[测量方案](comprehensive-load-design.md)与[D.7范围](cloud-operations-handoff-design.md)先处理必要条件。

宿主每次批量inspect已登记容器，复核服务标签/镜像和工具原ID，再由PID＋启动ticks及统一cgroup路径读取memory.current、可用memory.peak、Swap、OOM、CPU usage及pids.current；读取后再核对PID启动标识和cgroup归属。不把heap、RSS、容器内存混称，不为每容器启动docker exec。缺少内核peak保存null；身份或必要资源读取失败不伪造0。当前准备中的短命维护CLI未持续采样，不能据此报告CLI峰值。

宿主原子写健康心跳，含case/实例、15秒有效期、基线通过与stop；驱动在发送/轮询前读取。信号缺失、过期、超前异常、身份不符或stop均拒绝继续。工具不挂docker.sock或宿主根，宿主读数不通过HTTP暴露。

## 结果和失败处理

前台继续沿FIXED的3秒请求、90秒前台/60秒观察和250/25ms轮询口径，但本接线样本不用于性能结论。并发只用1；新SQL发行必须为1，HTTP观察成功后以实际受理ID/学生/原键核对SQL、Redis、outbox/消费及四队列；两次至少间隔5秒的稳定观察才能授权原键重放。HTTP 202不能替代持久发行，未知POST或删失不得绕过驱动的重放门槛。

原始请求CSV、观察/统计JSON及只读组件/宿主样本分别保存。失败时尽量保留允许名单内的诊断，不复制秘密目录或任意响应正文；原失败回执不能改写为成功。Compose结果未知或资源身份不符继续遵守C.1的保守拒绝，不根据一次空查询自动清理过关，也不自动重跑原case。

入口为Linux的`scripts/run_measurement_runtime.py`，必须由原case_runner准备/执行；它依赖特定正式release和镜像，不能直接在开发库运行。白名单包含8份测量Java、只读Lua、原runner/资源/夹具/C.2准备辅助及新增运行/健康/受控检查脚本。具体指纹、实际运行范围与未完成项见[报告](reports/measurement-runtime-2026-11-07.md)。
