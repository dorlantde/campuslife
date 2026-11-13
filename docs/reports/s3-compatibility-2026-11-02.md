# M04.2-A 私有S3兼容底座验收

2026-11-02。按用户已采用的[ADR-0056](../decisions/0056-private-object-storage.md)完成A。**最终71项不同检查通过：独立S3探针33项、原LOCAL文件策略38项，最终轮无失败/错误/跳过。** 普通构建及web-only可执行JAR启动通过；115项生产依赖逐项哈希保持，497个既有代码/脚本/前端文件保持，仅原pom新增隔离测试配置。[机器汇总及SDK依赖锁定](s3-compatibility-2026-11-02.json)／[实际复现入口](../s3-compatibility.md)。

当前仍75操作/V23/32表，业务文件仍LOCAL；本批没有S3业务适配、V24、上传/下载接口变更或历史文件迁移。没有启动MySQL、Redis、RabbitMQ或查询开发数据，未读取持久上传目录；web-only检查不能当作MySQL回归。

## 版本和工程改动

| 项目 | 实际采用 |
|---|---|
| SeaweedFS | 4.51，镜像索引`sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8` |
| 平台 | linux/amd64，子manifest `sha256:3a522c379599e163433a636e75022630848b63a74f08ea37bf06f8d776e9a707` |
| SDK | BOM 2.57.8，实际加载`s3-2.57.8.jar`，Apache5同步HTTP客户端 |
| 构建隔离 | 显式`s3-probe` profile；SDK为test依赖，源码`src/s3-test/java`、输出`target/s3-probe-test-classes`；普通测试路径保持src/test/java及target/test-classes |
| 运行隔离 | Java21，无Spring/数据库上下文；随机专用bridge、容器、卷、桶和凭据；只发布回环S3端口，无其它容器接入该网络 |

