"""Build the draft V1 OpenAPI catalog from reviewed docs and the implemented snapshot.

Python 3 standard library only. This never changes or starts the Java application.
Run from any directory: python scripts/build-api-design.py [--check]
"""
import argparse
import copy
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'docs/openapi/campuslife.v1-design.openapi.json'
LIVE = ROOT / 'docs/openapi/campuslife.openapi.json'
CONTRACT = ROOT / 'docs/v1-api-contract.md'
METHODS = {'get', 'post', 'put', 'patch', 'delete'}
S = {}


def ref(name):
    return {'$ref': '#/components/schemas/' + name}


def text(example=None, **kw):
    value = {'type': 'string', **kw}
    if example is not None:
        value['example'] = example
    return value


def integer(example=1, **kw):
    return {'type': 'integer', 'example': example, **kw}


def enum(*values, **kw):
    return text(values[0], enum=list(values), **kw)


ID = text('201', pattern=r'^[1-9][0-9]{0,18}$', description='BIGINT 标识符，按十进制字符串传输')
VERSION = text('0', pattern=r'^(0|[1-9][0-9]{0,18})$', description='BIGINT 非负版本字符串，原样回传，不转为 Number')
AMOUNT = integer(3000, minimum=0, maximum=9007199254740991, description='金额单位：分；具体业务上限待实现前确认')
DATE = text('2026-10-16', format='date', description='校园本地日期，Asia/Shanghai；示例日期需按实际测试时间修改')
TIME = text('09:00', pattern=r'^([01][0-9]|2[0-3]):[0-5][0-9]$', description='Asia/Shanghai 本地时间，不跨午夜')
INSTANT = text('2026-10-16T02:00:00.000Z', format='date-time', description='带 Z 或明确偏移量的时间；响应为 UTC')
BOOL = {'type': 'boolean', 'example': True}
ORDER_TYPES = enum('BOOKING', 'PRINT')
SERVICE_TYPES = enum('HAIRCUT', 'PRINT')
ORDER_STATES = enum('PENDING_PAYMENT', 'PENDING_ACCEPTANCE', 'ACCEPTED', 'IN_SERVICE', 'COMPLETED', 'CLOSED')
PAYMENT_STATES = enum('NOT_PAID', 'PAID', 'REFUNDED')
APPROVAL = enum('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')
NOTE = text('虚构校园测试订单', maxLength=300)


def array(item, **kw):
    return {'type': 'array', 'items': item, **kw}


def nullable(schema):
    if '$ref' in schema:
        return {'type': 'object', 'nullable': True, 'allOf': [schema]}
    return {**schema, 'nullable': True}


def obj(name, props, required=(), description='', request=False):
    schema = {'type': 'object', 'properties': props, 'x-decision-status': 'draft-needs-review',
              'description': '设计稿，字段映射及约束待对应业务实现前复核。' + description}
    if required:
        schema['required'] = list(required)
    if request:
        schema['additionalProperties'] = False
    S[name] = schema
    return ref(name)


def page(name, item):
    return obj(name, {'items': array(ref(item)), 'page': integer(1, minimum=1), 'size': integer(20, minimum=1, maximum=100),
                     'total': integer(0, minimum=0), 'totalPages': integer(0, minimum=0)},
               ('items', 'page', 'size', 'total', 'totalPages'))


def envelope(data):
    return {'type': 'object', 'required': ['code', 'message', 'data', 'traceId'], 'properties': {
        'code': text('OK'), 'message': text('成功'), 'data': data,
        'traceId': text('c48c3b0e-95b8-4e32-a1c4-83bdbf3ac607')}}


