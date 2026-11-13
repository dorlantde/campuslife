# M06.1-D.4 环境准备阶段记录

日期：2026-11-04，America/New_York。**M06.1-D.4已完成：工具、部署账号和六种原固定公开基础镜像均已验证。** [操作与对照测试](../cloud-tools-setup.md) / [机器记录](cloud-tools-2026-11-04.json)。本项没有部署业务或执行迁移，前端保持暂停；下面保留先前失败及分批过程。

## 已完成并验证

- 保留Ubuntu原签名源，安装OpenJDK/javac21.0.12.1、Maven3.6.3。Docker独立官方签名源安装Engine29.9.3/Compose5.7.0/Buildx0.38.1/containerd2.4.2，公钥指纹核对通过。没有整机upgrade或增加镜像代理。
- 用户明确回答“允许创建专用部署用户”后，创建campusdeploy，复制新机已有公钥并加入Docker组；实际SSH登录、Docker API及专用目录可写验证通过。无sudoers/原root登录策略修改，Docker组近似root权限已明确告知。
- Docker实际读取2 CPU、3799158784字节内存、cgroup v2、overlayfs；AppArmor/seccomp/cgroupns默认保护在。新机工具可用，不代表业务JAR构建或全部基础镜像已验证；后续小探针的cgroup结果见下文。
- 工具安装结束时读取新机containers=[]、volumes=[]、images=[]；后续手动样本验证后保留一份Redis镜像，containers/volumes仍为空。系统Docker服务和默认网络是有意安装保留的基础设施，不作临时应用资源删除；没有业务容器、SQL表、模拟资金或项目秘密产生。

## 失败与边界

1. 初次只读源检查末行受PowerShell管道CRLF影响，id命令报错；后续改为通过Python按LF原始字节输入脚本，源检查结果仍保留。没有将此错误当作系统用户故障。
2. 第一次安装在Docker官方公钥下载时TLS被重置，只完成仓库前置包/CA更新；加入有界IPv4重试后官方源成功，随后完整安装和版本检查通过。失败日志保留，没有靠关闭TLS或换未知源继续。
3. 自动审批最初拒绝创建账号/复制公钥/加入Docker组，原因是此持久权限变更近似root且未获明确授权。已向用户说明并请求具体许可；用户批准后才执行原脚本。无绕过审核的替代提权。
4. 本机导出时清理临时Redis版本标签，Docker联动移除了同仓库摘要索引；立即从刚导出的原样包恢复，原RepoDigest/镜像ID完全一致。后续保留同版本标签，不再清理该别名；原其他摘要未变，无运行服务受影响，不声称本机标签集合逐字节不变。
5. 25秒有界真实固定摘要拉取未完成。云端常规DNS与独立HTTPS DNS结果存在差异，指定独立IP仍TLS重置，Cloudflare DNS HTTPS也失败；没有据此定位永久封禁或具体根因，未写hosts/改DNS。
6. 自动传输入口首次误将Docker inspect的空数组当作已存在镜像，在传输前停止；修复解析后，55 MB样本SSH标准输入传输仍过慢，已停止唯一自有传输。改SFTP后在180秒上限失败。半包未导入，完整传输/目标摘要/运行检查均未通过，不能累计为测试成功。

7. 样本导入验证首次因本机Docker未运行，在执行云端操作前退出；改从逐blob校验的OCI原包提取平台配置和层身份后通过，没有为此启动本机Docker。

## 手动样本验证结果

用户选择自己PowerShell执行SCP并回复“已传完”。云端文件55,266,816字节，SHA256=`c8cdeeba1698060b953b1401bc5c58431c78cfe0de3376c4d16597f8428287eb`，与本机原包一致。导入前再次校验14个blob与原OCI根描述符；Docker load后原Redis固定摘要`sha256:66b7f4c2f3c6ef1b298bbd7b13c806bc6f71e61f1136a4f58710daf2cb053b21`、镜像ID、linux/amd64与RootFS层全部一致。

