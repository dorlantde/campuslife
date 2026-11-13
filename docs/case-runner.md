# M06.2-B.1 Linux 单次任务与回执

入口是 `scripts/case_runner.py`，用于将测量任务的生命周期与 SSH 通道分开。同一 case 只允许一次启动；连接断开后用原 ID 查询和取回结果，不能新开一轮替代未知结果。B.1 只覆盖受控 Python 任务、所属进程会话与本次 `work` 目录；七服务和测量数据的资源清理适配器留给 B.2。

## 使用条件与命令

需要 Linux、Python 3.10 及支持 pidfd 的内核。Windows 上只可检查源码语法，不能验证这些进程语义。任务脚本必须可信，不得脱离其会话；本工具不是安全隔离环境，也不允许把它当作任意 shell 执行器。

在项目根目录准备一个私有 JSON 文件，例如 `.cache/b1-example.json`。case ID 使用新的随机十六进制值；下面的 ID 仅为示例，重复使用不会再次运行。

```json
{
  "case_id": "case-0123456789abcdef",
  "task": "M06.2-B.1-example",
  "total_seconds": 10,
  "cleanup_seconds": 4,
  "script": "scripts/diagnostics/case_runner_fixture.py",
  "args": ["success"],
  "cleanup_script": "scripts/diagnostics/case_runner_fixture.py",
  "cleanup_args": ["cleanup-ok"]
}
```

在 Linux 上运行：

```sh
python3 -I scripts/case_runner.py prepare --spec .cache/b1-example.json
python3 -I scripts/case_runner.py start --case case-0123456789abcdef
python3 -I scripts/case_runner.py status --case case-0123456789abcdef
python3 -I scripts/case_runner.py fetch --case case-0123456789abcdef
```

`prepare` 固定脚本、可选 `inputs`、runner 源码和 Python 解释器的 SHA256，并保存输入副本；相对路径不能越界或经过符号链接。`start` 检查源与副本未变，持久化一次性启动标记后启动独立监督进程；进程的输入输出不连接 SSH。源发生变化应新建 case，不能修改已经启动的 case 来补跑。输入文件最多 32 个、每个最多 1 MiB；预算上限 1200 秒，清理预留最多 120 秒。不把密码/token放入参数、metadata 或输出；未来业务适配器必须通过本轮私有文件获取凭据。

`status` 的退出码 0 只说明查询成功。`fetch` 返回完整回执和回执 SHA256：任务及清理都成功才返回 0；已有失败回执返回 1；尚无回执或核验拒绝返回 2。并发或重复 `start` 不创建第二份执行，重复 `fetch` 不改写回执。

结果保存在项目内 `.cache/measurement-cases/<case-id>`，目录为当前用户私有。日志、输入和回执保留，不在任务完成后自动删除证据；临时工作区由明确的清理脚本删除。

## 截止、进程与结果边界

总预算从写入启动信息时计算，包含清理。工作阶段提前让出清理预留，监督器同时使用单调时钟和绝对时间的较早截止，不因查询或重新连接而续期。这是有界协作执行，不是宿主失去调度能力时仍可保证的硬实时截止。

监督器保留直接子进程的退出状态而暂不回收，使会话首进程的 PID 在清理前不可复用；对会话内存活进程逐个核对 UID、boot ID、PID、出生 ticks 和会话，再通过 pidfd 发 TERM/KILL。拒绝只凭旧 PID 终止进程。任务退出 0 后仍遗留子进程也判为失败。收到监督器 TERM/INT 时停止工作并进入有界清理，记录 `CANCELED`。

清理脚本需退出 0，所属进程必须结束，`work` 必须不存在，并生成以下精确结构的 `cleanup-proof.json`，身份值从 `CASE_ID` 和 `CASE_MANIFEST_SHA256` 环境变量读取：

```json
{
  "case_id": "case-0123456789abcdef",
  "manifest_sha256": "本轮manifest摘要",
  "clean": true,
  "resources_remaining": []
}
```

仅凭脚本退出 0 不证明清理完成。缺证明、清理非零退出、清理超时或残留工作区均为 `CLEANUP_FAILED`，同时保留原 `task_outcome`。回执用临时文件、fsync、原子替换及父目录 fsync 发布；读取时核对 case、manifest、launch、task 和冻结输入身份。

启动标记已落盘但启动信息缺失时为 `UNKNOWN_LAUNCH`；监督器消失且无回执为 `UNKNOWN_SUPERVISOR`。这些状态不触发接管或再次启动。监督器被 SIGKILL、宿主重启、可信脚本自行 setsid 或创建外部资源，均超出本轮自动清理保证；必须按身份人工核对。B.2 须对 Docker、网络、卷和实例秘密增加自有资源核验与清理，不能把本轮空证明直接用于七服务。

Linux 专项验证入口：

```sh
python3 -I scripts/test_case_runner.py --output .cache/case-runner-checks.json
```

它只创建短 Python 进程和私有测试目录，不启动 Docker 或访问业务数据。故意失败的清理用例由测试 harness 在断言后清掉其工作区，原失败回执仍保留，不能把 harness 收尾算成 runner 自身清理成功。具体实测结果见[报告](reports/case-runner-2026-11-06.md)。

实现依据：Python 3.10 的 [waitid/WNOWAIT 与 pidfd_open](https://docs.python.org/3.10/library/os.html)、[pidfd_send_signal](https://docs.python.org/3.10/library/signal.html) 和 [Popen 会话/stdio选项](https://docs.python.org/3.10/library/subprocess.html)。
