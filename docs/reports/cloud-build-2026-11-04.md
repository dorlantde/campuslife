# M06.1-D.5 新机源码包与构建报告

日期：2026-11-04，America/New_York。**已完成：源码白名单包上传核验、云端JAR和应用镜像构建及制品一致性检查通过。** [复现与范围](../cloud-build.md)/[机器记录](cloud-build-2026-11-04.json)。没有启动业务、执行迁移或开放端口，前端暂停。

## 输入与传输

- 只读复核campusdeploy、新机项目目录为空、JDK21.0.12.1/Maven3.6.3、约3 GiB可用内存、盘空47,789,297,664字节，容器与卷为空。
- 官方Maven仓库两项当前POM有界IPv4探测均200，无依赖/版本/源/DNS/TLS变更。
- [新增打包入口](../../scripts/prepare-cloud-source.py)实际导出460文件，2,916,158字节；包648,759字节。包含后端/测试源码、Maven描述、部署配置及三个部署Python脚本，不含前端、开发目录、旧制品、实际凭据或业务数据。逐文件清单、tar成员范围/普通文件及摘要复核通过；shell LF保持。
- 包SHA256=`110d141b50c26d6f5a54d3615d8f0b8791e0b88bb848978b9c991cf33b790a10`。仅此小包通过严格known_hosts与既有密钥上传；未检查或输出私钥内容。目标先核对包/清单与460个成员的大小/摘要，再写入全新版本目录，防止路径越界/链接/覆盖旧版本。没有重复传输镜像或整个工作区。

实际源码目录`/home/campusdeploy/campuslife/releases/110d141b50c2`，暂存日志目录`/home/campusdeploy/.cache/campuslife-build/110d141b50c2`。权限沿部署用户的私有目录管理，没有sudo或root策略变更。

## 构建与验证

目标运行原`python3 scripts/deploy_base.py build`，仅设置本次构建的`MAVEN_OPTS=-Xmx768m -XX:ActiveProcessorCount=2`。单次执行完成，整体443.959秒；Maven日志6分56秒，首次解析下载依赖后编译285个主源码及116个测试源码，`-DskipTests`明确跳过执行。测试源码原有unchecked提示保留，没有据此扩大业务修复或声称测试通过。

随后使用原固定Temurin JRE和Docker白名单上下文构建应用镜像，没有将源码/仓库缓存放入镜像。两段构建各有600秒上限；没有失败重试或第二次构建，没有执行Maven verify、业务回归或容量测试。

| 证据 | 实际结果 |
|---|---|
| 云端JAR | 72,025,330字节；SHA256 `2ad77758b9f8fc2bc070a19bf169ff989b978df32c66e7e04eae01eb96f7bb71` |
| 与原本机制品比较 | 649文件条目内容摘要全部一致，无新增/移除/改变；394条目时间戳不同，整体JAR摘要不同 |
| 运行内容 | 156依赖和388业务类/资源逐条相同，无test-classes进入运行包；116测试源码已编译而非执行 |
| 应用镜像 | `campuslife-base:m061a-2ad77758b9f8-468b0df58bc6` |
| 镜像ID | `sha256:c27d02862aebbda20b37004bff4a105551fc1e3ff538c613d81945c05efb4512` |
| 镜像静态配置 | JAR摘要标签一致；linux/amd64；User10001:10001；RootFS前缀为原固定JRE层 |
| 源码与业务边界 | 构建前后460输入摘要一致，本机输入亦复核一致；未改Java/DDL/前端/默认配置 |
| 最终运行状态 | containers=[]、volumes=[]；无实例身份/秘密、业务数据库、监听端口或业务运行 |

不能把整体JAR摘要不同误认为业务内容变化，也不能称两个压缩包逐字节相同。镜像配置通过不替代进程启动、健康状态和真实交易；75操作/V24/32表沿既有源码范围，未在云端执行建表或接口验证。

## 证据与下一项

本机`.cache/cloud-build`保留source包/receipt、preflight、官方POM探测、source-transfer、build-result、Maven/image/build原日志、runtime及完整JAR对照。机器报告记录这些证据与交付脚本的SHA256。

保留云端源码、Maven缓存、构建记录、基础及应用镜像，未运行清理全局资源、未启动本机Docker。M06.1-D.5收口；下一项建议M06.1-D.6 compact4g七服务实机验收，先核对测试范围和新实例，再运行自有虚构数据的交易、资源、原卷重启后新写。冷备、综合负载、公开发布不纳入下一项默认范围；M04既有冷恢复超时根因仍未知。
