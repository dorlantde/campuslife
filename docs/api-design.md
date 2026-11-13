# V1 完整接口规划与 Apifox 导入

2026-10-31：运行/完整快照仍75操作、75已实现/0待开发。D在原领券POST接通受控ASYNC与202/Location，维护是本机非HTTP入口；导入更新后的快照，见[公开受理/维护指南](async-coupon-admission.md)。以下数字按记录日期解释。

M08.2-C当前运行73操作/V19/28表；订单用券见[ADR-0052](decisions/0052-order-coupons.md)。设计导入文件由运行快照生成，不将Lua/MQ候选加入已实现目录。


2026-10-30 M08.2-B新增4个可调用操作，两份目录72已实现/待开发0（V1及已实现扩展范围）。订单报价/用券与Lua/MQ尚未实现；[点击指南](coupon-claims.md)。

2026-10-29 M08.2-A：V1之外新增商家活动6操作，两份OpenAPI现68个已实现操作、待开发0（统计范围为V1及已实现扩展）。[ADR-0050](decisions/0050-coupon-business.md)确认更完整的营销业务，但学生领取/订单用券仅在[方案](coupon-business-design.md)列出，尚未生成可调用契约；Lua/MQ协议亦未定稿。使用见[活动指南](merchant-coupon-campaigns.md)。

2026-10-26 当前交付：账号停用/恢复按[ADR-0046](decisions/0046-admin-account-status.md)接入。禁止修改本人，双账号按ID排序主键锁后复核正常ADMIN；实际变化时状态/version+1、目标全部旧会话撤销、状态历史原子提交，恢复须重新登录。旧版本409，同版本同状态200无写入；不自动处理订单/资金或改文件期限。V15新增account_status_change，23表；运行62、完整62/62/0，当前规划入口齐全。见[Swagger / Apifox指南](admin-account-status.md)；旧阶段“账号状态待开发”以本次为准，接口齐全不等于公开部署或全部工程工作完成。

2026-10-26 当前交付：管理员账号列表按[ADR-0045](decisions/0045-admin-account-list.md)接入。正常ADMIN查询全部业务账号，角色/状态交集、分页、创建时间/ID倒序，仅六字段AccountView；身份/会话与count/items同一只读RR快照，返回前核对到期，无业务行锁或写入。运行61、完整62/61/1，仍V1～V14/22表，无新迁移；账号停用/恢复尚未实现。见[Swagger / Apifox指南](admin-account-list.md)，旧阶段“账号列表待开发”以本次交付为准。

2026-10-26 当前交付：管理员隐藏评价按[ADR-0044](decisions/0044-admin-review-hide.md)接入。正常ADMIN说明原因后原子保存首次审核记录并隐藏；同人同原因重放保留事实，其他重复409。后续公开评价/真实评分排除，本人仍可读、reviewed=true且不能重发，不改订单/资金/文件期限。V14补三个审核字段，无新表，仍22表；运行60、完整62/60/2，仅管理员账号列表/状态管理待开发。见[Swagger / Apifox指南](admin-review-hide.md)，旧阶段“隐藏后续”以本次交付为准。

2026-10-26 当前公开评价扩展：[ADR-0043](decisions/0043-public-shop-reviews.md)新增匿名GET /shops/{shopId}/reviews，仅公开可见店铺VISIBLE记录、作者当前昵称；停用作者不自动隐藏。商家列表/详情rating/reviewCount改为真实全部VISIBLE均分/数量，两位HALF_UP、零条null/0，同响应短RR、页内批量统计。运行59/完整62/59/3，V1～V13/22表无新迁移，见[点击指南](public-shop-reviews.md)。旧统计阶段值说明由本次取代；隐藏入口后续。

2026-10-26 当前学生评价扩展：[ADR-0042](decisions/0042-student-order-review.md)接入POST/GET /orders/{orderId}/review，合法已付完成单一单一评，详情reviewed真实查询且隐藏也true；评价独立，不改订单版本/状态事件/资金/文件。V13/22表，运行58、完整62/58/4，见[点击指南](student-order-review.md)。公开评价/真实评分统计及隐藏入口后续；旧阶段固定reviewed=false的说明由本次取代。

