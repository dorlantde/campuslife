"""Validate and archive paired full-HTTP SQL adoption evidence; no database access."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FAST = {'hair_first', 'hair_size100', 'print_first', 'print_size100'}
LABELS = {'all_first': '未分类首页20', 'all_size100': '未分类首页100', 'all_deep': '未分类深页',
          'hair_first': '理发首页20', 'hair_size100': '理发首页100', 'hair_deep': '理发深页',
          'print_first': '打印首页20', 'print_size100': '打印首页100', 'print_deep': '打印深页'}


def load_run(folder, phase):
    folder = folder.resolve()
    assert folder.is_relative_to(ROOT / 'output/performance')
    manifest = json.loads((folder / 'completed.json').read_text(encoding='utf-8-sig'))
    assert manifest['phase'] == phase and manifest['evidence'] == phase + '.json'
    data = json.loads((folder / manifest['evidence']).read_text(encoding='utf-8'))
    assert data['phase'] == phase and len(data['states']) == 6
    log_path = (ROOT / manifest['log']).resolve()
    assert log_path.is_relative_to(ROOT / '.cache')
    log = log_path.read_text(encoding='utf-8-sig', errors='replace')
    for required in ['BUILD SUCCESS', 'PASS: isolated MySQL migration',
                     'PASS: Java 21 jar starts in mysql profile',
                     'Tests run: 172, Failures: 0, Errors: 0, Skipped: 0',
                     'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0']:
        assert required in log, required
    assert data['method']['rounds'] == 3 and data['method']['samplesPerRound'] == 12
    assert data['method']['warmupPerCaseVariant'] == 4 and data['method']['concurrency'] == 1
    target = 'current' if phase == 'adopted' else 'candidate'
    count = 0
    for state in data['states']:
        assert len(state['semanticChecks']) == 10 and set(state['cases']) == set(LABELS)
        for name, case in state['cases'].items():
            assert len(case['rounds']) == 3
            for variant in ['original', target]:
                plan = case['plans'][variant]
                assert plan['queryCount'] == 4 and 'actual time=' in plan['analyze']
                for block in case['rounds']:
                    assert len(block[variant]['samplesMs']) == 12
                    assert all(v > 0 for v in block[variant]['samplesMs'])
                    count += 12
            if phase == 'adopted':
                original = ' '.join(case['plans']['original']['pageSql'].split())
                current = ' '.join(case['plans']['current']['pageSql'].split())
                assert ('NO_SEMIJOIN' in current) == (name in FAST)
                if name not in FAST:
                    assert original == current, name
    assert count == 3888
    if phase == 'adopted':
        for name, digest in data['sourceSha256'].items():
            path = (ROOT / name).resolve()
            assert path.is_relative_to(ROOT / 'src')
            assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, name
    return {'folder': str(folder.relative_to(ROOT)).replace('\\', '/'), 'manifest': manifest,
            'raw': data, 'formalHttpSamples': count}


def percent(values, fraction):
    return sorted(values)[math.ceil(len(values) * fraction) - 1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--validation', required=True, type=Path)
    parser.add_argument('--adopted', required=True, type=Path)
    parser.add_argument('--regression-log', required=True, type=Path)
    parser.add_argument('--date', required=True)
    args = parser.parse_args()
    validation = load_run(args.validation, 'validation')
    adopted = load_run(args.adopted, 'adopted')
    log_path = args.regression_log.resolve()
    assert log_path.is_relative_to(ROOT / '.cache')
    log = log_path.read_text(encoding='utf-8-sig', errors='replace')
    for text in ['BUILD SUCCESS', 'Tests run: 172, Failures: 0, Errors: 0, Skipped: 0',
                 'Tests run: 153, Failures: 0, Errors: 0, Skipped: 0',
                 'PASS: isolated MySQL migration', 'PASS: Java 21 jar starts in mysql profile']:
        assert text in log
    ordinary = ['file.FilePolicyTest', 'merchant.service.BookingSlotPolicyTest',
                'order.service.BookingOrderPolicyTest', 'order.service.PrintOrderPolicyTest',
                'order.service.PrintQuotePolicyTest', 'RegistrationPolicyTest', 'TokenServiceTest',
                'WebFoundationTest']
    integration = ['PublicShopCatalogMysqlIT', 'PublicShopReviewsMysqlIT', 'BookingJourneyMysqlIT',
                   'PrintJourneyMysqlIT', 'OpenApiMysqlIT', 'MysqlIntegrationIT']
    reports = []
    for directory, names in [('surefire-reports', ordinary), ('failsafe-reports', integration)]:
        for name in names:
            file = ROOT / 'target' / directory / ('TEST-com.campuslife.' + name + '.xml')
            assert file.stat().st_mtime >= log_path.stat().st_ctime
            suite = ET.parse(file).getroot()
            assert all(int(suite.get(key, '0')) == 0 for key in ['failures', 'errors', 'skipped'])
            reports.append({'suite': suite.get('name'), 'tests': int(suite.get('tests'))})
    assert sum(r['tests'] for r in reports) == 325
    regression = {'log': str(log_path.relative_to(ROOT)).replace('\\', '/'), 'suites': reports,
                  'tests': 325, 'failures': 0, 'errors': 0, 'skipped': 0}
    lines = ['# M01.2 商家分类首页SQL采纳与HTTP验证（' + args.date + '）', '',
             '结论：只对“指定分类、没有有效名称关键词、第1页”的列表采用EXISTS资格判断＋ID派生分页及NO_SEMIJOIN查询块提示。总数、未分类、名称搜索、后续页和详情保留原查询。无新增索引、迁移、接口或基础设施。', '',
             '这是已落地的等价查询优化，V1资格/排序/分页/只读RR/营业状态和评分规则未变；Redis、RabbitMQ、优惠券仍未实现。决策见[ADR-0047](../decisions/0047-public-shop-list-query.md)，[方法及复现](../public-shop-list-adoption.md)。', '',
             '## 实际修改与为何限定范围', '',
             '`PublicShopCatalogService.list`只在category非空、规范化pattern为空且page=1时调用`PublicShopMapper.categoryFirstPage`。新方法是静态@Select，先按已有(approval_status,category_id,id)索引有序读取，完整检查OWNER及经营账号资格后LIMIT，再加载展示列；最终仍ORDER BY id。不是先截断原始商家再过滤资格。', '',
             '已有唯一OWNER约束及分类外键保证EXISTS与旧JOIN结果对应。固定分类可使用索引剩余id顺序；提示仅限资格子查询块，防止半连接改写又转回大范围联表排序。资格很稀疏时仍需跳过大量不合格候选，count仍处理全部匹配。', '',
             '第一轮validation为尚未采纳的双分支原型，包含未分类首页候选；第二轮adopted收窄为实际静态@Select。两阶段源码不同，不直接跨阶段计算收益。以下所有提升均来自adopted阶段同数据、同入口的原版/当前版成对比较；未分类分支保留原版，耗时波动不能算优化收益。', '',
             '## 测量条件', '',
             'MySQL ' + adopted['raw']['mysql']['version'] + '、Java ' + adopted['raw']['runtime']['java'] +
             '、Windows 11；buffer pool 128 MiB。独立空库与自有随机datadir，1,000/10,000店，60%/90%/10%公开资格分布，两分类平衡。C06夹具包含15,000/105,000条评价及热门店倾斜；切分资格不改变评价数据。', '',
             '九场景×六状态×两版本，每组预热4次，3轮×12次交错随机测量；每阶段3,888个正式HTTP样本。计时含HTTP响应读取和JSON解析，包含count/page/营业时间/评分的真实流程；固定测试Clock避免跨营业边界。整个data响应、独立期待total/ID、每状态10项额外语义/RR检查均通过。非空列表4条SQL、空页2条；新快照看到资格变更，原快照保持一致。', '',
             'p50/p95采用nearest-rank，对每场景的36个样本汇总；原始分轮数据、全部实际SQL/参数及ANALYZE树在[JSON归档](public-shop-list-adoption-' + args.date + '.json)。这是单客户端预热读实验，不是并发压测、QPS或公开部署容量结论。', '',
             '## 完整HTTP结果（毫秒）', '',
             '| 店数/资格分布 | 场景 | 原p50/p95 | 当前p50/p95 | 路径 |',
             '|---|---|---|---|---|']
    summaries = []
    for state in adopted['raw']['states']:
        for name, case in state['cases'].items():
            stats = {}
            for variant in ['original', 'current']:
                values = [v for block in case['rounds'] for v in block[variant]['samplesMs']]
                stats[variant] = {'n': len(values), 'p50': percent(values, .5), 'p95': percent(values, .95)}
            summaries.append({'shops': state['shops'], 'profile': state['profile'], 'case': name, **stats})
            a, b = stats['original'], stats['current']
            lines.append(f"| {state['shops']}/{state['profile']} | {LABELS[name]} | {a['p50']:.2f}/{a['p95']:.2f} | {b['p50']:.2f}/{b['p95']:.2f} | {'新分类首页' if name in FAST else '原版保留'} |")
    lines += ['', '10,000店mixed60下，理发首页20条p50为29.09→17.45 ms、p95为31.40→18.79 ms；打印为25.19→13.10 ms、27.14→14.62 ms。实际理发page计划从输出3,000个联表匹配后排序，变为资格过滤前扫描27个索引候选，取得20项后再回表。这个page计划证据不等于整个接口只访问27行。', '',
              '1,000店sparse10且size100时，分类结果总共仅50项，p50理发6.50→6.56 ms、打印5.58→5.65 ms，基本没有收益且有轻微回退。没有承诺所有请求都更快；保留简单的分类/关键词/页码路由，未引入根据total或机器计时动态调SQL的额外策略。', '',
              '## 回归与交付证据', '',
              '最终定向回归：普通/Web172＋真实MySQL153＝325项，14类，失败/错误/跳过均0。覆盖目录67、公开评价62、预约旅程4、打印旅程6、OpenAPI3、MySQL基础11；目录新增6例验证分类快路径、资格/停业/分类停用、空白关键词、搜索/后续页/未分类回退及真实RR。两次HTTP实验各自普通/Web172＋实验1＝173通过，测量样本不作为JUnit用例累加。', '',
              '三次自建MySQL实例均完成V1～V15/23表及mysql可执行JAR启动检查并清理；最终检查生成实例父目录为空。未操作现有项目数据、用户上传根或启动密钥，未重跑全部历史业务IT。62个接口不变，因此无需重新生成没有变化的OpenAPI契约。', '',
              '原始预验：`' + validation['folder'] + '/validation.json`；正式采纳：`' + adopted['folder'] + '/adopted.json`；各目录completed.json只在检查及清理成功后写入。回归日志：`' + regression['log'] + '`。本JSON归档保留两阶段完整记录、源码摘要、逐类回归数及汇总，避免依赖被忽略的本机输出目录。', '',
              '## 完成标准与限制', '',
              'M01.2已完成：实际Mapper采纳边界明确、语义与RR验证通过、完整HTTP同条件对照及相关回归通过、原始证据可复核。M01阶段结束，下一项M02.1先定稿一个缓存场景及一致性边界；本次未安装或实现Redis。', '',
              '两轮各是一台机器上的一个独立实例，36个样本的p95会受JIT、调度和热缓存影响；三种合成分布也不能覆盖所有生产倾斜。更大数据、更多分类、冷缓存、多并发和未来MySQL版本需重测，不能把优化提示当永久保证。count、名称包含匹配和深分页仍有成本，暂不继续扩张全库调优。MySQL提示及索引机制参考[官方提示文档](https://dev.mysql.com/doc/refman/8.0/en/optimizer-hints.html)、[复合索引文档](https://dev.mysql.com/doc/refman/8.0/en/multiple-column-indexes.html)。', '']
    output = ROOT / 'docs/reports' / ('public-shop-list-adoption-' + args.date)
    archive = {'date': args.date, 'status': 'verified', 'scope': 'category present, normalized keyword absent, page=1',
               'validation': validation, 'adopted': adopted, 'summary': summaries, 'regression': regression,
               'fixtureParentEmptyAtArchive': not any((ROOT / '.cache/mysql-it').iterdir())}
    assert archive['fixtureParentEmptyAtArchive']
    output.with_suffix('.json').write_text(json.dumps(archive, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    output.with_suffix('.md').write_text('\n'.join(lines), encoding='utf-8')
    print('PASS: source hashes, 2 x 3888 HTTP samples, final SQL branches, 325 regression cases; ' + str(output))


if __name__ == '__main__':
    main()
