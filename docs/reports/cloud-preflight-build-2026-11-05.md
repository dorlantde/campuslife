# M06.1-D.6d 修复版本云端构建与制品核验

后续D.6e已[定向执行但未通过](cloud-remaining-acceptance-2026-11-05.md)：管理员目录权限工具补丁已验证，初次Resume被预检拒绝；原JAR/镜像保持，462构建输入中仅部署工具有已记录差异。下文保留D.6d静态核验事实，不能称运行验收已通过。

**结论：修复版本的独立云端JAR/镜像构建与静态核验通过；本轮没有启动业务，D.6整体仍未通过。** 新制品包含[D.6c预检修复](maintenance-preflight-2026-11-05.md)，不是旧JAR或临时编译类。本轮不重跑完整交易链、冷备或压测，前端暂停。[机器记录](cloud-preflight-build-2026-11-05.json)/[构建操作](../cloud-build.md)。

## 输入与传输

沿原[白名单入口](../../scripts/prepare-cloud-source.py)，仅增加可选task标签以准确标记本轮；文件选取/排除/路径及摘要校验规则保持。实际执行：

```powershell
python scripts/prepare-cloud-source.py --task M06.1-D.6d --output-dir .cache/cloud-build/source-preflight-2026-11-05
```

源码包462个文件、2,930,019字节，压缩后652,140字节（约637 KiB），包SHA256为 `5382d9dd9ca2a9162a4a5ca33e01c8b981d2821417e987e3dfef2150ef53343a`。只含原构建白名单：pom/Maven相对配置、src/main、src/test Java、deploy/base及三份部署Python。不包含开发目录/凭据/业务数据/前端/旧target或测试诊断挂载类。

相对最初D.5的460项输入，457项保持，三项变化为两份维护修复类和已验收的mysql-init子shell修复；新增两份预检测试。维护源码逐项匹配D.6c已验证输入，没有临时修改依赖/DDL来构建。相对当前旧release的已记录有效输入，mysql-init修复原已存在。

通过原campusdeploy/严格known_hosts SSH与SCP上传，逐文件校验大小/摘要及路径，写入**全新**目录：

- release：`/home/campusdeploy/campuslife/releases/5382d9dd9ca2`
- 包及构建证据：`/home/campusdeploy/.cache/campuslife-build/5382d9dd9ca2`
- 原release `110d141b50c2`保留，未覆盖旧源码/JAR/镜像。

## 单次构建结果

先核对新源码、旧制品、空容器/卷、宿主可用内存与磁盘。显式将新release的 `.cache/maven-repository` 链接到旧release的依赖库，复用已批准的Linux Maven缓存；不是复制Windows缓存。后续清理旧版本时须保留或迁移该共享构建缓存；运行镜像不依赖此链接。

实际使用原构建入口及原预算：

```sh
cd /home/campusdeploy/campuslife/releases/5382d9dd9ca2
MAVEN_OPTS='-Xmx768m -XX:ActiveProcessorCount=2' python3 scripts/deploy_base.py build
```

单次外层构建与核验91.229秒，Maven日志总时间1分09秒，编译285个主源码/118个测试源码，`Tests are skipped`。测试仅编译，未重复执行D.6c的35项检查，也没有启动Spring业务上下文或数据库迁移。此耗时属于本次缓存条件，不作为固定性能或与D.5下载阶段的收益结论。

新构建记录生成于UTC 2026-11-05 09:41:34（纽约04:41）。JAR为72,025,890字节：

```text
SHA256 4dfd3a9f1efdd8f344417ac5c1ebe989a1dfdd1dd689499f4c7978017e9cef00
image  campuslife-base:m061a-4dfd3a9f1efd-468b0df58bc6
ID     sha256:8d654a790e39c6dbd7d1910b83458a3b5a1318aa107517ab8d69d86283fbf367
```

## 内容与镜像核验

与D.6c本机已验证的新JAR（`2bc27269...`）对照：649个文件条目全部内容摘要一致，无新增/删除/内容变化，394个条目时间戳不同，大小/压缩类型/外部属性无其他差异。整体ZIP/JAR字节不相同，不为追求相同摘要修改时间戳。

与旧D.5云端运行内容对照，544个业务类/资源及依赖条目中仅 `CouponMaintenance.class`、`CouponRedisRecovery.class` 内容改变；156项依赖逐条保持，测试类未进入运行JAR。这同时排除了错误地重建成旧业务类的情况。

镜像JAR标签等于实际新JAR摘要；linux/amd64、User `10001:10001`、原entrypoint/cmd和固定JRE RootFS层前缀检查通过。JRE仍为原固定 `eclipse-temurin:21.0.13_9-jre-noble@sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f`。Docker上下文仍仅JAR/Dockerfile/app shell白名单。没有启动应用验证，不把镜像EXPOSE或构建成功称为开放端口/服务上线。

## 独立复核与边界

构建后新462项源码摘要保持；独立SSH审计再确认旧release原D.5的459项输入和唯一既有mysql-init差异、四份已记录D.6测试补丁、旧JAR/镜像均保持。共享Maven缓存可以被构建更新，不声称旧版本目录每个缓存文件不变。

最终containers/volumes/test networks/生成secrets均为空；旧目录17个历史夹具留证，新目录实例文件夹0。新旧源码、构建记录、Maven缓存和镜像保留，当前无测试或长期实例运行。本机Docker未启动，未操作旧ECS、用户数据、公网或前端。

原始证据在 `.cache/cloud-build/source-preflight-2026-11-05`：包/清单/传输回执、local-jar-baseline、build-result、artifact-comparison、delta-from-d5、最终审计和两份构建日志；公开机器记录保留摘要。包/制品核验、Python语法/文档链接检查通过；无新增业务测试数。

## 下一项

建议 **D.6e：新制品的剩余实机边界验收**。先核对定向入口/自有数据和断言范围，使用本次正式新JAR/镜像检查七服务就绪、原五卷重启后的Resume与新领券，以及未完成的资源/凭据/安全钩子；不再挂载替换业务类，不跑第四轮打印/付款/取件/评价整链，不重复冷备或压测。

该项尚未开始。保留原三轮失败，不把旧23项断言、D.6c检查和本次构建拼成一轮整链通过；依据新执行证据再判断剩余边界是否收口。D.6整体、D.7长期演示与综合测量仍未完成，前端暂停。