2026-10-26 当前管理员PRINT扩展：[ADR-0041](decisions/0041-admin-print-exception.md)使原管理员详情/核实关闭支持两类订单；PRINT已付未结束四种阶段可全额模拟退款为CLOSED/REFUNDED/ABORTED，保留已发生时间，reason+expectedVersion防覆盖取件/履约新事实。全部文件引用保护、下载授权及重放沿已确认边界，见[点击指南](admin-print-exception.md)。运行仍56、完整62/56/6，无新迁移；旧阶段管理员仅BOOKING的描述以本次为准。

2026-10-25 当前取件扩展：[ADR-0040](decisions/0040-student-print-pickup.md)新增学生本人POST /orders/{orderId}/pickup，正常打印单可完成；COMPLETED/PAID/PICKED_UP与完成/取件时间、版本/事件、全部引用期限原子提交。合法终态pickup及商家start/ready可重试，不续期/回退，完成单撤销商家下载授权来源。运行56、完整62/56/6，见[点击验收](student-print-pickup.md)。下方旧阶段取件待开发、完成后start/ready拒绝的描述以本次为准。

2026-10-25：[ADR-0039](decisions/0039-merchant-print-fulfillment.md)开放PRINT开始、待取件和履约中内容下载，运行55、完整62/已实现55/待开发7，见[点击指南](merchant-print-fulfillment.md)。商家分组9个操作全部可调用；学生取件及管理员PRINT仍待后续。

2026-10-25：[ADR-0038](decisions/0038-merchant-print-download.md)扩展既有文件content下载支持本店合格未开始打印任务，见[点击指南](merchant-print-download.md)。运行54、完整62/已实现54/待开发8，无新路径；学生本人元数据与商家内容权限分开，IN_SERVICE内容授权仍待后续。

2026-10-25：原商家cancel入口现支持已接单未开始PRINT，见[ADR-0037](decisions/0037-merchant-print-cancellation.md)与[点击验收](merchant-print-cancellation.md)。运行54、完整62/已实现54/待开发8，无新路径或迁移；旧阶段取消仅BOOKING的说明由本次扩展替代。

当前扩展（2026-10-24）：商家原查询及接单/拒单五个操作支持PRINT，见[确认ADR-0036](decisions/0036-merchant-print-confirmation.md)及[点击验收](merchant-print-confirmation.md)。运行快照54，完整62/已实现54/待开发8；不新增路径。注解和运行快照为实际类型范围，旧阶段描述仅代表历史。

更新：2026-10-24。本文件说明完整规划版接口文档，供开发前查看、讨论和准备调用参数。

## 选择完整规划文件

导入 [campuslife.v1-design.openapi.json](openapi/campuslife.v1-design.openapi.json)，磁盘位置：

```text
D:\campuslife\docs\openapi\campuslife.v1-design.openapi.json
```

完整 V1 规划有 **62 个操作、9 个分组**，其中 **59 个已实现，3 个待开发**。入驻申请/审核/重提落实 ADR-0010，经营资料与营业开关落实 ADR-0013，每周营业时间落实 ADR-0014，服务目录落实 ADR-0015，打印价目落实 ADR-0016，理发师与绑定落实 ADR-0017，排班及营业时间保护落实 ADR-0018，公开商家目录落实 ADR-0019，公开理发师落实 ADR-0020，预约候选时段落实 ADR-0021，待支付预约及真实占用/释放落实 ADR-0022，模拟支付与待接单退款落实 ADR-0023，商家确认及已接单学生取消落实 ADR-0024，商家无法履约取消落实 ADR-0025，预约开始/完成落实 ADR-0026，管理员预约异常落实 ADR-0027，学生列表落实 ADR-0028，商家列表落实 ADR-0029，订单事件落实 ADR-0030，PDF上传/本人元数据落实 ADR-0031，本人PDF下载落实 ADR-0032，打印报价落实 ADR-0033，打印待支付与本人历史扩展落实ADR-0034，打印支付/待接单退款落实ADR-0035，其余 3 个仍保留讨论稿状态。一个路径可能包含多个方法，例如 GET /me 与 PATCH /me 是两个操作。

