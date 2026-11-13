# Java 包结构与阅读顺序

2026-11-02 M04.2-C仅新增测试/编排：S3Fixture共用隔离夹具，S3RecoveryChild/S3FaultProxy验证真实进程/网络窗口，S3RestoreResources/S3BackupRestoreCheck恢复新实例并在副本预演；scripts/test-s3-recovery.ps1提供分阶段运行及限时Swagger窗口。生产包不含停点/代理，JAR与B同SHA，见[报告](reports/s3-recovery-2026-11-02.md)。

2026-11-02 M04.2-B：`file/storage`增加FileStorage路由、FileLocation完整定位、共享FileGuards及S3FileStorage；`file/config`登记显式S3设置。LocalFileStorage继续负责受控暂存和LOCAL内容；上传/下载/首次打印创建/私有清理与墓碑共用互斥。V24及真实S3/MySQL测试见[指南](s3-file-storage.md)/[报告](reports/s3-file-chain-2026-11-02.md)。SDK从B开始进入生产包，后端默认关闭；下方A记录仅代表A时点。

2026-11-02 M04.2-A：S3协议探针独立位于`src/s3-test/java`，显式`s3-probe` profile才编译到`target/s3-probe-test-classes`，SDK依赖仅test范围；普通测试仍在`src/test/java`，业务文件模块未接S3。`scripts/seaweedfs-env.ps1`与`test-s3.ps1`负责自有隔离资源和编排，见[复现](s3-compatibility.md)。

M03.2-B：messaging增加NotificationDelivery/Listener、NotificationService/Controller、Mapper/视图及显式无HTTP维护入口bootstrap/MessageMaintenance。


M03.2-A：新增messaging/{config,mapper,model,service}，OutboxWriter加入原订单事务，OutboxTransactions负责短租约事务，RabbitOutboxTransport负责事务外发送，OutboxPublisher有界调度。仍为单Maven工程，未新增消费者、通知控制器或通用消息框架。


M08.2-C：`coupon/service/OrderCouponService`复用调用方订单事务；`coupon/mapper/OrderCouponMapper`与V19保存冻结用券事实，`order/service/BookingQuoteService`提供理发个人报价，`PrintQuoteService`扩展可选券；不新增通用消息框架或前端模块。


2026-10-30 coupon模块增加CouponClaimController/Service、持有券与请求Entity/Mapper及相关DTO/VO；继续模块内分层，注解SQL/MyBatis-Plus，无XML。前端未改，源码入口见[领取指南](coupon-claims.md)。

2026-10-29 M08.2-A新增`com.campuslife.coupon`：controller/dto/service/mapper/entity/vo/config，保持业务模块内分层；仅商家活动，尚无领取、持有券或异步模块。Mapper使用MyBatis-Plus与注解SQL，没有XML。阅读入口见[活动指南](merchant-coupon-campaigns.md)；前端停止推进，本批仅后端与相关文档/测试。

2026-10-29 M05.2批次D：`frontend/src/admin`放管理员原因/原版本协议校验和样式，`pages/BookingPage`处理候选/预约，三个Admin页面处理订单核实、账号状态和评价隐藏。`orders/pending`以小型通用存储工厂分别保存两类冻结创建，原打印键兼容；订单页面扩展两类。Java生产包不变，测试夹具仅追加真实校园未来日期排班。[D指南](frontend-booking-admin.md)明确真实时间人工待办及后续M08定稿范围。

2026-10-29 M05.2批次C：`frontend/src/orders`放打印规格/阶段提示、冻结创建恢复记录与样式；`src/api/files`处理安全附件名/浏览器保存，统一client增加FormData/幂等键/PDF响应。`pages/PrintPage`、`OrdersPage`、`OrderPage`实现打印下单及双方订单，Java生产包未改。仅测试夹具增加预置打印配置，Vite可选隔离故障注入不进入业务页面。当前状态见[打印指南](frontend-print.md)，下方A/B段保留当时结构与范围。

2026-10-29 M05.2批次A/B：前端独立位于仓库根`frontend/`，其`src/api`放类型和统一fetch、`src/auth`放标签页会话、`src/pages`放路由页面、`src/components`放共用显示组件、`src/shared`放金额/时区和页面内草稿选择，`tests`放客户端边界测试。B新增`src/merchant`承载经营配置面板、表单转换与作用域请求协调；商家申请/管理、管理员审核页面接通实际API。Java业务包不变，临时浏览器夹具只在`src/test`。运行与状态见[前端指南](frontend.md)、[商家管理指南](frontend-merchant.md)；订单页面仍待C/D批次。

