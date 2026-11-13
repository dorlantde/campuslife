# M06.1-D.5 源码部署包与云端构建

当前D.6i已[通过新正式包的剩余定向实机验收](reports/cloud-final-remaining-2026-11-06.md)，维护整改/剩余断言收口，资源清理；新正式release为1cf0cb53a24a。没有重跑完整打印交易，健康探针/SSH观察保留，下一项D.7交接方案定稿。以下保留各次构建历史，不把旧目录当新包。

最新D.6h已完成[维护初始化修复正式包构建/静态核验](reports/cloud-initialization-build-2026-11-06.md)：463项白名单/653227字节包，新release `/home/campusdeploy/campuslife/releases/1cf0cb53a24a`，单次88.580秒。新JAR6311c16a...与D.6g本机649条目内容全同、156依赖保持；云端未运行业务。原两版本保留、资源为空，D.6仍未通过。下一项D.6i剩余定向实机验收，先更新测试制品指纹，不第四轮整链。下文为历史构建记录。

后续D.6e执行[未通过](reports/cloud-remaining-acceptance-2026-11-05.md)，新release仅增加已记录的管理员目录权限部署工具补丁，其余461原输入及新旧JAR/镜像保持；初次Resume预检拒绝，资源已清理。下一项D.6f诊断，不把本构建记录当运行验收。

2026-11-05更新：D.6d已完成[修复版云端构建/静态核验](reports/cloud-preflight-build-2026-11-05.md)，使用同一白名单入口加 `--task M06.1-D.6d`，新release为 `/home/campusdeploy/campuslife/releases/5382d9dd9ca2`，新JAR/镜像记录在其 `.cache/deploy-base-build.json`。原release保留；新缓存显式链接旧Linux依赖库，清理旧版本时须保留/迁移共享构建缓存。没有启动业务，D.6整体仍未通过；下一项D.6e定向剩余实机验证。下文保留最初D.5构建事实，不把旧目录/制品当新版本。

状态：2026-11-04，**M06.1-D.5已完成：白名单源码包上传核验、目标机JAR及应用镜像构建通过**。[阶段报告](reports/cloud-build-2026-11-04.md)/[前置工具和镜像](cloud-tools-setup.md)。尚未初始化或启动业务，前端继续暂停。

## 明确的文件范围

本轮实际打包460个文件，共2,916,158字节；压缩包648,759字节。只包含：

- `pom.xml`、`.dockerignore`、可移植的`.mvn/maven.config`。
- `src/main`的Java、properties、SQL和Lua；`src/test`的Java源码，用于现有`-DskipTests package`的测试编译。
- `deploy/base`的Dockerfile、shell、YAML和conf。
- `scripts/deploy_base.py`、`scripts/deploy_storage.py`、`scripts/deploy_observability.py`。

不包含`.local`、`.cache`、`.tools`、`.vscode`、`.git`、前端、旧target制品、Windows专用`.mvn/vscode-settings.xml`、用户密钥、数据库和上传文件；测试中的虚构夹具不作为可登录的部署账号。测试脚本与s3-probe额外源码未纳入本包，不把本包当整个开发仓库副本。

[打包入口](../scripts/prepare-cloud-source.py)要求使用`.cache/cloud-build`下全新输出目录，拒绝覆盖旧包、非白名单扩展、符号链接和shell CRLF；同时检查私钥标识。实际执行：

```powershell
python scripts/prepare-cloud-source.py --output-dir .cache/cloud-build/source-2026-11-04
```

本机包及逐文件清单保留在该目录。包SHA256：

```text
110d141b50c26d6f5a54d3615d8f0b8791e0b88bb848978b9c991cf33b790a10
```

本轮通过现有campusdeploy及严格known_hosts校验，只上传此小包；不会把已成功的镜像重新传输。远端先核对包与内部清单SHA256、文件名集合、每个文件大小/摘要，拒绝链接和路径越界，再逐文件写入全新目录；没有调用不受约束的tar解压覆盖已有目录。