版本依据：[SeaweedFS 4.51官方发布](https://github.com/seaweedfs/seaweedfs/releases/tag/4.51)、[SDK 2.57.8官方发布](https://github.com/aws/aws-sdk-java-v2/releases/tag/2.57.8)。镜像实际查询/拉取按digest锁定，没有使用latest。SDK及HTTP模块的实际JAR名称/哈希从成功探针的类路径提取到机器汇总，不以“2.x候选”冒充已固定版本。

新增两个脚本`seaweedfs-env.ps1`、`test-s3.ps1`，分别负责所有权/配置/回收与测试编排；新增一个独立测试类。没有新生产Java类、Bean、API、迁移或前端文件。普通构建的115个BOOT-INF/lib与原基线逐项一致，正式包中没有探针类、S3客户端或AWS核心模块。

## 实际检查

| 范围 | 结果及边界 |
|---|---|
| 20MiB协议读写 | 20971520字节确定性二进制样本，PUT/HEAD/流式GET与SHA256一致；原hash `e1dc7599019abd59f3cf7afd4b7834a121b186f329b01701beac1132cfbb5fe2`。这是协议字节样本，不是PDF解析或业务上传验收 |
| 校验和 | 观察到默认CRC32相关请求头；显式SHA256成功并随GET返回；错误SHA256必须400 `BadDigest`且对象不存在，不能用任意400替代校验成立 |
| 重试与删除 | 同key同字节再次PUT后原内容一致；精确key重复DELETE成功，缺失对象404，其它对象保留；空、目录、穿越、非规范key在网络发送前拒绝 |
| 认证和权限 | 匿名GET/HEAD/PUT/DELETE、错签名、跨前缀/桶、对象列表及建删桶/改策略均拒绝；原字节与外前缀对象未被拒绝请求改写。桶列表是200空数组，不声称它返回403 |
| 标识与目录保护 | 应用可读原命名空间标识，不能读相似后缀、改/删标识；桶策略拒绝目录key删除。没有将应用身份改为整桶Read或Admin |
| SDK超时 | 自有本机黑洞套接字最终轮实际两次尝试、约1102ms结束（首个通过轮1124ms），测试配置为800ms尝试/1800ms调用及500ms读空闲，断言最多2次、少于4秒。不是生产S3总请求SLA，也不证明服务端取消写入 |
| 停止/启动、重启 | 实际停止自有SeaweedFS后请求失败，启动和重启后20MiB字节/hash、标识、匿名拒绝及目录保护保持；静态凭据配置在重启期间保留，不把它描述为只靠卷自动持久化凭据 |
| LOCAL及普通工程 | 原FilePolicyTest 38项通过，包括本地文件系统/策略；普通profile编译现有测试成功，可执行包web-only健康/UTF-8/追踪及访问边界通过。没有重跑全部V1或真实MySQL |

正常客户端配置显式path-style、已知长度单次PUT、`chunkedEncodingEnabled(false)`，没有关闭校验和；连接/获取2秒、socket读空闲5秒、尝试15秒/调用30秒、最多2次、池4。SDK禁用系统/环境代理自动发现，显式静态测试凭据及回环endpoint，不使用用户AWS凭据链。对下载流和慢客户端的总耗时不作硬30秒承诺。

## 发现的问题与修复过程

| 轮次 | 实际结果 | 修复/解释 |
|---|---|---|
| 1 | 初始化失败，未完成协议用例 | 原脚本漏引号的点号参数被PowerShell拆开，`-ip=127.0.0.1`变为两项，认证配置参数也未正确生效。改字符串数组传递并读取容器实际Args核对；未正确配置的容器不计安全验证通过 |
| 2 | 初始化失败，未完成协议用例 | 本机Docker的`--internal`网络使生效Ports为空，尽管HostConfig记录了映射。诊断容器内匿名403、宿主拒绝连接；改专用bridge＋回环唯一端口，同时核对生效Ports、网络成员和标签。专用bridge允许出站，不宣称断外网 |
| 3 | 测试编译失败，0项执行 | Apache5的proxyConfiguration接受已构造配置对象，不是Consumer；按实际API修正，显式禁用代理继承 |
| 4 | 33项：28通过、1断言失败、4错误 | 显式SHA256小PUT报IncompleteBody，另有小PUT超时；精确路径传统Actions未允许读标识；桶列表是200空数组。保留原结果，不据20MiB部分成功放行 |
| 5 | 33项全部通过，41.58秒 | 固定长度编码＋仍保留校验和；精确标识权限组合；桶列表空结果断言。完整构建77秒，容器/卷/网络回收 |
| 6 收尾复验 | 33项全部通过，41.816秒，构建74秒 | 协议源码不变，验证最终脚本的嵌套finally环境恢复及清理后才写完成标志；实际完成标志resourcesRemoved=true，独立核对资源均0 |
| 普通构建 | 38项全部通过，测试1.665秒，构建79秒 | 不启用s3-probe，之后web-only JAR smoke通过 |

传统Actions的无通配分支只匹配桶级动作，源自[固定4.51实现](https://github.com/seaweedfs/seaweedfs/blob/4.51/weed/s3api/auth_credentials.go)。因此标识采用窄`Read:桶/_storage-id*`加桶策略拒绝`_storage-id?*`，实测仅原标识可读；类似后缀对象仍403。B必须把这两部分一同落地，不能删掉Deny后声称权限相同，也不能照抄未通过的无通配精确路径。

固定长度配置后33项完整通过，说明该组合适合继续B；不据有限症状推断所有SDK、HTTP编码或S3兼容服务都具备相同问题。CRC/SHA与AWS通用兼容性没有扩大为全量协议认证。

原始日志：`.cache/m042a-probe1.log`～`m042a-probe5.log`、最终入口复验`.cache/m042a-final.log`，普通构建/启动日志为`.cache/m042a-default-build.log`、`.cache/m042a-smoke.log`。失败初始化及首轮33项XML按轮保留在`.cache/m042a-evidence`，成功XML在`probe-passed/default`；脱敏逐用例结果及全部失败概况进入机器汇总。旧失败不计为通过，也不把33项重复执行叠加到最终71项。

## 清理、限制与下一项

独立核对本轮标签：测试容器=0、卷=0、网络=0、项目测试JVM=0；诊断容器也按同一所有权回收。故障时docker run可能留下已创建容器，helper会按精确名称及身份标签识别再清理。仅测试资源被移除，没有全局prune或递归清空本地目录；镜像、Maven缓存和忽略目录中的诊断配置保留。

A没有验证文件业务的SQL/IO协调、受控HTTP下载、订单引用、未知PUT晚到补偿、真JVM崩溃窗口或完整备份恢复；测试侧key校验尚不是生产保护。没有性能/HA结论。当前仍LOCAL业务、默认资金/消息等开关保持，前端暂停。

下一项 **M04.2-B：文件链与LOCAL存量兼容**，按已采用方案推进：生产适配/共享暂存互斥、拟议V24、原业务入口及清理/墓碑复核，再用真实MySQL＋S3验证。B尚未实施，不重复询问已确认选型；实际用户文件搬迁、源文件删除仍需另行明确授权。