2026-10-28 M02.2：`merchant/cache`集中放置评分统计、版本键、显式JSON、Redis降级及有界在途加载合并；`merchant/config`增加条件化Redis连接和配置绑定，`merchant/service/PublicShopDetailTransactions`负责独立RR读取。`mapper/RatingRevisionMapper`及`mapper/model/PublicShopRatingRow`处理技术版本，`order/service/OrderReviewTransactions`在原事务末维护它。沿业务模块内分层，不新建空模块或XML；详见[缓存指南](redis-rating-cache.md)。下方结构为各历史阶段追加记录。

2026-10-26 当前交付：账号停用/恢复按[ADR-0046](decisions/0046-admin-account-status.md)接入。禁止修改本人，双账号按ID排序主键锁后复核正常ADMIN；实际变化时状态/version+1、目标全部旧会话撤销、状态历史原子提交，恢复须重新登录。旧版本409，同版本同状态200无写入；不自动处理订单/资金或改文件期限。V15新增account_status_change，23表；运行62、完整62/62/0，当前规划入口齐全。见[Swagger / Apifox指南](admin-account-status.md)；旧阶段“账号状态待开发”以本次为准，接口齐全不等于公开部署或全部工程工作完成。

2026-10-26 当前交付：管理员账号列表按[ADR-0045](decisions/0045-admin-account-list.md)接入。正常ADMIN查询全部业务账号，角色/状态交集、分页、创建时间/ID倒序，仅六字段AccountView；身份/会话与count/items同一只读RR快照，返回前核对到期，无业务行锁或写入。运行61、完整62/61/1，仍V1～V14/22表，无新迁移；账号停用/恢复尚未实现。见[Swagger / Apifox指南](admin-account-list.md)，旧阶段“账号列表待开发”以本次交付为准。

2026-10-26 当前交付：管理员隐藏评价按[ADR-0044](decisions/0044-admin-review-hide.md)接入。正常ADMIN说明原因后原子保存首次审核记录并隐藏；同人同原因重放保留事实，其他重复409。后续公开评价/真实评分排除，本人仍可读、reviewed=true且不能重发，不改订单/资金/文件期限。V14补三个审核字段，无新表，仍22表；运行60、完整62/60/2，仅管理员账号列表/状态管理待开发。见[Swagger / Apifox指南](admin-review-hide.md)，旧阶段“隐藏后续”以本次交付为准。

2026-10-15：按“业务模块 → 模块内职责”整理。auth、common、config、system 保留原职责，认证模块内部增加分层；SQL 沿用 MyBatis-Plus 和 MyBatis 注解。

## 当前实际结构

下面路径相对 src/main/java/com/campuslife：

```text
com.campuslife/
├── CampusLifeApplication.java
├── auth/
│   ├── controller/
│   │   ├── AuthController.java
│   │   └── MeController.java
│   ├── service/
│   │   ├── RegistrationService.java
│   │   ├── RegistrationPolicy.java
│   │   ├── LoginService.java
│   │   ├── SessionService.java
│   │   ├── PasswordChangeService.java
│   │   ├── PasswordChangeTransactions.java
│   │   └── ProfileUpdateService.java
│   ├── dto/
│   │   ├── RegisterRequest.java
│   │   ├── LoginRequest.java
│   │   ├── PasswordChangeRequest.java
│   │   └── ProfileUpdateRequest.java
│   ├── vo/
│   │   ├── AccountView.java
│   │   └── LoginView.java
│   ├── entity/
│   │   └── AccountEntity.java
│   ├── mapper/
│   │   ├── AccountMapper.java
│   │   ├── AuthSessionMapper.java
│   │   └── model/
│   │       ├── SessionAccount.java
│   │       └── AuthSessionState.java
│   ├── security/
│   │   ├── TokenService.java
│   │   ├── AuthPrincipal.java
│   │   ├── SessionAuthenticationFilter.java
│   │   └── AuthHttpErrors.java
│   └── config/
│       ├── ApiSecurityConfiguration.java
│       └── RegistrationConfiguration.java
├── common/
│   ├── api/ApiResponse.java
│   ├── exception/
│   │   ├── BusinessException.java
│   │   └── GlobalExceptionHandler.java
│   └── web/
│       ├── TraceContext.java
│       └── TraceIdFilter.java
├── config/
│   ├── TimeConfiguration.java
│   ├── DatabaseMigrationConfiguration.java
│   └── OpenApiConfiguration.java
└── system/
    └── controller/HealthController.java
```

共 36 个生产 Java 文件。本次移动其中 27 个文件，测试仍在 src/test/java/com/campuslife，通过更新 import 跟随新包。

