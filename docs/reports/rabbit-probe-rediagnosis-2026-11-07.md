# D.7.1-B.1 修正版单次诊断记录

日期：2026-11-07。**本轮唯一一次新诊断仍在准备阶段失败；已定位为诊断对照端点选型错误，修正后18项目标Linux短检查通过，未追加第三次Broker诊断。** 原Rabbit探针问题及C.3/B.2未收口，两个固定窗口与候选兼容验证仍未完成。[入口](../rabbit-probe-diagnosis.md) / [修订范围](../rabbit-probe-diagnosis-design.md) / [结构化证据](rabbit-probe-rediagnosis-2026-11-07.json) / [路线图](../roadmap.md)。

## 本轮实际执行与失败

case `case-27a0f54b72b346da`，manifest `140ef118550f36ebbd83c39b138eb0c6bd5e6dd8222c24f13ed3965c4434f7da`，原监督480秒总截止、最后120秒清理。实际 **101.298秒**，job内部98.578秒；原回执FAILED，失败码BROKER_PROTOCOL_READY_DEADLINE，cleanup_verified=true。原回执SHA256 `82cc0568464bdff5a702634fc50aea8eb8f8c937a8c033844c00281fc51d7793`。只执行本轮一个新case，没有重派或自动补样本；[前次101.072秒失败](rabbit-probe-diagnosis-2026-11-07.md)单独保留，不累加或改写。

七份白名单输入复用原监督/资源账本/指标辅助。只启动原固定4.3.6 Rabbit，原组合探针、5秒截止/5秒间隔/30次/15秒起始期、640MiB/no-swap/CPU0、1保持。原Init生成配置但没有启动其余六服务，无数据库、应用登录或订单/券/资金/文件/消息业务操作。工作者编译与1秒自截止检查通过；工作者容器创建后始终未启动，未产生60秒CPU竞争窗口。目标原版16项检查通过，用时1.101秒；这不能验证实际Rabbit端点权限。

上轮修正的HTTP调用已实际工作，且本轮每次准备尝试都落盘：**67次**中，前5次两个请求均HTTP_UNAVAILABLE，随后62次在相同正常凭据下overview均200且版本匹配、单vhost对照均401。此处的62是各端点响应次数，不是额外诊断case数。最后两个完整宿主调用分别约0.116/0.112秒。原始正文、密码及Authorization均未落盘。

## 端点选择错误与修正

本次我原设计将`GET /api/vhosts/{精确名称}`当作普通管理账号的认证/归属对照，这个前提不成立。固定4.3.6的[单vhost资源源码](https://raw.githubusercontent.com/rabbitmq/rabbitmq-server/v4.3.6/deps/rabbitmq_management/src/rabbit_mgmt_wm_vhost.erl)在is_authorized中统一调用管理员权限检查，包括GET；而正式`deploy_base.py`创建的campuslife_ops只带management标签、本轮vhost权限，没有administrator标签。同一凭据overview成功和该源码规则共同支持**对照端点权限不匹配**；不能把401直接解读为密码错误或Rabbit不可连接，也不能把它当作旧C.3 CLI失败的根因。

修正只影响诊断helper与相关检查：改用`GET /api/vhosts?disable_stats=true`，要求200、合法列表且本轮精确名称恰好出现一次。空列表、错误名称、重复名称、单对象和非法结构均拒绝，不靠“请求200”自动过关。固定4.3.6的[列表资源源码](https://raw.githubusercontent.com/rabbitmq/rabbitmq-server/v4.3.6/deps/rabbitmq_management/src/rabbit_mgmt_wm_vhosts.erl)使用普通认证、按用户可见范围返回vhost；disable_stats分支不附加统计。保留原账号标签/权限及原探针，不增加管理员权限。这是对已采用诊断方案的端点前提修正，原方案快照与两次失败证据均保留。

两份修正源在本轮case清理后才修改，未热改已派发7份输入。独立私有短检查目录 `/home/campusdeploy/.cache/campuslife-rabbit-control-checks/10025b378b6b` 下 **18项全部通过、无跳过**，用时1.468秒；覆盖实际有界HTTP子进程访问受控回环overview/列表路径，精确归属/非200/非法结构拒绝、秘密抑制、去重、截止与POSIX短路。Windows本机同套18项中16通过、2项POSIX检查跳过，不与目标机18重复累计。

短检查的响应是预设JSON，**修正列表端点尚未在真实Broker上验证**，正常/无/错误凭据对照与ready候选仍未执行。未将短检查通过写成专项诊断通过。

## 原探针与资源观察

准备阶段保留10次不同完成的原Docker探针：首条exit-1，后9条exit0；安全分类全为UNKNOWN，**没有明确DOCKER_TIMEOUT证据**。9次成功按Docker Start/End计算为 **3.233～3.588秒**。没有手动分段数据，不能推导两条CLI各自耗时或定位首条-1原因；本轮单Broker成功不能解释七服务C.3失败。

90份宿主采样、18份cgroup详细采样，Broker采样内存最大约183.992MiB；观测OOM/Swap0、host oom_kill0→0。不是完整瞬时峰值、CPU竞争、容量或吞吐结论。A/B窗口0，12个固定时槽均NOT_EXECUTED，ready候选/认证对照0；没有用准备阶段样本冒充窗口或补足原定样本。

## 清理与正式输入保护

最终只读审计核对两次诊断各自原回执、7份派发源及资源账本保持，均CLEANED、work已移除、监督/job/cleanup及本轮观察到的34个容器进程身份均不再运行。云端容器/卷/生成秘密均空，仅默认bridge/host/none网络；未启动本机Docker。

三个正式release的有效源460/462/463、JAR/镜像及历史夹具17/9/5保持。本机463正式输入/JAR及25份既有辅助源保持；仅本轮诊断helper和相关test两份源在失败后修正，run入口保持。未修改生产Java、DDL、依赖、正式部署配置、探针或账号权限，也没有重建业务JAR。

## 后续边界

下一项建议 **D.7.1-B.2：使用已修正对照端点，原范围一次新诊断**，仍480秒含120清理、两个固定60秒窗口、单Broker及原探针。获得下一次明确任务指令前不自动执行；若完成观察，再按证据决定最小处理与C.3恢复，不默认更换探针/增加预算或重跑C.3。Pilot、正式三批测量、M07尚未开始；前端暂停，无公网/常驻或性能结论。
