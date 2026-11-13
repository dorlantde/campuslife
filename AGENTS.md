# CampusLife 协作约定

2026-11-12按用户明确要求整理简历亮点及分级问题，M07.1-B题库资料已交付：[回答要点版](docs/interview/highlight-question-bank.md)/[无答案自测版](docs/interview/questions-only.md)/[核对](docs/reports/interview-questions-2026-11-12.json)。四核心H1订单事务幂等/H2版本缓存/H3Lua异步券/H4可靠消息，备选H5有界发布指标/H6SQL/H7S3；各基础3/深入3/异常3，共63＋整体6＝69题，附短回答要点、源码/历史证据与易夸大边界。正文初稿四条映射H1/H3/H2/H4，指标与SQL/S3按实际理解替换。包含真实个人贡献/AI辅助/验证参与问题，不虚构独立研发或亲自执行历史测试；本人69题均未验收，题库交付不等于M07完成。下一项先H1～H4的12基础题自测，或从H3-B1/B2/B3逐题讲解，不再自动生成更多题或回服务器。仅文档与源码核对，463正式/29辅助/JAR/133旧证据文件保持，未联系ECS/启Docker/构建/运行新测试；前端暂停，服务器/镜像/综合测量继续延后且未完成，旧失败保持，无新性能或容量结论。以下为历史状态。

2026-11-08 用户明确选择A“先进入面试整理”，服务器/派生镜像和综合测量延后；原缺客户端/未知根因/未通过及历史回执保持，不自动安装/构建/再诊断。M07.1-A首版已交付：[证据地图](docs/interview/project-evidence.md)/[源码复盘](docs/interview/source-review.md)/[简历初稿](docs/interview/resume-draft.md)/[核对](docs/reports/interview-materials-2026-11-08.json)。核对真实领券三阶段、版本评分缓存、outbox租约/confirm/消费ACK、有界单线程、预约日期保护行及S3墓碑；E01～E07逐项指源码/历史报告，数字仅旧新隔离负载口径（完整成功p95三轮中位数，不是POST/线上QPS；缓存普通店可能变慢）。默认四条无数字初稿，附可选数字句及SQL/S3替换项，不虚构独立研发/运营量/真实支付。本人理解/复述未验收，M07整体和最终简历尚未完成；下一项仅M07.1-B本人讲解核对，从领券前三题开始，不默认回服务器。仅文档/源码核对，463正式/29辅助/JAR及132既有证据文件保持，未连ECS/启Docker/构建/运行测试；V1旧导读增加当前入口/纠正过时“未Redis/MQ”说明，未改业务。前端继续暂停，云端资源仅引用C.1历史清理非实时查询，无新性能/容量或公网常驻结论。以下为历史状态。

2026-11-08 D.7.1-C.1-R仅收尾选择提案定稿，待选择/未实施：[方案](docs/rabbit-client-image-design.md)/[核对](docs/reports/rabbit-client-image-design-2026-11-08.json)。结合用户求职定位及近期运维投入，推荐A保留原镜像/探针、提前M07.1证据与源码复盘/简历初稿，综合测量继续未完成；此顺序调整未采用，不称M07已开始。备选B仅原digest派生curl及必要新增包，原已装包版本/运行配置/核心程序保持，签名索引锁版本/hash后离线构建；OS实核前置、curl版本和包SHA未确定，网页详情/下载版本不一致不据此造锁。选B后下一项仅R.1一次构建+兼容600秒含120清理，原5秒/HealthGate不改；失败停止云端分支建议转M07，不自动重试/拆新支线；通过也须原C.2故障/部署集成后才回七服务。仅文档/本地指纹链接核对，463正式/29辅助/JAR/10旧报告保持，未联系ECS/启Docker/构建/测试或写Dockerfile/下载包。旧根因未定、C.1不兼容及原失败保持，选择前不安装工具/改镜像/进入C.2或改变M07前置；前端暂停，无公网常驻/性能结论。以下为历史状态。

D.7.1-C提案已由用户采用，C.1兼容关卡完成，缺客户端按规则停止：[报告](docs/reports/rabbit-health-compatibility-2026-11-07.md)/[入口](docs/rabbit-health-compatibility.md)。case-8f1d9d3fc764426e单次2.368秒（300含120清理），监督SUCCEEDED/清理成功≠兼容通过；INCOMPATIBLE_TOOLS/NO_HTTP_CLIENT。固定4.3.6 digest实际curl/wget缺席、timeout GNU9.4，sh/bash存在。只启动32MiB无网络/无秘密/无匿名卷降权盘点sh，未起Rabbit/Init/生成秘密/HTTP权限矩阵或C.2。两新兼容源复用原监督/资源/HTTP辅助共5派发；原资源守卫不改，盘点适配先登记固定镜像工具，instance=null。5项解析检查本机/目标通过（同套不累加），实际缺工具分支/清理通过，后半Broker/HTTP分支未验证。scope/work/进程/盘点容器/卷/秘密均清，三正式release有效源/JAR/镜像/旧夹具17/9/5及本机463正式/27既有辅助/JAR/8旧报告保持，本机Docker未启。原探针/5秒/健康门槛/权限不变，未装软件/构建或拉新镜像；旧根因未定。下一项仅C.1-R增加单一HTTP客户端的最小依赖变更方案定稿（比较原基础派生镜像与保留现状、固定来源/指纹/验证/回退），未采用新依赖，不自动重复盘点/手写HTTP/装工具/进C.2或回C.3。原C.3/B.2未通过，Pilot/正式/M07未执行，前端暂停，无公网常驻/性能结论。以下为历史状态。

D.7.1-C仅最小处理提案定稿，待采用/未实现：[方案](docs/rabbit-health-treatment-design.md)/[核对](docs/reports/rabbit-health-treatment-design-2026-11-07.json)。建议本机HTTP ready为优先候选，保留5秒/5秒/30/15秒与资源/HealthGate，明确与全部监听TCP检查不等价，不宣称旧C.3根因。C.1先固定镜像客户端/总截止/无凭据及六项权限关卡（拟300含120清理，只原Broker，缺工具即停）；C.2再隔离脚本及停止应用/缺AMQP/超时/恢复/60秒竞争验证（拟480含120清理）；C.3独立部署白名单版本适配后，另项回原七服务C.3。固定4.3.6 ready源码提供语义线索，当前Docker Library 4.3已4.3.7，不能推定旧digest工具；不自动安装工具/扩权/加秘密或手工HTTP协议栈。采用只从C.1开始，尚未批准实施，不自动第四轮原诊断/替换探针/放宽预算或回C.3。本轮仅文档/本地指纹链接核对，463正式/27辅助/JAR/7旧报告保持，未连ECS/启Docker/构建/运行测试；资源清理只引上轮事实非实时审计。C.3/B.2仍未通过、Pilot/正式测量/M07未执行，前端暂停，无公网常驻或性能结论。以下为历史状态。

D.7.1-B.2按用户“进行下一项”完成一轮有界诊断，第二窗口按规则截断：[报告](docs/reports/rabbit-probe-observation-2026-11-07.md)/[入口](docs/rabbit-probe-diagnosis.md)。case-4fa17982960d4cb6单次100.607秒/480含120清理，监督SUCCEEDED/清理成功≠完整窗口/探针正常；诊断TRUNCATED_ON_MANUAL_TIMEOUT。18目标短检/1秒工作者检查通过，真实overview及可见列表约9.490秒就绪。A完整60.008秒/8原探针7成功1个-1（1跨界）；B20.011秒/2原探针1成功1个-1，原输出均UNKNOWN。A组合/单running/单ports约2.740/2.730/1.474秒；B组合5.006秒外层截止，RUNNING_END0后PORTS_BEGIN无结束，不是纯第二段耗时或旧C.3根因。8/12时槽执行，剩4/认证矩阵/客户端盘点未执行，不补样；工作者已运行但未验证60秒自退。A首ready503而vhost200/TCP通，后来3组ready200；非同时于手动超时，不证明协议当时健康。89宿主/18Rabbit/4工作者资源采样、观察错误0、OOMSwap0；scope/work/54观测进程身份/容器/卷/生成秘密均清，三原诊断回执保持。三正式release有效源/JAR/镜像/历史17/9/5、本机463正式输入/27辅助/JAR及5旧报告保持，无业务/源码/权限/探针变化、本机Docker未启。下一项仅D.7.1-C最小处理方案定稿，比较保留CLI与轻量信号语义/认证/客户端和有界验证，不自动第四轮/换探针/放宽5秒/重跑C.3。C.3/B.2仍未通过，之后Pilot→正式逐批→M07；前端暂停，无公网常驻/容量性能结论。以下为历史状态。

D.7.1-B.1按用户“进行下一项”执行一轮修正版诊断，仍准备失败：[报告](docs/reports/rabbit-probe-rediagnosis-2026-11-07.md)/[入口](docs/rabbit-probe-diagnosis.md)。case-27a0f54b72b346da单次101.298秒/480含120清理，67次准备尝试（前5次HTTP不可用，后62次overview200/精确vhost401）；固定4.3.6源码确认单vhost GET需admin，原ops只有management，原诊断方案选端点错误，不是密码错误或旧CLI根因。诊断helper改列表/api/vhosts?disable_stats=true并精确名称校验，目标18项短检全通过（受控回环，非真实Broker）；未追加第三轮。原探针10次/9成功3.233～3.588秒、1次-1分类UNKNOWN，90/18采样、max184MiB/OOMSwap0，A/B/12时槽/候选未执行、工作者未启动。两次原FAILED回执与派发源保持，scope/work/进程/容器/卷/生成秘密均清；三正式release源/JAR/镜像及历史17/9/5、本机463正式输入/JAR/25既有辅助保持，仅helper/test两源失败后修正，不改生产/权限/预算。D.7/C.3/B.2未通过，下一项仅建议B.2修正端点后原范围一次新诊断，之后据证据回C.3→Pilot→正式逐批→M07；不自动再跑/换探针，前端暂停/无公网常驻性能结论。以下为历史状态。

