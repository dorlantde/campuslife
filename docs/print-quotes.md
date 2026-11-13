# 打印报价验收：Swagger / Apifox

2026-10-24支付扩展：[ADR-0035](decisions/0035-print-mock-payment.md)已支持学生PRINT模拟付款、待接单取消/超时退款及成功资金历史。文件仍任一未终结引用NULL保护、最后实际终结后7天，支付不续期。操作见[打印支付指南](print-payments.md)；商家打印访问未开放。

2026-10-24更新：打印创建与学生PRINT详情/取消/列表/事件已按[ADR-0034](decisions/0034-print-orders.md)交付，见[打印订单点击指南](print-orders.md)。未引用文件仍上传后24小时；任一打印订单未终结则expiresAt=null，全部终结后7天；下载/报价不自行续期。商家打印访问与履约后续开发，下文原阶段记录保留。

本轮按[ADR-0033](decisions/0033-print-quotes.md)新增POST `/api/v1/print-quotes`，规则见[确认方案](print-quote-design.md)。先用学生自己的有效PDF和商家启用价目估价，不创建订单、不锁价或延长文件有效期。本轮无需新数据库迁移。

## 准备商家与服务

用原VS Code **CampusLife (MySQL)** 配置重启应用，打开[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。Apifox重新导入[运行快照](openapi/campuslife.openapi.json)，服务地址设为`http://127.0.0.1:8080`；[完整规划](openapi/campuslife.v1-design.openapi.json)仍包含待开发功能。

需要一个APPROVED的PRINT分类商家及有效店主。没有时按[商家入驻](merchant-onboarding.md)创建虚构打印商家，再由平台管理员按[审核指南](admin-shop-review.md)批准；不使用MySQL账号登录业务接口。已有获批打印商家可直接继续，店铺停业或没有营业时间也能配置和报价。

1. 商家登录，将`data.accessToken`填入 **Authorize**，只填token本身。
2. 在“06 商家经营配置 → POST /api/v1/merchant/services”建立专用虚构验收服务，避免覆盖已有配置：

```json
{
  "serviceType": "PRINT",
  "name": "模拟PDF打印报价验收",
  "description": "学习测试使用",
  "priceAmount": 20,
  "durationMinutes": null,
  "enabled": true
}
```

成功201后记录`data.id`为serviceId。enabled须为true；priceAmount只是参考起价，不作为报价兜底。复用已有服务时用GET /merchant/services确认当前上架状态，修改须使用最新服务版本，见[服务指南](merchant-service-catalog.md)。

3. 对刚创建的新服务调用PUT `/api/v1/merchant/services/{serviceId}/print-prices`，建立黑白双面每印刷面20分的规则：

```json
{
  "rules": [
    {
      "paperSize": "A4",
      "colorMode": "BW",
      "sideMode": "DOUBLE",
      "pricePerImpression": 20,
      "enabled": true,
      "expectedVersion": null
    }
  ]
}
```

新规格成功后version="0"，记录规则id和version。如果复用的服务已经有此规格，先GET同路径查询，并使用该规格实际版本，不能继续传null；详细操作见[价目指南](merchant-print-prices.md)。

## 学生上传并报价

1. 切换为虚构学生账号登录，替换Authorize中的token。没有账号可先POST /auth/register，公开注册固定创建STUDENT，再登录。
2. 在“04 文件与打印报价”调用POST /files，选择[两页测试PDF](samples/campuslife-print-demo.pdf)，成功201后记录`data.id`为fileId。上传步骤见[文件指南](print-file-upload.md)。
3. 调用POST /print-quotes。将下面serviceId和fileId替换为刚才的真实字符串ID，不能直接照用示例数字：

```json
{
  "serviceId": "201",
  "fileId": "301",
  "paperSize": "A4",
  "colorMode": "BW",
  "sideMode": "DOUBLE",
  "copies": 2
}
```

本指南的样例PDF是2页，应200 / OK：pageCount=2、copies=2、sheetCount=2、unitPriceAmount=20，originalAmount=payableAmount=80分，discountAmount=0，currency=CNY。priceRuleId/priceVersion来自实际规则，ID和版本保持字符串。5页文件的相同规格则6张纸、200分；3页2份双面是4张纸、120分，不能把各份合并后取整。

4. 再查GET /files/{fileId}，expiresAt和状态不因报价改变；重复报价不会创建订单。同一有效PDF可询价多个服务。
5. Apifox对报价接口选择Body → JSON，设置学生Bearer Token，发送相同请求；此接口不用form-data、Idempotency-Key或客户端页数/金额。

## 改价与异常点击验证

商家改价后，报价不会锁住原价。切回商家token，GET价目取最新版本，再PUT该规格，将pricePerImpression改为21并提供最新expectedVersion。切回学生token重新报价，样例PDF应84分，priceVersion随规则实际变更递增。报价不会改写此前创建订单的快照；本轮尚未开放打印下单。

| 场景 | 预期 |
|---|---|
| 店铺停业、休息或没有营业时间，资格/服务/规格仍有效 | 可估价，不保证能接单 |
| 缺少字段/null/未知字段，非法ID/枚举；copies为"2"、2.0、2e0、0或101 | 400 VALIDATION_FAILED |
| 其他学生的fileId、文件不存在/未发布 | 404 RESOURCE_NOT_FOUND |
| 本人文件达到截止或删除中/已删除 | 410 FILE_EXPIRED，等于截止即过期 |
| 店铺未获批/暂停、店主或经营账号无效、服务下架/非PRINT | 404 RESOURCE_NOT_FOUND |
| 请求未配置或停用的规格 | 409 PRINT_SPEC_UNAVAILABLE，不用参考起价兜底 |
| 商家或ADMIN token | 403 ROLE_FORBIDDEN |
| 无登录、退出/过期token或学生账号停用 | 401或403 ACCOUNT_DISABLED |

文件归属/生命周期错误优先于服务或规格错误。报价不读取磁盘；若数据库显示有效但磁盘对象丢失，报价不是文件健康检查，应通过[本人下载](file-download.md)发现对象问题。到期及磁盘故障验证由独立测试覆盖，无需你等24小时、修改开发库或删除真实上传文件。

## 自动验证记录

2026-10-24（America/New_York）完成最终定向480个用例，0失败/错误/跳过：普通及Web142（新增报价计算18）；真实MySQL/HTTP338，其中报价87、价目84、上传64、下载48、认证41、基础迁移11、OpenAPI3。V1～V11迁移及mysql模式JAR健康、UTF-8、追踪与鉴权检查通过。

报价87个覆盖四种规格、50亿分JSON整数、严格参数与权限/归属/到期边界、缺价/停用不兜底、停业/休息/无时段仍可估价和分类停用可见。受控并发使用真实MySQL验证改价/服务停用/店主停用/店铺暂停/清理/退出在快照后提交时，本次保持原结果、下次读取新状态；读取结束才判会话/文件期限。额外验证HTTP认证后身份变化会在报价事务复核；业务写锁及下载互斥均被另一线程持有时，报价仍可完成，事务内Clock只取一次。

首轮并发测试中误对MyBatis接口代理调用抽象真实方法，导致测试同步失败；改为真实SQL Mapper后重跑完整定向集合通过。测试未用mock数据代替数据库隔离验证。临时MySQL和存储目录均独立于用户服务数据；未代用户操作Swagger或Apifox，未执行全部历史订单/资金IT。

运行53个操作、完整规划62/53/9；报价不保留文件、不保证下单成功，打印订单、引用保护和商家内容访问仍待后续开发。
