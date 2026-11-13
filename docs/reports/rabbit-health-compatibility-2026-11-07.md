# D.7.1-C.1 固定镜像兼容关卡记录

日期：2026-11-07。**关卡已执行并按“缺工具即停”规则结束，HTTP候选兼容未通过。固定4.3.6镜像没有curl或wget，未启动Rabbit、未Init或生成部署秘密，也未进入C.2。** 原探针、权限、5秒预算和测量门槛保持。[已采用方案](../rabbit-health-treatment-design.md) / [入口](../rabbit-health-compatibility.md) / [结构化证据](rabbit-health-compatibility-2026-11-07.json) / [路线图](../roadmap.md)。

## 单次执行及原回执

用户明确采用C提案并从C.1开始后，执行唯一case `case-8f1d9d3fc764426e`。已采用稿指纹 `15b4918e8adbf6a132df6d5837b15bf9b87c748dba52865ad42efc0b84f77d90`，原文留`.cache/rabbit-health-compatibility/adopted-plan.md`；原方案整理报告保持，不追改为已实施。

原监督300秒含120秒清理，实际 **2.368秒**，job内部1.476秒、工具盘点1.038秒；manifest `bed2b38087f271c93bacc17a313eca3c38a743a1780512714d4cc735b0fb4d59`。监督回执SUCCEEDED、cleanup_verified=true，SHA256 `ce3707170c4d4366e7e3c78d4885eabda9c97c7f6e30dbe483b6fc57d6dd7fbd`。监督成功表示预定停止/清理协议完成，**不等于候选兼容或原探针问题解决**；兼容状态为INCOMPATIBLE_TOOLS / NO_HTTP_CLIENT。

## 固定镜像实际盘点

镜像RepoDigest `sha256:54cb013d76cd1fd6c0a0708f34062ad61c452bb6f016b434c6c33f1a765d2b58`，实际ImageId `sha256:54cb013d76cd1fd6c0a0708f34062ad61c452bb6f016b434c6c33f1a765d2b58`，linux/amd64、RABBITMQ_VERSION=4.3.6通过。下面是该实际镜像输出的允许列表字段，不是根据移动中的Dockerfile推测。

| 工具 | 实际路径/状态 | 已识别版本 |
|---|---|---|
| curl | absent | 不存在 |
| wget | absent | 不存在 |
| timeout | /usr/bin/timeout | GNU coreutils 9.4 |
| sh | /usr/bin/sh | 本轮未取版本 |
| bash | /usr/bin/bash | 本轮未取版本 |

无合适HTTP客户端，按已采用规则立即结束；没有尝试bash手工HTTP解析、Erlang HTTP替代、宿主客户端替代或在线安装工具。timeout存在不构成HTTP候选可用证明。

只创建一个32MiB、无网络、只读根、10001:10001降权、cap-drop ALL、pids16的短命盘点容器，入口显式为sh，不运行Rabbit。Rabbit数据目录以1MiB tmpfs覆盖，避免镜像VOLUME产生匿名卷；没有bind/volume秘密挂载、没有新增网络或宿主端口。工具信息不包含任意环境/argv/私钥/凭据内容。

## 辅助实现与检查范围

新增`run_rabbit_health_compatibility.py`和相关test，复用原case_runner、measurement_resources及HTTP辅助，共5份派发输入。原ResourceScope要求先生成部署实例才能登记应用工具，不适合“盘点先于秘密”；本轮仅在新入口中提供无部署instance的盘点适配，登记已核对Rabbit镜像ID后再派发create，沿用原标签/精确ID/未知创建拒绝和清理规则。通用资源辅助及旧守卫没有修改。

5个相关检查在本机和目标Python3.10均通过，目标0.002秒、无跳过；这是**同5项检查，不累计成10项**。覆盖缺客户端停止、curl选择、GNU wget识别、总截止工具必须存在、重复/未知字段/非法路径/超限输出拒绝。

本轮实际验证了秘密前盘点、真实缺客户端分支和原账本清理；**Broker启动、容器HTTP请求及正常/无/错误凭据六项对照分支均未执行**。入口虽包含这些条件分支，不能将源码存在或解析检查通过当作真实HTTP/权限兼容证据。没有实现部署候选脚本或C.2故障场景。

## 清理与正式输入

盘点容器原ID `dade6474150f894eca5c2c7d4e4670a97b5f76779ab6188ce4e216e9197731ac` 已移除，resources-http-tools为CLEANED、instance=null，登记清理容器1/卷0/网络0。无http-broker账本、无部署实例/生成秘密，work已移除，监督/job/cleanup均不再运行。

最终只读审计确认容器/卷/生成秘密空，仅默认bridge/host/none网络。三份正式release有效源460/462/463、JAR/镜像、历史夹具17/9/5保持；三旧诊断和本轮各自原回执/派发源保持。没有把本次执行称为第四轮旧A/B诊断。

本机463正式输入、27既有辅助、业务JAR及8旧报告指纹保持；只新增两份兼容辅助源和文档/证据，没有生产Java、DDL、依赖、正式Compose或原healthcheck变化，本机Docker未启动。没有安装软件、构建/下载新镜像、接触旧服务器或公网发布。

## 下一步

**C.2隔离实现和C.3部署集成暂停推进，不反复盘点同一镜像。** 下一项建议C.1-R：仅定稿“增加单一HTTP客户端”的最小依赖变更方案，比较固定原Rabbit基础镜像的派生镜像与保留现状，明确制品来源/指纹、兼容验证、回退和资源成本后再讨论采用。它涉及已采用提案明确禁止自动增加的工具，因此本轮不安装、构建或替换镜像，也不预先选定客户端版本。

原七服务C.3/B.2仍未通过，Pilot、正式测量和M07未执行；原故障未定因，前端暂停，无常驻/公网或新性能结论。
