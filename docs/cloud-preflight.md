# M06.1-D.2 阿里云部署条件核对

状态：2026-11-04，用户新ECS的只读条件核对已完成，[结果与缺项](reports/cloud-preflight-2026-11-04.md)。已通过专用密钥成功登录并读取系统资源；尚未安装Java/Maven/Docker/Compose或部署业务，Docker Hub探测超时待处理。下面保留检查方法及旧机连接故障历史，不据此认为旧机已恢复。

连接证据：受限终端首试返回本机网络权限错误；获准网络连接后，22端口TCP建立成功，但SSH在服务端版本交换/密钥认证之前返回`kex_exchange_identification: Connection closed by remote host`。没有提供私钥内容、没有进入密钥认证，也没有执行服务器命令。当前不能据此认定密钥错误、资源不足或具体云安全组原因；已请用户用原PowerShell命令核对手动登录结果，再决定后续检查。

2026-11-04：用户手动SSH同样返回`Connection closed by ... port 22`。当前不能仅凭双方报错定位服务器故障或中间网络策略，下一步从ECS控制台核对实例状态、当前IP及试用状态，再通过VNC查看系统/SSH日志；尚未取得这些结果，不修改密钥或SSH认证策略。排查入口参见[阿里云官方说明](https://help.aliyun.com/zh/ecs/troubleshooting-guidelines-when-you-cannot-remotely-log-on-to-a-linux-instance-through-ssh)。

## 已从源码确认的要求

- 当前完整showcase为七服务；配置内存上限合计5440 MiB（约5.31 GiB），是各容器上限之和，不是内存预留、实测最低需求或容量结论。还需考虑系统、已有服务及构建开销。
- 已验证的容器环境为Linux/x86_64；目标宿主的架构、Docker权限、发行版及安全策略仍需核对。
- 现有源码构建入口需要Python 3、JDK 21、Maven、Docker Engine及Compose插件；S3初始化另外需要OpenSSL及keytool。暂不改变构建和分发方式。
- 入口只映射宿主127.0.0.1:18080，其它数据和管理端口不发布。需要确认18080是否占用及SSH转发是否可用；本项不开放公网端口。
- 固定镜像与Maven依赖下载能力尚未验证。已安装Docker不代表镜像可获取，不直接替换固定镜像或配置未知镜像源。

来源：[基础配置](../deploy/base/compose.yaml)、[组件配置](../deploy/base/components.yaml)、[存储配置](../deploy/base/storage.yaml)、[部署入口](../scripts/deploy_base.py)、[Linux指南](linux-deployment.md)。

## 登录服务器后的只读检查

可在阿里云网页终端或原SSH客户端粘贴下面内容。不需要上传项目、输入数据库密码或使用sudo；缺少权限或命令时保留结果，之后再决定处理方法。不要粘贴环境变量、SSH配置全文、私钥或组件凭据。

```sh
printf '\n== OS / architecture ==\n'
awk -F= '/^(NAME|VERSION_ID)=/ {print}' /etc/os-release
uname -sr
uname -m
printf '\n== CPU / memory / load ==\n'
getconf _NPROCESSORS_ONLN
awk '/^(MemTotal|MemAvailable|SwapTotal|SwapFree):/ {print}' /proc/meminfo
cat /proc/loadavg
printf '\n== Disk space / inodes (current directory) ==\n'
df -Pk .
df -Pi .
printf '\n== Listening TCP ports (numbers only) ==\n'
if command -v ss >/dev/null 2>&1; then
  ss -H -ltn | awk '{n=split($4,a,":"); print a[n]}' | sort -nu
else
  printf 'ss unavailable\n'
fi
printf '\n== Tool versions ==\n'
for tool in python3 java mvn openssl keytool docker; do
  if command -v "$tool" >/dev/null 2>&1; then
    printf '%s: installed\n' "$tool"
  else
    printf '%s: missing\n' "$tool"
  fi
done
if command -v timeout >/dev/null 2>&1; then
  timeout 10s python3 --version 2>&1
  timeout 10s java --version 2>&1
  timeout 10s mvn --version 2>&1
  timeout 10s openssl version 2>&1
  timeout 10s docker version --format 'Client={{.Client.Version}} Server={{.Server.Version}}' 2>&1
  timeout 10s docker compose version 2>&1
  timeout 10s docker info --format 'Arch={{.Architecture}} CPUs={{.NCPU}} MemoryBytes={{.MemTotal}} Driver={{.Driver}} Security={{json .SecurityOptions}}' 2>&1
  timeout 10s docker ps --format 'Image={{.Image}} Ports={{.Ports}}' 2>&1
else
  printf 'timeout unavailable; bounded version/Docker checks skipped\n'
fi
```

以上命令尚未在目标服务器执行。当前目录磁盘结果不能代替Docker实际数据盘核对；端口列表不能证明云安全组或防火墙配置正确。工具输出可能带安装目录和现有镜像信息，转发结果前可遮盖与本项目无关的信息。

## 核对后交付与停止条件

2026-11-04补充：用户已报告新ECS创建完成、1 Mbps并确认绑定密钥/重启。再次按文件名检查指定目录后找到前导下划线的实际PEM，已完成SSH root认证及只读核对；未检查/输出私钥内容。项目忽略目录`.local/cloud/known_hosts`记录首次主机公钥，后续严格校验，未作云控制台带外核对。新机结果独立归档于[报告](reports/cloud-preflight-2026-11-04.md)；旧ECS故障保留，不默认继续改旧机。代金券约束对应的[compact4g预验证](compact4g-deployment.md)仍不是新机业务验收。

结合实际结果，确认可用资源、现有业务是否允许新增负载、Docker数据盘空间/权限、回环访问方式及工具缺项；必要时再补读Docker数据目录对应的文件系统。云安全组、主机防火墙、发行版特有策略及镜像获取条件未检查前明确列为待核对，不把本地报告冒充云端结果。

本项交付服务器条件结论及最小适配建议。资源不足、已有服务用途不明或缺少连接信息时先说明具体缺项；采购、安装、修改权限/安全组、停止已有服务及实际部署另按确定的范围推进。[路线图](roadmap.md)中的实机验收、固定负载及材料整理继续保留。