def definitions():
    obj('ShopCategoryView', {'id': ID, 'code': text('HAIRCUT'), 'name': text('理发')}, ('id', 'code', 'name'))
    obj('BusinessHour', {'weekday': integer(5, minimum=1, maximum=7), 'startTime': TIME,
                         'endTime': text('18:00', pattern=TIME['pattern'])}, ('weekday', 'startTime', 'endTime'))
    shop = {'id': ID, 'name': text('星海模拟理发店'), 'category': ref('ShopCategoryView'),
            'locationText': text('虚构校区服务中心 101'), 'businessEnabled': BOOL, 'openNow': BOOL,
            'rating': {'type': 'number', 'nullable': True, 'minimum': 1, 'maximum': 5, 'example': None},
            'reviewCount': integer(0, minimum=0)}
    obj('ShopSummary', shop, shop.keys())
    obj('ShopDetail', {**shop, 'description': text('仅用于学习验收的虚构店铺'),
                       'businessHours': array(ref('BusinessHour')), 'campusCode': text('XINGHAI_MAIN')})
    obj('MerchantShopView', {**S['ShopDetail']['properties'], 'approvalStatus': APPROVAL,
                            'approvalReason': nullable(text('管理员审核原因')), 'memberStatus': enum('PENDING', 'ACTIVE', 'DISABLED'),
                            'version': VERSION}, description='本人店铺可见审核信息，不提供账号摘要和会话字段。')
    obj('MerchantApplicationRequest', {
        'username': copy.deepcopy(BASE_SCHEMAS['RegisterRequest']['properties']['username']),
        'password': copy.deepcopy(BASE_SCHEMAS['RegisterRequest']['properties']['password']),
        'nickname': copy.deepcopy(BASE_SCHEMAS['RegisterRequest']['properties']['nickname']),
        'categoryId': ID, 'shopName': text('星海模拟理发店', maxLength=80),
        'locationText': text('虚构校区服务中心 101', maxLength=200),
        'description': text('模拟商家申请', maxLength=1000)},
        ('username', 'password', 'nickname', 'categoryId', 'shopName', 'locationText'), request=True,
        description='同事务创建 MERCHANT、PENDING 店铺与 PENDING OWNER 成员；规则仍待定稿，不自动登录。')
    obj('MerchantApplicationView', {'accountId': ID, 'shopId': ID, 'approvalStatus': enum('PENDING')},
        ('accountId', 'shopId', 'approvalStatus'))
    service = {'id': ID, 'serviceType': SERVICE_TYPES, 'name': text('模拟标准理发', maxLength=80),
               'description': text('模拟服务说明', maxLength=500), 'priceAmount': AMOUNT,
               'durationMinutes': {'type': 'integer', 'minimum': 15, 'multipleOf': 15, 'nullable': True, 'example': 30},
               'version': VERSION}
    obj('ServiceView', service, service.keys(), description='PRINT 的 durationMinutes=null，priceAmount 只作起价展示。')
    obj('MerchantServiceView', {**service, 'enabled': BOOL})
    obj('PublicBarberView', {'id': ID, 'displayName': text('模拟理发师'), 'serviceIds': array(ID)})
    obj('MerchantBarberView', {**S['PublicBarberView']['properties'], 'enabled': BOOL, 'version': VERSION})
    obj('BookingSlot', {'startAt': INSTANT, 'endAt': text('2026-10-16T02:30:00.000Z', format='date-time')}, ('startAt', 'endAt'))
    obj('BookingSlotsView', {'serviceVersion': VERSION, 'priceAmount': AMOUNT, 'durationMinutes': integer(30),
                             'slots': array(ref('BookingSlot'))}, ('serviceVersion', 'priceAmount', 'durationMinutes', 'slots'))
    obj('PublicReviewView', {'reviewId': ID, 'rating': integer(5, minimum=1, maximum=5), 'content': text('模拟评价'),
                             'reviewerNickname': text('星海同学'), 'createdAt': INSTANT},
        ('reviewId', 'rating', 'content', 'reviewerNickname', 'createdAt'))
    obj('OwnReviewView', {'id': ID, 'orderId': ID, 'rating': integer(5, minimum=1, maximum=5),
                          'content': text('模拟评价'), 'visibility': enum('VISIBLE', 'HIDDEN'), 'createdAt': INSTANT})
    obj('FileView', {'id': ID, 'originalName': text('campus-demo.pdf'), 'byteSize': integer(1024, minimum=1, maximum=20971520),
                     'mediaType': enum('application/pdf'), 'pageCount': integer(5, minimum=1),
                     'status': enum('AVAILABLE', 'DELETING', 'DELETED'), 'expiresAt': nullable(INSTANT)},
        ('id', 'originalName', 'byteSize', 'mediaType', 'pageCount', 'status', 'expiresAt'),
        description='有效未终结订单保护文件时 expiresAt=null；不返回对象位置或公开直链。')
    print_spec = {'serviceId': ID, 'fileId': ID, 'paperSize': enum('A4'), 'colorMode': enum('BW', 'COLOR'),
                  'sideMode': enum('SINGLE', 'DOUBLE'), 'copies': integer(2, minimum=1, maximum=100,
                      description='1～100 为现有讨论稿建议范围，仍待业务确认')}
    obj('PrintQuoteRequest', print_spec, print_spec.keys(), request=True)
    quote = {**print_spec, 'sideMode': {**print_spec['sideMode'], 'example': 'DOUBLE'},
             'shopId': ID, 'pageCount': integer(5, minimum=1), 'sheetCount': integer(6, minimum=1),
             'priceRuleId': ID, 'priceVersion': VERSION, 'unitPriceAmount': {**AMOUNT, 'example': 20},
             'originalAmount': {**AMOUNT, 'example': 200}, 'discountAmount': integer(0, enum=[0]),
             'payableAmount': {**AMOUNT, 'example': 200}, 'currency': enum('CNY')}
    obj('PrintQuote', quote, quote.keys(), description='示例 5 页、2 份双面：6 张纸，10 个印刷面，每面 20 分，共 200 分。')
    booking = {'serviceId': ID, 'barberId': ID, 'startAt': INSTANT, 'expectedServiceVersion': VERSION,
               'expectedPayableAmount': AMOUNT, 'note': NOTE}
    obj('BookingCreate', booking, tuple(k for k in booking if k != 'note'), request=True,
        description='归属、结束时间、单价由服务端取得；时间窗、报价边界依状态文档的讨论稿复核。')
    printing = {**print_spec, 'expectedPriceVersion': VERSION, 'expectedPayableAmount': {**AMOUNT, 'example': 200}, 'note': NOTE}
    obj('PrintCreate', printing, tuple(k for k in printing if k != 'note'), request=True)
    order = {'id': ID, 'orderNo': text('SIM_ORDER_001'), 'orderType': ORDER_TYPES, 'status': ORDER_STATES,
             'paymentStatus': PAYMENT_STATES, 'shopId': ID, 'shopName': text('星海模拟理发店'),
             'serviceName': text('模拟标准理发'), 'payableAmount': AMOUNT, 'currency': enum('CNY'),
             'createdAt': INSTANT, 'bookingStartAt': nullable(INSTANT), 'bookingEndAt': nullable(INSTANT),
             'printingProgress': nullable(enum('QUEUED', 'PRINTING', 'READY_FOR_PICKUP', 'PICKED_UP', 'ABORTED'))}
    obj('OrderSummary', order, description='快照字段与类型特有摘要的具体映射为设计建议。')
    obj('BookingDetailView', {'barberId': ID, 'barberName': text('模拟理发师'), 'startAt': INSTANT,
                              'endAt': text('2026-10-16T02:30:00.000Z', format='date-time'), 'durationMinutes': integer(30),
                              'occupancyStatus': enum('HELD', 'ACTIVE', 'CONSUMED', 'RELEASED')})
    obj('PrintingDetailView', {'fileId': ID, 'fileName': text('campus-demo.pdf'), 'pageCount': integer(5),
                               'copies': integer(2), 'paperSize': enum('A4'), 'colorMode': enum('BW', 'COLOR'),
                               'sideMode': {**enum('SINGLE', 'DOUBLE'), 'example': 'DOUBLE'}, 'sheetCount': integer(6),
                               'unitPriceAmount': {**AMOUNT, 'example': 20},
                               'progress': enum('QUEUED', 'PRINTING', 'READY_FOR_PICKUP', 'PICKED_UP', 'ABORTED'),
                               'readyAt': nullable(INSTANT), 'pickedUpAt': nullable(INSTANT)})
    obj('PaymentView', {'paymentNo': text('SIM_PAY_001'), 'amount': AMOUNT, 'status': enum('SUCCEEDED'), 'paidAt': INSTANT})
    obj('RefundView', {'refundNo': text('SIM_REFUND_001'), 'amount': AMOUNT, 'status': enum('SUCCEEDED'), 'refundedAt': INSTANT})
    detail = {**order, 'originalAmount': AMOUNT, 'discountAmount': integer(0, enum=[0]), 'paymentDeadline': INSTANT,
              'acceptanceDeadline': nullable(INSTANT), 'closeReason': nullable(text('ADMIN_EXCEPTION')),
              'closeNote': nullable(NOTE), 'note': nullable(NOTE), 'version': VERSION, 'reviewed': BOOL,
              'booking': nullable(ref('BookingDetailView')), 'printing': nullable(ref('PrintingDetailView')),
              'payment': nullable(ref('PaymentView')), 'refund': nullable(ref('RefundView'))}
    for name in ('paidAt', 'acceptedAt', 'startedAt', 'completedAt', 'closedAt'):
        detail[name] = nullable(INSTANT)
    obj('OrderDetail', detail, description='booking / printing 恰好一个非空；公共状态、支付状态和打印进度须组合一致。')
    obj('OrderEventView', {'id': ID, 'eventSeq': VERSION, 'action': text('CREATE'),
                           'fromStatus': nullable(ORDER_STATES), 'toStatus': ORDER_STATES,
                           'actorType': enum('STUDENT', 'MERCHANT', 'ADMIN', 'SYSTEM'),
                           'actorId': nullable(ID), 'reason': nullable(NOTE), 'occurredAt': INSTANT})
    obj('PaymentResult', {'orderId': ID, 'orderStatus': enum('PENDING_ACCEPTANCE'), 'paymentStatus': enum('PAID'),
                          'paymentNo': text('SIM_PAY_001'), 'amount': AMOUNT, 'paidAt': INSTANT, 'acceptanceDeadline': INSTANT})
    obj('ReviewWrite', {'rating': integer(5, minimum=1, maximum=5),
                        'content': text('模拟评价', minLength=1, maxLength=1000)}, ('rating', 'content'), request=True)
    obj('ShopPatch', {'name': text('星海模拟店铺', maxLength=80), 'locationText': text('虚构校区 101', maxLength=200),
                      'description': text('模拟介绍', maxLength=1000), 'businessEnabled': BOOL, 'expectedVersion': VERSION},
        ('expectedVersion',), request=True)
    obj('BusinessHoursWrite', {'expectedVersion': VERSION, 'intervals': array(ref('BusinessHour'))},
        ('expectedVersion', 'intervals'), request=True)
    service_write = {k: v for k, v in service.items() if k not in ('id', 'version')}
    service_write['enabled'] = BOOL
    obj('ServiceWrite', service_write, ('serviceType', 'name', 'priceAmount', 'durationMinutes', 'enabled'), request=True)
    obj('ServicePatch', {**{k: v for k, v in service_write.items() if k != 'serviceType'}, 'expectedVersion': VERSION},
        ('expectedVersion',), request=True, description='serviceType、shopId 不可改。')
    price = {'paperSize': enum('A4'), 'colorMode': enum('BW', 'COLOR'), 'sideMode': enum('SINGLE', 'DOUBLE'),
             'pricePerImpression': {**AMOUNT, 'minimum': 1, 'example': 20}, 'enabled': BOOL}
    obj('PrintPriceView', {'id': ID, **price, 'version': VERSION})
    obj('PrintPriceWrite', {**price, 'expectedVersion': nullable(VERSION)}, (*price.keys(), 'expectedVersion'), request=True,
        description='已有规格传当前版本；新规格传 null。遗漏规格保持不变；任一项失败整批回滚。')
    obj('PrintPricesWrite', {'rules': array(ref('PrintPriceWrite'))}, ('rules',), request=True)
    obj('BarberWrite', {'displayName': text('模拟理发师', maxLength=40), 'serviceIds': array(ID)},
        ('displayName', 'serviceIds'), request=True)
    obj('BarberPatch', {'displayName': text('模拟理发师', maxLength=40), 'enabled': BOOL, 'expectedVersion': VERSION},
        ('expectedVersion',), request=True)
    obj('BarberServicesWrite', {'serviceIds': array(ID), 'expectedVersion': VERSION}, ('serviceIds', 'expectedVersion'), request=True)
    obj('ScheduleInterval', {'startAt': INSTANT, 'endAt': text('2026-10-16T10:00:00.000Z', format='date-time')}, ('startAt', 'endAt'), request=True)
    obj('ScheduleView', {'date': DATE, 'intervals': array(ref('ScheduleInterval')), 'barberVersion': VERSION})
    obj('ScheduleWrite', {'date': DATE, 'intervals': array(ref('ScheduleInterval')), 'expectedBarberVersion': VERSION},
        ('date', 'intervals', 'expectedBarberVersion'), request=True)
    obj('ReasonWrite', {'reason': text('模拟无法履约原因', minLength=1, maxLength=300)}, ('reason',), request=True)
    obj('VersionWrite', {'expectedVersion': VERSION}, ('expectedVersion',), request=True)
    obj('ReviewDecisionWrite', {'expectedVersion': VERSION, 'reason': text('模拟审核原因', minLength=1, maxLength=300)},
        ('expectedVersion', 'reason'), request=True)
    obj('AccountStatusWrite', {'status': enum('DISABLED', 'ACTIVE'), 'expectedVersion': VERSION},
        ('status', 'expectedVersion'), request=True)
    for name, item in [('ShopPage', 'ShopSummary'), ('PublicReviewPage', 'PublicReviewView'), ('OrderPage', 'OrderSummary'),
                       ('AdminShopPage', 'MerchantShopView'), ('AdminAccountPage', 'AccountView')]:
        page(name, item)