D.7.1-B按用户采用已实现/单次执行，但准备失败：[报告](docs/reports/rabbit-probe-diagnosis-2026-11-07.md)/[入口](docs/rabbit-probe-diagnosis.md)。case-b9b27f8f0da94279，101.072秒（480含120清理），原FAILED/cleanup_verified=true，7输入快照保持。只起原Rabbit，观察10探针（第一69/应用未运行分类、后9 exit0/3.246～3.529秒），90宿主样本/18资源采样/OOM Swap0；CPU工作者只创建未启动。HTTP模块被同名函数遮蔽，构造连接前AttributeError；匹配实际派发源码指纹的无服务重现确认，原失败轮询没落盘，不能编造状态或称Broker HTTP故障。已独立导入HTTPConnection、每次尝试落盘；目标另16项短检（真实受控回环HTTP子进程/无Broker）通过，3修正输入摘要核对，未重新诊断。原14目标短检通过不能替代A/B；窗口/手动CLI/候选权限均未执行。四新源（3Python/1诊断Java），463正式/23既有源/JAR和三云端release/历史17/9/5保持；scope/进程/work/秘密已清、34已观察容器进程身份不再运行，云端资源0/默认网络，本机Docker未启。D.7.1-B及C.3/B.2未收口；下一项仅建议B.1修正版原范围新单次执行，须新授权，不自动追加样本/更改探针/重跑C.3，前端暂停。以下为历史状态。

D.7.1-A仅方案整理完成，待采用/未实施：[方案](docs/rabbit-probe-diagnosis-design.md)/[核对](docs/reports/rabbit-probe-design-2026-11-07.json)。建议B单次480秒含120清理，原固定4.3.6/640MiB/no-swap/CPU0、1，只启Rabbit，复用原B.1/C.1；无/有60秒128MiB单核CPU工作者两个60秒窗口，记录原探针全部可见结果/首5预定子集，每窗口仅各1次组合副本/单条CLI，各外层5秒，超时停后续样本并核对/回收自有容器。原Docker探针、权限、秘密挂载保持；不加业务或新镜像。4.3.6源码就绪端点未声明认证回调，不能预设认证；ready＋精确vhost HTTP观察正常/无/错凭据，宿主HTTP可用不证明容器客户端/探针适用，不默认扩权。D.7.1-B只实现辅助/相关短检查并诊断一次；C最小处理另据证据讨论，不自动第五轮C.3/Pilot或换探针。未知/不足可结束专项，不承诺找到旧根因。纯文档，未连ECS/启Docker/构建或测试，本机463正式/23既有辅助源/JAR保持；云端空资源仅引用上轮审计，非本轮查询。C.3/B.2仍未通过、前端暂停，无公网/常驻。以下为历史状态。

M06.2-B.2-C.3接线已实现，验收未通过：[报告](docs/reports/measurement-runtime-2026-11-07.md)/[说明](docs/measurement-runtime.md)。最后case-e8d9047fa1904b96，270.841秒（900含120清理），原七服务healthy/StorageInit/一次原Resume51.622秒及空发行两稳定事实检查到位；拟60秒基线在6.069秒因最近两个不同End探针exit=-1、Rabbit失败计数1→2而拒绝，前一End在基线前，后一在窗口内。没有探针Output，不能认定本次具体超时或与旧故障同因。未发go，登录/首次详情/八前台/真实领券/重放未执行，不是C.3/B.2通过。11目标Python健康用例、12云端Java信号、另13本机持久JSON用例通过，不能替代整链。6宿主/4组件样本，OOM/Swap0，仅轻量观察。前三轮缺edge、无效create参数及BigDecimal值树失败保留；第二轮原CLEANUP_FAILED在确切CLI解析拒绝/原进程退出/原ID证据后事后回收5容器5卷3网，原回执不变，不放宽未知规则。最终四账本/进程/work/资源/秘密已清，三正式release与历史17/9/5、本机463/18既有源/JAR保持，本机Docker未启。新增3Python/2测量Java，无生产更改。下一项仅D.7.1有界Rabbit探针专项定稿（旧拟480秒范围，先采原两CLI各自证据/HTTP候选语义，不默认更换/加预算）；其后回C.3→B.3 Pilot→正式逐批→M07。不自动第五轮/公网/常驻，前端暂停。以下为历史状态。

M06.2-B.2-C.2指标/发行事实观察已验证：[报告](docs/reports/measurement-facts-2026-11-07.md)/[说明](docs/measurement-facts.md)。最终case-3d97454eea1b4ac6单次175.919秒/37项/9采样通过，600含120清理；四组件＋一次原Resume main，SQL合成2券/请求/outbox/消费后Redis重建，不是正常HTTP领取或正式负载。SELECT-only RR、EVAL_RO、四队列GET、身份/代次/守恒及两次5秒稳定观测；不一致/ready/unacked/缺队列拒绝，指标丢失/重置/截断不可用。首轮宿主timeout参数TypeError失败和中间37通过保留，最终不拼接；三轮scope/进程/work/资源/秘密均清，三正式release及历史17/9/5、本机463/12既有源/JAR保持，本机Docker未启。三个新测量Java/只读Lua/宿主入口，无生产改动。C/B.2仍未整体完成，下一项仅C.3真实登录/健康停止/宿主采样/七服务总接线，再B.3 Pilot→正式逐批→M07。Rabbit/SSH观察、前端暂停、无性能结论/公网/常驻保持。以下为历史状态。

M06.2-B.2-C拆为C.1资源/C.2指标事实/C.3总接线；本轮C.1已验证：[报告](docs/reports/measurement-resources-2026-11-07.md)/[说明](docs/measurement-resources.md)。最终case-bf78851ad4a74b07单次37.116秒/30项/10scope/4真实子进程SIGKILL，通过且原回执清理成功；未启动DB/应用或请求业务。账本先于私有文件，固定部署实现仅当前进程ROOT转到case内，正式源/config/build不改；未知缺席或未落账Compose必须拒绝，不能靠空查询自动过关。3.884秒CLI参数失败与26.118秒测试注入物删除未记账导致CLEANUP_FAILED保留，后者仅按原ID证据事后清理，不改原失败；最终不拼接。最终三轮18账本均已清、进程/work/容器/卷/生成秘密0，三正式release源/JAR/镜像及历史17/9/5保持，本机463/9既有源/JAR保持，两个新宿主脚本，Docker本机未启。未知结果测试修复属harness，不是任意迟到创建或主机重启自动恢复；helper拒绝规则保持。C/B.2整体未完成，下一项仅C.2组件指标/真实发行排空核对，再C.3登录/健康/七服务入口→B.3 Pilot→正式逐批→M07。Rabbit/SSH观察、前端暂停、无公网/常驻/性能结论保持。以下为历史状态。

M06.2-B.2-B请求/轮询/统计受控验证完成：[报告](docs/reports/measurement-driver-2026-11-07.md)/[说明](docs/measurement-driver.md)。最终case-083cc10a34714343完整54项/21.312秒，Windows JDK21.0.12.1临时127响应端、heap128/CPUcount2；C1/5/10各600受控请求及20观察成功/40售罄/6重放，非正式三批或性能结论。保留首轮回环受限18后失败、两处辅助编译错误及中间52通过，最终不累加。FIXED3/90/60秒、250/25ms保持，完整响应体截止/256KiB、UNSENT/UNKNOWN_POST/删失/迟到终态、事实重放和JSON/CSV验证；重放须外部verifiedDrain明确批准，HTTP不推导SQL实际发行。四新Java＋检查脚本，独立测量包；463正式输入/JAR/两份V24夹具保持。JVM已退出、12原始文件留证，不连接DB/ECS/项目服务、不启Docker；云端空资源仅上轮事实无本轮实时审计。B.2整体未完成，下一项仅B.2-C指标/登录/健康信号/真实排空核对与七服务生命周期，含初始化登记/部分创建/未知结果及准确清理；之后B.3 Pilot→正式逐批→M07。Rabbit/SSH观察与前端暂停保持，不默认公网/常驻。以下为历史状态。

M06.2-B.2因工作量拆成A夹具/B驱动统计/C资源接线，本轮A已验证：[报告](docs/reports/measurement-fixture-2026-11-07.md)/[指南](docs/measurement-fixture.md)。单次case-28976ef913ef4dfa共51.216秒（360含120清理），24项Java＋6宿主负面检查通过；独立MySQL/工具，无Spring业务上下文或HTTP。V23拒绝→V24/32空表→完整种子注入回滚→正常提交→非空拒绝/事实保持。1000店/600公开、180学生均有历史、11000订单/支付/评价及55000事件，3库存20活动ASYNC/PAUSED、零发行/新资金/消息；全为SQL合成事实，未Resume。PILOT分支定义未实测。四新增源码＋原runner独立云端白名单；原463/旧V23 guard/本机JAR及三云端release源/JAR/镜像保持。最终本轮登记进程/work/容器/卷/生成秘密均空，历史夹具17/9/5；本机Docker未启。普通收尾通过不证明初始化强杀/未知创建/七服务清理，留C；B.2整体未完成，下一项仅B.2-B请求/轮询/统计，之后C→B.3 Pilot→C1/C2/C3/D→M07。Rabbit/SSH观察保持、前端暂停，无性能结论或常驻/公网。以下为历史状态。

M06.2-B.1按用户“进行下一项”采用[M06.2方案](docs/comprehensive-load-design.md)，只实现case监督/回执底座：[报告](docs/reports/case-runner-2026-11-06.md)/[指南](docs/case-runner.md)。Linux16/16短任务通过（26.739秒外围），另一次真实SSH客户端主动关闭时任务仍RUNNING，原case取回SUCCEEDED、仅执行1次/清理成功，3轮查询重放回执保持；不是D.6i Connection reset复现或修复。固定输入/启动标记/身份、pidfd精确清理、总截止含清理及原子回执，UNKNOWN不接管/重跑。只拥有可信脚本会话与work，Docker/数据资源适配未做，SIGKILL/宿主重启不保证自动清理。3新增脚本在独立云端工具目录；463正式输入/本机JAR/旧入口、三云端release有效输入与JAR镜像保持。最终本轮登记存活进程/work均0，云端容器/卷/生成秘密空，旧17/9、新4业务夹具保持；16技术case目录留证，本机Docker未启。未启七服务或测量，无新性能结论。下一项仅B.2测量工具/V24夹具，B.3 Pilot及C1/C2/C3/D随后；Rabbit观察保持、前端暂停，不默认公网/常驻。以下为历史状态。

