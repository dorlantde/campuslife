# ADR-0033：本人PDF打印报价

- 日期：2026-10-23。
- 状态：用户明确“采用并实现”。规则见[确认方案](../print-quote-design.md)，实际验证及操作见[报价验收](../print-quotes.md)。

## 决定与理由

新增POST /api/v1/print-quotes，正常STUDENT使用本人有效PDF，六字段必填：serviceId、fileId、paperSize、colorMode、sideMode、copies。ID为规范正BIGINT字符串；A4、BW/COLOR、SINGLE/DOUBLE精确枚举，copies严格JSON整数1～100，拒绝字符串/小数/指数形式、null、缺失及未知字段。客户端不提供页数、单价、金额或身份。

店铺须满足既有公开可见资格，分类为PRINT，服务为本店上架PRINT，价目命中且启用。学生文件校验优先：不存在/他人/UPLOADING为404，本人到期（等于即过期）/DELETING/DELETED为410；店铺或服务无资格404，缺少/停用规格409 PRINT_SPEC_UNAVAILABLE，不能回退到参考起价。分类字典停用不另行隐藏获批店铺。

停业、休息或未配置营业时间仍可估价，便于提前准备；报价不代表当前能接单。未来打印创建须另行定稿接单/营业规则并重新校验，不能据此推导休息时可下单。

## 金额与版本

页数来自file_asset，单价来自具体print_price_rule，按印刷面计算pageCount × copies × unitPriceAmount；单双面可有不同单价。单面纸张pages × copies，双面每份先向上取整pages/2再乘copies，空白末背面不计费。优惠固定0，CNY，不新增纸张或装订费用。

最大500页 × 100份 × 100000分=5000000000分；金额及中间乘法用long与精确整数运算，不用int截断或浮点。响应金额为JSON整数，ID/版本字符串，priceVersion仅为命中价目的版本，不是服务/店铺版本。不保存报价、锁价期限或虚假quoteId。

## 只读事务与边界

一个短只读RR事务复用现有AuthSessionMapper、FileAssetMapper、ServiceItemMapper、PublicShopMapper、PrintPriceRuleMapper，不为单服务至多四种价目再造Mapper。资格、文件、价格和版本来自同一普通读快照，完成必要读取后一次Clock统一判会话/文件期限，错误仍按身份→文件→服务→价目顺序返回。

快照后提交的改价、停用、退出或文件清理允许本次返回原快照结果，后续请求读取新状态；判定时已达到期限仍拒绝。报价不访问磁盘、不取下载互斥或业务行锁，不修改文件、不延后清理、不创建订单/事件/资金。磁盘对象缺失不会由报价探测，下载仍按ADR-0032实际核对。

未来下单必须在写事务内重读文件、资格和价目、校验版本及预期金额并冻结快照；文件引用及最终终结后7天保护随打印订单实现，本轮没有开放打印创建或商家内容访问。

报价放在现有order模块的独立Controller/Service/Policy/DTO/VO，共五个生产Java文件；file保留生命周期、merchant保留配置职责。无新表、迁移、依赖、XML或缓存；V1～V11及20张业务表保持不变。运行及测试数字以验收记录为准。
