# 订单用券：Swagger UI / Apifox 验收

本轮M08.2-C，规则与锁序见[ADR-0052](decisions/0052-order-coupons.md)，自动验证见[报告](reports/order-coupons-2026-10-30.md)。使用原VS Code CampusLife (MySQL)启动，Flyway会执行V19；不需要手工修改表。模拟支付仍须本机原开关显式启用。前端本轮未接券。

打开本机Swagger UI或重新导入[运行OpenAPI](openapi/campuslife.openapi.json)。使用虚构店铺/账号，分别登录后把data.accessToken填入Authorize；无需在聊天发送密码或token。

1. 商家按[活动指南](merchant-coupon-campaigns.md)创建并发布本店活动，建议理发满2000减500；打印按实际报价设置门槛。时间窗口应覆盖本次测试。
2. 学生按[领券指南](coupon-claims.md)领取，GET /me/coupons取得id作为userCouponId。不要把campaignId/claimId误作券ID。
3. 理发POST /booking-quotes填serviceId及userCouponId，得到serviceVersion、originalAmount、discountAmount、payableAmount。匿名候选只提供时段和原价；下单expectedPayableAmount使用个人报价实付，expectedServiceVersion使用当前服务版本。填候选的barberId/startAt、同一userCouponId与新Idempotency-Key，创建后coupon.state=LOCKED。
4. 打印先上传本人PDF；POST /print-quotes在原六字段后加userCouponId。创建PRINT复制规格、priceVersion、实付和同一券ID。创建后文件仍按全部引用保护，券不改变内容下载权限。
5. GET /orders/{orderId}核对原价－优惠＝正实付及冻结coupon；商家/管理员旧详情入口也可见。GET /me/coupons?state=LOCKED核对占用。
6. 未支付取消，订单快照RELEASED，本人券未过期时AVAILABLE，可用新创建键再次下单；同原键重放返回原关闭订单，不创建新单。到期释放后EXPIRED。
7. 支付得到冻结实付金额，coupon变USED。已付款待接单取消按实付全额模拟退款，券仍USED，不增加可领库存。此后不能拿同券新建订单。
8. 同一个创建键原内容重试返回200与Idempotency-Replayed；换券/改实付沿用旧键409。去掉userCouponId或填null按原无券流程，原创建键兼容。

重点错误：非本人/跨店券404；已占用、已核销或不在使用窗口409 COUPON_UNAVAILABLE；原价未达门槛409 COUPON_THRESHOLD_NOT_MET；版本或实付变化409 QUOTE_CHANGED。数值型券ID或未知字段400；不要反复用新键掩盖重放问题。

报价不保证下单成功。停业可估价，下单仍受原经营时间/排班等限制。活动停止不撤回已领权益；合法旧单付款不因券在15分钟窗口内到期而失效。退款和履约的原权限、截止时间及模拟开关保持。

真实时间等待不是自动测试要求：专项在独立MySQL与测试Clock中验证锁等待、截止时点、故障回滚和两类订单生命周期。人工流程由用户按需执行；本轮不声称已人工验收。