M06.2-A仅方案整理完成，待采用/未实现：[方案](docs/comprehensive-load-design.md)。推荐现有ECS固定6311.../7656...七服务，额外256MiB临时测量工具（原镜像/已有库、非第二业务实例）及专用运维挂载均为建议。1/5/10前台并发，3批平衡/9段5400请求，60/20/10/10详情/列表/本人历史订单/ASYNC领取；202与实际完成分开、观察截止右删失保留，闭环批测非容量。原V23/本机目录guard不放宽，新V24夹具显式合成历史/ASYNC PAUSED，原main Resume在计时外。拟Pilot900秒、正式每批1200秒均含最后120清理，不改业务CLI/原预算；全批尚未执行。下一项采用后B.1仅case监督/回执底座短任务，不启动七服务；B.2工具/夹具→B.3 Pilot/健康条件→C1/C2/C3逐批→D汇总→M07。D.7探针与回执观察保持，不自动修探针/加预算或启动长期公网实例。纯文档，未联系ECS/启Docker/构建/运行测试；463构建输入、既有代码与本机JAR保持，前端暂停。以下为历史状态。

M06.1-D.7仅方案整理完成，实施建议待采用：[方案](docs/cloud-operations-handoff-design.md)。按用户澄清，常驻/现场演示非必选，建议维持无常驻实例；下一项M06.2-A综合负载范围定稿，不自动实施整个运维分支。原Rabbit两CLI串行共用5秒预算已核对，4.3手册/当前4.4监控与HTTP说明仅提供语义/候选参考，固定4.3.6兼容和超时根因未验证。D.7.1有界单Broker专项（拟480秒）/HTTP等候选及秘密挂载变化待另采用；D.7.2按case启动/查询/原子回执要求随测量方案定稿，不宣称已具备。D.6i收口和探针/SSH未定因观察保持。纯文档，未联系ECS/启动Docker/运行测试；463构建输入、运维代码与本机JAR保持，云端清理仅引用上轮审计非实时查询。路线图纠正将已完成SQL/Redis/MQ等仍列待开发的旧当前段落，保留历史报告。后续方案→必要条件验证→有界测量→M07；前端暂停，不默认公网/长期实例。以下为历史状态。

M06.1-D.6i单次定向验收通过，D.6维护整改/剩余断言收口：[报告](docs/reports/cloud-final-remaining-2026-11-06.md)/[范围](docs/cloud-final-remaining-acceptance.md)。case-f3b1fc89a03e，841.056秒，32项＋5组配置；正式JAR6311.../镜像7656...原main初次Resume及原五卷重启后Resume成功（71.344/76.285秒为整CLI，未采预检次数），两次持久202实际发行，库存2/券2/消费2、Redis2/0/0/2，旧会话/幂等/24凭据与LOCAL/S3身份保持，无订单/资金/文件交易。73采样常驻最高1.121GiB/含CLI1.298，host oom0→0/Swap约4.023MiB；最终资源/配置保护、13类凭据及密码/4token日志钩子通过。保留观察：Rabbit重启前unhealthy/探针连续40失败（近期超时），重启后healthy仍有10/17次超时，原因未定/未修；SSH Connection reset导致原本机空回执，确认原监督存活后取回同轮exit0回执，未重跑/增预算。仅测试入口指纹/标识/失败SQL证据变化，463正式输入、三版本源码/既有补丁/7辅助/JAR镜像保持；资源/生成秘密均空，旧17/9、新4夹具留证，无运行任务，本机Docker未启动。不是一轮新整链通过，原三轮及D.6e失败保持。下一项仅建议D.7云端观测与私有演示交接方案定稿（评估探针/回执与是否保留实例），不默认修探针/长期实例/公网；M06.2/M07随后，前端暂停。以下为历史状态。

M06.1-D.6h正式云端构建/静态核验完成：[报告](docs/reports/cloud-initialization-build-2026-11-06.md)。463项/653227字节包→独立release `/home/campusdeploy/campuslife/releases/1cf0cb53a24a`；相对D.6d清单460保持/配置＋既有deploy_base权限补丁两变化/新增配置测试，相对有效D.6e仅配置变＋新测试。单次88.580秒（Maven1:06），285主/119测试编译，测试未执行。JAR6311c16a.../镜像 `campuslife-base:m061a-6311c16a12b2-468b0df58bc6` / ID76569040...；与D.6g本机649条目内容全同、394时间戳不同，156依赖保持；User10001:10001/linux-amd64/JRE层/标签通过。两旧release源码/已记录补丁/7辅助/JAR镜像保持；新目录显式链接原Linux Maven缓存。仅打包工具增加任务标签，业务/DDL/pom/前端未改。未Init/运行业务；最终容器/卷/测试与探针网络/生成秘密均[]，旧17/9夹具/新0实例目录，无运行任务，本机Docker未启动。D.6仍未通过。下一项仅建议D.6i先核对/更新测试入口制品指纹及自有范围，再一次新正式包原main Resume/原五卷重启/新领券及剩余资源安全钩子；不替换业务class、不第四轮打印整链、不加预算/默认公网或长期实例，前端暂停。以下为历史状态。

M06.1-D.6g按用户“进行下一项”采用[方案](docs/maintenance-initialization-budget-design.md)，已实现/定向验证：[报告](docs/reports/maintenance-initialization-budget-2026-11-05.md)。仅CouponRedisConfiguration一份生产源变化，显式维护profile初始化/URI及同步等待5秒，固定命令expiry500ms；HTTP/后台500、TCP300、两次预检、lease30、CLI90保持。真实Redis最终完整5项通过：延迟响应1200ms旧配置失败/候选成功，激活后暂停2500ms的INFO在541ms超时，错误认证/拒绝连接有界；前两轮内部栈帧断言失败留证不计数。14单元＋12真实MySQL及新JAR启动通过；本机JAR67b6c80b.../649条目仅两配置class变、156依赖保持。云端正式JAR4df.../镜像8d65...未更新，无原main Resume/重启/新领券验收，D.6仍未通过。云端有效462输入/7辅助/旧制品保持，容器/卷/测试网络/秘密均空，旧17/新9业务夹具不变，本机mysqld进程0、Docker未启动。下一项仅建议D.6h白名单独立release构建/静态核验，之后再剩余定向验收；不加预算/重试、不第四轮整链，前端暂停。以下为历史状态。

M06.1-D.6f单次诊断已完成：[报告](docs/reports/cloud-preflight-trace-2026-11-05.md)。264.072秒，正式JAR4df.../镜像8d65...不变，仅2诊断输入23842字节；technical零发行夹具＋原维护配置/Bean，额外观察main非原Bootstrap.main，不计业务验收。预检第1次2696ms失败，根帧RedisHandshakeHandler.lambda$channelRegistered$0，非INFO执行超时；第2次768ms成功、未中断，原恢复完成OPEN gen4/v7无遗留票据。D.6e最终预检拒绝未复现，不能称已修复或旧异常全同因。已核对同字节库lettuce7.5.2/SpringDataRedis4.1.1：commandTimeout500ms进入RedisURI并复用于channelRegistered启动的初始化计时器；监听会影响类加载/时序，事件时戳非包时间。密码与挂载文件匹配/预期账号主机端口均真，ACL拒绝记录空；不输出参数/message/URI。新462有效输入（461原项＋既有部署工具补丁）、7辅助、新旧制品保持；最终containers/volumes/test networks/生成secrets均[]，旧17/新9夹具留证，无运行任务，本机Docker未启动。D.6e/D.6仍未通过。下一项仅建议采用[D.6g维护初始化预算隔离提案](docs/maintenance-initialization-budget-design.md)后先隔离验证：只维护profile初始化5秒，TCP300/已连接命令expiry500/两次预检/lease30/CLI90保持；同步等待也受配置影响，须真实暂停测试证明命令不变，否则停止讨论，不能直接生产调参。提案待确认/未实施，不自动再诊断/定向或第四轮整链；D.7/压测/前端等待。以下为历史状态。

M06.1-D.6e本轮执行结束，**未通过，D.6仍未收口**：[报告](docs/reports/cloud-remaining-acceptance-2026-11-05.md)/[范围](docs/cloud-remaining-acceptance.md)。两次均定向非打印整链：209.255秒在Admin初始化拒读/run/admin；umask0077将mkdir0755变0700，无凭据/nonroot探针复现1→0，deploy_base.py显式chmod0755修复，私有父目录700/文件444保持。第二次455.159秒，13检查＋5配置，正常Admin/商家审核/活动发布及Pause/Async完成；初次Resume返回COUPON_REDIS_PREFLIGHT_FAILED。该拒绝发生票据前，但每次尝试/底层类型未记录，不能认定两次都超时或旧异常同因；现场已按测试规则清理，无事后SQL门闩快照。首次发行/原卷重启/重启后新领券/最终资源与13类凭据钩子均未执行。末次40采样常驻最高约1.01GiB/含CLI1.23GiB，host oom0→0/Swap约2.77MiB，仅观察非资源验收。新release462原输入461保持＋唯一已记录deploy_base工具补丁（69dafb6e...），Java/DDL/pom/前端/预算/JAR4df.../镜像8d65...不变；8有效输入135210字节核验。最终containers/volumes/test networks/生成secrets均[]，旧17/新8夹具目录留证，无运行任务，本机Docker未启动。下一项仅建议D.6f正式包预检分阶段安全诊断（每次异常类型/耗时/中断与初始化证据，必要时测试观察类，不替换生产类），先定位、不增重试/300/500ms/30秒/90秒，不自动第三次定向或第四轮整链；尚未开始。D.7/压测/前端等待。以下为历史状态。

M06.1-D.6d修复版云端构建/静态制品核验已完成：[报告](docs/reports/cloud-preflight-build-2026-11-05.md)。白名单462项/652140字节包→独立release `/home/campusdeploy/campuslife/releases/5382d9dd9ca2`，逐项摘要核对；相对D.5为457保持/两维护类＋既有mysql-init变化/两新增测试。单次91.229秒（Maven1:09），285主/118测试编译，测试未执行。新JAR SHA4dfd3a9f...、镜像 `campuslife-base:m061a-4dfd3a9f1efd-468b0df58bc6` / ID8d654a79...，与D.6c本机新JAR649条目内容全同、394时间戳不同，156依赖保持；User10001:10001/linux-amd64/JRE层/标签通过。原release110d...源码/制品及四份测试补丁保持；新目录显式链接原Linux Maven缓存（缓存可更新，运行镜像不依赖）。仅打包工具增加可选task标签，业务/DDL/pom/前端未改。本轮未Init/迁移/运行业务；最终containers/volumes/test networks/生成secrets均[]，旧17夹具/新0实例目录，本机Docker未启动，无运行任务。**D.6整体仍未通过，勿启动第四轮整链。** 下一项仅建议D.6e新正式制品的定向七服务就绪/原五卷重启后Resume与新领券及剩余资源/安全钩子，先核对入口/自有数据范围；不替换挂载业务类、不重跑完整打印交易或冷备/压测，不默认公网/长期实例，前端暂停。以下为历史状态。