2026-10-19 新增 merchant 模块，共增加 13 个生产 Java 文件，总计 49。上图保留原账号模块整理记录，新增结构如下：

```text
merchant/
├── config/MerchantConfiguration.java
├── controller/
│   ├── MerchantController.java
│   ├── PublicShopController.java
│   ├── MerchantOperationController.java
│   ├── MerchantServiceController.java
│   ├── PrintPriceController.java
│   ├── BarberController.java
│   └── BarberScheduleController.java
├── dto/
│   ├── MerchantApplicationRequest.java
│   ├── MerchantResubmitRequest.java
│   ├── ShopPatchRequest.java
│   ├── BusinessHoursRequest.java
│   ├── ServiceCreateRequest.java
│   ├── ServicePatchRequest.java
│   ├── PrintPricesRequest.java
│   ├── PrintPriceRuleRequest.java
│   ├── BarberCreateRequest.java
│   ├── BarberPatchRequest.java
│   ├── BarberServicesRequest.java
│   └── ScheduleWrite.java
├── entity/
│   ├── ShopEntity.java
│   ├── ServiceItemEntity.java
│   ├── PrintPriceRuleEntity.java
│   └── BarberEntity.java
├── mapper/
│   ├── ShopCategoryMapper.java
│   ├── ShopMapper.java
│   ├── PublicShopMapper.java
│   ├── PublicBarberMapper.java
│   ├── ShopMemberMapper.java
│   ├── BusinessHourMapper.java
│   ├── ServiceItemMapper.java
│   ├── PrintPriceRuleMapper.java
│   ├── BarberMapper.java
│   ├── BarberServiceMapper.java
│   ├── BarberScheduleMapper.java
│   └── model/
│       ├── OwnShopRow.java
│       ├── PublicShopRow.java
│       ├── PublicBarberRow.java
│       ├── BusinessHourRow.java
│       ├── BarberServiceRow.java
│       ├── BarberDay.java
│       └── BarberScheduleRow.java
├── service/
│   ├── MerchantApplicationService.java
│   ├── MerchantShopService.java
│   ├── PublicShopCatalogService.java
│   ├── BookingSlotsService.java
│   ├── BookingSlotPolicy.java
│   ├── MerchantShopPolicy.java
│   ├── MerchantResubmissionService.java
│   ├── MerchantOperationService.java
│   ├── BusinessHoursPolicy.java
│   ├── BusinessHoursService.java
│   ├── MerchantServicePolicy.java
│   ├── ServiceCatalogService.java
│   ├── PrintPricePolicy.java
│   ├── PrintPriceService.java
│   ├── BarberPolicy.java
│   ├── BarberManagementService.java
│   ├── SchedulePolicy.java
│   ├── BarberScheduleService.java
│   └── ScheduleHoursProtection.java
└── vo/
    ├── ShopCategoryView.java
    ├── ShopSummary.java
    ├── PublicBarberView.java
    ├── BookingSlot.java
    ├── BookingSlotsView.java
    ├── ShopDetail.java
    ├── ShopPage.java
    ├── ServiceView.java
    ├── MerchantApplicationView.java
    ├── MerchantShopView.java
    ├── BusinessHourView.java
    ├── MerchantServiceView.java
    ├── PrintPriceView.java
    ├── MerchantBarberView.java
    ├── ScheduleInterval.java
    └── ScheduleView.java
```

MerchantApplicationService 复用账号规范及 AccountMapper，协调三表申请事务；本人店铺查询按服务端当前身份限定归属。Mapper 由模块配置扫描，继续使用 MyBatis-Plus 和注解 SQL。

## 各层负责什么

2026-10-21 理发师新增 Controller、三个 DTO、VO、Entity、两个 Mapper、关系 Row、Policy 与 Service 十一个文件，该阶段生产 Java 文件共 99。V7 新增两表；列表批量加载绑定，姓名/启停和完整替换关系共用人员版本，沿用 MyBatis-Plus 与注解 SQL。见 [ADR-0017](decisions/0017-merchant-barbers.md)。

2026-10-20 打印价目新增 Controller、两个 DTO、VO、Entity、Mapper、Policy 和 Service 八个文件，当时生产 Java 文件共 88。V6 建立价目表，通过服务确定店铺归属；沿用 MyBatis-Plus 与注解 SQL，不增加 XML 或基础设施。批量保存使用每条价目的独立版本、父服务锁及整体事务，见 [ADR-0016](decisions/0016-merchant-print-prices.md)。

