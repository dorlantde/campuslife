"""Validate and archive completed M02.3 paired read/fault/write experiments (no database access)."""
import argparse
from datetime import date
import hashlib
import json
import math
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def stats(values):
    ordered = sorted(values)
    return {'n': len(values), 'p50': ordered[math.ceil(len(values) * .5) - 1],
            'p95': ordered[math.ceil(len(values) * .95) - 1], 'max': ordered[-1]}


def read(folder):
    folder = folder.resolve()
    assert folder.parent == ROOT / '.cache/redis-it' and re.fullmatch('[a-f0-9]{32}', folder.name)
    marker = json.loads((folder / 'completed.json').read_text(encoding='utf-8-sig'))
    assert marker['phases'] in (['Acceptance'], 'Acceptance') and marker['redisRemoved'] is True
    log_path = folder / 'acceptance.log'
    log = log_path.read_text(encoding='utf-8-sig')
    for phrase in ['BUILD SUCCESS', 'CACHE_ACCEPTANCE PASS', 'Tests run: 190, Failures: 0, Errors: 0, Skipped: 0',
                   'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0',
                   'PASS: Java 21 jar starts in mysql profile', 'PASS: isolated MySQL migration']:
        assert phrase in log, phrase
    raw = json.loads((folder / 'acceptance.json').read_text(encoding='utf-8'))
    assert raw['status'] == 'passed' and len(raw['reads']) == 122 and len(raw['writes']) == 12 and len(raw['faults']) == 6
    for name, digest in raw['sourceSha256'].items():
        path = (ROOT / name).resolve()
        assert path.is_relative_to(ROOT) and hashlib.sha256(path.read_bytes()).hexdigest() == digest, name
    total = 0
    for stage in raw['reads']:
        assert stage['errors'] == 0 and stage['n'] == len(stage['samplesMs'])
        assert all(v > 0 for v in stage['samplesMs'])
        assert stage['sqlDelta']['sql'] == stage['n'] * 2 + stage['sqlDelta']['aggregates']
        total += stage['n']
    assert total == 4656
    for stage in raw['writes']:
        assert len(stage['samples']) == 32
        assert stage['sqlDeltaIncludingFinalGenerationAdvance']['revisionWrites'] == (65 if stage['versioned'] else 1)
        assert len(stage['revisionStatementMsIncludingFinalAdvance']) == (65 if stage['versioned'] else 1)
    return {'folder': str(folder.relative_to(ROOT)).replace('\\', '/'), 'marker': marker,
            'logSha256': hashlib.sha256(log_path.read_bytes()).hexdigest(), 'raw': raw}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', nargs=2, required=True, type=Path)
    parser.add_argument('--date', required=True)
    args = parser.parse_args()
    date.fromisoformat(args.date)
    runs = [read(path) for path in args.runs]
    assert runs[0]['folder'] != runs[1]['folder']
    assert runs[0]['raw']['sourceSha256'] == runs[1]['raw']['sourceSha256']
    prior = json.loads((ROOT / 'docs/reports/redis-rating-cache-2026-10-28.json').read_text(encoding='utf-8'))
    for name, digest in prior['sourceSha256'].items():
        if name.startswith('src/main/') or name == 'pom.xml':
            assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == digest, name
    assert not any((ROOT / '.cache/mysql-it').iterdir())
    summaries, cold, write_summary = [], [], []
    for number, run in enumerate(runs, 1):
        for scale in [1000, 10000]:
            for scenario in ['normal', 'hot', 'empty']:
                for workers in [1, 4, 16]:
                    summary = {'run': number, 'scale': scale, 'scenario': scenario, 'workers': workers}
                    for enabled, label in [(False, 'off'), (True, 'on')]:
                        stages = [s for s in run['raw']['reads'] if s['kind'] == 'warm' and s['scale'] == scale
                                  and s['scenario'] == scenario and s['workers'] == workers and s['cache'] == enabled]
                        assert len(stages) == 3
                        values = [value for stage in stages for value in stage['samplesMs']]
                        item = stats(values)
                        item['aggregateSql'] = sum(s['sqlDelta']['aggregates'] for s in stages)
                        item['sql'] = sum(s['sqlDelta']['sql'] for s in stages)
                        item['batchCompletedPerSecond'] = sum(s['n'] for s in stages) / (sum(s['wallMs'] for s in stages) / 1000)
                        if enabled:
                            item['redisHits'] = sum(s['after']['redis']['hits'] - s['before']['redis']['hits'] for s in stages)
                            item['redisMisses'] = sum(s['after']['redis']['misses'] - s['before']['redis']['misses'] for s in stages)
                            item['redisErrors'] = sum(s['after']['redis']['errors'] - s['before']['redis']['errors'] for s in stages)
                            assert 0 <= item['redisHits'] <= item['n'] and item['aggregateSql'] >= 0
                        else:
                            assert item['aggregateSql'] == item['n']
                        summary[label] = item
                    summaries.append(summary)
        for stage in run['raw']['reads']:
            if stage['kind'] != 'warm':
                cold.append({'run': number, 'scale': stage['scale'], 'scenario': stage['scenario'], 'workers': stage['workers'],
                             'requests': stage['n'], 'aggregateSql': stage['sqlDelta']['aggregates'],
                             'coalesced': stage['after']['loads']['coalesced'] - stage['before']['loads']['coalesced'],
                             'waitTimeouts': stage['after']['loads']['waitTimeouts'] - stage['before']['loads']['waitTimeouts'],
                             **stats(stage['samplesMs'])})
        for workers in [1, 8]:
            for versioned in [False, True]:
                stages = [s for s in run['raw']['writes'] if s['workers'] == workers and s['versioned'] == versioned]
                row = {'run': number, 'workers': workers, 'versioned': versioned,
                       'create': stats([v['createMs'] for s in stages for v in s['samples']]),
                       'hide': stats([v['hideMs'] for s in stages for v in s['samples']]),
                       'rowLockTimeMs': sum(s['allInnoDbRowLockDelta']['Innodb_row_lock_time'] for s in stages),
                       'rowLockWaits': sum(s['allInnoDbRowLockDelta']['Innodb_row_lock_waits'] for s in stages)}
                write_summary.append(row)
    evidence = {'date': args.date, 'status': 'verified', 'productionChanged': False, 'operations': 62,
                'migrations': 'V1-V16', 'applicationTables': 24, 'runs': runs, 'warmSummary': summaries,
                'coldSummary': cold, 'writeSummary': write_summary, 'formalWarmHttpSamples': 8640,
                'coldExpiryHttpSamples': 672, 'writeTransactionSamples': 1536,
                'junitCounting': '190 ordinary/Web and 1 explicit acceptance test per run; repeated cases are not unique additions',
                'allFixtureMysqlCleaned': True}
    hot_single = '、'.join(f"{s['off']['p50']:.2f}→{s['on']['p50']:.2f} ms" for s in summaries if s['scale']==10000 and s['scenario']=='hot' and s['workers']==1)
    hot_concurrent = '、'.join(f"{s['off']['p50']:.2f}→{s['on']['p50']:.2f} ms" for s in summaries if s['scale']==10000 and s['scenario']=='hot' and s['workers']==16)
    total_on = sum(s['on']['n'] for s in summaries)
    aggregate_on = sum(s['on']['aggregateSql'] for s in summaries)
    hits_on = sum(s['on']['redisHits'] for s in summaries)
    errors_on = sum(s['on']['redisErrors'] for s in summaries)
    runtime = runs[0]['raw']['runtime']
    baseline_tail = next(s['create']['p95'] for s in write_summary if s['run']==2 and s['workers']==8 and not s['versioned'])
    actual_tail = next(s['create']['p95'] for s in write_summary if s['run']==2 and s['workers']==8 and s['versioned'])
    tail_note = (f'第二轮8线程省略组创建p95为{baseline_tail:.2f} ms，实际版本组为{actual_tail:.2f} ms。'
                 '未采集足以定位尾部波动的GC/调度轨迹，不归因于具体组件，也不据此反推增加版本维护会更快。原始样本保留，不剔除离群值；若需要尾延迟SLA，应另行进行更长测量及定位。')
    lines = ['# M02.3 缓存故障与性能对照（' + args.date + '）', '',
             '**结论：热门商家详情受益，普通/零评价店的中位延迟反而增加；保留现有可关闭的详情评分缓存，不扩展缓存范围。** 两轮独立MySQL/Redis验收通过，M02结束；下一项M05.1最小前端范围定稿。生产Java、POM/资源、V16协议保持M02.2版本，无新接口/迁移/组件。', '',
             '完整复现口径见[方法](../redis-rating-cache-measurement.md)，操作入口见[使用指南](../redis-rating-cache.md)。[JSON归档](redis-rating-cache-measurement-' + args.date + '.json)保存两轮原始请求/事务样本、每阶段SQL/命中/回源/错误指标、故障结果、实际源码摘要和完成标记，避免只保留有利数字。', '',
             '## 条件与通过范围', '',
             f"Java{runtime['java']}、MySQL{runtime['mysql']}、Redis8.2.4固定镜像；每轮新临时MySQL和专用Redis，同构建两个实际应用上下文以真实配置关闭/开启缓存，共用同一测试库。固定Clock，每应用Hikari最多5连接，客户端1/4/16个闭环工作线程；Windows到Docker Linux Redis的路径属于实测条件。数据为1,000/10,000店、15,000/105,000评价，普通店20总/18可见、热门店5,020总/4,518可见、零评价店。不是线上流量。", '',
             '每轮190项普通/Web用例及1个显式专项实验通过，失败/错误/跳过0；普通190重复执行，不宣称新增382个不同业务用例。专项中每轮4,320个正式预热HTTP样本、336个冷缓存/到期样本、384次创建＋384次隐藏事务；两轮合计8,640＋672个HTTP样本、1,536个写事务样本。另有预热、96次并发更新读和故障/恢复请求，不混入稳定热读分位数。', '',
             '所有采样HTTP状态200且data符合真实对照/合法快照，热读及开启缓存的故障读SQL检查RR/readOnly。两轮mysql JAR检查与自有实例清理通过；失败试跑残留已核对无进程后清理，当前MySQL fixture父目录为空、测试Redis容器为0。未操作用户开发Redis、正式库、上传目录或启动凭据，未重跑全部历史业务IT。', '',
             '## 完整HTTP结果', '',
             '表中p50/p95为3轮合并的nearest-rank毫秒，单线程每版本72样本，多线程每版本144样本；全部逐轮原始数据仍保留。批次完成速率是固定闭环批次，不是开放负载的稳定QPS容量。', '',
             '| 轮次/店数 | 店型 | 工作线程 | 关闭p50/p95 | 开启p50/p95 | 聚合SQL 关→开 | 批次完成速率 关→开（次/秒） |',
             '|---|---|---|---|---|---|---|']
    labels = {'normal': '普通', 'hot': '热门', 'empty': '零评价'}
    for s in summaries:
        a, b = s['off'], s['on']
        lines.append(f"| {s['run']}/{s['scale']} | {labels[s['scenario']]} | {s['workers']} | {a['p50']:.2f}/{a['p95']:.2f} | {b['p50']:.2f}/{b['p95']:.2f} | {a['aggregateSql']}→{b['aggregateSql']} | {a['batchCompletedPerSecond']:.1f}→{b['batchCompletedPerSecond']:.1f} |")
    lines += ['', f'10,000店热门详情：两轮单线程p50为{hot_single}；16工作线程为{hot_concurrent}。普通/零评价的聚合便宜，增加Redis往返与版本联表等成本后，中位数普遍更慢；不能把尾延迟的个别改善或单个最快样本概括为全场景提升。未单独分离网络、JSON、JIT和索引访问各自占比。', '',
              f'预热正式样本中，关闭缓存{total_on:,}次请求各执行一次聚合（3条业务SQL），开启缓存{total_on:,}次请求中实际命中{hits_on:,}次、聚合{aggregate_on}次、缓存错误{errors_on}次；开启的业务SQL数=2×请求数＋聚合数。两组热读HTTP错误0。这里只代表稳定数据/预热条件，不代表混合负载或长期生产命中率。热门聚合对应4,518条VISIBLE评价，但本实验没有测存储层物理I/O；不能把逻辑匹配行数当磁盘读取行数。', '',
              '## 冷缓存与批量到期', '',
              '| 轮次/店数 | 场景/线程 | 请求 | 实际聚合 | 复用已提交结果 | 等待超时 | p50/p95 ms |', '|---|---|---|---|---|---|---|']
    for s in cold:
        lines.append(f"| {s['run']}/{s['scale']} | {labels.get(s['scenario'],s['scenario'])}/{s['workers']} | {s['requests']} | {s['aggregateSql']} | {s['coalesced']} | {s['waitTimeouts']} | {s['p50']:.2f}/{s['p95']:.2f} |")
    lines += ['', '本轮健康冷波次观察到单店通常一次聚合，三个键同时到期则按三个键分别回源；实际次数和等待情况以上表为准。不同版本、超过20 ms等待、Redis故障和多实例仍可能重复回源，不将本轮结果提升为“所有并发只查一次”承诺。16客户端工作线程也不等于16个数据库事务同时持有连接，池上限仍为5。', '',
              '## 真实故障与并发更新', '',
              '| 轮次 | 场景 | 观察 |', '|---|---|---|']
    for number, run in enumerate(runs, 1):
        for fault in run['raw']['faults']:
            if 'elapsedMs' in fault:
                description = f"观察耗时{fault['elapsedMs']:.2f} ms；以实际命中/回填判定恢复"
            elif 'samplesMs' in fault:
                p = stats(fault['samplesMs'])
                description = f"{p['n']}次成功响应，p50/p95={p['p50']:.2f}/{p['p95']:.2f} ms"
                if 'observedStates' in fault:
                    description += f"，观察到{fault['observedStates']}/{fault['validSnapshotStates']}个合法快照状态"
            else:
                description = '断言通过'
            lines.append(f"| {number} | {fault['scenario']} | {description} |")
    lines += ['', '暂停容器产生真实命令超时，触发5秒绕过后恢复；SET被Redis ACL实际拒绝时，回源读取仍成功，恢复权限后重建。停止Redis期间真实管理员隐藏与统计版本同事务提交，新读取已排除该评价；同端点启动后恢复。超时恢复计时从暂停注入前开始，重启恢复计时从docker start返回后开始，两者不能直接比较。50 ms是客户端命令超时配置，并非HTTP硬上界，表中已保留高于50 ms的观测。', '',
              '六次隐藏与96次并发GET只允许命中由实际提交推导的快照集合，最终响应必须为最后状态。并发历史全验证通过不等于所有中间状态都被采样到。核心旧快照/晚回填、事务回滚/溢出等证据仍见M02.2报告；本轮不新增业务规则。', '',
              '## 评价版本维护成本', '',
              '以下为真实事务调用到提交返回的计时，未包含HTTP/JSON。测试对照仅省略版本更新，其它代码/校验/锁保持；它不是历史V1二进制，也不是生产开关。每组96个创建＋96个隐藏样本。行锁统计涵盖该批次全部InnoDB行锁，不独占归因于统计版本行；单条版本语句耗时原样保存在JSON中。', '',
              '| 轮次/线程 | 版本维护 | 创建p50/p95 ms | 隐藏p50/p95 ms | 全部行锁等待次数/毫秒 |', '|---|---|---|---|---|']
    for s in write_summary:
        a, b = s['create'], s['hide']
        lines.append(f"| {s['run']}/{s['workers']} | {'实际V16' if s['versioned'] else '测试省略'} | {a['p50']:.3f}/{a['p95']:.3f} | {b['p50']:.3f}/{b['p95']:.3f} | {s['rowLockWaits']}/{s['rowLockTimeMs']} |")
    lines += ['', '实际方案每次创建/首次隐藏增加一次统计版本upsert；每批32组为64次，另有不计事务延迟的批末恢复版本1次。测试对照没有这64条SQL。8线程同店实际版本维护出现额外行锁等待，不能声称版本门控免费或高写入量下没有热点。测量包含现有账号/订单锁、提交和连接池等待，不只是一条UPDATE的耗时。', '',
              tail_note, '',
              '## 结论、限制与可复现证据', '',
              '保留已经批准的可关闭缓存与一致性协议：它解决热门店反复聚合，在当前普通/零评价场景额外往返可能不合算。暂不为了该结果追加自动热点识别、缓存更多接口或改变RR承诺；是否在具体部署启用应结合读写比例和实际店铺分布。M02停止条件已满足，下一项M05.1定稿最小前端页面与接口清单，RabbitMQ/优惠券仍未实现。', '',
              '本次生产源码摘要与M02.2归档核对一致；新增的是显式隔离验收/汇总工具和文档。首遍试跑在两档读阶段后因测试生成LongNode与传输解析IntNode的JSON数值节点差异误判，已将独立推导期望通过JSON传输形式规范化，再完成两轮全流程；不是通过改业务数据或放宽快照合法性规避失败。失败不计通过，日志仍保留。', '',
              '只在一台机器、两个独立实例、合成分布和短闭环批次测量；p95样本有限，存在JIT/调度/热缓存噪声。故障对象是Redis容器，不是整台宿主机断电；没有操作系统级应用强杀、多实例集群或公网部署验收。采样计时含HTTP读取/JSON，SQL观察拦截器两组相同但仍有观测开销。禁止把结果写成线上运营成绩或高可用SLA。', '',
              '实际入口：', '', '```powershell', '.\\scripts\\benchmark-rating-cache.ps1 -Runs 2', '```', '',
              '汇总归档使用`scripts/report-rating-cache-measurement.py --runs <两次已完成目录> --date 2026-10-28`；它核对完成标记、样本/SQL/事务数、源码摘要及清理，不连接数据库。两轮原始目录：`' + runs[0]['folder'] + '`、`' + runs[1]['folder'] + '`。完整JSON已经包含这两份记录，本机忽略目录不必永久保留。', '']
    base = ROOT / 'docs/reports' / ('redis-rating-cache-measurement-' + args.date)
    base.with_suffix('.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    base.with_suffix('.md').write_text('\n'.join(lines), encoding='utf-8')
    print('PASS: 2 independent completed runs, unchanged production sources, 8640 warm + 672 cold HTTP samples, 1536 write transactions archived')


if __name__ == '__main__':
    main()