M06.1-D.6c按用户“进行下一项”采用[预检方案](docs/coupon-maintenance-preflight-design.md)并已实现/定向验证：[报告](docs/reports/maintenance-preflight-2026-11-05.md)。仅两份生产类变化；ASYNC Resume及ASYNC→SYNC维护票据前预检，同类连接超时最多再读一次，中断停止、失败安全码且不写门闩，成功仍原事务复核版本；HTTP/后台/300/500ms/30秒/90秒保持。11故障＋24真实MySQL/装配检查通过，本机新JAR2bc27269...已启动，649条目仅两业务class变、156依赖保持。首轮受限attach管道失败留证不计数。云端单次282.845秒，暂存新源码类路径优先执行Resume→新HTTP异步请求实际得券→Pause/Sync/SYNC Resume通过；两个冷JVM首次均成功，未自然触发重试。观察器Mapper采样不保证当前读，门闩用外部SQL/真实MySQL测试断言。云端旧release/JAR2ad.../镜像c27...未替换，459原输入及已记录补丁保持；四份暂存输入29942字节已核验。最终云端containers/volumes/test networks/生成secrets均[]，17夹具目录留证，本机自有MySQL进程0，Docker未启动，无运行任务。**D.6仍未通过，勿启动第四轮整链。** 下一项建议D.6d独立新release白名单云端构建/制品核验，不上传整个工作区或大JAR、不启动业务/公网；之后剩余实机断言，前端暂停。以下为历史状态。

M06.1-D.6b只读对照已完成：[报告](docs/reports/cloud-connection-comparison-2026-11-05.md)。单次398.865秒，新技术夹具/原五卷重启，原维护上下文连续两次INFO：首次3619ms连接超时（同D.6a异常类型链），第二次311ms成功且runId匹配；两次后完整SQL门闩均保持PAUSED gen5/v8、无票据，外部SQL/Redis计数保持1/1/0。没有调用Resume/取票据，也没有修复生产逻辑，不能追认旧泛化异常同因或承诺第二次总成功。新增诊断Java/Py共16424字节；D.6a诊断源码/原459个D.5输入及既有补丁、JAR/镜像保持。最终containers/volumes/test networks/生成secrets均[]，16历史夹具目录留证，无运行任务/长期实例，本机Docker未启动。**D.6仍未通过，勿启动第四轮整链。** 下一项建议D.6c按[预检提案](docs/coupon-maintenance-preflight-design.md)先确认再实现：仅ASYNC Resume及ASYNC→SYNC维护在票据前预检，同类连接超时最多额外尝试一次，失败不改门闩，成功仍原事务复核版本；不增预算/租约、不推广到HTTP或后台。方案尚未采用/实现；D.7/压测等待，前端暂停。以下为历史状态。

M06.1-D.6a专项诊断已完成：[报告](docs/reports/cloud-resume-diagnosis-2026-11-05.md)/[操作](docs/cloud-resume-diagnosis.md)。单次371.011秒，技术已发券夹具＋原五卷重启后调用原Resume Bean，首次Redis runId/INFO冷连接失败：RedisConnectionFailureException→RedisConnectionException→RedisCommandTimeoutException（4013ms）。SQL已从PAUSED gen4/v7提交到REBUILDING gen5/v8、保留OPEN意图，失败时租约剩约25.725秒；未进入prepare/seed/activate，已发1/持有券1/处理中0保持。不是本次版本冲突或租约到期，但不能追认此前两次泛化失败同因，具体握手/资源原因未定。仅新增测试Java/Py共18474字节，原预算/规则/业务Java/JAR/镜像保持；其余459个D.5输入及已记录测试补丁核对通过。最终containers/volumes/test networks/生成secrets均[]，15历史夹具目录留证，无运行中任务/长期实例；本机Docker未启动。**D.6仍未通过，勿启动第四轮整链。** 下一项仅建议D.6b同维护上下文冷/热连接只读对照（最多两次INFO、先不取得SQL票据，不盲重试Resume），据证据提出最小修复；尚未开始，不默认延长预算/租约。D.7/压测/材料等待，前端暂停。以下为历史状态。

M06.1-D.6本轮验收执行结束，**整项未通过**：[报告](docs/reports/cloud-host-acceptance-2026-11-05.md)/[操作](docs/cloud-host-acceptance.md)。无运行中测试，勿启动第四轮整链。三轮57.483/643.016/1152.169秒，第三轮23项生命周期断言及5组配置通过（不拼接前两轮），完成20MiB用券打印/通知/取件评价、原五卷重启旧事实/会话/文件/幂等、重启后新S3读写；最后ASYNC Resume泛化报错，新领券及最终资源钩子未执行。原两次泛化根因未完整定位；隔离维护诊断CONTROL_VERSION_CHANGED不能直接当全部根因。已修复MySQL初始化source泄漏set-u（子shell）、旧内核memory.peak缺失（null+采样）、短命CLI list/inspect竞态（窄重列）；测试补SQL/Redis代次就绪等待32.969/2.230秒，不改版本/租约/超时。第三轮101采样，常驻最大约1.30GiB，host oom_kill0→0、Swap最大约0.76MiB，非容量验收；最终资源断言未跑。最终云端containers/volumes/test networks/生成secrets均[]，14夹具目录留证；无长期实例。本机Docker未启动、无公网/冷备/旧机操作。D.5仅mysql-init部署shell变更，其余459输入及业务Java/DDL/pom/前端、JAR2ad77758.../镜像c27d0286...保持；3份测试Py及2诊断Java单独记录。下一项仅建议D.6a重启后Resume专项安全诊断（复用隔离入口，必要时安全异常分类；先取确切根因，不先改预算/规则），尚未开始；D.7/压测/材料随后，前端暂停。

M06.1-D.5已完成：[报告](docs/reports/cloud-build-2026-11-04.md)/[操作与范围](docs/cloud-build.md)。460文件白名单包（648759字节）经严格SSH上传，新版本目录`/home/campusdeploy/campuslife/releases/110d141b50c2`逐文件核验；不含开发目录/密钥/业务数据/前端/旧制品。新机单次原Build成功约444秒，Maven日志6:56，构建堆768MiB；编译285主源码/116测试源码，测试未执行。云端JAR SHA=`2ad77758b9f8fc2bc070a19bf169ff989b978df32c66e7e04eae01eb96f7bb71`；与原本机649文件条目内容全部一致（156依赖/388业务类资源），394条时间戳不同，非整个JAR字节相同。应用镜像`campuslife-base:m061a-2ad77758b9f8-468b0df58bc6`，ID=`sha256:c27d02862aebbda20b37004bff4a105551fc1e3ff538c613d81945c05efb4512`，JAR标签/linux-amd64/User10001:10001/原JRE层通过。源460摘要保持，云端containers/volumes均[]，源码/构建记录/Maven缓存/镜像保留；未Init/建库/运行/开放端口，未启动本机Docker，业务Java/DDL/前端未改。下一项建议D.6：先核对compact4g新实例测试入口/自有虚构数据范围，再进行七服务实机交易、资源及原卷重启新写验收；不默认公网、不重跑冷备/压测；该项尚未开始，前端仍暂停。以下为历史状态。

M06.1-D.4已完成：[阶段记录](docs/reports/cloud-tools-2026-11-04.md)/[操作记录](docs/cloud-tools-setup.md)。JDK21.0.12.1/Maven3.6.3/Docker29.9.3/Compose5.7.0/Buildx0.38.1及campusdeploy已验证；账号/Docker组曾被自动审批拒绝，用户明确批准后才创建，root策略/sudoers未改。**六种原固定公开基础镜像6/6就绪**：用户侧SCP上传后，包大小/SHA256及原镜像ID/RepoDigest/linux-amd64/RootFS全部一致，Redis不重传/重导入；云端containers/volumes均[]。Redis此前无网络128MiB/cgroup探针通过，另外五份仅load/inspect，不冒充七服务运行。93个blob为导出校验数，不是业务测试数。保留原pull/SSH/SFTP失败证据，网络根因未明，无速率收益结论；未改DNS/hosts/TLS/镜像源，未加云服务。此前临时本机Docker导出后正常退出，本轮未启动。下一项建议M06.1-D.5：先核对源码白名单包与大小，排除.local/.cache/.tools/.vscode/密钥/业务数据，再传输和验证目标机JAR/应用镜像构建；本轮未上传源码/应用镜像/秘密，未构建或启动业务、迁移库或开放端口，不把工具镜像就绪写作云端业务完成。私钥未检查/输出，业务Java/DDL/前端保持，前端继续暂停。以下为历史状态。

M06.1-D.2新ECS只读核对已交付：[报告](docs/reports/cloud-preflight-2026-11-04.md)。用户确认绑定/重启后，找到实际文件名含前导下划线的PEM，SSH root认证成功；未检查/输出私钥内容。实测Ubuntu22.04/x86_64、2核/cgroupv2，可见内存3.54GiB（当时可用3.13）、Swap4GiB未用、盘空49.68GiB；Python3.10/OpenSSL有，Java/Maven/keytool/Docker/Compose无。密码认证关闭、root公钥可用、UFW inactive，云安全组未核对；1Mbps仅用户报告。Docker官网/Maven目录200，Docker Hub初次及IPv4复核超时，未测真实拉取，非永久封禁结论。没有安装/改系统/上传或部署业务。主机首次信任记录留.local/cloud，后续严格校验，未带外核对。下一项建议仅D.4环境准备/固定镜像获取验证，分发路径若变先说明，不静默换镜像或上传整个工作区；之后才实机部署/验收。前端暂停，D.3预验证不冒充云端业务已过。

M06.1-D.3按用户明确授权已交付[compact4g指南](docs/compact4g-deployment.md)/[报告](docs/reports/compact4g-deployment-2026-11-04.md)：29组实链/资源及5组配置通过，失败不累计。七服务共用CPU0/1、常驻上限3GiB且禁容器Swap，MySQL参数生效、20MiB用券打印/MQ/评价及原卷重启后新请求通过；29次采样常驻合计最大约1.16GiB，7个常驻cgroup无OOM，临时CLI未完整测量。默认配置、Java/JAR/156依赖/DDL/前端保持，75操作/V24/32表；4既有部署/测试入口变化，568选定文件中564保持。字符串内存字段及Windows cnf被忽略的失败留证，后者改启动参数并先验核对。自有运行资源/实例秘密均0，Docker/WSL仍运行；本机引擎约7.60GiB，非完整4GiB宿主/云端/容量验收。compact4g仅新showcase显式选择，E冷备演练暂拒绝该profile；不重复E。下一项新ECS信息到位后续D.2只读核对，不默认排查/更改旧机，不自动采购/发布；前端暂停，M04旧超时原因仍未知。以下为历史状态。

