# M06.1-D.6a Resume 安全诊断操作记录

最新D.6c已[实现并定向验证维护预检](reports/maintenance-preflight-2026-11-05.md)；本机新JAR及云端源码类路径检查通过，正式云端新制品尚待D.6d核验。D.6整体仍未通过，当前无运行测试，不重跑第四轮整链。以下保留各阶段当时记录。

后续D.6b已[完成只读对照](reports/cloud-connection-comparison-2026-11-05.md)：首次连接超时、同上下文第二次成功，门闩/计数保持且未申请票据。当前下一项为[票据前预检提案](coupon-maintenance-preflight-design.md)待确认；尚未修复，D.6仍未通过。下文保留D.6a当时记录。

状态：**专项诊断完成，Resume失败，尚未修复**。[报告](reports/cloud-resume-diagnosis-2026-11-05.md)/[机器记录](reports/cloud-resume-diagnosis-2026-11-05.json)。D.6整体仍未通过；当前没有运行中测试，不自动再跑整链。

本次只上传[Python驱动](../scripts/diagnostics/coupon_resume_case.py)与[Java观察类](../scripts/diagnostics/CouponResumeTrace.java)，同目录保存。固定release为 `/home/campusdeploy/campuslife/releases/110d141b50c2`，远端诊断源码留于 `/home/campusdeploy/.cache/campuslife-resume/case-53000cbe2b9c`。不重新Build，不替换生产类，不上传整个工作区。

本次实际执行的目标机命令如下；它会创建新的隔离技术夹具，**并非要求现在再次运行**。输出目录必须是尚不存在的release `.cache/diagnostics`子目录，旧证据目录不能复用。

```sh
python3 -u /home/campusdeploy/.cache/campuslife-resume/case-53000cbe2b9c/coupon_resume_case.py --release /home/campusdeploy/campuslife/releases/110d141b50c2 --output-dir /home/campusdeploy/campuslife/releases/110d141b50c2/.cache/diagnostics/case-53000cbe2b9c
```

入口检查真实Linux宿主、无现有容器/卷、原JAR摘要；用原依赖编译额外观察类，再创建带唯一test身份的七服务。同步领券后技术SQL构造已有异步发行记录并等待暂停就绪，保留原五卷重启，执行一次原Resume并读前后快照；最终仅销毁自身测试资源及生成凭据。原D.6三轮业务证据不与本技术夹具累计。

SSH包装本轮最多780秒，超时先中断以进入清理，再给60秒清理机会；单次诊断CLI上限110秒。两者只是诊断包装，未改原维护CLI90秒或生产连接/命令/租约预算；实际单次371.011秒、CLI至结果53.892秒。正常退出0只代表取得诊断，请同时检查 `operationSucceeded`。

本地原始记录在 `.cache/cloud-resume/case-53000cbe2b9c/runner-result.json`、`final-audit.json` 和 `manifest.json`；本地目录被Git忽略，公开摘要与证据文件SHA保存在机器报告。目标机私有目录保留result/snapshots/trace日志、编译结果及原夹具日志。仅分享结构化安全异常类型/受控码；不粘贴原始连接异常message或未审查日志，不发送账号密码、token或密钥。

本次明确失败于 `CouponRedisStore.runId()` 的冷连接/INFO路径，尚未进入Redis重建写入，SQL票据仍在租约内。后续只建议D.6b做同上下文的冷/热只读对照和最小修复方案，不默认改超时、租约或增加Resume重试；D.7长期实例、压测及前端继续等待。