def parameter(name, schema, where='query', required=False, description=''):
    return {'name': name, 'in': where, 'required': required, 'description': description or schema.get('description', name), 'schema': schema}


PAGING = [parameter('page', {**integer(), 'default': 1, 'minimum': 1}),
          parameter('size', {**integer(20), 'default': 20, 'minimum': 1, 'maximum': 100})]
ORDER_FILTERS = [parameter('orderType', ORDER_TYPES), parameter('status', ORDER_STATES)] + PAGING
KEY = parameter('Idempotency-Key', text('demo-order-001', minLength=1, maxLength=64, pattern=r'^[A-Za-z0-9_-]+$'),
                'header', True, '订单创建必须提供；相同请求重试保留原键，改请求内容用新键')


def sample(schema, seen=()):
    if '$ref' in schema:
        name = schema['$ref'].rsplit('/', 1)[1]
        assert name not in seen, f'Recursive example: {name}'
        return sample(S[name], (*seen, name))
    if schema.get('nullable'):
        return None
    if 'allOf' in schema:
        value = {}
        for part in schema['allOf']:
            value.update(sample(part, seen))
        return value
    if 'example' in schema:
        return schema['example']
    if 'enum' in schema:
        return schema['enum'][0]
    kind = schema.get('type')
    if kind == 'object':
        return {k: sample(v, seen) for k, v in schema.get('properties', {}).items()}
    if kind == 'array':
        return []
    if kind == 'integer':
        return schema.get('minimum', 0)
    if kind == 'number':
        return schema.get('minimum', 0)
    if kind == 'boolean':
        return True
    return '示例'