无网络、只读根、无额外能力的临时探针返回Redis8.2.9，并实读cgroup：CPU0–1、内存上限128MiB、Swap上限0、进程上限32。只运行版本命令，没有启动服务监听。探针清理后容器和卷均为空；这只验证该镜像及小探针限制，不是七服务4GiB容量验收。证据`.cache/cloud-image-probe/manual-redis-result-2026-11-04.json`。没有实际传输时间/速率，不能据成功定位前两次慢传原因或宣称手动方式更快。

其余五个公开基础镜像仅有先前本机缓存检查，没有上传。现有生产JAR/业务Java/pom/DDL/前端和默认部署引用未改，没有运行不相关测试。

## 其余镜像准备与最终收口

本轮已新增并实际执行`prepare-cloud-images.py --export-remaining --output-dir ...`本地模式：五份原固定公开镜像导出、79个blob与包SHA256复核通过，共702,969,344字节（670.4 MiB）；只添加/保留四个缺失的同版本标签，没有删除别名。目录只含五个tar及manifest.json；Redis不重导出、不重传。目标父目录可写，待传目录尚不存在，空闲51,430,047,744字节。原始证据：`.cache/cloud-images/remaining-public-2026-11-04/manifest.json`与`.cache/cloud-images/remaining-public-preflight-2026-11-04.json`。

本机Docker原为关闭，本轮仅为导出而临时启动；启动时已有名为redis的容器自动恢复，未手动启动业务。导出结束正常退出Docker Desktop，恢复原先关闭状态，未删除容器/卷/镜像；不声称所有WSL发行版已退出。首次容器清单读取遇Windows默认GBK解码失败，随后直接读取清单纠正；五份镜像检查和导出仍分别验证，未将该次清单读取计为通过。

用户随后按[单条SCP命令](../cloud-tools-setup.md)上传五份包并确认完成。独立只读收据先核对五包字节数、SHA256、目录内仅预期文件及清单本身SHA256，全部一致；比较依据是本机原清单，不信任仅由上传文件自报的摘要。随后再重验收据并逐包导入，对全部六种固定引用核对镜像ID、RepoDigest、linux/amd64及RootFS层，全部通过；Redis直接复用，不重传/重导入。

| 固定基础镜像 | 最终结果 |
|---|---|
| Redis8.2.9 | 已存在，身份/平台/层复核一致 |
| SeaweedFS4.51 | 新导入，身份/平台/层一致 |
| Temurin21.0.13_9-jre-noble | 新导入，身份/平台/层一致 |
| MySQL8.4.13 | 新导入，身份/平台/层一致 |
| Nginx1.30.6-alpine | 新导入，身份/平台/层一致 |
| RabbitMQ4.3.6-management | 新导入，身份/平台/层一致 |

收据及最终证据：`.cache/cloud-images/remaining-receipt-2026-11-04.json`、`.cache/cloud-images/remaining-import-2026-11-04.json`；执行入口原样保留在`.cache/cloud-images/verify-uploaded-remaining.py`。最终containers/volumes均为空，本轮只执行load/inspect，没有启动云端容器或本机Docker。本地93次blob校验来自Redis14与其余五包79，不能当作93个业务测试用例；六镜像一致也不是七服务运行或容量验收。

D.4至此收口。下一项建议M06.1-D.5：先核对源码白名单部署包的具体内容/大小，排除开发目录和秘密，再传输并验证目标机JAR/应用镜像构建；业务初始化与启动随后独立验收。没有运行Maven/JUnit或云端应用构建，不能写作业务已落地运行；不进入性能或冷备重跑。

本机及目标缓存保留公开包/部分失败证据，不含应用数据或密钥。账号和工具按用户授权保留；未购买额外云服务、未公开共享文件。M06.2/M07及前端状态保持原路线。