2026-10-20 服务目录新增上述 Controller、两个 DTO、VO、Entity、Mapper、Policy 与 Service 八个文件，当时生产 Java 文件共 80。V5 建立 service_item，实体插入用 MyBatis-Plus，查询/锁定/更新沿用注解 SQL。服务独立版本避免与店铺资料、营业时间产生无关冲突；店铺锁仍用于协调经营资格。见 [ADR-0015](decisions/0015-merchant-service-catalog.md)。

营业时间任务新增上图的 BusinessHoursRequest、BusinessHourView、BusinessHourRow、BusinessHourMapper、BusinessHoursPolicy、BusinessHoursService 六个文件，当时总计 72 个生产 Java 文件。V4 建立时段表，经营 Controller 新增 PUT；本人和管理员的店铺模型同步返回真实时段。批量 SQL 使用 Java Provider 和参数绑定，无 XML 文件；详情见 [ADR-0014](decisions/0014-merchant-business-hours.md)。

管理员任务新增 admin 模块：controller/AdminShopController、dto 下的 ShopApprovalRequest/ShopRejectionRequest、vo/AdminShopPage、service/ShopReviewService、config/AdminConfiguration、mapper 下的 AdminShopMapper/ShopReviewMapper 与 model/ReviewShopRow，另有 bootstrap/AdminBootstrap 和 AdminAccountInitializer。共增加 11 个 Java 文件，当时总计 60。bootstrap 使用独立配置与非 Web 主类，只在显式命令行工具调用；默认 JAR 仍启动 CampusLifeApplication。

重提阶段在 merchant 模块新增 MerchantResubmitRequest、MerchantResubmissionService 和包内共享校验 MerchantShopPolicy，当时生产 Java 文件总计 63。首次申请与重提复用分类 ID、Unicode 文本校验；重提服务负责身份/会话复核、行锁、版本与状态迁移，ShopMapper 继续采用注解 SQL。无新迁移或依赖，详情见 [ADR-0012](decisions/0012-merchant-resubmission.md)。

后续经营任务新增 ShopPatchRequest、MerchantOperationService 与 MerchantOperationController，当前生产 Java 文件共 66。经营 Controller 单独归入“06 商家经营配置”，复用 ShopMapper 的行锁与注解 SQL；仅修改提供的白名单字段，锁定后的当前值用于合并和无变更响应。详情见 [ADR-0013](decisions/0013-merchant-operation.md)。

| 包 | 责任 | 典型内容 |
|---|---|---|
| auth.controller | HTTP 路径、请求接收、响应返回 | 注册、登录、本人资料入口 |
| auth.service | 业务规则、流程与事务 | 密码校验、版本冲突、会话创建和撤销 |
| auth.dto | 客户端提交的数据 | LoginRequest、ProfileUpdateRequest |
| auth.vo | 返回给客户端的数据 | AccountView、LoginView |
| auth.entity | 数据表映射实体 | AccountEntity 对应 account，用于 MyBatis-Plus |
| auth.mapper | 访问数据库 | BaseMapper 常规 CRUD、自定义注解 SQL |
| auth.mapper.model | 数据库查询结果的内部载体 | 会话与账号联查、会话状态记录，不直接作为接口返回 |
| auth.security | 登录凭证与请求鉴权 | JWT、当前身份、过滤器、认证错误输出 |
| auth.config | 认证模块专用组件装配 | 安全链、密码编码器、Mapper 扫描 |
| common | 跨业务模块复用的基础代码 | 响应结构、异常、请求追踪 |
| 顶层 config | 应用级配置 | 时钟、数据库迁移、接口文档 |
| system.controller | 系统接口 | 健康检查 |

DTO、VO、Entity、数据库查询结果分开，避免把数据库实体直接返回给客户端。当前 Service 使用具体类，没有为每个单一实现额外建立接口与 impl 包。

后续商家、订单等模块沿用同样的组织方式，等有实际文件时再建立对应目录。

## 以修改昵称为例阅读代码

1. [MeController](../src/main/java/com/campuslife/auth/controller/MeController.java) 接收 PATCH /api/v1/me。
2. [ProfileUpdateRequest](../src/main/java/com/campuslife/auth/dto/ProfileUpdateRequest.java) 表达 nickname、expectedVersion。
3. [ProfileUpdateService](../src/main/java/com/campuslife/auth/service/ProfileUpdateService.java) 校验参数，在事务内复核身份和版本。
4. [AccountMapper](../src/main/java/com/campuslife/auth/mapper/AccountMapper.java) 与 [AuthSessionMapper](../src/main/java/com/campuslife/auth/mapper/AuthSessionMapper.java) 查询或更新数据库。
5. [AccountView](../src/main/java/com/campuslife/auth/vo/AccountView.java) 表达对外资料，[ApiResponse](../src/main/java/com/campuslife/common/api/ApiResponse.java) 提供统一响应外层。

