# M05.1 最小前端：页面、接口与验收方案

草案日期：2026-10-28。**2026-10-29用户明确“采用该方案”，选型/范围获确认，见[ADR-0049](decisions/0049-minimal-frontend.md)。** 本文件保留定稿时建议与源码核对依据；实际实现及验收进度以[路线图](roadmap.md)和[前端指南](frontend.md)为准，不把批准当作全部页面交付。

## 1. 目标与源码依据

让学生、商家和管理员通过页面完成现有V1业务，方便日常联调和面试演示。后端62操作、V16/24张应用表保持；优惠券、消息和对象存储继续按[路线图](roadmap.md)推进。前端阶段不重做事务、锁、支付、权限或缓存。

已核对[运行接口导出](openapi/campuslife.openapi.json)、[认证配置](../src/main/java/com/campuslife/auth/config/ApiSecurityConfiguration.java)、[登录响应](../src/main/java/com/campuslife/auth/vo/LoginView.java)、[商家入驻](../src/main/java/com/campuslife/merchant/controller/MerchantController.java)、[订单控制器](../src/main/java/com/campuslife/order/controller/BookingOrderController.java)、[打印创建字段](../src/main/java/com/campuslife/order/dto/PrintCreate.java)、[预约创建字段](../src/main/java/com/campuslife/order/dto/BookingCreate.java)、[文件控制器](../src/main/java/com/campuslife/file/controller/FileController.java)及管理员控制器。使用真实请求字段，不按早期文档中的“待开发”字样推断当前能力。

两处限制直接影响界面：

- 管理员有商家/账号列表，但没有全部订单列表、全部评价列表或评价详情GET。异常订单按订单ID加载；隐藏评价使用公开页面可见的评价ID，提交前展示已取得的内容摘要和原因确认。未取得内容时明确“仅按ID处理”，不合成后台审核列表。批准/拒绝商家不等于已有暂停/恢复店铺接口。
- 理发至少提前2小时预约，商家仅可在预约区间内开始。页面不提供改时钟、提前开始或测试后门。打印可即时完成三角色演示；理发人工完整履约仍需到真实时段，隔离自动整链报告单独引用。

## 2. 推荐技术与工程边界

| 项目 | 推荐 | 原因及边界 |
|---|---|---|
| 工程 | 仓库内独立`frontend/`，单个Vue 3 SPA | 学生与管理工作区共用登录/API层，不分拆三套工程；Java仍为原单Maven工程 |
| 开发 | Vite、TypeScript、Composition API与`script setup` | 类型明确两类订单、字符串ID、报价和可空字段，便于后续优惠券增量接入 |
| 路由与界面 | Vue Router手写路由；Element Plus表格/表单/弹窗，普通CSS | 完成业务页面所需控件，不引入完整后台模板、SSR或微前端。桌面优先，窄屏可查看和操作主要表单，无单独移动端工程 |
| 请求与状态 | 原生`fetch`统一封装；一个轻量响应式会话模块 | 统一JSON/Blob/错误/超时和过期处理；页面状态就近管理，暂不增加Axios/Pinia/查询缓存组件 |
| 运行与构建 | 项目自有Node LTS＋npm，锁定`package-lock.json` | M05.2准备并验证可复现工具，不依赖Codex内部运行时；选择兼容稳定版本后固定实际版本，不在草案虚构锁文件 |
| 测试 | 类型检查/构建，Vitest验证关键客户端边界，真实浏览器联调 | 自动化重点是会话、请求去重、错误恢复、金额/时区与下载分支；不为每个展示字段写镜像测试 |

