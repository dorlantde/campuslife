# M06.1-D.6h 维护初始化修复的正式云端制品

后续[D.6i新正式包定向实机验收](cloud-final-remaining-2026-11-06.md)单次通过32项＋5组配置，维护整改/剩余断言收口；资源清理、探针/SSH观察保留。下文保持本次只构建/静态核验的事实。

**结论：独立新release构建与静态制品核验通过；没有运行业务，D.6整体仍未通过。** 新正式包包含[D.6g维护初始化预算隔离](maintenance-initialization-budget-2026-11-05.md)，不再需要临时业务类覆盖。[机器记录](cloud-initialization-build-2026-11-06.json)/[构建入口与边界](../cloud-build.md)。

## 白名单与新目录

使用原[打包工具](../../scripts/prepare-cloud-source.py)，本轮只增加D.6h可选任务标签，文件选取、排除及安全校验规则不变。没有修改业务Java、依赖、DDL或前端。实际执行：

```powershell
python scripts/prepare-cloud-source.py --task M06.1-D.6h --output-dir .cache/cloud-build/source-initialization-2026-11-05
```

463项源码共2,933,607字节，压缩包653,227字节；包SHA256 `1cf0cb53a24acaa99d684222d99db544d8a979a84ae2b394668834e586e0b001`。缓存目录按纽约11月5日开始打包的日期命名，构建完成于纽约11月6日00:01；未重命名旧证据。

相对D.6d原462项清单，460项保持、两项变化、一项新增：变化为D.6g的CouponRedisConfiguration和既有deploy_base管理员输入目录权限补丁；新增CouponRedisConfigurationTest。相对当前D.6e有效源码，461项保持，只有一份配置源变化并新增该测试。文件摘要与上一轮已验证的本机输入匹配，未带入诊断类或临时覆盖。

包经原campusdeploy和严格known_hosts校验上传，目标逐文件核对路径、大小与摘要，只写入全新目录：

- release：`/home/campusdeploy/campuslife/releases/1cf0cb53a24a`
- 包及构建证据：`/home/campusdeploy/.cache/campuslife-build/1cf0cb53a24a`

两个旧release `110d141b50c2`、`5382d9dd9ca2`均保留。不上传整个工作区、本机大JAR、Windows缓存、凭据或业务数据。

## 单次原入口构建

构建前复核源码、前两份制品、空容器/卷、内存与磁盘。新release的 `.cache/maven-repository` 显式链接原 `110d141b50c2` 中的Linux依赖缓存；缓存可更新，运行镜像不依赖该链接，后续清理旧版本须保留或迁移缓存。

```sh
cd /home/campusdeploy/campuslife/releases/1cf0cb53a24a
MAVEN_OPTS='-Xmx768m -XX:ActiveProcessorCount=2' python3 scripts/deploy_base.py build
```

仅执行一次，外层构建及核验88.580秒，Maven日志1分06秒。编译285份主源码、119份测试源码，`Tests are skipped`；本轮没有执行测试、启动Spring业务上下文或迁移数据库。耗时仅属于本次缓存条件，不当作性能基准。

正式记录生成于UTC 2026-11-06 05:01:25（纽约00:01）。JAR为72,026,099字节：

```text
JAR SHA256 6311c16a12b2a756353150cefd0ac9a2e7074194a8012204326c85df3df3bd12
image      campuslife-base:m061a-6311c16a12b2-468b0df58bc6
image ID   sha256:7656904023ca692349cf4606576c06d271ea9cd9588ec7c0000c3fbcfae291d6
```

## 制品与旧版本核验

与D.6g本机已通过相关回归和启动的新JAR `67b6c80b...` 比较：649个文件条目集合及内容摘要全部相同；394个条目时间戳不同，文件大小、压缩类型及外部属性无其他差异。整体JAR摘要不同，不能称ZIP字节相同。

与D.6d旧正式JAR `4dfd3a9f...` 比较，只有CouponRedisConfiguration外部类和Enabled内部类两个class内容变化；156项依赖逐项保持。没有测试类进入运行包，仍75操作/V24/32表。静态一致性承接上一轮已验证代码，不能替代云端正式入口运行验证。

镜像JAR标签对应实际新摘要；linux/amd64、User10001:10001、原entrypoint/cmd、原固定JRE的RootFS层前缀均通过。Docker上下文仍仅JAR、Dockerfile和两个app shell。没有启动业务、公开端口或初始化演示实例。

独立SSH审计确认：新463项源摘要保持；前一正式release的462项有效源码及七份既有辅助工具、最初release的源码/已记录补丁，以及两份旧JAR/镜像均保持。新目录未覆写旧版本，也没有复制原测试实例。

## 收口与下一项

最终containers、volumes、测试网络、探针网络、生成secrets与探针秘密目录均为空。旧历史夹具目录为17/9，新release实例目录0；保留源码、构建记录、缓存和镜像。本机Docker未启动，没有运行中构建或测试。

原始证据位于 `.cache/cloud-build/source-initialization-2026-11-05`，机器报告记录包/清单/传输、源变化、本机制品基线、云端结果、制品对照、独立审计及两份日志的摘要。构建期间仅一次只读查询进度，没有重新构建或调参。本轮不新增测试通过数，D.6g的31项相关检查仍是上一轮证据。

下一项建议 **M06.1-D.6i：新正式包的剩余定向实机验收**。先核对并更新测试入口中的旧JAR/镜像指纹及自有虚构数据范围，再用正常维护main检查Resume/首次领券、原五卷重启后的Resume/新领券，以及剩余资源/凭据安全钩子。沿用原预算，不替换挂载业务类、不重跑完整打印交易或冷备/压测，不默认公网或长期实例。

D.6i尚未开始；遇到失败保存现场允许的安全证据后清理并停止，不自动扩大超时或反复重跑。旧失败不被本次静态通过覆盖，D.6/D.7和综合测量仍等待，前端暂停。
