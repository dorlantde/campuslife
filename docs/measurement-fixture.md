# M06.2-B.2-A：V24 测量数据夹具

后续B.2-B[请求/轮询/统计工具](measurement-driver.md)与C.1[资源底座](measurement-resources.md)已独立验证；下一项C.2指标/事实，再C.3总接线，Pilot仍未执行。原A入口未改为新资源模块，只有总接线完成后才能声称组合使用这些保护。以下保留A的数据与验证范围。

这是综合测量的计时外准备，不执行测量请求。入口位于独立的 `src/measurement/java/campuslife/measurement`；不属于 Maven 默认 source set，不打入生产 JAR，也不覆盖 `com.campuslife` 类。原 `PublicShopQueryFixture` 的 V23/Windows 目录保护保持不变。

## 业务数据与范围

本夹具在全新自有测试库中生成以下数据：

- 1,000家虚构店，理发/打印各500家；可见600家，其余覆盖待审、暂停、经营账号停用和店主停用。
- 500家理发店各20条历史完成订单，热点店额外1,000条，共11,000条订单、支付事实和评价，55,000条事件；排班、日期保护行及CONSUMED占用保持一致，保留隐藏评价与500行评分版本。
- 180名正常学生分3组，每人有非空历史订单；学生共用本批随机生成的临时密码，保存于本轮私有文件，数据库中使用随机盐BCrypt摘要。管理员/店主使用另一个不可知随机密码摘要，学生密码不能用于这些账号。
- FORMAL配置3个库存20的活动；PILOT配置定义为1个库存2的活动。均为合法满减、已发布、ASYNC/PAUSED，领取窗口约2小时、使用窗口约3小时。没有手工打开SQL门闩、写Redis库存或发行券。

历史订单、支付、审核与活动发布是**SQL合成事实**，没有走申请、支付或履约接口。数据规模用于可重复实验，不代表真实经营量；数据插入与ANALYZE不计入后续HTTP延迟。

生成的 `expected.json` 包含固定学生/分组/本人订单数量与倒序首20个ID、600个公开店ID、六个详情目标、可见评价总和/数量/平均分及活动ID/期限。以后驱动按这些预期校验响应。文件不包含密码、摘要或token；密码单独存放且任务清理时删除。

## 两层写入保护

`scripts/measurement_fixture.py` 先用既有 Deployment 校验当前生成目录、kind=test、showcase/compact4g、非恢复/非模拟资金配置；核对MySQL容器、镜像、服务/实例/project标签、真实数据卷标签及名称、私有core网络与无发布端口，随后获取该服务器的UUID/hostname。凭据只在通过这些核对后提供给工具。

Java夹具再次核对case/实例/project/卷/容器标识、固定schema、实际服务器UUID/hostname和datadir，要求V24成功迁移、32张应用表、原始两条分类字典，其他全部业务表为空。单独数据库名不能解锁写入。它信任宿主刚检查并私下传入的permit，不是对拥有Docker或宿主写权限者的安全隔离。

种子写入使用独立连接、命名互斥及一个事务；持锁后再次检查空库。写入/关系检查失败则回滚；成功提交后才执行会隐式提交的ANALYZE。未知结果再次seed会拒绝已有事实，不覆盖旧数据。180个学生都有订单、公开资格、历史支付/事件、排班范围与无重叠、活动关闭状态及零新交易事实都有检查。

## Linux 验证入口

`scripts/test_measurement_fixture.py` 是 **case_runner的job/cleanup适配入口**，不能直接脱离case运行。当前仅允许已核对的ECS正式release `1cf0cb53a24a` 和固定JAR/镜像，开跑先核对463项正式源和制品。运行前要求没有其他容器或卷；不启动本机Docker或读取开发库。

在具备这些源文件和既有正式release的Linux工具目录，使用新的case ID准备以下spec，再按[case_runner指南](case-runner.md)调用prepare/start/status/fetch：

```json
{
  "case_id": "case-0123456789abcdef",
  "task": "M06.2-B.2-A",
  "total_seconds": 360,
  "cleanup_seconds": 120,
  "script": "scripts/test_measurement_fixture.py",
  "args": ["job"],
  "cleanup_script": "scripts/test_measurement_fixture.py",
  "cleanup_args": ["cleanup"],
  "inputs": [
    "scripts/measurement_fixture.py",
    "src/measurement/java/campuslife/measurement/V24MeasurementFixture.java",
    "src/measurement/java/campuslife/measurement/V24FixtureChecks.java"
  ]
}
```

它只启动当前showcase配置中的MySQL及一个独立工具容器，Flyway在这个新库先迁移V23验证拒绝、再迁移V24验证种子。工具复用正式JAR中的156个依赖和现有应用镜像；不启动Spring应用。内存256MiB、禁额外容器Swap、CPU0/1、heap128MiB、只读根、无额外能力/宿主端口；以部署用户UID/GID读取本轮私有文件。只挂工具class/依赖/迁移脚本/专用DB与学生凭据/输出，不挂JWT、root数据库密码、S3身份、CA私钥或Docker socket。

结束后检查回执和checks/expected/host-checks，保留证据；清理会核对本case登记的工具身份，再用原DestroyTest移除本次MySQL/网络/卷和生成秘密，最后移除work。故意回滚与拒绝检查保留，不当作接口旅程或性能样本。

B.2-A只验证数据侧，不包含请求调度或指标/七服务总适配器；请求工具后来按B.2-B独立受控验证。当前初始化完成与登记之间的强杀恢复、工具部分创建/结果丢失的接管仍需B.2-C收口；本适配器不提供自动接管或覆盖宿主重启的保证。A实际验证范围及结果见[报告](reports/measurement-fixture-2026-11-07.md)。