| 分组 | 操作数 | 当前状态 |
|---|---:|---|
| 00 系统 | 1 | 已实现 |
| 01 账号认证 | 3 | 已实现 |
| 02 本人资料 | 3 | 已实现 |
| 03 商家入驻 | 10 | 分类/申请/本人店铺/重提与公开列表/详情/上架服务/理发师及候选时段及公开评价10个均已实现 |
| 04 文件与打印报价 | 4 | PDF上传/本人元数据/本人下载/打印报价4个均已实现 |
| 05 学生订单 | 10 | 预约/打印创建、详情/取消、预约/打印模拟支付及本人列表/事件、打印取件及评价读写10个均已实现 |
| 06 商家经营配置 | 13 | 资料/开关、营业时间、服务、打印价目、理发师与绑定及按日排班共 13 个全部已实现 |
| 07 商家订单履约 | 9 | 本店预约/打印查询、接拒单、未开始取消、开始、打印待取件及预约完成，9个均已实现 |
| 08 管理员 | 9 | 店铺审核4个及预约/打印详情与异常退款2个共6个已实现，其余3个为待开发讨论稿 |

完整文件与 [现有接口快照](openapi/campuslife.openapi.json)是两份不同用途的文档。运行中的 /v3/api-docs 与浏览器 Swagger 展示已实现的 59 个操作，不会因导入完整规划文件而生成业务功能。

## 在 Apifox 中导入

运行版共59个操作，完整规划62个操作、97个数据结构；新增打印待支付创建、本人历史及文件保护见[打印订单验收](print-orders.md)。其余主要流程分别见[管理员审核](admin-shop-review.md)、[理发师验收](merchant-barbers.md)、[公开目录](public-shop-catalog.md)、[预约订单](booking-orders.md)、[学生列表](student-order-list.md)、[商家列表](merchant-order-list.md)、[事件](order-events.md)、[上传](print-file-upload.md)、[下载](file-download.md)、[报价](print-quotes.md)、[预约支付](booking-payments.md)、[商家确认](merchant-booking-confirmation.md)、[商家取消](merchant-booking-cancellation.md)、[预约履约](merchant-booking-fulfillment.md)及[管理员异常](admin-booking-exception.md)。尚未代用户操作Apifox客户端。

运行 OrderDetail 如实描述本阶段字段；未实现履约/打印操作引用 PlannedOrderDetail 讨论稿，保留未来打印规格与流水结构。该模型仅用于规划文档，不新增 Java 空模块或已实现能力。

1. 打开 Apifox，选择“导入项目”，格式选 **OpenAPI / Swagger**。
2. 选择上面的 **campuslife.v1-design.openapi.json**，不要误选只有 54 个操作的 campuslife.openapi.json。
3. 推荐导入成单独的“CampusLife V1 规划”项目，维护方式可选“以可视化界面维护接口”。这样可以与原来用于实际调试的项目对照查看。
4. 预览中应看到 9 个分组、62 个操作。若入口支持导入接口状态和鉴权组件，可一起勾选。
5. 完成导入后即可浏览全部请求、响应、参数、权限与 Schemas。查看文档不要求本地服务已经启动。
6. 要实际调用已实现接口，启动应用，将本机环境服务地址设为 **http://127.0.0.1:8080**，按 [点击验收指南](api-testing.md)注册、登录、填写 Bearer Token。

如果要导入现有项目，先在预览确认相同方法、路径的更新策略，避免覆盖你保存的调试用例和环境配置。本文只提供本地文件，没有替用户上传项目或登录 Apifox。

## 看懂状态与示例

每个标题都带明确前缀，即使导入工具不识别扩展字段，也能区分：

- **[已实现]**：沿用经过本机验证的运行快照，Apifox 状态为 tested（已测完），不表示已经公开发布。
- **[待开发·规则已确认]**：此标记预留给规则确认但尚未交付的接口；当前重提已实现，没有剩余接口使用此标记。
- **[待开发·规则待确认]**：状态为 designing（设计中）。包含目标方法、路径、请求、响应和权限；实际请求当前服务可能得到 401 或 404。

实现状态还记录于 x-implementation-status，决策状态记录于 x-decision-status。它们相互独立，confirmed 不能替代 implemented。已确认的业务基线仍有效，但导入文件不等于批准所有字段、参数上限和边界规则。

所有示例为虚构数据。示例 ID 不保证在本机库存在，示例日期需要按测试当天和预约窗口修改，版本必须从实际查询结果取得。文件上传使用 multipart/form-data，字段名为 file；PDF 下载成功返回附件流，其他普通 API 使用统一 JSON 包装。

