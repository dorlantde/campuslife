# M06.1-D.4 新机工具与固定镜像准备

状态：2026-11-04，**M06.1-D.4已完成：工具/部署账号及六种原固定公开基础镜像均已验证**。[阶段记录](reports/cloud-tools-2026-11-04.md)。没有启动业务、迁移数据库、上传项目秘密或开放业务端口。下方传输命令是本次已执行记录，无需重传。

## 工具与权限

Ubuntu 22.04原签名软件源安装OpenJDK21/Maven；Docker官方HTTPS APT仓库安装Engine/Compose/Buildx，以独立Signed-By及公钥指纹`9DC858229FC7DD38854AE2D88D81803C0EBFCD88`验证，不运行未知一键安装脚本。

已验证JDK/javac21.0.12.1、Maven3.6.3、Docker29.9.3、Compose5.7.0、Buildx0.38.1；Maven满足[Spring Boot列出的最低工具要求](https://docs.spring.io/spring-boot/system-requirements.html)，不代表本项目已在新机编译成功。Docker安装依据[官方Ubuntu步骤](https://docs.docker.com/engine/install/ubuntu/)。版本来自本轮实际安装记录，未来候选可能变化。

[安装脚本](../scripts/prepare-cloud-tools.sh)仅用于明确的新Ubuntu22.04/amd64主机：保留原Ubuntu软件源，只增加项目命名的Docker签名源，拒绝接管已有Docker/冲突包或覆盖已有源文件；它不是当前已安装主机的一键重跑/升级脚本。无整机apt upgrade，选定包及所需依赖由APT维护。

经用户明确授权，[账号脚本](../scripts/prepare-cloud-access.sh)创建campusdeploy，复制新机现有公钥并加入Docker组；密钥登录、Docker API访问、专用项目目录可写均已验证。**Docker组拥有近似root的宿主能力**，没有将其描述为最小权限。没有配置免密sudo或更改现有root登录策略。私钥始终由本机SSH客户端使用，未检查或输出内容。

## 镜像条件与已停止的尝试

原六份公开基础镜像仍按Compose及Dockerfile固定摘要，包括双Redis共用的一份、MySQL/Nginx/RabbitMQ/SeaweedFS及JRE。新机真实固定摘要拉取在25秒预算内未完成；不等于永久不可达。DNS在不同网络中返回不同结果，指定独立解析IP仍出现TLS连接重置；原因未定位，未改系统DNS/hosts或关闭TLS验证。阿里云[官方加速器已停止同步最新镜像](https://help.aliyun.com/zh/acr/user-guide/accelerate-the-pulls-of-docker-official-images)，不能假定含本项目固定版本，更不静默换未知镜像站。

受控传输尝试只导出公开Redis镜像，未包含应用镜像、项目目录、文件卷、密钥或数据库。导出包的OCI原描述符与逐blob摘要已校验；本机重新load曾验证同一摘要/平台。后续要求传输SHA256、目标原RepoDigest/镜像ID、linux/amd64及RootFS层标识全部一致，才算该镜像可用；仅导出成功不算导入成功。[候选自动入口](../scripts/prepare-cloud-images.py)目前未完成端到端验证，不作为已可用的云端发布工具。

Windows SSH标准输入样本数分钟仅传输数MiB，已停止；随后SFTP样本在180秒内未完成。原因尚未定位，不能仅归咎于用户选择的1Mbps公网出带宽。没有批量传输其余镜像，没有将任何半包导入Docker。

## 用户本机的单样本对照测试

已由用户在自己的Windows PowerShell上传一份55,266,816字节（约52.7 MiB）的公开镜像包，并确认完成。以下是本次已使用的命令，**无需重复执行**；未取得传输耗时/速率，不能声称手动路径更快：

```powershell
scp -i "$env:USERPROFILE\.ssh\your-ecs-key.pem" -o "UserKnownHostsFile=D:/campuslife/.local/cloud/known_hosts" -o StrictHostKeyChecking=yes "D:\campuslife\.cache\cloud-image-probe\redis-named.tar" campusdeploy@ecs.example.com:/home/campusdeploy/.cache/campuslife-images/manual-redis.tar
```

目标目录已存在且归部署用户所有；该样本未含秘密，原始包SHA256为：

```text
c8cdeeba1698060b953b1401bc5c58431c78cfe0de3376c4d16597f8428287eb
```

用户确认后，云端字节数及SHA256与原包完全一致；导入前重验OCI原描述符及14个blob摘要，导入后原固定RepoDigest/镜像ID、linux/amd64和RootFS层与包内配置完全一致。首次检查因本机Docker未运行而在云端操作前退出，随后改从原样OCI包读取身份，不启动本机Docker。

云端以`--pull=never`、无网络、只读根及丢弃能力运行一次版本/cgroup探针：Redis8.2.9、CPU0–1、memory.max=134217728、memory.swap.max=0、pids.max=32；没有启动Redis服务监听。临时容器及其卷清理后containers/volumes均为空，保留已验证Redis镜像。原始结果见`.cache/cloud-image-probe/manual-redis-result-2026-11-04.json`。

样本通过后按下节准备其余五份原固定公开基础镜像，沿用明确的样本分发方式。自动入口仍没有完成自动传输端到端验证；此次成功的是用户侧SCP与后续独立核验/导入。没有增加ACR/OSS等新云服务、替换业务镜像或搬迁数据。JAR仍计划在目标机按原源码构建，构建与业务运行分开。

## 其余五份镜像：导出与上传记录（已完成）

新增本地导出模式，实际运行命令为：

```powershell
python scripts/prepare-cloud-images.py --export-remaining --output-dir .cache/cloud-images/remaining-public-2026-11-04
```

该模式不建立SSH连接、不拉取或运行镜像、不包含Redis/应用镜像；要求在`.cache/cloud-images`下使用全新目录，拒绝覆盖旧包。为保留原固定摘要，只添加/保留同版本标签，不清理别名。五份包的原OCI描述符、共79个blob及包SHA256已验证，清单位于该目录的`manifest.json`。本次为导出临时启动Docker Desktop；它自动恢复一个已有`redis`容器，导出结束正常退出Desktop，未删除任何已有容器/卷/镜像。退出Desktop不代表其他WSL发行版均已关闭。

| 包 | 字节数 |
|---|---:|
| chrislusf-seaweedfs.tar | 197619200 |
| eclipse-temurin.tar | 103273472 |
| mysql.tar | 254016512 |
| nginx.tar | 26977280 |
| rabbitmq.tar | 121082880 |

合计702,969,344字节（670.4 MiB，不含小型JSON清单）。目标父目录可写、待传目录未占用、磁盘空闲51,430,047,744字节。这里是上传前条件，不是这批包已到达云端。

用户已在自己的Windows PowerShell执行以下一条命令（无需重复）：

```powershell
scp -r -i "$env:USERPROFILE\.ssh\your-ecs-key.pem" -o "UserKnownHostsFile=D:/campuslife/.local/cloud/known_hosts" -o StrictHostKeyChecking=yes "D:\campuslife\.cache\cloud-images\remaining-public-2026-11-04" campusdeploy@ecs.example.com:/home/campusdeploy/.cache/campuslife-images/
```

该目录仅五份公开镜像包与摘要清单，没有项目源码、凭据或业务数据；不要改为上传整个`.cache`或项目目录。用户回复“已传完”后，先以本机原清单为准核对五包大小/SHA256及上传清单摘要，全部一致才执行导入。随后复核全部六种原固定引用的镜像ID、RepoDigest、linux/amd64和RootFS层，全部通过；Redis识别为已存在，没有重复导入。

只读收据和最终结果分别为`.cache/cloud-images/remaining-receipt-2026-11-04.json`、`.cache/cloud-images/remaining-import-2026-11-04.json`。最终containers/volumes均为空；本轮没有启动本机Docker、容器版本探针或云端业务，也没有改DNS/hosts/安全组。工具和公开镜像保留，旧失败半包未导入、未全局清理。

## 后续D.5已完成，下一项D.6

仅准备经文件白名单及摘要清单核对的源码部署包，检查源码/构建描述/必要部署脚本，不复制整个工作区，不包含`.local`、`.cache`、`.tools`、`.vscode`、私钥、真实数据库或上传文件。先给出具体文件范围与传输大小，再传输并在业务未运行时验证云端JAR/应用镜像构建。依赖下载条件、构建结果与实际制品摘要届时记录，不能用基础镜像就绪代替构建成功。业务实例初始化、启动和交易验收留给随后任务，仍只计划回环/SSH转发，不默认公网发布。

D.5现已按上述范围[完成构建与核验](cloud-build.md)，后续请从该记录的实际版本目录/构建清单继续，不重传源码或重跑Build。D.6实机运行验收尚未开始。