请求进入 Controller 前，还会经过 [SessionAuthenticationFilter](../src/main/java/com/campuslife/auth/security/SessionAuthenticationFilter.java) 校验登录凭证。

## Mapper 与 SQL 的位置

AccountMapper 继承 BaseMapper<AccountEntity>，常规 insert、selectById 等由 MyBatis-Plus 提供；需要明确条件、行锁或专用更新的 SQL 继续放在 @Select、@Update、@Insert 等 MyBatis 注解中。AuthSessionMapper 使用自定义注解 SQL。

本次没有新增 Mapper XML，没有更改现有 SQL、业务规则或数据库迁移。Mapper 扫描更新为 com.campuslife.auth.mapper，Spring 组件扫描仍从 CampusLifeApplication 的 com.campuslife 根包开始。OpenAPI 扫描的 auth / system 根包会继续包含这些子包。

重启旧应用时继续使用原启动方式，浏览器和 Apifox 的接口地址无需修改。目录整理属于代码组织调整，不需要重新建库或重新注册账号。

## 本轮验证

2026-10-15 已执行 Maven clean 后的完整 Java 21 构建、独立 MySQL 8.0.45 验证及 mysql 模式 jar 启动检查。140 个用例全部通过，0 failures / errors / skipped，涵盖注册、JWT、认证会话、改密、昵称并发和回滚、文档与扫描边界。

对整理前后的 45 个生产/测试源文件进行对比，除 package、import 与 MapperScan 路径外，代码正文一致。重新导出的 OpenAPI 与原运行快照解析后完全一致，完整 61 接口设计稿校验通过。包内含全部 27 个移动后的类，没有旧 auth 根包、auth.persistence 或旧健康 Controller 的残留编译文件。

验证使用独立临时数据库，未访问用户正式库；测试实例和目录结束后清理。详细测试报告在 target/surefire-reports 与 target/failsafe-reports。

2026-10-21 按日排班阶段新增 10 个生产 Java 文件，当时共 109 个：BarberScheduleController；ScheduleWrite；ScheduleInterval / ScheduleView；BarberScheduleMapper；mapper/model/BarberDay / BarberScheduleRow；SchedulePolicy / BarberScheduleService / ScheduleHoursProtection。排班无额外实体 CRUD，使用注解 SQL 的 Row 映射；V8 新增两表。GET 只读 RR，写为 RC，复用店铺入口和人员版本，见 [ADR-0018](decisions/0018-merchant-schedule.md)。

2026-10-21 公开目录阶段新增 8 个生产 Java 文件，当时共 117 个：PublicShopController、PublicShopCatalogService、PublicShopMapper、mapper/model/PublicShopRow，以及 ShopSummary / ShopDetail / ShopPage / ServiceView 四个 VO。SQL 统一可见性，独立公开响应不复用本人店铺视图；无新依赖、XML 或迁移。规则见 [ADR-0019](decisions/0019-public-shop-catalog.md)。

2026-10-21 公开理发师新增 3 个生产 Java 文件，当前共 120 个：PublicBarberView、PublicBarberMapper、mapper/model/PublicBarberRow。复用 PublicShopController / PublicShopCatalogService 的只读 RR 与店铺资格；一次联表读取，EXISTS 筛选人员并保留全部公开绑定，按人员 ID 聚合。不增加依赖、XML 或迁移，见 [ADR-0020](decisions/0020-public-barbers.md)。

2026-10-21 候选时段新增 BookingSlotsService、包内 BookingSlotPolicy 和 BookingSlot/BookingSlotsView 两个 VO，共四个生产 Java 文件，当前总计 124。沿用 PublicShopController 和现有 Mapper，绑定 Mapper 增加只读存在性查询；不增加 Controller、空业务模块、XML、依赖或迁移。时间纯计算与真实 MySQL/HTTP 分别验证，见 [ADR-0021](decisions/0021-booking-slot-candidates.md)。

2026-10-22 新增 order 模块，按 config/controller/dto/vo/entity/mapper/service 分层，共16个生产Java文件，当前共140个。四个Mapper分别维护订单、预约占用、事件、幂等；BookingOrderTransactions负责数据库原子性，BookingOrderService在事务提交后转换HTTP错误，BookingExpiryJob扫描，BookingConfigurationProtection供商家配置复用。merchant的候选查询接入BookingMapper，相关写服务按日期保护行顺序检查原预约。无新依赖/XML，V9为四张订单表。