金额单位为分；ID 与版本为十进制字符串。订单创建需要 Idempotency-Key，相同请求重试保留原键。学生、商家与店铺身份来自服务端会话，不能靠请求中的 accountId、studentId、shopId 或 role 切换身份。

如果后续在 Apifox 使用 Mock，返回的是模拟结果，不能据此认定订单、支付或退款已实现。本项目没有建立 Mock 服务；已实现接口以实际运行与验证记录为准。

## 仍需讨论的设计细节

来源为 [接口契约](v1-api-contract.md)、[业务基线](decisions/0001-v1-baseline.md)、[数据库设计](v1-database-design.md)和[状态迁移](v1-state-transitions.md)。部分响应视图原文只给了字段概述，本次按这些设计补充了数据结构映射，均标记待复核，没有新增审批、支付或订单业务规则。

申请与重提按 ADR-0010、经营资料权限与开关按 ADR-0013 已确认并交付。每周营业时间按 ADR-0014 已确认。服务目录与分类绑定按 ADR-0015 确认，打印价目按 ADR-0016、理发师与绑定按 ADR-0017 确认。对应模块实现前仍要确认：暂停店铺后的存量履约、预约订单占用状态与到期处理、有效预约对配置变更的保护、打印 copies 上限、文件存储选型、管理员账号状态操作边界等。完整售后、优惠券、设施、消息等 V2 内容不属于这份 V1 文件。

## 维护和本轮检查

生成来源为 scripts/build-api-design.py，Python 3 标准库即可运行，不影响 Java 应用的构建或启动：

```text
python scripts/build-api-design.py
python scripts/build-api-design.py --check
```

先维护接口契约及生成脚本；实现新接口后，更新运行快照，再重新生成完整文件。已实现接口由运行快照自动识别，标题和状态随之更新。校验模式检查契约中的方法/路径覆盖、唯一 operationId、路径参数、模型引用及生成文件是否最新；发现重复定义会停止。

2026-10-15 历史检查（当时 61 个操作、7 已实现）：

- 与 Markdown 契约逐项对齐：61 个操作，7 已实现、54 待开发，无遗漏或额外业务入口。
- 通过 OpenAPI 官方 3.0 JSON Schema 格式检查，全部模型引用可解析。
- 21 个待开发 JSON 请求示例、参数示例及全部模型结构通过 schema 检查；示例不代表实际业务执行成功。
- 已实现 7 个操作的原始参数、请求、响应和鉴权定义保持一致，只补充状态与标题说明。

2026-10-15 该次仅维护规划文件，没有修改 Java 代码、数据库或运行配置，没有重复执行当时的 140 个业务/文档测试，也未完成 Apifox 客户端导入验证。

2026-10-19 更新：本轮实际新增三个 Java 接口与 V2 迁移，运行快照由真实 MySQL 下的 OpenApiMysqlIT 导出；同步重提规划和已确认状态。商家代码、迁移和验证记录见 [入驻指南](merchant-onboarding.md)，本轮依然没有代用户操作 Apifox 客户端。

