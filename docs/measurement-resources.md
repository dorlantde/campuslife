# M06.2-B.2-C.1 测量资源归属与清理

本模块只负责测量资源的登记、核对与回收，尚不启动七服务测量、登录或采集指标。入口为Linux专用 `scripts/measurement_resources.py`，配套 `scripts/test_measurement_resources.py` 在[case_runner](case-runner.md)下验证。B.2-C其余接线和Pilot仍未完成。

## 先登记，再生成私有文件

每个case下的scope先用临时文件、fsync、原子替换及父目录fsync保存 `resources-<slot>.json`，再创建 `work/scopes/<slot>/deployment-root`。私有初始化文件只能落在这棵已登记的目录树内。

复用正式release `1cf0cb53a24a` 的部署实现，但只把当前Python进程中该模块的输出ROOT绑定到scope根目录。Compose文件、构建记录、正式源码/JAR/镜像仍从原绝对位置读取并核对。没有改动原部署源文件、配置或普通初始化入口；同一进程只能绑定一个scope，其他scope需独立工作进程，避免导入状态混用。

PREPARING阶段还不允许创建Docker资源。初始化完成并通过原校验后，保存实例ID/project、instance.json摘要、七服务镜像ID、五卷/三网络允许集合，才能进入REGISTERED。初始化中途停止或完整生成后、登记提交前停止时，可以回收这棵私有目录；若发现未登记Docker资源则拒绝，不能从一个可读清单倒推出创建授权。

## 变更和未知结果

Compose变更由scope包装器在调用前标记pending，正常返回后才清除；工作进程失踪且pending仍在时拒绝绿色清理回执。不能把已经看到的部分资源当作全部操作完成。

临时工具容器先登记固定名字、预期镜像及case/scope标签，再以DISPATCHED记录发起；确认真实容器身份后转为OBSERVED。原命令结果丢失时只按原名字、标签、镜像与原ID核对并接续登记，不自动再次create。如果DISPATCHED状态下还查不到容器，返回TOOL_CREATE_UNRESOLVED，保留原状态；一次“查无资源”不能证明服务端不会迟到创建。

此模块不提供对未知操作的通用自动接管。B.2-C后续适配器必须经这些登记接口发起变更，不能绕过包装器直接运行Compose/Docker后假定账本已覆盖。真实未知操作需要独立事实核对，不能通过手改pending或丢弃intent继续；测试脚本对刻意未发命令/已返回但未记账的场景有明确的测试证据修复，它不是生产恢复入口。

## 清理核对

清理先核对完整私有树无符号链接、实例清单摘要不变，并预检全部资源：

- 普通容器/卷/网络须同时匹配实例、test、showcase、project；容器另匹配服务名、镜像ID和compact4g标签。
- 卷/网络名必须属于登记的允许集合；网络按不可复用ID操作，卷删除前复核创建时间和挂载位置。
- 工具按固定名字、case/scope/kind标签及镜像核对；已记录ID后名字被另一个容器占用会拒绝。

全部预检通过才开始逐个按身份删除容器、网络、卷，删除前再次核对；没有全局prune。Docker资源确认为空后才删除scope私有目录并记CLEANED。重复清理只核对原身份没有重新出现，不重新创建或执行任务。身份不一致、清单漂移、符号链接、命令未知、删除失败或遗留资源均不能报告成功。

这是可信脚本、单操作者和case进程互斥下的归属保护，不是针对宿主/Docker管理员的安全隔离；卷名称检查不是针对恶意并发替换的原子CAS。监督器被SIGKILL或宿主重启时仍沿用B.1的UNKNOWN/人工核对边界，不声称自动恢复完成。

## 已具备的验证入口

在已经配置固定release和原镜像的Linux工具目录，准备一个新case spec，再按case_runner使用prepare/start/status/fetch：

```json
{
  "case_id": "case-0123456789abcdef",
  "task": "M06.2-B.2-C.1",
  "total_seconds": 480,
  "cleanup_seconds": 120,
  "script": "scripts/test_measurement_resources.py",
  "args": ["job"],
  "cleanup_script": "scripts/test_measurement_resources.py",
  "cleanup_args": ["cleanup"],
  "inputs": ["scripts/measurement_resources.py"]
}
```

检查要求开始时没有既有容器/卷；只创建停止状态MySQL容器、关联空卷/网络和无业务shell探针，测试初始文件、登记、未知创建和身份拒绝分支，不启动数据库或应用。测试故意制造的错误标签、未发命令intent、未落账返回、清单修改和链接由独立测试harness在断言拒绝后按证据修复；不能把这个修复当作资源库自行处理任意未知结果。

有关create选项已核对[Docker官方命令说明](https://docs.docker.com/reference/cli/docker/compose/create/)，测试使用显式服务与禁止拉取参数。实际轮次、失败记录、数量和制品保持见[报告](reports/measurement-resources-2026-11-07.md)。