def add(method, path, title, tag, permission, response, body=None, params=(), status=200, notes='', conflicts=None, idem=False):
    full_path = '/api/v1' + path
    op = {'tags': [tag], 'summary': '[待开发·规则待确认] ' + title,
          'operationId': method + ''.join(part[:1].upper() + part[1:] for part in re.findall(r'[a-zA-Z0-9]+', path)),
          'description': '【待开发／设计讨论稿】以下为目标契约，并非当前服务行为。'
                         '接口可见不代表已实现；当前本机调用可能返回 401/404。权限：' + permission + '。\n\n' + notes,
          'security': [] if permission == '匿名' else [{'bearerAuth': []}],
          'x-implementation-status': 'planned', 'x-decision-status': 'draft-needs-review',
          'x-apifox-status': 'designing', 'x-required-permission': permission,
          'x-contract-source': 'docs/v1-api-contract.md', 'parameters': [], 'responses': {}}
    for name in re.findall(r'\{([^}]+)\}', path):
        op['parameters'].append(parameter(name, ID, 'path', True))
    op['parameters'].extend(copy.deepcopy(params))
    if idem:
        op['parameters'].append(copy.deepcopy(KEY))
    if not op['parameters']:
        del op['parameters']
    if body:
        schema = ref(body)
        media = 'multipart/form-data' if body == 'PdfUpload' else 'application/json'
        content = {'schema': schema}
        if media == 'application/json':
            content['example'] = sample(schema)
            if body == 'ServiceWrite':
                content['example']['durationMinutes'] = 30
        op['requestBody'] = {'required': True, 'content': {media: content}}
    if response == 'PDF':
        op['responses'][str(status)] = {'description': '目标成功响应：受控 PDF 附件流，非 JSON 包装',
            'headers': {'Content-Disposition': {'schema': text(), 'description': '安全 attachment 文件名'}},
            'content': {'application/pdf': {'schema': {'type': 'string', 'format': 'binary'}}}}
    else:
        data = ref(response) if isinstance(response, str) else response
        schema = envelope(data)
        op['responses'][str(status)] = {'description': '目标成功响应（待开发）', 'content': {'application/json': {'schema': schema}}}
    error_codes = {'400': 'VALIDATION_FAILED', '401': 'AUTH_REQUIRED / TOKEN_INVALID / SESSION_EXPIRED / SESSION_REVOKED',
                   '403': 'ROLE_FORBIDDEN / ACCOUNT_DISABLED / SHOP_NOT_APPROVED', '404': 'RESOURCE_NOT_FOUND',
                   '406': 'NOT_ACCEPTABLE', '500': 'INTERNAL_ERROR', '503': 'SERVICE_TEMPORARILY_UNAVAILABLE'}
    if permission == '匿名':
        error_codes.pop('403')
    if body:
        error_codes['415'] = 'UNSUPPORTED_MEDIA_TYPE' if body != 'PdfUpload' else 'FILE_TYPE_UNSUPPORTED'
    if conflicts:
        error_codes['409'] = conflicts
    if body == 'PdfUpload':
        error_codes.update({'413': 'FILE_TOO_LARGE', '422': 'PDF_INVALID / PDF_ENCRYPTED'})
    if response == 'FileView' or response == 'PDF':
        error_codes['410'] = 'FILE_EXPIRED'
    if idem:
        error_codes['400'] += ' / IDEMPOTENCY_KEY_REQUIRED'
        error_codes['409'] = (error_codes.get('409', '') + ' / IDEMPOTENCY_KEY_CONFLICT').strip(' /')
        replay = copy.deepcopy(op['responses'][str(status)])
        replay['description'] = '目标幂等重放响应，返回同一资源（待开发）'
        replay['headers'] = {'Idempotency-Replayed': {'schema': {'type': 'boolean', 'example': True}}}
        op['responses']['200'] = replay
    if '/payments/mock' in path or any(path.endswith('/' + action) for action in ('cancel', 'reject', 'close-exception')):
        error_codes['503'] += ' / MOCK_PAYMENT_DISABLED（产生模拟资金流水时）'
    for code, description in error_codes.items():
        op['responses'][code] = {'description': '目标失败响应：' + description,
                                 'content': {'application/json': {'schema': ref('ApiError')}}}
    assert method not in DESIGN['paths'].get(full_path, {}), f'Duplicate operation: {method} {path}'
    DESIGN['paths'].setdefault(full_path, {})[method] = op