2026-10-22 模拟支付与待接单退款新增 MoneyMapper、MockPaymentPolicy 和 PaymentView/RefundView/PaymentResult 五个生产Java文件，当前145个。沿用 order 模块事务、Controller及扫描任务，不创建独立支付基础设施。MoneyMapper 使用注解SQL及只读record映射，查询与写入避免重载同名方法（MyBatis statement按方法名定位）。V10新增支付/退款两表；OrderDetail以具体流水类型代替Object，规则见[ADR-0023](decisions/0023-booking-mock-payment.md)。

2026-10-22 商家预约确认新增 order/controller/MerchantBookingOrderController 和 order/dto/BookingRejection 两个生产Java文件，当前147个。复用BookingOrderService/BookingOrderTransactions，详情RR、写RC；退款关闭共用同一内部过程，根据实际fromStatus及操作者写事件。OrderMapper增本店归属与接单/允许状态关闭，MoneyMapper增有说明的退款写入，保留原待接单退款路径。无新模块、依赖、XML或迁移，见[ADR-0024](decisions/0024-merchant-booking-confirmation.md)。

2026-10-22 商家无法履约取消新增order/dto/BookingCancellation一个生产Java文件，当前148个。沿用MerchantBookingOrderController、BookingOrderService和BookingOrderTransactions；拒单/取消共用严格reason归一化，三个商家写动作抽取同一私有锁入口及锁后结果record，不新建通用框架。复用原退款关闭SQL与资金Mapper，无新迁移、XML或依赖，见[ADR-0025](decisions/0025-merchant-booking-cancellation.md)。

2026-10-22 预约开始/完成沿用MerchantBookingOrderController、BookingOrderService及BookingOrderTransactions的商家锁入口；OrderMapper新增startService/completeService条件更新，BookingMapper新增consumeActive，原VO注解补齐状态说明。无新生产Java文件，仍148个；V1～V10无新增表/迁移/依赖/XML。数据库主状态、占用和事件整体事务，资金查询只验证现有事实，见[ADR-0026](decisions/0026-booking-fulfillment.md)。

2026-10-22 管理员预约异常新增order/controller/AdminBookingOrderController与order/dto/BookingExceptionClose两个生产Java文件，当前150个。沿用BookingOrderService/Transactions，管理员写入口单独验证ADMIN且不要求目标OWNER资格；OrderMapper增加限BOOKING及来源状态的adminRefundClose，OrderEventMapper增加末事件读取，用于核对实际操作者/原因/版本重放。资金与关闭仍复用现有事务；IN_SERVICE异常关闭使用consumeActive，不扩大普通取消SQL的状态范围。无新模块、XML、依赖或迁移，见[ADR-0027](decisions/0027-admin-booking-exception.md)。

2026-10-22 学生订单列表新增order/mapper/OrderListMapper、mapper/model/OrderSummaryRow、vo/OrderSummary及vo/OrderPage四个生产Java文件，当前154个。沿用BookingOrderController/Service/Transactions，列表RR事务调用同条件countOwn/pageOwn；单页联表读取预约快照、UTC转换，不逐单调用详情/资金。新Mapper由现有OrderConfiguration扫描，无新模块、依赖、XML、迁移或分页插件，见[ADR-0028](decisions/0028-student-order-list.md)。

2026-10-23 商家订单列表无新生产Java文件，仍154个。复用MerchantBookingOrderController、BookingOrderService/Transactions、OrderPage/OrderSummary及OrderSummaryRow，在OrderListMapper增加countShop/pageShop。服务端确定本店归属，RR内先读店铺及成员再读取同条件count与分页；共享学生列表参数解析，不新增模块/XML/插件/迁移。见[ADR-0029](decisions/0029-merchant-order-list.md)。

2026-10-23 事件查询新增order/vo/OrderEventView、mapper/model/OrderEventRow两个生产Java文件，当前156个。复用学生/商家订单Controller及BookingOrderService/Transactions，RR内先授权后调用OrderEventMapper.listByOrder；原latest/append不变。九字段映射ID/序号字符串与UTC，不新增实体CRUD、模块、XML、依赖或迁移，见[ADR-0030](decisions/0030-order-events.md)。

2026-10-23 私有PDF模块新增file/config、controller、entity、mapper、service、storage、vo：FileConfiguration、FileController、FileAssetEntity、FileAssetMapper、FileView、FileRules、FileTransactions、FileCleanup、FileUploadService、PdfInspector、LocalFileStorage，共11个生产Java文件，当前167个。V11仅file_asset，无打印空表；存储目录配置/根标识/互斥和IO在storage，短RC发布/清理状态在事务服务，PDFBox在解析组件，不新增XML。见[ADR-0031](decisions/0031-print-file-upload.md)。

