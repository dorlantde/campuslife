"""Validate and summarize opt-in M01.1 evidence; standard-library only, no database access."""
import argparse
from datetime import datetime
import hashlib
import json
import math
import os
import re
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

ROOT = Path(__file__).resolve().parents[1]
VARIANTS = ['original', 'exists_derived', 'exists_no_semijoin']
LABELS = {'first': '首页20条', 'size100': '首页100条', 'category': '理发分类首页',
          'common': '常见名称', 'rare': '稀有名称', 'absent': '无匹配名称', 'deep': '深分页'}


def percentile(values, fraction):
    return sorted(values)[math.ceil(len(values) * fraction) - 1]


def summarize(folder, output, date):
    manifest = json.loads((folder / 'completed.json').read_text(encoding='utf-8-sig'))
    runs = []
    for item in manifest['runs']:
        assert item['evidence'] == f"run{item['run']}.json"
        record = json.loads((folder / item['evidence']).read_text(encoding='utf-8'))
        log_path = (ROOT / item['log']).resolve()
        assert log_path.is_relative_to(ROOT / '.cache')
        log = log_path.read_text(encoding='utf-8-sig', errors='replace')
        assert 'BUILD SUCCESS' in log and 'PASS: isolated MySQL migration' in log
        assert 'PASS: Java 21 jar starts in mysql profile' in log
        assert 'Tests run: 172, Failures: 0, Errors: 0, Skipped: 0' in log
        assert 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in log
        assert record['run'] == item['run']
        assert record['method']['rounds'] == 3 and record['method']['samplesPerRound'] == 20
        assert record['productionChanged'] is False
        assert record['indexesOrOptimizerSettingsChanged'] is False
        for name, digest in record['sourceSha256'].items():
            path = (ROOT / name).resolve()
            assert path.is_relative_to(ROOT / 'src') and path.exists()
            assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, name
        assert [tier['shops'] for tier in record['tiers']] == [1000, 10000]
        for tier in record['tiers']:
            assert set(tier['scenarios']) == set(LABELS)
            assert len(tier['semanticChecks']) == 18
            for scenario in tier['scenarios'].values():
                assert set(scenario['variants']) == set(VARIANTS)
                assert len(scenario['rounds']) == 3
                for variant in VARIANTS:
                    for kind in ['count', 'page']:
                        plan = scenario['variants'][variant][kind]
                        assert 'actual time=' in plan['analyze'] and plan['explain']
                        assert not [w for w in plan['warnings'] if w['Level'].lower() == 'warning']
                for block in scenario['rounds']:
                    for variant in VARIANTS:
                        values, stats = block[variant]['samplesMs'], block[variant]['summaryMs']
                        assert len(values) == stats['n'] == 20 and min(values) > 0
                        for key, fraction in [('p50', .5), ('p95', .95)]:
                            assert abs(stats[key] - percentile(values, fraction)) < 1e-8
        runs.append(record)
    assert len(runs) in [1, 2]
    assert [r['run'] for r in runs] == list(range(1, len(runs) + 1))
    assert all(r['sourceSha256'] == runs[0]['sourceSha256'] and r['indexes'] == runs[0]['indexes'] for r in runs)
    summary = {'date': date, 'task': 'M01.1', 'scope': 'count+page JDBC RR transaction; no production adoption or HTTP speedup claim',
               'manifest': manifest, 'runs': runs, 'formalPairSamples': len(runs) * 2 * 7 * 3 * 3 * 20}
    lines = [
        '# M01.1 商家列表SQL等价优化对照报告', '',
        f'- 日期：{date}（America/New_York）。',
        f'- 执行：{len(runs)}个独立临时MySQL实例；每实例两档1,000/10,000店、7场景、3版本、3轮×20样本。',
        '- 结果：原版与两个候选的总数、全部返回列、排序及额外语义边界均通过；count/page保持真实只读RR快照。',
        '- 范围：只比较SQL，生产Mapper/Service、索引、迁移、业务规则和接口未修改；候选仅存在测试工具中。',
        '- 计时单位：count＋page两个SELECT的JDBC事务往返，**不是完整HTTP接口耗时**。', '',
        '## 1. 环境与验证范围', '',
        f"MySQL {runs[0]['mysql']['version']}、Java {runs[0]['runtime']['java']}、{runs[0]['runtime']['os']}，InnoDB缓冲池{runs[0]['mysql']['bufferPoolBytes']//1024//1024} MiB，JVM可见{runs[0]['runtime']['availableProcessors']}处理器；单客户端、无计时阶段并发写。未采集CPU型号、物理内存与后台负载。", '',
        '每实例沿原隔离脚本创建随机端口/凭据/文件根，校验空库及真实datadir后造数。沿用C06同分布：60%店铺公开，两档15,000/105,000历史完成预约与评价、热门店5,020条评价。成功结束后关闭并清理自建实例，不访问现有项目库或持久文件。', '',
        '每实例普通/Web172用例＋实验1用例，共173，零失败/错误/跳过；mysql JAR启动检查通过。实验一个用例内包含多场景/多轮与语义断言，不能把5,040个样本说成5,040个JUnit用例，也不累加成全量业务回归。', '',
        '语义验证包括：分类/名称交集、不存在分类、特殊字符字面量、越过末页、long偏移、店铺/OWNER/账号角色状态、分类停用与主动停业仍展示；每版验证另一连接提交资格变化后同RR页保持旧快照、新RR看到新资格。全部参数绑定、整页数据与顺序对齐。', '',
        '## 2. 比较版本', '',
        '| 版本 | 差异 |', '|---|---|',
        '| original | 从实际生产MyBatis MappedStatement渲染SQL，并与真实Mapper核对count/ID |',
        '| exists_derived | count移除依赖外键保证的分类存在性JOIN；EXISTS核对OWNER/账号，先完整资格筛选并取ID页，再加载店铺/分类展示列；可选条件动态生成 |',
        '| exists_no_semijoin | 上一候选在资格查询块增加NO_SEMIJOIN提示，其余相同；未修改全局/会话优化器设置 |', '',
        'EXISTS与原JOIN等价依赖当前成员唯一约束，去掉count分类JOIN依赖真实非空外键。分类enabled没有加入公开资格。ID页内完整应用资格后才LIMIT；展示加载和count仍在同一RR快照。原参数/结果/分页契约保持。', '',
        '每版每场景预热5次，3轮正式请求交错随机顺序，每轮20次，计时包括连接/事务/JDBC/结果映射与共同的连接检查；参数构造、结果等价比较在计时外。保存全部分轮样本，p50/p95为nearest-rank。没有清空缓存，计划采集和预热造成热读；存在调度、JIT、GC及统计信息差异。', '',
        '## 3. 分实例、分轮结果', '',
        '下表为每轮p50/p95，单位ms，保留完整结果而非只挑最快样本。各列来自同轮同场景；不能与C06的HTTP p50=61.06ms直接计算接口提升率。', ''
    ]
    for record in runs:
        for tier in record['tiers']:
            lines += [f"### 实例{record['run']}，{tier['shops']:,}家店", '',
                      '| 场景 | 轮次 | original p50/p95 | exists_derived p50/p95 | exists_no_semijoin p50/p95 |',
                      '|---|---:|---:|---:|---:|']
            for name, scenario in tier['scenarios'].items():
                for block in scenario['rounds']:
                    cells = [f"{block[v]['summaryMs']['p50']:.2f} / {block[v]['summaryMs']['p95']:.2f}" for v in VARIANTS]
                    lines.append(f"| {LABELS[name]} | {block['round']} | " + ' | '.join(cells) + ' |')
            lines.append('')
    lines += ['## 4. 查询工作量与关键计划', '',
              '以下保留实例1、大档的三个分类分页计划。ANALYZE时间是独立带观测开销的一次执行，不能用它替代正式样本分布；主要比较输入行数、循环和排序工作。', '']
    for variant in VARIANTS:
        plan = runs[0]['tiers'][1]['scenarios']['category']['variants'][variant]['page']['analyze']
        lines += [f'### 分类分页：{variant}', '', '```text', plan.rstrip(), '```', '']
    lines += ['<!-- RECOMMENDATION -->', '', '## 6. 复现与证据', '',
              '实际已执行：', '', '```powershell', '.\\scripts\\experiment-public-shop-sql.ps1', '```', '',
              '- [实验方法与学习入口](' + os.path.relpath(ROOT/'docs/public-shop-sql-experiment.md', output.parent).replace('\\', '/') + ')。',
              '- 同名JSON归档包含全部原始样本、SQL/参数、传统EXPLAIN、ANALYZE、提示警告、语义检查名、环境/索引和源码指纹。',
              '- 不导出JUnit系统属性、环境变量、数据库密码、token或用户文件。',
              f"- 正式count/page事务样本共{summary['formalPairSamples']:,}个，每个两个SELECT；计划共{len(runs)*2*7*3*2}组EXPLAIN＋ANALYZE。", '']
    for item in manifest['runs']:
        evidence_path = os.path.relpath(folder/item['evidence'], output.parent).replace('\\', '/')
        lines.append(f"- 实例{item['run']}：[原始JSON]({evidence_path})，日志`{item['log']}`。")
    lines += ['', '未新增索引、直方图或优化器全局/会话设置；NO_SEMIJOIN仅为候选语句内提示。未做评分/营业时间优化、HTTP候选替换、并发容量或部署验证。生产采纳和完整HTTP回归留到M01.2，不将实验收益写成已上线接口收益。', '']
    output.parent.mkdir(parents=True, exist_ok=True)
    output.with_suffix('.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    output.write_text('\n'.join(lines), encoding='utf-8')
    print(f"PASS: {len(runs)} isolated runs, {summary['formalPairSamples']} samples, statistics/source/equivalence evidence validated")
    print(output.relative_to(ROOT))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--date', help='Report date in YYYY-MM-DD; defaults to completion date in America/New_York')
    args = parser.parse_args()
    folder = args.input.resolve()
    assert folder.parent == ROOT/'output/performance'
    assert re.fullmatch(r'public-shop-sql-\d{8}-\d{6}-[a-f0-9]{8}', folder.name)
    if args.date is None:
        try:
            completed = json.loads((folder/'completed.json').read_text(encoding='utf-8-sig'))['completedAt']
            args.date = datetime.fromisoformat(completed.replace('Z', '+00:00')).astimezone(ZoneInfo('America/New_York')).date().isoformat()
        except ZoneInfoNotFoundError:
            parser.error('Timezone data unavailable; pass --date YYYY-MM-DD explicitly.')
    assert re.fullmatch(r'\d{4}-\d{2}-\d{2}', args.date)
    output = args.output.resolve() if args.output else folder/'summary.md'
    assert output.parent in [ROOT/'docs/reports', folder] and output.suffix == '.md'
    summarize(folder, output, args.date)
