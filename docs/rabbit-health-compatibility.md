# D.7.1-C.1 固定镜像兼容入口

当前：**关卡已执行，HTTP候选不兼容（缺客户端），按规则结束并清理。** [报告](reports/rabbit-health-compatibility-2026-11-07.md) / [已采用方案](rabbit-health-treatment-design.md)。原固定镜像没有curl/wget，仅有GNU timeout9.4和shell；未启动Broker、未生成部署秘密、未执行权限矩阵。不能进入C.2，也无需重复同一盘点。

入口为`scripts/run_rabbit_health_compatibility.py`，相关解析检查为`scripts/test_rabbit_health_compatibility.py`。只能通过原case_runner的job/cleanup角色运行，总300秒含120秒清理；不直接对既有实例执行。五项白名单为原监督、资源辅助、HTTP辅助及两个新兼容文件。

顺序固定：登记http-tools盘点意图→核对原镜像digest/版本→无网络/无秘密短命sh容器→保存允许列表工具信息→原ID清理。缺客户端或总截止工具即记录不兼容并结束，不生成部署实例。只有满足工具条件才允许另建http-broker资源scope，调用原Init、只启Rabbit并验证实际客户端和六项权限对照；该后半分支本轮未执行，不宣称已验证。

盘点适配只存在于新入口，登记单个固定镜像工具、instance=null；原通用资源辅助保持。create前先落账，DISPATCHED后缺席继续按未知结果拒绝，不用空查询自动通过。镜像VOLUME用tmpfs覆盖，无bind或匿名卷；不拉新镜像、不安装工具、不挂秘密/业务数据。

主要证据：`image-inventory.json`、`compatibility.json`、`resources-http-tools.json`、目标短检查日志及原监督回执。监督SUCCEEDED意味着按停止/清理规则结束，兼容结果必须读取`compatibility.json`，本轮为INCOMPATIBLE_TOOLS。相关5项检查验证解析和分支选择，不是业务或HTTP故障回归。

C.1-R[收尾选择提案](rabbit-client-image-design.md)已定稿、待选择。推荐先提前M07.1，备选一次有界派生镜像验证；未批准安装或构建。原探针、5秒预算和测量健康门槛保持，C.3/B.2未通过，前端暂停。