2026-10-23 本人PDF下载新增file/service/FileDownloadService一个生产Java文件，当前168个。复用FileController、FileTransactions的短只读RR、LocalFileStorage的安全对象读取和现有有界互斥；GlobalExceptionHandler对已提交响应不写JSON，将异常交回容器终止连接。无新模块/实体/Mapper/XML/依赖/迁移，见[ADR-0032](decisions/0032-file-download.md)。

2026-10-24 打印报价在既有order模块新增controller/PrintQuoteController、dto/PrintQuoteRequest、vo/PrintQuote、service/PrintQuotePolicy及PrintQuoteService共五个生产Java文件，当前173个。直接复用已扫描的账号/文件/服务/公开店铺/价目Mapper，无额外Mapper、XML、实体、配置、模块或依赖。file保持生命周期，merchant保持配置职责；一次只读RR报价，不把计算塞进上传服务，见[ADR-0033](decisions/0033-print-quotes.md)。

2026-10-24 打印待支付订单沿现有order模块增加PrintOrderController、PrintCreate、PrintingOrderView、PrintDetailEntity、PrintOrderMapper、PrintOrderPolicy、PrintOrderService、PrintOrderTransactions和PrintExpiryJob；file模块增加FileReferenceMapper及FileReferenceProtection。Controller/Service/Transactions/Mapper职责仍分层，MyBatis-Plus实体及注解SQL，无XML或新基础设施。学生共享入口按订单冻结类型分派，打印不会塞入预约写事务；引用统计/期限统一由file负责，见[ADR-0034](decisions/0034-print-orders.md)。

2026-10-24 打印支付沿现有PrintOrderService/PrintOrderTransactions增加付款、待接单退款、资金视图及统一锁入口，PrintOrderMapper补明细锁/待接单候选，PrintExpiryJob分未付和待接单配额。共用学生支付入口按冻结类型分派，复用MoneyMapper/MockPaymentPolicy及原VO；无新增生产Java文件、XML或基础设施，生产文件仍184个。定位普通读与写返回锁查询分开，以免MyBatis一级缓存混读；见[ADR-0035](decisions/0035-print-mock-payment.md)。

2026-10-24 [ADR-0036](decisions/0036-merchant-print-confirmation.md)在原MerchantBookingOrderController/BookingOrderService分派商家PRINT动作，PrintOrderService/PrintOrderTransactions维护打印权限、接拒单和退款；BookingOrderTransactions共享商家列表/事件读取，OrderListMapper扩展本店两类联表/类型筛选。无新增生产Java文件（仍184）、模块、XML或依赖；新增MerchantPrintConfirmationMysqlIT作真实MySQL/HTTP验证。

2026-10-25 [ADR-0037](decisions/0037-merchant-print-cancellation.md)沿原merchantCancel入口按冻结类型分派，PrintOrderTransactions复用商家锁入口并实现接单后取消；退款事件使用实际原状态，BookCancellation DTO/VO与MoneyMapper继续复用。无新增生产Java文件（仍184）、XML、模块或依赖，新增MerchantPrintCancellationMysqlIT验证真实MySQL/HTTP异常及并发。

2026-10-25 [ADR-0038](decisions/0038-merchant-print-download.md)沿FileController/FileDownloadService和FileTransactions.download扩展MERCHANT分支，FileReferenceMapper增加成员与合格订单只读查询，FileRules区分内容下载角色与仍仅STUDENT的上传/元数据。复用全部二进制IO/互斥，未新建生产Java文件（仍184）、模块、XML或依赖；新增MerchantPrintDownloadMysqlIT，普通定位与复核始终两个短事务。

2026-10-25 [ADR-0039](decisions/0039-merchant-print-fulfillment.md)沿MerchantBookingOrderController和BookingOrderService扩展start、增加ready入口，打印动作落既有PrintOrderService/Transactions与商家锁入口；OrderMapper/PrintOrderMapper补条件写，FileReferenceMapper扩展下载状态。无新增生产Java文件（184）、实体、DTO、XML、模块或依赖；新增MerchantPrintFulfillmentMysqlIT。

2026-10-25 [ADR-0040](decisions/0040-student-print-pickup.md)沿BookingOrderController/Service接入学生pickup，由PrintOrderService/Transactions及PrintOrderMapper实现；复用OrderMapper完成条件写、FileReferenceProtection。无新生产Java文件/模块/DTO/XML/依赖，生产仍184个文件；新增StudentPrintPickupMysqlIT，测试共47个Java文件。

