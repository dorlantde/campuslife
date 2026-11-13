# 同步领券：Swagger / Apifox点击验收

2026-10-31当前状态：D已接通同一POST的受控ASYNC分支与202/Location，默认SYNC不变；使用见[公开受理/维护指南](async-coupon-admission.md)。下文各日期描述保留当时批次范围。

2026-10-30 M08.3-B更新：当前V22/32表、75操作；默认仍同步，结果支持已持久PENDING/FAILED，公开异步新受理未开放。原键已有PENDING返回202、终态200，旧SYNC新增受理/期限/失败字段为null；新请求须OPEN/SYNC，故障不自动降级。详见[底座指南](async-coupon-foundation.md)。下列M08.2-B为历史点击流程；当前订单用券已按[指南](order-coupons.md)接通。

M08.2-B，规则见[ADR-0051](decisions/0051-synchronous-coupon-claims.md)。重启原VS Code MySQL配置，Flyway应用V18。Swagger：`http://127.0.0.1:8080/swagger-ui/index.html`；Apifox导入[运行契约](openapi/campuslife.openapi.json)。本批没有前端页面、订单抵扣或异步队列。

1. 使用商家账号，按[活动指南](merchant-coupon-campaigns.md)创建并发布活动。为立即验收，把领取开始设在当前之前、结束及使用结束设在未来，且useStart≤claimStart<claimEnd≤useEnd。记录活动ID和店铺ID。
2. 无授权调用GET /shops/{shopId}/coupon-campaigns，可看到当前窗口的已发布活动，包括售罄活动；停业不隐藏。remainingQuantity只作本次快照参考。
3. 使用学生账号POST /auth/login，将data.accessToken填Authorize。在“10 学生优惠券”POST /coupon-claims填请求头Idempotency-Key（如`claim-demo-1`），正文只填下列字段。

```json
{"campaignId":"替换为活动ID"}
```

4. 首次201，保存data.id（领取结果ID）和data.coupon.id（持有券ID，两者不可混用）。结果SUCCEEDED、券AVAILABLE；GET /coupon-claims/{claimId}和GET /me/coupons均能读取。
5. 原键原正文再发200，仍是原ID，不再加发行数；原键换活动409 IDEMPOTENCY_KEY_CONFLICT。换键领相同活动409 COUPON_ALREADY_CLAIMED。
6. 商家查看原活动，issuedCount增加，活动version不变。停止后原领取键仍可重放，旧持有券仍可读；新学生首次领取409 COUPON_NOT_CLAIMABLE。店铺/经营者失效时新领取404，学生仍能读旧券和原成功结果。

到useEndAt（等于即过期）后，GET /me/coupons?state=EXPIRED返回旧券；发行数不返还，不修改数据库持有事实。可以组合shopId/state/page/size筛选；默认1/20，大小最多100，领取时间/ID倒序。LOCKED/USED当前返回空页，订单用券留到C阶段。

错误检查：缺少/非法键400 IDEMPOTENCY_KEY_REQUIRED；campaignId必须字符串、规范正BIGINT，省略/null/多字段400；非学生403；他人的claimId404。售罄409 COUPON_SOLD_OUT，未开始/停止/窗口结束409 COUPON_NOT_CLAIMABLE。503或响应未知时保留原键重试，不自动换键。

自动验证含12名学生争4张券恰好4成功、同学生同/异键并发、失败/到期回滚、行锁等待跨窗口及RR分页。人工点击尚未记为完成，实际证据见[报告](reports/coupon-claims-2026-10-30.md)。