def operations():
    public = '03 商家申请与公开查询'
    add('post', '/merchant-applications', '提交商家申请', public, '匿名', 'MerchantApplicationView', 'MerchantApplicationRequest', status=201,
        notes='商家账号、待审店铺、店主成员同事务创建；不自动登录。categoryId 来自启用分类，campusCode 服务端固定。拒绝后重提策略待讨论。', conflicts='USERNAME_EXISTS')
    add('get', '/shop-categories', '查询启用分类', public, '匿名', array(ref('ShopCategoryView')))
    add('get', '/shops', '分页查询公开商家', public, '匿名', 'ShopPage', params=[parameter('keyword', text('模拟')), parameter('categoryId', ID)] + PAGING,
        notes='仅 APPROVED，排序 id ASC；其他列表默认 createdAt DESC,id DESC。')
    add('get', '/shops/{shopId}', '读取公开商家详情', public, '匿名', 'ShopDetail', notes='未审核与不存在均返回 404。')
    add('get', '/shops/{shopId}/services', '查询上架服务', public, '匿名', array(ref('ServiceView')), params=[parameter('serviceType', SERVICE_TYPES)])
    add('get', '/shops/{shopId}/barbers', '查询可用理发师', public, '匿名', array(ref('PublicBarberView')), params=[parameter('serviceId', ID)])
    add('get', '/shops/{shopId}/booking-slots', '查询可预约时段', public, '匿名', 'BookingSlotsView',
        params=[parameter('serviceId', ID, required=True), parameter('barberId', ID, required=True), parameter('date', DATE, required=True)],
        notes='查询不预留资源；创建时复核。未来 7 天、提前 2 小时、15 分钟对齐；日期计算和边界仍需确认。')
    add('get', '/shops/{shopId}/reviews', '分页查询公开评价', public, '匿名', 'PublicReviewPage', params=PAGING,
        notes='只返回 VISIBLE，不公开学生 ID、订单 ID、账号名。')
    obj('PdfUpload', {'file': {'type': 'string', 'format': 'binary', 'description': '单个 PDF，最多 20 MiB'}}, ('file',), request=True)
    add('post', '/files', '上传 PDF', '04 文件与打印报价', 'STUDENT', 'FileView', 'PdfUpload', status=201,
        notes='只接收 PDF，不信任扩展名或客户端页数，服务端解析页数。存储组件选型仍待验证。')
    add('get', '/files/{fileId}', '读取文件元数据', '04 文件与打印报价', '本人学生或本店有效履约商家', 'FileView',
        notes='先鉴权再判过期；管理员没有默认内容权限。')
    add('get', '/files/{fileId}/content', '下载受控 PDF 附件', '04 文件与打印报价', '本人学生或本店有效履约商家', 'PDF',
        notes='商家须有本店 ACCEPTED / IN_SERVICE 打印订单。文件以附件流返回，不暴露存储位置或永久直链。')
    add('post', '/print-quotes', '计算打印报价', '04 文件与打印报价', 'STUDENT', 'PrintQuote', 'PrintQuoteRequest',
        notes='不创建订单，不占用文件；按印刷面数计价，双面不将印刷面数减半。报价版本及 copies 上限仍待定稿。',
        conflicts='SHOP_UNAVAILABLE / SERVICE_UNAVAILABLE')
    student = '05 学生订单'
    add('post', '/orders/bookings', '创建理发预约订单', student, 'STUDENT', 'OrderDetail', 'BookingCreate', status=201, idem=True,
        notes='一单一项服务，冻结快照与金额；15 分钟支付期限。不能传 studentId、shopId、endAt 或单价。锁和报价边界参照状态设计稿。',
        conflicts='BOOKING_CONFLICT / QUOTE_CHANGED / BOOKING_TIME_INVALID / SHOP_UNAVAILABLE / SERVICE_UNAVAILABLE / BARBER_UNAVAILABLE')
    add('post', '/orders/prints', '创建打印订单', student, 'STUDENT', 'OrderDetail', 'PrintCreate', status=201, idem=True,
        notes='服务端确认文件归属、有效性、规格、价目版本和金额；同一个有效文件可创建本人多笔订单。',
        conflicts='QUOTE_CHANGED / SHOP_UNAVAILABLE / SERVICE_UNAVAILABLE')
    add('get', '/orders', '分页查询本人订单', student, 'STUDENT', 'OrderPage', params=ORDER_FILTERS)
    add('get', '/orders/{orderId}', '读取本人订单详情', student, '本人 STUDENT', 'OrderDetail')
    add('get', '/orders/{orderId}/events', '读取本人订单事件', student, '本人 STUDENT', array(ref('OrderEventView')), notes='按 eventSeq 升序。')
    add('post', '/orders/{orderId}/payments/mock', '模拟支付订单', student, '本人 STUDENT', 'PaymentResult',
        notes='只按冻结金额支付，不接收客户端金额。now 必须严格早于支付截止时间的边界为待确认细节；不涉及真实资金。',
        conflicts='ORDER_STATE_CONFLICT / PAYMENT_DEADLINE_PASSED')
    add('post', '/orders/{orderId}/cancel', '取消本人订单', student, '本人 STUDENT', 'OrderDetail',
        notes='待支付、待确认可取消；已确认理发距开始至少 1 小时可取消；打印接单后不直接取消。已支付取消全额模拟退款。',
        conflicts='ORDER_STATE_CONFLICT / CANCEL_WINDOW_CLOSED')
    add('post', '/orders/{orderId}/pickup', '确认打印取件', student, '本人 STUDENT', 'OrderDetail',
        notes='仅 PRINT + READY_FOR_PICKUP，可将公共状态改为 COMPLETED。商家不能代替学生确认。', conflicts='ORDER_STATE_CONFLICT')
    add('post', '/orders/{orderId}/review', '评价已完成订单', student, '本人 STUDENT', 'OwnReviewView', 'ReviewWrite', status=201,
        notes='COMPLETED + PAID，只能评价一次；隐藏评价仍阻止重发。具体内容校验细节待确认。', conflicts='REVIEW_NOT_ALLOWED / REVIEW_ALREADY_EXISTS')
    add('get', '/orders/{orderId}/review', '读取本人订单评价', student, '本人 STUDENT', 'OwnReviewView', notes='尚未评价返回 404。')
    config = '06 商家经营配置'
    merchant = 'MERCHANT + ACTIVE 成员 + APPROVED 店铺'
    add('get', '/merchant/shop', '查看本人店铺审核信息', config, 'MERCHANT，本人申请者可未审核', 'MerchantShopView', notes='未审核可读取，不代表可经营。')
    add('patch', '/merchant/shop', '修改本人店铺资料', config, merchant, 'MerchantShopView', 'ShopPatch',
        notes='不接收审批状态；临时停业不影响存量履约。平台暂停后的存量处理另行确认。', conflicts='CONFIG_VERSION_CONFLICT')
    add('put', '/merchant/shop/business-hours', '更新店铺营业时间', config, merchant, 'MerchantShopView', 'BusinessHoursWrite',
        notes='不跨午夜，区间不得重叠；同事务更新 shop.version。涉及已有预约的边界待定稿。', conflicts='CONFIG_VERSION_CONFLICT / SCHEDULE_CONFLICT')
    add('get', '/merchant/services', '查询本店全部服务', config, merchant, array(ref('MerchantServiceView')),
        params=[parameter('serviceType', SERVICE_TYPES), parameter('enabled', BOOL)])
    add('post', '/merchant/services', '创建本店服务', config, merchant, 'MerchantServiceView', 'ServiceWrite', status=201,
        notes='理发为固定价格、15 分钟倍数时长；打印时长 null、价格只作起价。不能超出店铺支持场景。')
    add('patch', '/merchant/services/{serviceId}', '修改本店服务', config, merchant, 'MerchantServiceView', 'ServicePatch',
        notes='不可更改 shopId、serviceType；下架不修改存量订单快照。', conflicts='CONFIG_VERSION_CONFLICT')
    for method, title in [('get', '读取本店打印价目'), ('put', '批量更新打印价目')]:
        add(method, '/merchant/services/{serviceId}/print-prices', title, config, merchant, array(ref('PrintPriceView')),
            'PrintPricesWrite' if method == 'put' else None,
            notes='按规格 upsert；未传规格保留；已有规则携带版本、新规格为 null；单次重复规格 400，整批原子提交。',
            conflicts='CONFIG_VERSION_CONFLICT' if method == 'put' else None)
    add('get', '/merchant/barbers', '查询本店理发师', config, merchant, array(ref('MerchantBarberView')))
    add('post', '/merchant/barbers', '创建理发师及服务关系', config, merchant, 'MerchantBarberView', 'BarberWrite', status=201)
    add('patch', '/merchant/barbers/{barberId}', '修改本店理发师', config, merchant, 'MerchantBarberView', 'BarberPatch',
        notes='有存量预约时停用策略需复核；不能直接关闭已有订单。', conflicts='CONFIG_VERSION_CONFLICT / SCHEDULE_CONFLICT')
    add('put', '/merchant/barbers/{barberId}/services', '更新理发师可提供服务', config, merchant, 'MerchantBarberView', 'BarberServicesWrite',
        notes='服务须属于本店且为 HAIRCUT；覆盖服务关系，递增理发师版本。', conflicts='CONFIG_VERSION_CONFLICT / SCHEDULE_CONFLICT')
    add('get', '/merchant/barbers/{barberId}/schedule', '读取理发师当天排班', config, merchant, 'ScheduleView', params=[parameter('date', DATE, required=True)])
    add('put', '/merchant/barbers/{barberId}/schedule', '更新理发师当天排班', config, merchant, 'ScheduleView', 'ScheduleWrite',
        notes='按理发师+日期保护行协调；不重叠、不跨午夜、不侵占有效预约；空数组清空须没有存量有效预约，细节待确认。',
        conflicts='CONFIG_VERSION_CONFLICT / SCHEDULE_CONFLICT')
    fulfillment = '07 商家订单履约'
    add('get', '/merchant/orders', '分页查询本店订单', fulfillment, merchant, 'OrderPage', params=ORDER_FILTERS)
    add('get', '/merchant/orders/{orderId}', '读取本店订单详情', fulfillment, merchant, 'OrderDetail')
    add('get', '/merchant/orders/{orderId}/events', '读取本店订单事件', fulfillment, merchant, array(ref('OrderEventView')))
    actions = [
        ('accept', '接受订单', None, 'PENDING_ACCEPTANCE → ACCEPTED；支付后 30 分钟内接单；精确截止边界待确认。', 'ACCEPTANCE_DEADLINE_PASSED'),
        ('reject', '拒绝待确认订单', 'ReasonWrite', 'PENDING_ACCEPTANCE → CLOSED，全额模拟退款。', None),
        ('cancel', '取消无法履约订单', 'ReasonWrite', '仅 ACCEPTED 且开始前，关闭并全额模拟退款。', None),
        ('start', '开始履约', None, 'ACCEPTED → IN_SERVICE；打印进度变为 PRINTING，理发开始时间窗待确认。', 'BOOKING_TIME_INVALID'),
        ('ready-for-pickup', '标记打印待取件', None, '仅 PRINTING → READY_FOR_PICKUP，公共状态保持 IN_SERVICE。', None),
        ('complete', '完成理发服务', None, '仅 BOOKING + IN_SERVICE → COMPLETED；已用区间不重新放出为待确认细节。', None),
    ]
    for action, title, body, notes, extra in actions:
        add('post', '/merchant/orders/{orderId}/' + action, title, fulfillment, merchant, 'OrderDetail', body,
            notes=notes, conflicts='ORDER_STATE_CONFLICT' + (' / ' + extra if extra else ''))
    admin = '08 管理员'
    add('get', '/admin/shops', '分页查询含待审店铺', admin, 'ADMIN', 'AdminShopPage', params=[parameter('approvalStatus', APPROVAL)] + PAGING)
    add('get', '/admin/shops/{shopId}', '读取商家申请详情', admin, 'ADMIN', 'MerchantShopView')
    add('post', '/admin/shops/{shopId}/approve', '批准商家申请', admin, 'ADMIN', 'MerchantShopView', 'VersionWrite',
        notes='PENDING → APPROVED，同事务激活成员；具体审核入口仍为设计稿。', conflicts='CONFIG_VERSION_CONFLICT')
    add('post', '/admin/shops/{shopId}/reject', '拒绝商家申请', admin, 'ADMIN', 'MerchantShopView', 'ReviewDecisionWrite',
        notes='PENDING → REJECTED，不激活成员。', conflicts='CONFIG_VERSION_CONFLICT')
    add('get', '/admin/accounts', '分页查询账号摘要', admin, 'ADMIN', 'AdminAccountPage',
        params=[parameter('role', enum('STUDENT', 'MERCHANT', 'ADMIN')), parameter('status', enum('ACTIVE', 'DISABLED'))] + PAGING,
        notes='不返回摘要或会话；不提供公开创建管理员或修改任意角色。')
    add('patch', '/admin/accounts/{accountId}/status', '停用或恢复账号', admin, 'ADMIN', 'AccountView', 'AccountStatusWrite',
        notes='停用须同事务撤销该账号全部会话；存量订单处理及管理员自操作边界仍待定稿。', conflicts='CONFIG_VERSION_CONFLICT')
    add('post', '/admin/reviews/{reviewId}/hide', '隐藏不适当评价', admin, 'ADMIN', 'OwnReviewView', 'ReasonWrite',
        notes='记录操作者与原因；保留评价事实。')
    add('get', '/admin/orders/{orderId}', '读取异常核实用订单详情', admin, 'ADMIN', 'OrderDetail', notes='不包含打印内容访问链接或默认内容下载权限。')
    add('post', '/admin/orders/{orderId}/close-exception', '核实异常后全额模拟退款关闭', admin, 'ADMIN', 'OrderDetail', 'ReviewDecisionWrite',
        notes='已确认：未到店、服务开始后异常由管理员核实，全额一次模拟退款并关闭，记录 ADMIN_EXCEPTION。'
              '开始前释放占用，开始后保留已用区间；已完成订单不适用。'
              '仅最新同管理员、同原因、expectedVersion=当前version-1 的同动作重试返回原结果，其余冲突；重试细节仍为设计稿。',
        conflicts='ORDER_STATE_CONFLICT / CONFIG_VERSION_CONFLICT')


