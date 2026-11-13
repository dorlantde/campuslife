# 商家优惠券活动：Swagger / Apifox验收

2026-10-30后续进展：B同步领取已实现，issuedCount现在会随真实成功领取累计增加，活动配置版本保持。学生入口见[领取指南](coupon-claims.md)；下方是A活动维护验收步骤与当时阶段范围。

对应M08.2-A、[ADR-0050](decisions/0050-coupon-business.md)。本批6个商家操作，仅活动维护；学生领券、用券订单与Lua/MQ在后续批次实现。无需先做前端页面。

## 入口与准备

按原VS Code的MySQL启动配置重启后端，Flyway应用V17；密码仍只留在本机原配置。浏览器打开`http://127.0.0.1:8080/swagger-ui/index.html`，或Apifox导入[运行快照](openapi/campuslife.openapi.json)。正常商家调用POST /api/v1/auth/login，将data.accessToken填入Authorize（只填token本身）。

活动维护在“09 商家优惠券活动”分组。先用GET /merchant/shop确认本人店铺APPROVED、OWNER有效；停业不妨碍维护。业务ADMIN不是数据库账号，也不具有这组商家权限。

## 点击创建与修改

POST /merchant/coupon-campaigns，示例全部为虚构数据。**四个日期按实际时间选择，发布时claimEndAt必须还在未来**；下面示例不是固定长期有效配置：

```json
{
  "name": "校园满30减5",
  "thresholdAmount": 3000,
  "reductionAmount": 500,
  "totalQuantity": 100,
  "claimStartAt": "2026-11-01T00:00:00+08:00",
  "claimEndAt": "2026-11-07T00:00:00+08:00",
  "useStartAt": "2026-11-01T00:00:00+08:00",
  "useEndAt": "2026-11-10T00:00:00+08:00"
}
```

预期201，data.status=DRAFT、version="0"、issuedCount=0，响应四个窗口转换为UTC；记录id。金额单位分，门槛3000即30元。创建不会自动发布或领取；网络结果未知先查询，重发创建可能产生另一草稿。

GET /merchant/coupon-campaigns（page默认1、size默认20）应出现本店草稿；GET /merchant/coupon-campaigns/{campaignId}读取完整记录。分页每页最多100、全部阶段按创建时间/ID倒序；没有状态筛选参数，也不会根据时间自动写STOPPED。

PATCH /merchant/coupon-campaigns/{campaignId}必须填写**完整八个业务字段**，再加`"expectedVersion":"0"`；不是只填写改动字段。例如复制创建正文，将name改成“周末满30减5”，加原版本。预期200、version="1"；完全相同字段且版本匹配则时间/版本都不变。旧版本重复修改409 CONFIG_VERSION_CONFLICT。

## 发布、停止与重试

POST /merchant/coupon-campaigns/{campaignId}/publish，只提交刚查到的活动版本：

```json
{"expectedVersion":"1"}
```

预期PUBLISHED、version="2"，publishedBy为本人业务账号ID、publishExpectedVersion="1"，保存首次publishedAt。再发完全相同发布请求，应返回原结果，时间/版本保持；当前窗口后来结束也不重写。此重试仍需本人当前正常且店铺获批。

发布后尝试PATCH完整业务字段并传当前version，应409 COUPON_CAMPAIGN_STATE_CONFLICT。要换金额/窗口/总量应另建新活动，不改旧规则。

POST /merchant/coupon-campaigns/{campaignId}/stop，提交当前版本`{"expectedVersion":"2"}`；预期STOPPED、version="3"，首次stoppedBy/stopExpectedVersion/stoppedAt保存。相同操作者用原版本"2"重复停止成功且不写；改用"3"不视为原动作重试。停止后无恢复或删除入口，不改订单/资金，也不作废后续批次已成功领取的权益。

## 异常验收清单

| 操作 | 预期 |
|---|---|
| thresholdAmount=100、reductionAmount=100，或数量0/100001 | 400 VALIDATION_FAILED，不创建草稿 |
| 金额为"3000"、3000.0或3e3，时间不带偏移/超过毫秒精度 | 400；不把数字字符串/浮点静默转换为整数 |
| 省略业务字段、任一字段null、额外shopId/status/version | 400；归属、状态与版本由服务端控制 |
| 发布时now等于或晚于claimEndAt | 409 COUPON_CLAIM_WINDOW_ENDED，草稿仍DRAFT |
| 原版本过期；阶段或操作者不符的发布/停止重试 | 409，不覆盖首次记录 |
| 另一个商家读取/修改同一活动 | 404 RESOURCE_NOT_FOUND |
| STUDENT/ADMIN访问该分组 | 403 ROLE_FORBIDDEN |
| 店铺暂停后查询/停止已发布活动 | 正常账号/ACTIVE OWNER仍可；新建/修改/发布403 SHOP_NOT_APPROVED |
| 账号/OWNER停用 | 拒绝操作，不批量处理已有活动或订单 |

锁等待跨截止、并发更新、读快照、会话到期和故障回滚由独立真实MySQL自动测试覆盖；本指南不是已经完成的人工验收记录。数据库故障503时，发布/停止保留原版本稍后重试，先读取核对；草稿创建没有幂等承诺。

## 本批技术阅读入口

从[Controller](../src/main/java/com/campuslife/coupon/controller/MerchantCampaignController.java)→[Service](../src/main/java/com/campuslife/coupon/service/MerchantCampaignService.java)→[Mapper](../src/main/java/com/campuslife/coupon/mapper/CampaignMapper.java)→[V17约束](../src/main/resources/db/migration/V17__create_coupon_campaigns.sql)阅读。对照解释：店铺共享锁如何挡住暂停并发、活动独立版本如何防止覆盖、首次操作记录为什么能识别安全重试、只读RR为什么使total与items一致。

后续推荐M08.2-B同步领取；本批issuedCount=0是尚未接入领取的事实，不是预设虚构发券成绩。最终自动验收证据见[本批报告](reports/coupon-campaigns-2026-10-29.md)。