M06.1-D.2已开始：[条件与连接证据](docs/cloud-preflight.md)已记录；用户已给ECS地址、skyadmin/既有本机SSH密钥，并确认原外卖服务不再需要。22端口TCP成功但SSH在版本交换/认证前被远端关闭，等待用户手动登录核对；未读私钥内容、未认证/执行云端命令、未停止旧服务，不能称云端核对完成。七服务内存上限合计5440MiB不是最低资源或容量保证。本轮不启动本机Docker测试、不安装/部署/修改安全组，前端暂停。

M06.1-E隔离冷备恢复已交付：[报告](docs/reports/full-stack-recovery-2026-11-03.md)/[指南](docs/full-stack-recovery.md)，沿[ADR-0057](docs/decisions/0057-single-instance-deployment.md)。最终单入口32组、独立复核18项、3模式配置兼容通过，重叠/失败不累计；同维护窗口五卷/配置身份冷备，全新卷恢复前33表摘要一致，原会话/资金/券/文件/消息去重、暂停新代次及新领券/新S3完整读写通过。运行ID与持久dataIdentity分离仅供显式test恢复，默认身份不改；新空卷nocopy，冻结/不完整恢复禁止启动。C镜像/JAR/156依赖、Java/DDL/前端保持，75操作/V24/32表；3既有部署/测试文件变化。JSON转义/latin1夹具转码、镜像种子文件、稀疏统计、工具命名及文件ACL问题已保留并修复；NTFS备份权限与可读性/摘要复核通过。运行资源/实例秘密清理，6份未加密私有备份保留.cache，Docker/WSL仍运行。用户已补充有阿里云Linux服务器，当前未核对/未连接/未部署；下一项建议M06.1-D.2只读部署条件核对，之后实机验收→M06.2固定负载→M07，前端暂停。M04冷恢复新写超时原因仍未知，不据本轮首次201称修复，无真实迁移/公网授权。以下为历史证据。

M04.2-C按[ADR-0056](docs/decisions/0056-private-object-storage.md)已验证并收口：[报告](docs/reports/s3-recovery-2026-11-02.md)/[复现与Swagger](docs/s3-recovery.md)，110项不同相关检查分轮通过。4个真实业务强杀窗口、新JVM恢复、2个原始签名PUT在墓碑删除后晚到并回收；完整DB/存储卷/LOCAL根恢复到新实例，33表含Flyway摘要一致，恢复副本的前向迁移/丢响应重试/源保留通过。生产JAR及148依赖与B指纹完全相同，75操作/V24/32表，默认LOCAL/S3关闭，前端暂停。自有运行资源均0，备份/失败证据留.cache，未访问项目库/用户根或真实迁移。下载不复算业务SHA，外部同长覆盖可200；冷恢复新写曾超时、具体原因未定位，不能称已修复或承诺固定RTO。下一项仅M06.1部署/观测方案定稿，纳入恢复新写就绪、私有认证/密钥与备份保管；真实Apply/源删除/公网仍须独立授权。以下为历史证据。

M04.2-B按已采用[ADR-0056](docs/decisions/0056-private-object-storage.md)已验证：[报告](docs/reports/s3-file-chain-2026-11-02.md)/[指南](docs/s3-file-storage.md)，918项不同相关检查分轮通过。文件链按完整定位路由LOCAL/S3，原PDF/权限/引用/锁序保持；S3上传完整回读校验，IO在SQL事务外，共享FileGuards；私有清理线程/零积压、永久DELETED墓碑循环精确删除。V24已实现，75操作/32表；SDK2.57.8/Apache5进生产包，148项依赖含115项原依赖指纹保持，默认LOCAL/S3关闭。真实隔离MySQL＋S3、V23升级/两类JAR启动通过，自有测试资源均0；原失败轮次及审批用量中断记录保留。未访问项目库/用户上传根或迁移真实文件，前端暂停。下一项C按已采用方案做真JVM/晚到IO、备份恢复、隔离迁移预演及Swagger清单；B的合成晚到不冒充C，实际搬迁/删除仍需独立授权。以下为历史证据。

M04.2-A按用户已采用[ADR-0056](docs/decisions/0056-private-object-storage.md)已验证：[报告](docs/reports/s3-compatibility-2026-11-02.md)/[复现](docs/s3-compatibility.md)。SeaweedFS4.51 digest固定、SDK2.57.8/Apache5仅隔离test profile；最终S3协议33＋LOCAL策略38＝71项通过，普通构建/web-only JAR及115项生产依赖指纹保持，497个既有代码/脚本/前端文件保持；仍75操作/V23/32表、LOCAL业务，未测业务MySQL或迁移文件。失败启动/编译/首轮33项已保留；固定长度PUT保留校验和，标识窄Actions＋Deny组合、空桶列表语义、实际回环端口/参数校验见报告。自有容器/卷/网络/JVM均0。下一项B按已采用方案做文件链与拟议V24/墓碑，不重复确认；C晚到IO/备份与隔离迁移预演未做，真实搬迁/删除仍需授权。默认开关/前端暂停保持。以下为历史证据。

M04.1已整理[对象存储/历史文件提案](docs/object-storage-design.md)，**待用户采用，尚未实施**：推荐SeaweedFS单节点私有S3＋SDK 2.x，默认LOCAL、存量按定位共存、代理下载；保留事务/文件引用/单实例边界，未知PUT晚到由持久墓碑循环核对，不以一次DELETE或超时证明结束。拟A隔离兼容/版本锁定→B文件链/V24→C故障/备份和隔离迁移预演，实际迁移Apply/源文件删除另行明确授权。当前75操作/V23/32表，未改代码/依赖/DDL/前端或启动组件，未读实际上传目录；本轮仅文档与源指纹/链接核对。采用后从A开始，不将S3或V24写成已实现。以下为历史证据。

M08.5-C已按[ADR-0055](docs/decisions/0055-bounded-outbox-publishing.md)完成：[报告](docs/reports/outbox-publisher-comparison-2026-11-01.md)/[复现](docs/outbox-publisher-comparison.md)。62项不同相关检查最终通过，11个真实JVM崩溃窗口；旧新各24轮/4104 POST全通过，三档异步完整p95中位由约19秒降至0.58～1.15秒（降93.88%～96.95%），达到约定目标。DML仍180/20券，同步60；非整体优于SYNC、非容量/HA结论。首次旧版第11轮管理队列断言失败已保留；仅修复窗后有界排空观察，全新依赖完整重跑，无生产调参。361个生产/前端/pom文件保持，75操作/V23/32表，默认SYNC/依赖关闭、前端暂停；JAR/清理通过，旧基线保留。M08.5到此收口；下一项M04.1对象存储/历史文件迁移方案定稿，前端暂停故后端先评估，不自动实施或搬迁文件。以下为历史证据。

M08.5-B按用户已确认[ADR-0055](docs/decisions/0055-bounded-outbox-publishing.md)已实现/正确性验证：[报告](docs/reports/bounded-outbox-publishing-2026-11-01.md)/[指南](docs/bounded-outbox-publishing.md)，182项不同检查分轮最终通过。私有单线程/零队列，默认32次claim及1秒继续预算，ACK+条件回写成功才继续，失败结束，总8秒关闭；原claim/finish/transport与幂等保持。旧基线`.cache/m085-baseline-2026-11-01`含376类/资源、旧JAR、115依赖/源码指纹，已复核，C完成前勿删。下一项C已在方案内：完整隔离旧/新生产类路径、真进程故障及一次旧新各24轮对照；当前无新性能收益结论，≤5秒/降≥70%未验证。仍75操作/V23/32表，默认SYNC/依赖关闭、前端暂停，临时资源已清理。以下为历史证据。

M08.5-A[方案](docs/outbox-bounded-publishing-design.md)已整理，**待用户确认，未实现**：推荐私有单线程/SynchronousQueue，1秒tick仅唤醒；每轮最多32次claim、1秒继续认领预算，逐条临执行取租约，ACK且条件回写成功才继续；首个失败/未知结果停止本轮，总8秒关闭等待，保留原lease/退避/幂等。采用后B先实现/正确性回归（开工先保存旧生产classes/制品/依赖指纹），C做真进程故障及一次旧新24轮对照，目标各档ASYNC完整p95≤5秒且较旧版降≥70%，不是业务SLA。当前代码/配置未改，75操作/V23/32表，默认SYNC、前端暂停。以下为历史证据。

M08.4-B已完成[同条件测量](docs/reports/coupon-measurement-2026-11-01.md)/[复现](docs/coupon-measurement.md)：正式24轮/2052 POST，三档各3组配对，不变量及9项不同相关检查通过。并发5/10行锁等待中位59/68→0，DML60→180；ASYNC完整p95约19秒，1秒单条发布构成瓶颈，未获整体加速。无生产/依赖/DDL/前端改动，默认SYNC及依赖关闭，临时资源清理。下一项仅建议M08.5-A有界发布优化定稿，尚未批准实施；最多一次优化/同口径对照，避免无限扩张。前端继续暂停，原对象存储/部署目标保留。以下为历史证据。

M08.4-A已验证：[报告](docs/reports/coupon-faults-2026-10-31.md)/[复现](docs/coupon-faults.md)，8个真实JVM崩溃窗口，最终轮110项相关检查全部通过；真实时钟30秒仲裁/接管，SENT与队列排空、库存和期限保持。修复4消费者占满共享通道：发布与RabbitAdmin声明共用独立发布模板，确认/return/有界参数保留。仍75操作/V23/32表，默认SYNC/依赖关闭、前端暂停；临时资源已清理。下一项M08.4-B按[测量口径](docs/coupon-measurement-design.md)执行同条件对照，尚无性能收益/HA结论。以下为历史批次证据。