def validate():
    assert all(S[name] == schema for name, schema in BASE_SCHEMAS.items()), 'Implemented schemas changed'
    assert DESIGN['components']['securitySchemes'] == BASE['components']['securitySchemes'], 'Bearer scheme changed'
    expected = {('get', '/api/v1/system/health')}
    for method, path in re.findall(r'^\| (GET|POST|PUT|PATCH|DELETE) \| (/[^ |]+)', CONTRACT.read_text(encoding='utf-8'), re.M):
        expected.add((method.lower(), '/api/v1' + path))
    actual = {(method, path) for path, item in DESIGN['paths'].items() for method in item if method in METHODS}
    assert actual == expected, f'Contract coverage mismatch: missing={expected - actual}; extra={actual - expected}'
    ids = []
    for path, item in DESIGN['paths'].items():
        for method, op in item.items():
            if method not in METHODS:
                continue
            ids.append(op['operationId'])
            slots = set(re.findall(r'\{([^}]+)\}', path))
            params = op.get('parameters', [])
            assert slots == {p['name'] for p in params if p['in'] == 'path'}, path
            assert all(p['required'] for p in params if p['in'] == 'path'), path
            assert len({(p['in'], p['name']) for p in params}) == len(params), path
            assert op.get('responses') and op['tags'], path
            if op['x-implementation-status'] == 'planned':
                assert op['x-apifox-status'] == 'designing' and '待开发' in op['summary'], path
                assert op['x-decision-status'] == 'draft-needs-review', path
            else:
                original = copy.deepcopy(BASE['paths'][path][method])
                original['summary'] = '[已实现] ' + original['summary']
                original['description'] = '【已实现并完成本机自动化验证】\n\n' + original.get('description', '')
                for key in ('x-implementation-status', 'x-decision-status', 'x-apifox-status'):
                    original[key] = op[key]
                assert op == original, f'Implemented contract changed: {method} {path}'
    assert len(ids) == len(set(ids)), 'Duplicate operationId'
    for path in ('/api/v1/orders/bookings', '/api/v1/orders/prints'):
        create = DESIGN['paths'][path]['post']
        assert any(p['name'] == 'Idempotency-Key' and p['in'] == 'header' and p['required'] for p in create['parameters'])
        assert {'200', '201'} <= create['responses'].keys(), path
    assert 'multipart/form-data' in DESIGN['paths']['/api/v1/files']['post']['requestBody']['content']
    assert 'application/pdf' in DESIGN['paths']['/api/v1/files/{fileId}/content']['get']['responses']['200']['content']

    def walk(node):
        if isinstance(node, dict):
            if '$ref' in node:
                prefix = '#/components/schemas/'
                assert node['$ref'].startswith(prefix) and node['$ref'][len(prefix):] in S, node['$ref']
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)
    walk(DESIGN)
    return Counter(op['x-implementation-status'] for item in DESIGN['paths'].values() for method, op in item.items() if method in METHODS)