目标源码目录：`/home/campusdeploy/campuslife/releases/110d141b50c2`。上传包与构建日志暂存：`/home/campusdeploy/.cache/campuslife-build/110d141b50c2`。传输与解包核验通过，证据`.cache/cloud-build/source-transfer-2026-11-04.json`。

## 目标机构建

构建前复核campusdeploy、空项目目录、JDK21.0.12.1/Maven3.6.3、可用内存约3 GiB和空容器/卷。Maven官方仓库中当前Boot父POM及MyBatis-Plus starter POM探测均200；这只说明两项可达，不等于全部依赖已下载。没有更换依赖版本、镜像源、DNS或关闭TLS。

实际目标机执行的构建等价于：

```sh
cd /home/campusdeploy/campuslife/releases/110d141b50c2
MAVEN_OPTS='-Xmx768m -XX:ActiveProcessorCount=2' python3 scripts/deploy_base.py build
```

现有入口运行`mvn -B -DskipTests package`，随后通过原白名单Docker上下文构建镜像；Maven及镜像构建各有600秒上限。测试会编译但不执行，不使用本机Maven缓存、旧JAR或旧应用镜像作为云端构建结果。Maven仓库按原相对配置写入目标版本目录`.cache/maven-repository`，不会带入Windows绝对路径。

Docker构建上下文仍只允许新JAR、原Dockerfile及两个app shell文件，源码、测试、仓库缓存和凭据不会进入应用镜像。JRE继续使用D.4已验证的固定摘要；默认单实例和运行配置保持。

结束后核对源码未变、JAR依赖/内容与既有本机制品的差异、运行包不含test-classes、镜像标签对应JAR摘要、linux/amd64、User10001:10001及JRE层前缀。整体JAR摘要可能受构建元数据影响，须根据真实差异说明，不能只凭不同摘要判断代码变化，也不能不查内容便声称字节完全一致。

## 本次结果与后续复用

单次构建完成，整体约444秒，Maven日志记录6分56秒；编译285个主源码和116个测试源码，测试执行跳过，未连接业务数据库。初次下载依赖和构建插件的耗时属于该次环境记录，不作为性能基准。Maven仓库留在此版本目录，后续可复用；本轮不再次运行Build验证缓存加速。

目标JAR SHA256为`2ad77758b9f8fc2bc070a19bf169ff989b978df32c66e7e04eae01eb96f7bb71`，大小72,025,330字节。与既有本机制品相比，649个文件条目内容摘要全部一致，没有增加/删除/改变；其中394个文件条目时间戳不同。包含的156个运行依赖及388个业务类/资源均逐条相同，不能称两个JAR文件字节相同；本轮未调整构建时间戳以追求相同摘要。

应用镜像为`campuslife-base:m061a-2ad77758b9f8-468b0df58bc6`，ID=`sha256:c27d02862aebbda20b37004bff4a105551fc1e3ff538c613d81945c05efb4512`。JAR标签、linux/amd64、User10001:10001及原固定JRE的RootFS层前缀检查通过；只检查镜像构建/静态配置，没有启动应用。目标版本目录`.cache/deploy-base-build.json`是后续Init使用的实际构建记录，不使用本机旧记录代替。

构建后460个输入文件摘要保持；云端containers/volumes均为空。保留版本目录、应用镜像、Maven缓存及构建日志，没有生成实例秘密或修改本机业务源码/DDL/前端。本机Docker Desktop未启动。当前未验证云端数据库、文件、消息、券业务或完整4 GiB宿主运行稳定性。

下一项M06.1-D.6：按compact4g范围准备并执行新机七服务实机验收，先核对具体测试入口及自有数据范围，再初始化/启动自有测试实例，检查交易链、资源与原卷重启后的新写；不重跑冷备，不默认公网发布。该项尚未开始。

构建/依赖下载失败时保留日志与版本目录，先定位具体失败，不修改已验收业务/DDL或静默更换版本。本项不执行Init/StorageInit/Start/Admin，不生成实例凭据，不运行数据库迁移，不做云端业务或容量验收，不公开端口。随后实机运行验收另列任务；只使用回环与受控SSH转发。