M08.3-D已按ADR-0054接通并验证：75操作/V23/32表，147项不同相关检查分轮最终通过，[报告](docs/reports/async-coupon-admission-2026-10-31.md)/[指南](docs/async-coupon-admission.md)。公开POST按模式经SQL预检→事务外Lua→短RC提交PENDING/outbox才202/Location；原键先重放，未知提交不直接返库存。维护仅本机独立无HTTP入口，controlVersion防旧操作；Pause排空/核对，ASYNC Resume用新票据保留OPEN意图，Sync须先新代次关闭Redis后条件切换，失败保持关闭。默认SYNC/依赖关闭，未改现有活动，前端暂停。下一项M08.4真进程故障与同条件对照，尚未验证性能收益/HA。以下为各历史批次证据。

M08.3-C已按[ADR-0054](docs/decisions/0054-async-coupon-claims.md)验证：75操作/V23/32表，191项不同相关检查分轮最终通过，[报告](docs/reports/coupon-redis-recovery-2026-10-31.md)/[指南](docs/coupon-redis-recovery.md)。独立ACL/AOF/noeviction Redis，Lua预留/条件结算、永久失败墓碑仲裁、代次影子重建/30秒协调租约；V23 resume_phase保留暂停，5分钟业务截止不延长。IO不进SQL事务，结算checkpoint复核代次，旧修复不重开已更新代次；私有单工作线程不占公共调度。默认SYNC/消息与券Redis关闭，本机自有券Redis已停止并保留卷。下一项D公开受理/受控切换，尚未实现；前端暂停。B的原事务/锁序与存量兼容继续保持。

M03.3单节点消息故障验收已通过：[报告](docs/reports/messaging-faults-2026-10-30.md)/[复现](docs/messaging-faults.md)，65项不同相关检查分轮最终通过。真实杀自有JVM覆盖提交前/提交后ACK前/发布确认后，死信目标恢复约178秒，8秒退出预算通过；生产代码/依赖/迁移未变，仍75操作/V21/31表。无多节点HA/容量结论。下一项M08.3-A独立定稿Redis受理/补偿/重建，前端暂停，Lua异步领取未实现。


M03.2-B通知消费与本人接口已验证：75操作/V21/31表，[指南](docs/notifications.md)/[报告](docs/reports/notifications-2026-10-30.md)，253项不同相关检查分轮最终通过。凭据/通知同事务、提交后ACK；历史90天展示/首次已读，维护重放原ID且拒绝活动租约。默认消息关闭，前端暂停；M03.3已验证，下一项M08.3-A协议定稿，Lua/异步领券未实现。


M03.2-A发布链按[ADR-0053](docs/decisions/0053-reliable-order-messaging.md)已验证：73操作/V20/29表，403项不同相关检查分轮最终通过，见[报告](docs/reports/outbox-publishing-2026-10-30.md)。两个原动作同事务写outbox，关闭传输也不得绕过；网络IO在认领提交后，回写必须带租约token，SENT不等于消费成功。默认传输关闭，本机自有Broker已停止并保留卷；[启停/联测指南](docs/reliable-messaging.md)。A/B及M03.3已验证，前端暂停，Lua/异步领券未实现。


M08.2-C两类订单用券已按[ADR-0052](docs/decisions/0052-order-coupons.md)实现；73操作/V19/28表。资源/订单后取券锁，预约清理旧单须先锁旧订单，再排序取得新旧所有券锁；未付释放/已付不返、原摘要兼容。659项相关检查分轮最终通过，见[报告](docs/reports/order-coupons-2026-10-30.md)。前端暂停；[M03.1方案](docs/reliable-messaging-design.md)及A/B与M08.3-A拆分已确认；A发布链已验证，B通知消费已验证，Redis受理细则仍未实现。

M02详情评分缓存现按[ADR-0048](docs/decisions/0048-public-rating-cache.md)实现：V16统计版本技术表，当前75操作/32表；只详情评分用缓存，原RR/资格保持。评价新增写入口必须同事务维护统计版本，关闭缓存也不能绕过；版本键逻辑失效，不依赖删除成功。仓库默认关闭、本机自有Redis启用及专用联测见[指南](docs/redis-rating-cache.md)，勿以普通MySQL回归代替真实Redis验证；M02.3已[归档](docs/reports/redis-rating-cache-measurement-2026-10-28.md)两轮性能/故障、收益/退化及写成本，后续按路线图推进。

## 项目目标

CampusLife 是 Java 后端学习与实习展示项目，采用虚构校园、商家和测试账号，支付与退款均为模拟操作。优先形成可运行、可验证、能够解释设计取舍的业务闭环。

面试目标要求实际应用并能解释关键技术栈。V1业务闭环完成后，继续围绕MySQL优化、Redis缓存、MQ可靠异步、JUC及工程部署等已规划方向逐项定稿和深化；每项说明业务问题、实现取舍、故障边界及验证证据。功能演示和讲解文档不能替代这些实际实现，也不能据V1接口齐全判断整个面试项目完成。

用户已接受校园本地生活/点评交易类定位：限量优惠券进入M08主线，与Redis Lua、MySQL和RabbitMQ协调；最小前端分批前移，ZSet排行次优先级。该确认是方向与优先级，优惠券规则/异步协议/前端范围仍须定稿，不能标为已实现；保留V1已有事务、锁和幂等成果。

用户最新明确指令优先于本文。业务范围与技术选型以已确认的决策为依据；设计文档中的讨论稿、候选方案和待确认规则不能自动视为已经批准。

## 学习与推进方式

- 每次推进一个明确、可验收的小任务。需要拆解时，说明推荐任务、理由和完成标准，让用户不必逐个指挥文件操作。
- 数据库、事务、并发、缓存和消息等核心设计，解释问题、关键选择及取舍；按用户学习节奏留出动手机会，不要求用户先掌握全部工程知识。
- 重复 CRUD、DTO 和常规配套代码可以辅助生成。命名、普通实现细节及可逆修复由 agent 自主处理。
- 已授权任务内持续完成实现、相关验证和发现问题的修复，不为每个常规步骤反复请求确认。
- 完成当前任务后说明结果并推荐下一项任务，不自行扩展为整个项目的一次性开发。

## 决策边界

- 新增或实质改变业务规则、订单模型、模块边界，或引入重要基础设施时，先给出具体推荐、影响及理由，再与用户讨论。已确认的决定无需重复询问。
- 发现已有方案存在问题时，明确指出并提出修改建议；不盲目遵循过时设计，也不静默改变已确认行为。
- 根据实际需求采用最简单的充分方案，避免提前抽象、强行使用设计模式或为简历堆叠技术。
- 发布、对外共享及破坏性操作需要明确授权。密钥不写入仓库；演示账号和模拟支付不得默认暴露在公开部署配置中。

## 验证与交付

- 按改动风险选择相关检查，不为简单文档修改强制构建，也不在没有新问题时反复运行全套测试。
- 事务、权限、金额和并发等核心逻辑需要对应的异常与边界验证；数据库锁正确性使用真实 MySQL 验证，不能仅凭 mock 测试声称成立。
- 如实区分已执行、未执行和受环境限制的验证；不虚构测试通过、性能提升或真实运营成绩。
- 交付说明改了什么、为何这样做、验证结果及尚存问题。核心模块补充可供用户复现的验收方法。
- 人工验收优先提供 Swagger UI / Apifox 点击流程，不要求用户手写 PowerShell 请求；新增接口同步维护注解、OpenAPI 导入文件与相关指南。
- 运行和测试命令只记录实际可用的内容。项目尚未具备运行条件时，明确说明缺少什么，不编造启动步骤。

## 上下文与维护

后续任务顺序、状态、依赖与验收/停止条件统一维护在[执行路线图](docs/roadmap.md)。开工说明任务编号，交付后更新证据与下一项；调整顺序说明原因。路线图中的候选不等于已批准规则或基础设施，不按旧阶段记录重复开发已验证能力。

按当前任务需要查阅相关资料，无需每次读取全部文档：

- [README.md](README.md)：项目入口、当前进度与运行说明。
- [业务决策记录](docs/decisions/0001-v1-baseline.md)：范围、已确认规则、待讨论边界及选型。
- [数据库设计](docs/v1-database-design.md)：实体关系、字段、约束与索引。
- [状态迁移与事务](docs/v1-state-transitions.md)：状态规则、权限、锁协议与验收案例。
- [接口契约](docs/v1-api-contract.md)：请求、响应、权限、错误与接口实现顺序。
- [商家入驻决策](docs/decisions/0010-merchant-onboarding.md)：已确认的申请、审核、重提及管理员初始化规则；区分规则确认与实际交付。
- [工程基础](docs/engineering-foundation.md)：实际目录、环境、运行命令与验证记录。
- [MySQL 接入](docs/mysql-setup.md)：密码本机输入、迁移与真实数据库验收。

当前为单 Maven 工程，入口位于 src/main/java/com/campuslife，测试位于 src/test/java。Java 21 下使用 mvn test / mvn verify / mvn spring-boot:run，或 scripts/dev.ps1 的 Test / Build / Run；scripts/smoke.ps1 检查可执行 jar。Codex 受限终端若阻止回环网络，真实 HTTP 验证需要允许本地网络，不能将权限失败误判为应用缺陷。

S3协议探针源码另在src/s3-test/java，仅scripts/test-s3.ps1显式启用s3-probe profile，不启动业务上下文；B开始SDK进入普通生产包，默认不创建S3客户端。scripts/test-s3-business.ps1联测真实隔离MySQL/S3；协议探针不替代业务验收，V23升级检查须在新临时库单独运行。

平台 ADMIN 仅通过 scripts/init-admin.ps1 显式创建，使用独立无 HTTP 的 AdminBootstrap 入口；普通启动不自动创建，不提供公开管理员注册或默认密码。审核与重提事务、锁顺序见 [ADR-0011](docs/decisions/0011-admin-shop-review.md) / [ADR-0012](docs/decisions/0012-merchant-resubmission.md)。

经营资料与营业开关规则见 [ADR-0013](docs/decisions/0013-merchant-operation.md)：PATCH 省略保留、null 拒绝；开关不等于接单就绪，锁后当前读用于合并和返回。

每周时段按 [ADR-0014](docs/decisions/0014-merchant-business-hours.md) 整周替换，与经营资料共用店铺版本；相邻合并、重叠拒绝。排班保护按 [ADR-0018](docs/decisions/0018-merchant-schedule.md)、预约存量保护按 ADR-0022 落实。

服务目录按 [ADR-0015](docs/decisions/0015-merchant-service-catalog.md) 限制分类对应类型，金额为整数分，PRINT 时长须显式 null。服务版本独立，不递增店铺版本；上架不等于可接单。

