# M06.1-D.6e 新制品剩余实机边界验收

后续[D.6i新正式包定向验收](reports/cloud-final-remaining-2026-11-06.md)单次32项＋5组配置通过，维护整改与剩余断言收口，资源已清理；原D.6e失败不改写。入口当前固定D.6h制品，复现范围见[新指南](cloud-final-remaining-acceptance.md)。下文保留D.6e原执行与旧固定值，不直接套用到新包；下一项D.7交接方案定稿，探针/SSH观察保留。

状态：**本轮执行结束，未通过**；[报告](reports/cloud-remaining-acceptance-2026-11-05.md)/[机器记录](reports/cloud-remaining-acceptance-2026-11-05.json)。首轮管理员目录权限已修复，末次Resume被连接预检拒绝，重启/新领券/最终资源钩子尚未执行。资源已清理，无运行中测试，不自动重跑；下一项仅建议D.6f预检分阶段诊断。

使用D.6d正式云端制品，release为 `/home/campusdeploy/campuslife/releases/5382d9dd9ca2`，JAR SHA为 `4dfd3a9f1efdd8f344417ac5c1ebe989a1dfdd1dd689499f4c7978017e9cef00`，镜像ID为 `sha256:8d654a790e39c6dbd7d1910b83458a3b5a1318aa107517ab8d69d86283fbf367`。本轮未挂载替换业务类；只修复部署工具管理员临时目录的显式权限，原JAR/镜像与业务源码保持。

## 已核对的范围与入口

新增[定向入口](../scripts/test_deploy_remaining.py)，复用原compact4g/真实宿主/安全钩子。首次七份白名单Python输入107,062字节；权限修复后的第二次八份有效输入135,210字节，含已记录的deploy_base.py补丁。六份现有辅助测试工具加入新release的scripts，不在462项原构建清单中；清单内仅部署工具一项已记录差异，其他461项保持。主入口在私有暂存目录。虽然辅助模块导入原showcase定义，入口只调用本轮定向流程，不调用完整打印交易main。

实际目标机入口：

```sh
python3 -u /home/campusdeploy/.cache/campuslife-remaining/case-e50cd28a2f4a/test_deploy_remaining.py --release /home/campusdeploy/campuslife/releases/5382d9dd9ca2 --output-dir /home/campusdeploy/campuslife/releases/5382d9dd9ca2/.cache/acceptance/case-e50cd28a2f4a
```

该入口会创建新的自有test资源，输出目录必须未存在；命令保留为本轮复现记录，不要求额外再运行。外层最多900秒，超时先中断，再给90秒清理机会；原维护CLI90秒、客户端300/500ms、30秒租约和5分钟业务期限均保持。

先核对真实Linux宿主、当前无容器/卷、正式JAR/镜像身份，再执行五组原默认/profile配置检查。新建showcase/compact4g/observe自有实例，模拟资金关闭，仅Nginx随机回环端口；其余组件及管理面保持私有。

计划中的虚构数据通过原接口和维护入口生成：学生注册登录、显式AdminBootstrap创建测试管理员、商家申请/审核、发布两张库存优惠券、原Pause/Async/Resume、第一次真实202发行；再Pause保留原五卷/24份凭据指纹重启、Resume及第二次发行。没有SQL播种业务资格或异步元数据。**实际末次只完成活动发布、Pause/Async，初次Resume预检拒绝，后续步骤未执行**；不能将此计划当作已完成结果。

此后执行原资源/凭据钩子：实际cgroup内存/CPU/Swap/OOM及MySQL参数，拒绝reinterpret资源profile，拒绝未经验证的compact冷备freeze，host oom_kill计数变化检查，生成凭据及本轮密码/token不进服务日志。另查管理面拒绝匿名/JWT、应用不挂载八类运维/root秘密、75操作/V24/32表等边界。

## 完成与停止条件

同一执行中必须通过全部定向断言及最终钩子，并清理准确归属本任务的容器、卷、网络和生成凭据，保留结构化结果/失败日志。不得把旧轮次的通过项加进本轮数目，不因失败扩大业务超时或租约，不做全局prune或停止Docker系统服务。

采样包含常驻及有机会捕获的临时CLI；旧内核缺memory.peak时保留null，采样峰值不当内核峰值，不能保证捕获全部短命进程或瞬时峰值。只验证当前单节点轻载边界，不作容量、HA或固定RTO承诺。

本轮不创建订单/资金/上传业务文件，不运行第四轮完整打印/付款/取件/评价链，也不重复冷备或压测。旧三轮整链失败保持历史事实；依据本轮新制品的缺陷回归/剩余钩子证据判断整改是否收口，不能声称新增一轮完整整链通过。D.7长期实例/公网发布及前端仍未启动。