2026-10-26 [ADR-0041](decisions/0041-admin-print-exception.md)沿AdminBookingOrderController/BookingOrderService分派两类，PRINT新增逻辑落既有PrintOrderService/Transactions；OrderMapper/PrintOrderMapper补条件更新，MoneyMapper/OrderEventMapper补重放事实读取。无新增生产Java文件/DTO/XML/模块，仍184个；新增AdminPrintExceptionMysqlIT，测试48个Java文件。

2026-10-26 [ADR-0042](decisions/0042-student-order-review.md)在order内新增OrderReviewController、OrderReviewService/Transactions、OrderReviewMapper、OrderReviewEntity、OrderReviewWrite及OwnReviewView共7个生产Java文件；生产共191个。BOOKING/PRINT视图依赖OrderReviewMapper读取真实reviewed，无新空模块/XML/依赖。V13迁移；新增OrderReviewsMysqlIT，测试49个Java文件。

2026-10-26 [ADR-0043](decisions/0043-public-shop-reviews.md)沿merchant的PublicShopController/PublicShopCatalogService扩展，新建PublicReviewMapper、PublicReviewView、PublicReviewPage共3个生产Java文件，生产194个。内部记录承载精确聚合及公开行，订单评价仍由order写入；无新模块/实体/XML/依赖/迁移。新增PublicShopReviewsMysqlIT，测试50个Java文件。

管理员隐藏评价：order/controller/AdminReviewController、dto/ReviewHideWrite；复用OrderReviewService/OrderReviewTransactions/OrderReviewMapper与实体审核字段。V14__review_moderation.sql新增三列和约束；不创建空模块，不增加XML。

管理员账号列表位于admin/controller/AdminAccountController、service/AdminAccountService、mapper/AdminAccountMapper、vo/AdminAccountPage；复用auth/vo/AccountView和AuthSessionMapper普通读。无XML或空模块；AdminAccountListMysqlIT覆盖权限/分页/并发快照。

账号状态扩展AdminAccountController/AdminAccountMapper，新增admin/dto/AccountStatusWrite、service/AdminAccountStatusService、mapper/AccountStatusChangeMapper，复用AuthSessionMapper撤销会话；V15__account_status_change.sql。AdminAccountStatusMysqlIT验证权限/版本/事务/并发，AdminAccountStatusDomainMysqlIT验证现有预约/打印及公开边界。无XML或空模块。

预约整链验收位于src/test/java/com/campuslife/BookingJourneyMysqlIT.java：HTTP创建商家、审批与配置，再验收正常和退款分支；SQL仅读取不变量及清理本次归属数据。人工对应[全流程指南](booking-journey.md)。无新生产模块或迁移。

打印整链验收：src/test/java/com/campuslife/PrintJourneyMysqlIT.java通过HTTP创建PRINT商家、价目、私有PDF和订单，验证完整履约与退款/文件引用分支；SQL只读断言/精确清理，自有测试文件根明确校验。人工对应[全流程指南](print-journey.md)，无新生产模块或迁移。

M08.3-B在coupon模块内增加DispatchGateMapper、CouponIssueMessage、CouponIssuance、CouponIssueListener、CouponClaimExpiryJob及消费凭据Mapper；沿模块内分层，不新增XML或空模块。messaging仍管理通用outbox发布和Rabbit配置，显式增加独立券路由/队列；通知消费者保持原职责。边界见[ADR-0054](decisions/0054-async-coupon-claims.md)。

M08.3-C在coupon/redis放置CouponRedisStore，在resources/redis/coupon放置有界脚本；coupon/config显式管理独立凭据/连接，coupon/service新增恢复数据库事务、分阶段协调与后台私有单工作线程。恢复Mapper沿注解SQL，普通发行与outbox仍复用B/M03；scripts/coupon-redis*.ps1仅管理自有资源，不操作业务mode或读取DB/JWT。参见[C指南](coupon-redis-recovery.md)。

M08.3-D：`coupon/service/CouponAdmissionService`协调三阶段且NEVER进入外层事务，`CouponAdmissionTransactions`只做SQL短事务，原`CouponClaimService`保留SYNC与读取。`CouponMaintenance`/`CouponMaintenanceTransactions`及`CouponMaintenanceMapper`负责本机控制；`coupon/bootstrap/CouponMaintenanceBootstrap`使用独立非Web profile，`scripts/coupon-mode.ps1`显式启动，不随普通应用执行。无新业务模块、HTTP路径或迁移。