打印价目按 [ADR-0016](docs/decisions/0016-merchant-print-prices.md) 以印刷面计价；批量按规格新增/更新、遗漏保留、整批回滚，使用价目独立版本。写事务 RC + 父服务锁，查询 RR；报价/订单保护随对应模块落实。

理发师按 [ADR-0017](docs/decisions/0017-merchant-barbers.md) 作为人员资料维护；姓名/启停与完整替换绑定共用人员版本，允许 0～50 个本店理发服务。停用/解绑已按 ADR-0022 接入存量预约保护和日期锁协调。

Java 按业务模块内分层组织，实际目录见 [包结构说明](docs/project-structure.md)。当前 Mapper 使用 MyBatis-Plus 与注解 SQL；新增业务沿用现有组织方式，不提前创建空模块。

默认运行使用 mysql 模式，密码由用户本机填写；普通构建的 Web 测试显式使用 web-only。scripts/database.ps1 -Action Check 运行正式项目库迁移与验收；scripts/test-mysql.ps1 用独立临时实例验证并清理，不访问现有服务数据。不能把 web-only 或跳过数据库测试当作 MySQL 接入成功。

行为或设计发生变化时，同步更新相关文档；新增重要决策记录其理由与取舍。应用工程建立后，再补充真实目录结构、构建与测试命令。

保持本文简短、具体。根据反复出现的问题增删约定；只有专项流程稳定且重复使用时才考虑提炼 Skill，不为每个任务强制增加技能或流程。模型升级后复查旧规则是否仍有必要。

按日排班按 [ADR-0018](docs/decisions/0018-merchant-schedule.md)：校园时区今天至第6天、15分钟对齐、原始最多4段；与资料/绑定共用人员版本。统一锁顺序改为店铺入口（配置 X / 未来预约 S）→ 日期保护行（人员/日期升序）→ 人员/时段，替代旧日期优先草案。清空保留保护行；营业时间须覆盖今天及之后全部已有排班，包括停用人员。

公开商家目录按 [ADR-0019](docs/decisions/0019-public-shop-catalog.md)：APPROVED + ACTIVE OWNER + ACTIVE/MERCHANT 账号可见，停业/分类停用不隐藏。目录 GET 精确匿名放行，只读 RR、营业时间批量加载；openNow 只看校园时区开关/营业区间，不等于可下单。评价暂为 null/0，不虚构统计。

公开理发师按 [ADR-0020](docs/decisions/0020-public-barbers.md)：仅启用且有本店上架理发绑定；serviceId 筛选目标不可见统一 404，返回全部公开服务 ID。联表验证同店/类型，只读 RR，不等于有排班或可预约。

预约候选时段按 [ADR-0021](docs/decisions/0021-booking-slot-candidates.md)：匿名只读 RR，上海今天至第 6 天，每请求一次 Clock，至少提前 2 小时（等于允许），15 分钟起点网格且完整落在营业/排班交集。资格无效 404 优先于停业空结果；真实占用与创建/释放现已按 ADR-0022 接入；查询排除 HELD/ACTIVE/CONSUMED，未提交关闭的过期 HELD 仍保守占用，不预留资源。

待支付预约按 [ADR-0022](docs/decisions/0022-booking-orders.md)：V9 四表；创建/本人详情/未支付取消，后续模拟支付与待接单退款按ADR-0023开放，商家履约仍待开发。写事务 RC，账号→会话→店铺 S→日期保护行→配置/订单；配置店铺 X→日期行→后续资源，禁止反向锁经营账号。15 分钟到期，关闭/释放/事件原子提交，超时取消提交后再返回 409；同创建键按归一化摘要返回原单。人员停用/解绑保护未结束 HELD/ACTIVE，排班/营业时间保护还包含 CONSUMED；名称/价格/时长仅影响新单。

模拟支付与待接单退款按 [ADR-0023](docs/decisions/0023-booking-mock-payment.md)：V10唯一支付/全额退款事实；付款固定锁后时刻，待接单30分钟，不重复计时；资金/状态/占用/事件原子提交。模拟资金默认关闭，本机开发显式启用，所有新增退款路径均受保护；关闭时已付到期仍占用，恢复按原期限扫描（未支付/待接单各100条、每单独立事务）。期限关闭先提交再HTTP409；已有事实重放不写入。商家履约后续开发。

商家预约确认按 [ADR-0024](docs/decisions/0024-merchant-booking-confirmation.md)：本店详情/接单/拒单；接单须APPROVED，正常账号及ACTIVE OWNER在暂停后仍可读/拒单退款。重复接单、同原因拒单不写新事实；到期退款提交后409。已接单学生距开始至少1小时可取消（等于允许）；新退款受模拟开关保护，合法接单无需开关。写沿用账号→会话→店铺S→OWNER复核→日期行→订单/流水；已接单不被待接单扫描或创建清理退款。

商家无法履约取消按 [ADR-0025](docs/decisions/0025-merchant-booking-cancellation.md)：仅已接单且锁后now<预约startAt，不受学生1小时限制；暂停店铺的正常账号/ACTIVE OWNER可处理旧单。MERCHANT_UNAVAILABLE原因、全额退款、释放、版本/事件同事务；同原因重放不重判首次窗口或生成资金，异原因/其他状态冲突。窗口已过409无写入，新退款受模拟开关保护；复用商家锁入口，不反向锁学生账号。

预约开始/完成按 [ADR-0026](docs/decisions/0026-booking-fulfillment.md)：仅本店已付BOOKING，正常商家/ACTIVE OWNER在暂停后仍可履行旧单；开始[startAt,endAt)，完成now>=startedAt，可立即或晚于原结束。状态/时间/占用/事件同事务，完成CONSUMED保留原区间；重复不覆盖时间/版本，CLOSED不恢复。无新资金、不受模拟开关限制；原区间不因提前完成释放或因晚完成延长，人员/排班保护沿用ADR-0022。

管理员预约异常按 [ADR-0027](docs/decisions/0027-admin-booking-exception.md)：正常ADMIN可查任意BOOKING、核实关闭已付待接单/已接单/服务中；目标账号/OWNER停用仍可处理，无普通取消时间窗口。reason+expectedVersion防核实期间覆盖；同管理员/原因/原版本且最后事件匹配才重放。未开始RELEASED、开始后CONSUMED保留原区间；全额退款/状态/占用/事件同事务，新退款受开关保护。写锁管理员账号→会话→店铺S→日期行→订单/预约，不反向锁目标账号；与扫描先提交者保留关闭原因，已完成不退款。

学生订单列表按[ADR-0028](docs/decisions/0028-student-order-list.md)：仅正常STUDENT本人BOOKING，类型/六种状态筛选，PRINT暂空页仍完整校验；默认页1/大小20，大小1～100、页最大INT，创建时间/ID倒序。历史不依赖店铺/OWNER当前资格，摘要读取冻结快照；GET不清理超时或推断状态。只读RR内同条件count与联表分页，不逐单读详情/资金、不取业务写锁；页偏移用long，无新迁移/分页插件。

商家订单列表按[ADR-0029](docs/decisions/0029-merchant-order-list.md)：正常MERCHANT及ACTIVE OWNER仅查本店订单，无所属店铺404、无效成员403；PRINT已按ADR-0036扩展，仍完整校验权限/参数。暂停/停业/下架或学生停用不隐藏旧单；分页/冻结摘要沿用ADR-0028。只读RR从本人店铺读取固定资格/count/page快照，快照后成员停用下次请求拒绝，不取业务写锁、不清理超时或生成资金；复用已有商家索引，无新迁移。

订单事件查询按[ADR-0030](docs/decisions/0030-order-events.md)：正常学生本人/正常商家ACTIVE OWNER本店订单，PRINT已按ADR-0034/0036扩展；历史权限沿用详情，跨归属/不存在/不支持类型统一404。RR内先核对归属/成员再按eventSeq升序返回完整数组；ID/序号字符串，SYSTEM actorId=null，原原因含管理员核实原因，不附加账号资料。无分页/截断/合成事件，无业务写锁或关单退款；latest仍用于原管理员重试，无新迁移。

私有PDF上传按[ADR-0031](docs/decisions/0031-print-file-upload.md)：本地单实例私有根`.local/files`、PDFBox3.0.8，正常学生上传/本人元数据，20MiB/1～500页、加密拒绝、两个处理槽。V11 file_asset，内部UPLOADING占位→事务外移动→复核身份发布AVAILABLE，24小时有效；账号→会话→文件短RC，IO不进事务。清理实例内按key互斥，标记DELETING→事务外删→DELETED，60秒/100条、失败重试，限定当前根标识/路径且不递归清空；本人下载按ADR-0032、报价按ADR-0033接入，订单引用保护后续接入。测试必须使用自有临时存储根，不访问用户持久上传目录。

本人PDF下载按[ADR-0032](docs/decisions/0032-file-download.md)：仅正常学生本人，互斥后新RR复核身份/会话/文件/期限，获准传输允许完成；普通读无业务行锁，IO不进事务。传输持有实例互斥，清理tryLock跳过，释放后重试；先归属后生命周期，404/410，存储异常503不改元数据。完整PDF附件、安全UTF-8名称、no-store，不续期/改版本；已提交IO异常交回容器终止连接，禁止JSON尾接。商家文件权限随真实打印订单接入。

打印报价按[ADR-0033](docs/decisions/0033-print-quotes.md)：正常学生本人有效PDF，六字段严格JSON，A4四种规格、1～100份，命中启用价目按印刷面计费，双面每份取整纸张；long支持50亿分。可见PRINT店铺/上架服务，停业/休息仍可估价；文件404/410优先服务404、缺/停规格409。单一只读RR、读取后一次Clock判会话/文件期限，无行锁/下载互斥/磁盘IO，不锁价/续期/创建订单。打印下单须另行重计价与接入引用保护。

打印待支付订单按[ADR-0034](docs/decisions/0034-print-orders.md)：V12 print_detail；CREATE_PRINT与CREATE_BOOKING键隔离，当前营业才能创建，锁后重计规格版本/金额并冻结。实例文件互斥在事务外等待，IO不进事务；RC账号→会话→店铺S→文件X→订单/扩展，重放无需磁盘/实例互斥但沿同一DB锁序协调。所有打印引用写入先file_asset X，任一未终结expiresAt=null，全部CLOSED/COMPLETED则最晚终结+7天（含未付取消/超时）；仅实际期限变化更新文件版本。清理文件X内额外确认无未终结引用。独立打印扫描启动/30秒/100条逐单事务；未付款超时取消提交关闭后409，不生成资金；支付及已付款退款现按ADR-0035扩展。学生历史支持PRINT；商家查询/确认按ADR-0036扩展，商家后续履约及管理员入口仍BOOKING限定。测试使用独立临时MySQL/文件根，自动打印扫描显式关闭。