BASE = json.loads(LIVE.read_text(encoding='utf-8-sig'))
BASE_SCHEMAS = BASE['components']['schemas']
S.update(copy.deepcopy(BASE_SCHEMAS))
DESIGN = copy.deepcopy(BASE)
DESIGN['openapi'] = '3.0.3'
DESIGN['info'] = {'title': 'CampusLife V1 完整接口设计稿（含待开发）', 'version': 'v1-design-0.1',
    'description': '完整规划目录，不等于已上线能力。已实现接口沿用运行快照；待开发接口均为规则待确认的讨论稿。'
                   '不能仅因导入成功就当作业务已实现或规则已批准。所有金额均模拟、示例均虚构。'
                   '测试时替换示例 ID、版本、日期和实际登录 token。浏览器运行文档仍只有已实现接口。'}
DESIGN['components']['schemas'] = S
DESIGN['tags'] += [{'name': name, 'description': '待开发；业务规则与字段细节仍需实现前复核。'} for name in
                  ('03 商家申请与公开查询', '04 文件与打印报价', '05 学生订单', '06 商家经营配置', '07 商家订单履约', '08 管理员')]
for item in DESIGN['paths'].values():
    for method, op in item.items():
        if method in METHODS:
            op['summary'] = '[已实现] ' + op['summary']
            op['description'] = '【已实现并完成本机自动化验证】\n\n' + op.get('description', '')
            op.update({'x-implementation-status': 'implemented', 'x-decision-status': 'confirmed', 'x-apifox-status': 'tested'})
definitions()
operations()
counts = validate()
DESIGN['x-implementation-summary'] = dict(counts)
rendered = json.dumps(DESIGN, ensure_ascii=False, indent=2) + '\n'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check', action='store_true', help='Validate coverage and check that the committed file is current')
args = parser.parse_args()
if args.check:
    assert OUT.read_text(encoding='utf-8') == rendered, 'Design snapshot is stale; regenerate it'
else:
    OUT.write_text(rendered, encoding='utf-8')
print(f'PASS: {sum(counts.values())} operations; implemented={counts["implemented"]}, planned={counts["planned"]}; contract coverage, references and snapshot checked.')