参考：[OpenAPI 3.0 规范](https://spec.openapis.org/oas/v3.0.3.html)、[Apifox 接口状态扩展](https://docs.apifox.com/x-apifox-status)。

2026-10-19 最新：重提已实现，运行快照 15 个操作，规划 62 个（15 已实现、47 待开发）。快照由真实 MySQL 下的 OpenApiMysqlIT 导出；生成器与官方 OpenAPI 3.0 schema 检查通过，18 个请求示例及模型/参数引用有效。业务验证见 [重提验收](merchant-resubmission.md)。

随后经营资料与营业开关按 ADR-0013 实现：当前快照 16 个操作，规划共 62 个（16 已实现、46 待开发）。运行导出、生成器与官方 OpenAPI 3.0 schema、17 个请求示例及模型/参数引用检查通过，全回归 277 个用例通过。操作步骤见 [经营验收](merchant-operation.md)，尚未代用户执行 Apifox 导入。

最新每周营业时间按 ADR-0014 实现，运行快照 17 个操作，规划共 62 个（17 已实现、45 待开发）。真实导出、生成器、官方 OpenAPI 3.0 schema、16 个请求示例及模型/参数引用检查通过，验证记录见 [营业时间验收](merchant-business-hours.md)。

2026-10-20 服务配置按 ADR-0015 实现，运行快照 20 个操作，规划共 62 个（20 已实现、42 待开发）、9 分组、73 个 schemas。真实导出、生成器一致性、官方 OpenAPI 3.0 schema、14 个待开发请求示例及模型/参数引用校验通过，执行结果见 [服务验收](merchant-service-catalog.md)。

2026-10-20 打印价目按 ADR-0016 实现，最终运行快照 22 个操作，规划共 62 个（22 已实现、40 待开发）、9 分组、74 个 schemas。实际导出、生成器、官方 OpenAPI 3.0 schema、14 个请求示例与模型/参数引用检查通过。批量创建示例显式提供 expectedVersion=null；执行记录见 [价目验收](merchant-print-prices.md)，未代用户操作 Apifox。

2026-10-21 理发师与绑定按 ADR-0017 实现，实际运行快照 26 个操作，完整规划共 62 个（26 已实现、36 待开发）、9 分组、76 个 schemas。真实导出、生成器、官方 OpenAPI 3.0 schema、11 个请求示例及模型/参数引用检查通过，执行记录见 [理发师验收](merchant-barbers.md)。该阶段排班仍是待确认讨论稿；现已按下面的 ADR-0018 记录交付。未代用户操作 Apifox。

2026-10-21 按日排班及营业时间保护按 ADR-0018 交付，实际运行快照 28 个操作，完整规划 62 个（28 已实现、34 待开发）、9 分组、77 个 schemas。真实导出、生成器一致性、官方 OpenAPI 3.0 schema、10 个请求示例及模型/参数引用检查通过。完整回归 696 个用例全部通过，操作和执行记录见[排班验收](merchant-schedule.md)。

2026-10-21 公开商家目录按 ADR-0019 交付：运行 31 个操作，完整规划 62 个（31 已实现、31 待开发）、9 分组、80 个 schemas。真实导出、生成器一致性、官方 OpenAPI 3.0 schema、10 个请求示例及参数/模型引用检查通过。定向 106 个测试与 mysql JAR 通过，完整执行过程见[目录验收](public-shop-catalog.md)；本轮未重跑全部旧业务。

2026-10-21 公开理发师按 ADR-0020 交付：运行 32 个操作，完整规划 62 个（32 已实现、30 待开发）、9 分组、81 个 schemas。真实导出、生成器、官方 OpenAPI 3.0 schema、10 个请求示例及引用检查通过；本轮定向 139 个用例和 mysql JAR 通过，具体记录见[公开理发师验收](public-barbers.md)。

2026-10-21 预约候选时段按 ADR-0021 交付：运行 33 个操作，完整规划 62 个（33 已实现、29 待开发）、9 分组、82 个 schemas。实际导出、生成器一致性、官方 OpenAPI 3.0 schema、10 个请求示例及引用检查通过；本轮定向 227 个测试和 mysql JAR 检查通过，未重跑全部旧业务。候选暂不扣订单占用，规则及执行记录见[候选时段验收](booking-slots.md)。

2026-10-22 待支付预约按 ADR-0022 交付：运行36个操作，完整规划62个（36已实现、26待开发）、9分组、85 schemas。真实运行导出、生成器一致性、官方OpenAPI 3.0 schema、9个请求示例及模型/参数引用检查通过。未实现生命周期使用 PlannedOrderDetail 讨论稿，当前运行DTO不被规划字段覆盖；最终定向154个用例及mysql JAR检查通过。全回归与各阶段实际结果见[预约验收](booking-orders.md)，未代用户操作Apifox。

2026-10-22 模拟支付与待接单退款按 ADR-0023 交付：实际运行37个操作，完整62个（37已实现、25待开发）、9分组、86 schemas。Money视图以实际运行类型为准；规划履约/打印仍使用 PlannedOrderDetail。真实MySQL下导出、生成器一致性、官方schema及9个请求示例/参数/模型引用校验通过。最终定向272个用例及mysql JAR检查通过，具体范围见[支付验收](booking-payments.md)，未重跑全部旧业务或代用户导入Apifox。

2026-10-22 商家预约确认按ADR-0024交付：本店详情/接单/拒单3个新增操作及学生已接单取消扩展；实际40个操作，完整62个（40已实现/22待开发）、9分组、87schemas。运行模型与接口由真实MySQL下的OpenApiMysqlIT导出；生成器一致性、官方schema、8个请求示例及参数/模型引用检查通过。最终定向330个用例及mysql JAR检查通过，未重跑全部旧业务或代用户操作Apifox，范围见[确认验收](merchant-booking-confirmation.md)。

2026-10-22 商家无法履约取消按ADR-0025交付：新增一个POST /merchant/orders/{orderId}/cancel；运行41个操作，完整62个（41已实现/21待开发）、9分组、88schemas。运行快照由真实MySQL下OpenApiMysqlIT导出；生成器一致性、官方OpenAPI3.0 schema、7个请求示例和参数/模型引用检查通过。定向235个用例及mysql JAR通过，未重跑全部旧业务或代用户操作Apifox，范围见[取消验收](merchant-booking-cancellation.md)。

2026-10-22 预约开始/完成按ADR-0026交付，两个操作当前仅支持BOOKING，打印开始仍未开放。运行43个操作，完整62个（43已实现/19待开发）、9分组、88schemas；实际OpenApiMysqlIT导出、生成器一致性、官方schema及7个请求示例/参数/模型引用校验通过。首轮回归246个、最终定向143个分别通过，新增履约54个真实MySQL用例，mysql JAR通过；未重跑全部旧业务或代用户导入Apifox，范围见[履约验收](merchant-booking-fulfillment.md)。

2026-10-22 管理员预约详情/核实异常退款按ADR-0027交付，仅BOOKING；运行45个操作，完整62个（45已实现/17待开发）、9分组89schemas。实际OpenApiMysqlIT导出、生成器一致性、官方schema、6个请求示例/参数/模型引用检查通过。首轮相关280个、最终定向205个分别通过，新增管理员异常57个真实MySQL用例；mysql JAR通过。未重跑全部旧业务，未代用户导入Apifox，范围见[异常验收](admin-booking-exception.md)。

2026-10-22 学生本人订单列表按ADR-0028交付，GET /orders只返回本人BOOKING摘要，PRINT筛选当前空页。实际运行导出46个操作，完整62个（46已实现/16待开发）、9分组90schemas；生成器一致性、两份官方schema、6个请求示例/参数/模型引用检查通过。定向197个用例全部通过，含43个列表真实MySQL用例；mysql JAR启动检查通过，未代用户导入Apifox，范围见[列表验收](student-order-list.md)。

2026-10-23 商家本店订单列表按ADR-0029交付，GET /merchant/orders只展示授权本店BOOKING，PRINT暂空页但完整校验权限/参数。运行导出47个操作，完整62个（47已实现/15待开发）、9分组90schemas；生成器、两份官方schema、6个请求示例及参数/模型引用检查通过。独立MySQL定向239个用例通过（新增商家列表48个），mysql JAR检查通过；尚未代用户导入Apifox，范围见[商家列表验收](merchant-order-list.md)。

2026-10-23 学生本人/商家本店订单事件按ADR-0030交付，两个GET返回已授权预约的原事件完整数组；原原因及操作者ID可见，无账号资料。运行49个操作，完整62/49/13、9分组91schemas；生成器、两份官方schema、6个请求示例及参数/模型引用检查通过。独立MySQL定向259个用例通过（新增事件54个），mysql JAR检查通过；尚未代用户导入Apifox，范围见[事件验收](order-events.md)。

2026-10-23 PDF上传/本人元数据按ADR-0031交付，POST /files及GET /files/{fileId}仅正常学生，未开放内容下载或商家文件权限。运行51、完整62/51/11，9分组92schemas；生成器、两份官方schema、6个请求示例及参数/模型引用检查通过。最终定向242个用例通过（新增文件单元37和文件真实MySQL64），mysql JAR检查通过；未代用户导入Apifox，范围见[上传验收](print-file-upload.md)。

2026-10-23：本人PDF下载按ADR-0032交付，当前52、完整62/52/10；成功为application/pdf binary，错误为ApiError JSON，Bearer仅限正常学生本人。下载不续期、清理并发及响应中断边界见[验收](file-download.md)；商家文件访问仍须未来打印订单授权。

2026-10-24：打印报价按ADR-0033交付，当前53、完整62/53/9；复用运行PrintQuoteRequest/PrintQuote及ApiResponsePrintQuote，不覆盖实际字段/金额上限。运行和规划均要求Bearer正常学生本人，有效元数据/上架服务/启用价目；报价不代表可下单。详见[验收](print-quotes.md)，未代用户导入Apifox。