打印支付/待接单退款按[ADR-0035](docs/decisions/0035-print-mock-payment.md)：共用本人模拟支付路径支持PRINT，冻结金额/锁后时刻，文件只复核本人AVAILABLE及NULL保护，不打开磁盘或等实例互斥；旧单停业/改价不阻止付款，经营资格失效阻止首次付款。唯一支付/全额退款，30分钟待接单，QUEUED→ABORTED及资金/状态/事件/全部引用期限同事务。重复事实不续期，新资金受原开关保护，关闭时已付到期仍保留NULL，恢复按原截止退款。打印未付/待接单各100条逐单事务，预约配额不变。写返回和创建重放用订单/明细锁查询，避免普通定位selectById的MyBatis一级缓存混读；RR只读仍原快照。学生支付已支持PRINT；商家查询/确认按ADR-0036扩展，管理员PRINT待后续。

商家打印确认按[ADR-0036](docs/decisions/0036-merchant-print-confirmation.md)：本店列表/详情/事件及接拒单支持PRINT；正常商家/ACTIVE OWNER，接单额外APPROVED且严格早于30分钟截止，成功ACCEPTED/PAID/QUEUED。暂停仍可读/说明原因退款拒单；原因同步closeNote/退款/事件，actor明确MERCHANT。重复合法接单/同原因拒单不重写；到期SYSTEM退款提交后409，新退款受资金开关保护。商家RC账号→会话→店铺S→OWNER→文件X→订单/打印明细，不锁学生账号；写返回用锁后视图，RR读取同快照。接单后学生取消409、扫描不退款、文件持续NULL保护；商家PRINT取消现按ADR-0037开放；内容访问现按ADR-0038开放当前合格任务；开始/待取件现按ADR-0039开放，complete及管理员PRINT仍未开放。

商家打印取消按[ADR-0037](docs/decisions/0037-merchant-print-cancellation.md)：原cancel支持本店ACCEPTED/PAID/QUEUED、acceptedAt非空且startedAt/readyAt/pickedUpAt空的PRINT；无预约时间窗口，不重判原30分钟接单期限。正常商家/ACTIVE OWNER暂停也可说明原因全额退款，MERCHANT_UNAVAILABLE/原因/一次MERCHANT事件（fromStatus=ACCEPTED）及全部文件引用期限原子提交。同原因重放不重写/续期，新退款受开关保护，未付/待接单/开始/完成及其他关闭原因409；本入口不代跑超时。沿原商家RC锁序/锁后视图，首退款只复核文件保护元数据，无磁盘IO或反向学生锁；BOOKING窗口保持。内容下载现按ADR-0038开放当前合格任务，开始/待取件现按ADR-0039开放，学生取件与管理员PRINT后续。

商家PDF下载按[ADR-0038](docs/decisions/0038-merchant-print-download.md)：原content入口支持正常MERCHANT/ACTIVE OWNER，须本店至少一笔PRINT满足ACCEPTED/PAID/QUEUED、acceptedAt非空及startedAt/readyAt/pickedUpAt空、文件归属一致。无合格关联404，保留期不授权；暂停/配置失效/学生停用不阻止旧任务，IN_SERVICE授权现按ADR-0039扩展合法PRINTING/READY组合。互斥后新短RR复核身份/成员/关联/文件，普通读无业务行锁，IO不进事务；获准快照后取消不打断本次传输，后续重判，取消退款不等实例互斥，清理跳过活动文件。商家保护元数据异常503，学生原404/410保持；下载不续期/写事件/资金，ADMIN无默认内容权限。

打印履约按[ADR-0039](docs/decisions/0039-merchant-print-fulfillment.md)：原start支持PRINT，新增ready-for-pickup，运行55/完整62/55/7。正常商家/OWNER暂停也可履行旧单；ACCEPTED/PAID/QUEUED→IN_SERVICE/PRINTING→IN_SERVICE/READY_FOR_PICKUP，允许立即待取件/时间相等，倒退409 PRINT_TIME_INVALID。首次复核支付与保护元数据，阶段时间/进度/公共版本/事件同事务；重复保留事实、不回退，无资金或文件期限写入。沿原商家RC锁序/锁后视图，IO不进事务。合法PRINTING/READY增加本店内容授权，错配/关闭/完成不授权；普通取消开始后拒绝，complete仅BOOKING，学生取件与管理员PRINT异常后续实现。

学生打印取件按[ADR-0040](docs/decisions/0040-student-print-pickup.md)：新增本人pickup，运行56/完整62/56/6。正常学生IN_SERVICE/PAID/READY_FOR_PICKUP，锁后now≥readyAt可立即完成，COMPLETED/PAID/PICKED_UP、completedAt=pickedUpAt、版本/一次PICKUP事件与全部文件引用期限同事务；经营方资格失效不阻止旧单完成，无新资金。合法完成的pickup及本店商家start/ready重试保留事实，清理后也可重放，不续期/回退，当前权限仍校验；取代ADR-0039终态暂拒绝边界。RC学生账号→会话→店铺S→文件X→订单/明细，锁后视图，无反向商家锁或事务内IO/互斥等待。全部引用终结最晚终结+7天，有未结束引用仍NULL；必要期限写失败全回滚。完成单不授权商家内容，另一本店合格任务可授权，已获准传输允许结束；complete仍仅BOOKING，管理员PRINT异常/评价后续。

管理员打印异常按[ADR-0041](docs/decisions/0041-admin-print-exception.md)：原管理员详情/close-exception支持PRINT，仍56/62/56/6。正常ADMIN对已付待接单/已接单/打印中/待取件以reason+expectedVersion核实全额退款，CLOSED/REFUNDED/ABORTED，保留接单/开始/待取件事实；已完成不退款，锁后now≥paidAt及已有阶段，倒退409 PRINT_TIME_INVALID。目标账号/OWNER停用及暂停可处理，不反向锁目标账号；RC管理员账号→会话→店铺S→文件X→订单/明细，退款/状态/版本/实际ADMIN事件与全部引用期限原子提交，锁后视图，无事务内IO/互斥等待。非关闭旧版本409需重核，同管理员/原因/原版本且关闭事件/退款事实完整才重放，不续期或重复资金，清理后可重放；新退款受资金开关保护。全部引用终结最晚终结+7天；关闭单不授权商家内容，已获准传输可结束，ADMIN无默认内容权限。BOOKING仍ADR-0027，评价/账号管理后续。

学生本人评价按[ADR-0042](docs/decisions/0042-student-order-review.md)：POST/GET orders/{orderId}/review，运行58/完整62/58/4，V13 order_review/22表。正常学生本人合法COMPLETED/PAID BOOKING(CONSUMED)/PRINT(PICKED_UP)，匹配支付无退款、锁后now≥completedAt，完成即评无截止。严格整数1～5分、strip后1～1000码点文字，首次201 VISIBLE，一单一次含隐藏，重复409不覆盖；本人GET未评404、隐藏仍可见。reviewed派生EXISTS全部记录，订单状态/版本/事件/资金/资源/文件不变。RC学生账号→会话→店铺S→订单X→评价，扩展普通读，无日期/文件X/反向经营账号锁或IO/实例互斥；最终会话失败回滚，唯一键仅映射明确评价订单冲突。GET身份/归属/评价短RR同快照。经营方失效/文件清理不阻止正常学生旧单评价；公开评价/真实评分统计和隐藏入口后续，目录null/0为统计阶段值。

公开评价/真实评分按[ADR-0043](docs/decisions/0043-public-shop-reviews.md)：匿名GET shops/{shopId}/reviews，运行59/完整62/59/3。沿公开店铺资格，只返回VISIBLE五字段（评价ID/评分/文字/当前昵称/UTC时间），createdAt/id倒序、page1/size20至100、offset long。作者停用/角色变化不自动隐藏，改昵称影响历史后续展示；HIDDEN不计页/统计，本人和reviewed规则保持。公开列表/详情真实全部VISIBLE COUNT和精确SUM/COUNT，十进制两位HALF_UP，零条null/0；列表页内批量聚合，空页不查，单响应资格/分页/昵称/统计短RR，无业务锁/写入/IO或实例互斥，不保证跨HTTP/跨页同快照。精确GET匿名放行，错误token仍拒绝，隐藏入口/账号管理后续，无新迁移。

管理员隐藏评价按[ADR-0044](docs/decisions/0044-admin-review-hide.md)：正常ADMIN，reason strip后1～300码点，VISIBLE→HIDDEN及首次管理员/原因/UTC毫秒时间原子提交；同人同原因重放保留记录，其他重复/旧HIDDEN无审核信息409，首次时钟早于创建409。V14三审核列全空或全有，完整记录仅HIDDEN；RC管理员账号X→会话X→店铺S→评价X，锁后视图、提交前会话复核。目标停用仍可处理；不反向锁目标账号，不改订单版本/事件/资金/文件期限。公开后续RR排除，本人仍可读、reviewed=true且不能重发；无恢复/改原因入口。运行60/完整62/60/2。

管理员账号列表按[ADR-0045](docs/decisions/0045-admin-account-list.md)：GET /admin/accounts仅正常ADMIN，有效会话；全部角色/状态含本人和其他管理员/停用账号，role/status精确枚举交集、page默认1/size20最多100、创建时间/ID倒序、long偏移。仅AccountView六字段，SQL不加载摘要；身份/会话/count/page同一短只读RR，返回前核对期限，快照后变化下次请求见；无业务行锁或写入。运行61/完整62/61/1，无迁移；停用/恢复及自操作保护另行定稿。

账号状态按[ADR-0046](docs/decisions/0046-admin-account-status.md)：正常ADMIN仅管理其他账号，禁止本人含同状态；RC双方账号按ID升序逐条主键X锁→操作者会话X→目标会话撤销/历史，全部账号锁先于会话锁，其他业务原锁序保持，不取业务资源锁。锁后复核身份保护有效管理员，互停失效方拒绝；实际变化状态/version+1、全部旧会话撤销、V15历史同事务，恢复需重登。旧版本409，同版本同状态200不写，MAX只可无变化；提交前会话到期全回滚。不自动处理订单/资金/文件期限，旧快照/获准传输沿原边界。V1～V15/23表、运行62/完整62/62/0；当前接口规划齐全。