官方[Vue快速开始](https://vuejs.org/guide/quick-start.html)介绍Vite/TypeScript与路由选项；[Vue Router](https://router.vuejs.org/guide/)提供客户端路由；[Element Plus](https://element-plus.org/en-US/guide/installation.html)提供Vue控件。具体依赖版本及Node兼容范围在实施时根据[Vue](https://vuejs.org/guide/quick-start.html)、[Vite](https://vite.dev/guide/)与[Node发布表](https://nodejs.org/en/about/previous-releases)共同核对，不只看某一个工具最低版本。

2026-10-28定稿核对时，PATH可见Node 24.22.1来自Codex依赖目录，未找到PATH中的npm，仓库没有`package.json`。因此当时不能宣称前端具备可复现构建环境。M05.2需在`.tools/`准备项目自有工具并确认下载完整性；后续实际环境以指南记录为准。

建议本地前端监听`127.0.0.1:5173`，固定端口占用时清楚报错；所有业务请求用相对路径`/api/v1`，由[Vite开发代理](https://vite.dev/config/server-options.html#server-proxy)转发原后端`127.0.0.1:8080`。不为本地联调放开全局CORS，不改原VS Code启动凭据。将来M06由同源反向代理承接前端与API；当前后端关闭静态资源映射且默认接口要求认证，不能直接把`dist`塞进jar就声称可部署。构建产物为`frontend/dist`，部署方案后续确认。

## 3. 页面范围与接口映射

以下是**13个页面模块**，列表/详情、选项卡和弹窗可能共用组件；不把复杂配置压成“一个简单页面”的工作量承诺。业务路径均为现有后端接口；页面路径是建议的客户端路由，不改变API。

| 模块与建议路由 | 角色与操作 | 使用的现有接口 |
|---|---|---|
| P01 登录/学生注册 `/login`、`/register` | 统一登录，按服务端角色进入工作区；注册成功后手动登录 | `POST /api/v1/auth/login`、`POST /api/v1/auth/register`、`POST /api/v1/auth/logout` |
| P02 商家申请 `/apply` | 独立创建商家账号与申请；成功展示店铺ID/待审说明，再登录 | `POST /api/v1/merchant-applications`、`GET /api/v1/shop-categories` |
| P03 本人资料 `/me` | 三角色昵称、改密、退出；字段版本由服务器返回 | `GET /api/v1/me`、`PATCH /api/v1/me`、`POST /api/v1/me/password` |
| P04 商家目录 `/shops` | 匿名或登录浏览；名称/分类筛选与分页，真实评分和营业状态 | `GET /api/v1/shop-categories`、`GET /api/v1/shops` |
| P05 商家详情 `/shops/:shopId` | 公开资料、服务、评价；学生选择理发服务/人员/校园日期/候选，确认预约 | `GET /api/v1/shops/{shopId}`、`GET /api/v1/shops/{shopId}/services`、`GET /api/v1/shops/{shopId}/reviews`、`GET /api/v1/shops/{shopId}/barbers`、`GET /api/v1/shops/{shopId}/booking-slots`、`POST /api/v1/orders/bookings` |
| P06 打印下单 `/shops/:shopId/print/:serviceId` | 正常学生选择PDF、查看页数/期限、选规格份数、报价、确认创建；可输入已知本人文件ID复用 | `POST /api/v1/files`、`GET /api/v1/files/{fileId}`、`GET /api/v1/files/{fileId}/content`、`POST /api/v1/print-quotes`、`POST /api/v1/orders/prints`；进入页另复核P05店铺/服务 |
| P07 本人订单 `/orders`、`/orders/:orderId` | 类型/状态分页、冻结明细、模拟付款、允许取消、打印取件、本人评价、事件时间线及打印原件下载 | `GET /api/v1/orders`、`GET /api/v1/orders/{orderId}`、`GET /api/v1/orders/{orderId}/events`、`POST /api/v1/orders/{orderId}/payments/mock`、`POST /api/v1/orders/{orderId}/cancel`、`POST /api/v1/orders/{orderId}/pickup`、`GET /api/v1/orders/{orderId}/review`、`POST /api/v1/orders/{orderId}/review`；下载复用P06文件接口 |
| P08 商家店铺 `/merchant/shop` | 待审核/拒绝/批准状态；重提；批准后的经营资料与营业开关、整周营业时段、服务、价目、理发师/绑定/按日排班选项卡 | `GET /api/v1/merchant/shop`、`POST /api/v1/merchant/shop/resubmit`、`PATCH /api/v1/merchant/shop`、`PUT /api/v1/merchant/shop/business-hours`、`GET /api/v1/merchant/services`、`POST /api/v1/merchant/services`、`PATCH /api/v1/merchant/services/{serviceId}`、`GET /api/v1/merchant/services/{serviceId}/print-prices`、`PUT /api/v1/merchant/services/{serviceId}/print-prices`、`GET /api/v1/merchant/barbers`、`POST /api/v1/merchant/barbers`、`PATCH /api/v1/merchant/barbers/{barberId}`、`PUT /api/v1/merchant/barbers/{barberId}/services`、`GET /api/v1/merchant/barbers/{barberId}/schedule`、`PUT /api/v1/merchant/barbers/{barberId}/schedule` |
| P09 商家任务 `/merchant/orders`、`/merchant/orders/:orderId` | 本店分页/详情/事件，接单/拒单/无法履约取消；预约开始/完成；打印下载/开始/待取件 | `GET /api/v1/merchant/orders`、`GET /api/v1/merchant/orders/{orderId}`、`GET /api/v1/merchant/orders/{orderId}/events`、`POST /api/v1/merchant/orders/{orderId}/accept`、`POST /api/v1/merchant/orders/{orderId}/reject`、`POST /api/v1/merchant/orders/{orderId}/cancel`、`POST /api/v1/merchant/orders/{orderId}/start`、`POST /api/v1/merchant/orders/{orderId}/complete`、`POST /api/v1/merchant/orders/{orderId}/ready-for-pickup`；下载复用P06文件接口 |
| P10 管理员商家审核 `/admin/shops`、`/admin/shops/:shopId` | 审核状态分页；读取详情后批准/填原因拒绝，携带读取版本 | `GET /api/v1/admin/shops`、`GET /api/v1/admin/shops/{shopId}`、`POST /api/v1/admin/shops/{shopId}/approve`、`POST /api/v1/admin/shops/{shopId}/reject` |
| P11 管理员订单异常 `/admin/orders`、`/admin/orders/:orderId` | 输入从演示双方获得的订单ID，加载详情；核实原因及读取版本后全额模拟退款关闭 | `GET /api/v1/admin/orders/{orderId}`、`POST /api/v1/admin/orders/{orderId}/close-exception` |
| P12 管理员账号 `/admin/accounts` | 角色/状态分页、停用/恢复其他账号；本人操作禁用，明确不会自动退款旧单 | `GET /api/v1/admin/accounts`、`PATCH /api/v1/admin/accounts/{accountId}/status` |
| P13 管理员评价处理 `/admin/reviews` | 可从P05公开评价带入ID/摘要；输入原因后隐藏；已有隐藏不提供恢复/改原因 | `POST /api/v1/admin/reviews/{reviewId}/hide` |

上表覆盖**61个不同操作**，重用接口只计一次。另有`GET /api/v1/system/health`用于运行排查，不增加系统管理页面；合计与当前62操作一致。这里只规划UI映射，并不要求在一个开发小任务中实现全部页面。

导航只出现已可用业务；优惠券和消息仅保留后续模块组织空间，不显示虚构券数量、不可用领取按钮或伪消息。P05评价可见ID用于真实审核联系；不新增举报业务。全局空态、加载态、错误重试及无权限/未找到页面为共用组件，不新增业务后台。

## 4. 登录与权限体验

- 登录响应保存`accessToken`、`expiresAt`与账号到当前标签页`sessionStorage`，内存驱动界面；刷新后先`GET /me`复核，再展示受保护内容。密码仅提交本次请求，不保存、不输出到日志；不要求用户复制token。
- 单标签页同一时刻一个账号。演示学生/商家/管理员时，各自手动新开独立标签页并登录，打开链接用`noopener`，避免opener初始复制会话；复制浏览器标签页可能复制初始存储，必须检查当前账号再操作。[sessionStorage行为](https://developer.mozilla.org/en-US/docs/Web/API/Window/sessionStorage)按来源和标签页隔离，但可被同源脚本读取，并非防XSS保险箱。评价/昵称/文件名按纯文本渲染，不使用`v-html`。
- 同标签页切换账号先退出当前会话；退出请求失败时提示“仅本地已清除，服务端会话可能仍有效”，不能声称撤销成功。改密成功清除当前会话并返回登录，沿用后端撤销全部旧会话。无refresh token或自动续期。
- 路由按服务端`account.role`分区；商家待审/被拒仍可登录看店铺状态。前端路由只改善体验，最终权限仍由后端决定，不能修改本地role取得授权。
- 认证失效/过期/被撤销清除当前会话、业务缓存和跨账号草稿，回到登录且只保留站内安全返回路径。账号停用同样清除；一般403角色不足只展示无权限，不把所有403都当成退出。无效token不能靠公共GET自动忽略：匿名请求不附带Authorization；认证失败后明确提示会话已失效并可重新匿名浏览。
- 发请求时捕获会话代次/账号；切换账号后旧响应不得写入新账号页面，旧请求失败不得清除新会话；读请求可以取消，写请求取消/超时不代表服务端回滚。

## 5. 一致性、金额与文件交互

| 情况 | 前端处理规则 |
|---|---|
| 创建订单与网络不确定 | 每次用户确认的一份冻结请求生成`crypto.randomUUID()`作为`Idempotency-Key`；发送前按账号＋创建类型把原键/原正文保存到当前标签页。重复点击禁止并发提交；超时/断网保留原键和正文，提供“查询/重试原提交”，不自动换键。修改内容是新提交，需提醒原提交可能成功并先核查订单；重登/换账号不自动重放，清除草稿前提示未决提交。此轻量恢复只覆盖当前标签页生命周期 |
| 报价或版本冲突 | `QUOTE_CHANGED`重新取候选/打印报价，展示新旧金额后由用户确认；`CONFIG_VERSION_CONFLICT`重新读对应资源并要求核对。已明确失败后的修改提交使用新创建键，不复用旧正文键；审核/异常/状态修改不自动拿新版本重试 |
| 服务端409可能已提交关闭 | `PAYMENT_DEADLINE_PASSED`、`ACCEPTANCE_DEADLINE_PASSED`等先显示原错误，再重新读取订单/事件；状态可能已关闭及退款。不能统一显示“操作失败，订单不变” |
| 时间与动作 | 显示校园上海时间并标明时区；日期选择明确按上海今天至第6天，UTC候选原值直接提交，避免浏览器纽约日期偏移。按钮按已取状态/阶段作提示，后端锁后校验为准；本地倒计时只提示，不把到零当作关单/退款事实。提供刷新，必要时仅在可见详情低频轮询，离页停止，无毫秒轮询 |
| 金额与标识 | ID、version、事件序号保持字符串，不转JS Number。现有最高50亿分仍在安全整数范围；以整数分提交、以整数拆分格式化元显示，不用浮点元参与报价计算；折扣仍取后端真实值，未实现优惠券时不得让用户填写折扣 |
| PDF上传 | 原生文件选择＋`FormData`单一`file`，不手写multipart边界；前端提示20MiB/PDF限制但页数/可解析/加密以服务器为准。文件不保存到浏览器持久存储；元数据页显示实际页数及期限，`expiresAt=null`显示“关联订单保护”。没有本人文件列表接口，不制作虚构文件库 |
| PDF下载 | 用带Bearer的fetch读取完整Blob；先检查状态与Content-Type，JSON错误不能当PDF保存。按Content-Disposition安全取名/兜底名称，临时Object URL触发附件下载后释放；不把token放URL，不直接打开无Authorization的下载链接，不提供ADMIN内容访问 |
| 后端失败/忙碌 | 400保留表单并解释字段，404统一资源不可见，410提示文件失效重选，429可稍后手动重试，503显示依赖或模拟资金不可用；不伪造成功。提示保留业务code及traceId供排障，界面不展示原始堆栈、token或内部存储路径 |

商家整周时段、按日排班、服务绑定的“完整替换”和价目批量“遗漏保留”分别遵守现有接口语义；PATCH未修改字段省略，不能把空控件一律发null。字符串ID/版本直接来自最新对应资源；店铺、服务、理发师、价目版本分别管理，不共用一个version。

模拟付款/退款按钮明确标注模拟。模拟开关关闭时如实呈现503和未发生的新资金，不由前端自动启用；此前付款/退款已发生时按返回事实展示。允许取消、取件和服务开始等不可由界面自行推断成功。提交拒单/取消/核实原因后避免自动重复，失败保留原原因供核对。

## 6. M05.2分批实施与停止条件

确认本方案后依次实施下列批次，每次交付一个可验收任务；批次是M05.2内部拆分，不调整路线图稳定编号。

| 批次 | 交付范围 | 验证与完成标准 |
|---|---|---|
| A：工程/认证/浏览 | 自有Node/npm、前端工程、P01～P05的公开浏览与P03，角色导航壳；预约入口随D启用 | 实际类型检查/构建；真实浏览器注册/登录/刷新/退出/昵称冲突、匿名搜索详情评价；三角色导航与直达权限；旧响应不覆盖新会话。页面只显示本批可用操作，交付可用启动方式 |
| B：入驻/审核/经营配置 | P02、P08、P10；按店铺分类显示打印或理发配置 | 虚构商家新申请→管理员拒绝→商家重提→批准→配置服务/价目或人员/时段→公开可见；旧版本与排班/存量冲突不覆盖数据。正常配置流程可只用页面完成，ADMIN仍由原本机脚本显式初始化 |
| C：打印整链 | P06、P07及P09的PRINT部分；创建未决恢复、受控下载 | 浏览器完成上传→报价→下单→模拟付款→商家接单/下载→开始→待取件→学生取件→评价；另做拒单退款/已接未开始取消、无权限/完成后商家下载拒绝、本人保留期下载、重复提交与报价变化反馈。浏览器断网后原键重试仍同一订单，保留请求与服务端证据 |
| D：预约/异常与其余管理 | P05预约、P07/P09 BOOKING、P11～P13 | 浏览器完成候选→预约→付款→接单/允许取消及冲突；管理员查看真实订单后核实异常退款，评价隐藏同步公开评分、账号停用撤销会话。预约开始/完成在真实时段人工核对，未等到则明确记录未执行；已有隔离时钟整链不能冒充本次浏览器验收 |

测试账号、文件根和业务数据使用独立可辨认的验收环境；不自动注册默认ADMIN、不在源码放密码或token，不为赶验收删除用户已有记录。测试夹具必须隔离；若需用户现有本机应用配合，明确实际使用的数据与待手动步骤。真实浏览器覆盖前后端协作，事务/锁的保证继续引用真实MySQL专项证据。

每批只为受影响的客户端边界补充有意义测试；后端不变不强制重跑2675个历史用例。如果联调发现契约或后端缺陷，先定位并做对应验证；新增业务/接口或改变规则须另外讨论。浏览器控制受文件选择或本机凭据限制时保留具体人工验收步骤，不把未执行写成通过。

M05.2结束需有：可复现工具与锁文件、真实接口页面、类型检查/构建、按批次的点击指南及已执行/未执行报告。完成打印与预约可操作阶段、三角色审核/异常分支和关键失败反馈后结束本阶段，进入M08.1；不继续扩展图表大屏、聊天、地图、收藏、举报、排行或新消息业务。前端工程基础不等于M05.2全部完成，预约人工末段未完成也必须在报告中保留。

## 7. 定稿时核对与确认记录

2026-10-28仅形成方案与路线图待确认状态。已核对62操作与页面映射、源码字段/认证/时间/幂等/文件约束、当时Node/npm可见情况及本地文档链接；当次没有安装依赖、创建UI、启动应用、修改生产源码或执行浏览器业务验收。前端依赖可安装性、构建、代理和会话体验留到实施验证。

2026-10-29用户确认：Vue 3/Vite/TypeScript/Vue Router/Element Plus的独立前端；上述13模块与61业务操作映射；标签页会话/同源代理；A→B→C→D逐批实施，第一批仅工程/认证/浏览。依此记录ADR并开始M05.2批次A，不一次性扩展整个项目。
