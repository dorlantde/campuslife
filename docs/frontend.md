# 最小前端：本地运行与分批验收

2026-10-29，按[ADR-0049](decisions/0049-minimal-frontend.md)与[确认方案](minimal-frontend-design.md)交付A～D页面。认证/公开浏览、入驻经营、打印整链、预约可操作阶段及管理员管理已验收；C窄屏已由D用Chrome实际390px补齐。新预约到店开始/完成/新评价尚待真实时间人工验证。点击见[商家管理](frontend-merchant.md)、[打印](frontend-print.md)、[预约与管理员](frontend-booking-admin.md)；下方保留A认证/浏览使用方法。[D报告](reports/frontend-batch-d-2026-10-29.md)区分本轮与历史证据，营销未实现。

## 使用你现有的后端

1. 在VS Code按原来的CampusLife MySQL配置启动后端。数据库和JWT配置不变，不需要把密码写到前端。
2. 在仓库根目录打开终端，首次按下面顺序准备项目工具与依赖。当前设备已准备好，日常只需运行Run。
3. 浏览器打开 **http://127.0.0.1:5173**。先浏览商家，或点击右上角“登录 / 注册”。登录后无需手动复制accessToken。

```powershell
# 首次准备；下载官方固定版Node，只写项目.tools/.cache
.\scripts\setup-frontend.ps1
.\scripts\frontend.ps1 -Action Install

# 日常启动；保持此终端运行，Ctrl+C停止
.\scripts\frontend.ps1 -Action Run
```

前端仅监听127.0.0.1:5173，端口占用会明确失败；请求`/api/v1`代理到127.0.0.1:8080。只启动前端而后端未启动时，页面会显示请求失败及重试，不会填充假商家。不要将前端代理目标改成公网地址；现有脚本只接受HTTP回环源。无需开启后端跨域或更改VS Code运行配置。

2026-10-29各批隔离验收均已结束并清理：A用户亲自改密，B经营配置，C打印整链/网络恢复，D预约与管理员分支。临时账号不会出现在原项目库。现在可以Run连接原项目后端；今后另开预览时，须先结束预览再Run，避免端口冲突或误把临时库当成原项目库。

独立前端构建和测试：

```powershell
.\scripts\frontend.ps1 -Action Test
.\scripts\frontend.ps1 -Action Build
```

Node 24.22.2/npm 11.19.1位于`.tools/node/node-v24.22.2-win-x64`，不使用Codex内部工具、不全局安装。Node ZIP的SHA-256为`ca1bff5d8ead13c9f2ea404a176db46bd430704d5b5113734049fa365e7799e2`，来源是[官方校验表](https://nodejs.org/download/release/v24.22.2/SHASUMS256.txt)。`setup-frontend.ps1`固定校验后解压；依赖由`frontend/package-lock.json`锁定，Install执行npm ci。

实际版本：Vue 3.5.44、Vue Router 4.6.4、Element Plus 2.14.7；Vite 8.3.5、plugin-vue 6.0.10、TypeScript 5.9.3、vue-tsc 3.3.13、Vitest 4.1.12。依赖安装时`@types/estree@1.0.10`的官方tarball返回404，固定兼容的1.0.8 override后完成安装与类型检查；这是构建类型依赖修复，不是业务协议变更。未来升级应重新验证，不用删除锁文件来“修复”安装。

Build输出`frontend/dist`，普通Maven打包仍只产出后端jar；完整部署在M06定稿。静态Preview不包含开发代理，不能据Preview页面加载成功判断API联调成功。页面懒加载、按需注册Element Plus控件，使用本地CSS/字体回退，没有外链字体、图片或运行时CDN依赖。

## 点击检查

| 流程 | 操作与预期 |
|---|---|
| 匿名浏览 | 无需登录打开目录；搜索名称、切换分类、翻页、进入详情；查看服务、上海营业时间与分页评价。搜索返回空时显示空态，重试不会生成模拟记录 |
| 注册与登录 | 注册只创建STUDENT，不自动登录；成功回到登录页提示。使用已有商家/管理员也通过相同登录页，界面显示服务端角色；管理员不提供公开注册入口 |
| 刷新与退出 | 登录后打开本人资料，刷新后会先GET /me复核。退出返回登录；若请求失败，明确只清除了本地会话，服务端可能尚未撤销 |
| 昵称 | 在本人资料修改并保存，标题昵称同步变化。用两独立标签页登录同一学生，先读取资料，再一边保存、另一边提交旧草稿；第二边应提示版本冲突并显示最新当前昵称，草稿保留，不自动覆盖 |
| 密码 | 由你在本机填写当前/新密码，两次新密码一致后提交；成功回登录，其他旧会话随后拒绝。不要将密码、token或含它们的请求截图发到聊天/仓库 |
| 角色边界 | 学生直达`/admin`或`/merchant`显示无权访问；匿名直达`/me`回登录；商家/管理员可进入店铺管理/入驻审核。操作权限最终仍由API校验 |
| 标签页账号 | 手动新开独立标签页分别登录不同角色，各页昵称/角色独立。复制标签页可能复制初始会话，操作前核对账号；单页切换账号先退出 |
| 服务失败 | 后端不可达时公开页给出失败/重试；刷新受保护页面时验证失败进入会话验证页，不能先展示未经复核的旧资料。后端恢复后点重试 |

C已开放打印流程，D把“我的订单”和“订单任务”扩展为两类及类型筛选，并加入预约/管理员入口，见[打印指南](frontend-print.md)、[预约与管理指南](frontend-booking-admin.md)。至少提前2小时和真实开始窗口保持，页面不会绕过。营业中不等于可下单；详情PRINT价格仅参考起价。

## 可重复的隔离浏览器环境

以下可选环境供开发验收，启动原项目后端不是其前提。它使用随机端口的自有原生MySQL、Spring Boot真实后端、独立文件根和5173前端；不访问你原来的MySQL服务数据。需要本机MySQL客户端/服务端、Java21/Maven，以及已安装前端依赖；不要同时占用5173。Redis在该夹具中明确关闭，缓存联测仍使用M02专用流程。

```powershell
.\scripts\preview-frontend.ps1 -Action Start
.\scripts\preview-frontend.ps1 -Action Status
# 浏览器验收后务必停止；会先结束夹具测试，再检查jar并清理自有数据库目录
.\scripts\preview-frontend.ps1 -Action Stop
.\scripts\preview-frontend.ps1 -Action Status
```

账号`ui_student_m052a`、`ui_merchant_m052a`、`ui_admin_m052a`，密码均为`Ui_Test_2026!`，**仅存在于本次新建、回环可见、一次性隔离数据库**。不得用于正常项目库或部署。注册新账号同样只进入该临时库。预览不是生产初始化工具，不改变普通启动/ADMIN初始化规则。

Start会阻止覆盖尚未结束的预览记录；失败时保留日志供诊断，按Stop触发后台正常退出。已有ready记录的失败轮次，只有确认runner/Vite均已退出、datadir路径有效且无所属MySQL进程后才能Start新轮；旧诊断目录不会因此自动删除。夹具最长90分钟；超时为验收失败并保留诊断目录，不能无限运行。Stop不删除用户持久文件；已有`test-mysql.ps1`只在检查成功后验证绝对路径并清理本次生成的`.cache/mysql-it/<guid>`。状态完成以`completed.json`为准，不以浏览器关闭为准。日志采用操作系统级stdout/stderr独立捕获，避免PowerShell 5.1把Java警告当作终止错误。失败时不要手动清空整个.cache或停止系统MySQL服务。

证据保存于`.cache/frontend-it/<guid>`；测试账号、浏览器存储与临时库均不进入Git。正式验收摘要见[批次A](reports/frontend-batch-a-2026-10-29.md)、[批次B](reports/frontend-batch-b-2026-10-29.md)、[批次C](reports/frontend-batch-c-2026-10-29.md)和[批次D报告](reports/frontend-batch-d-2026-10-29.md)，明确区分浏览器实际执行、自动客户端测试和未执行项。C隔离夹具另有已批准打印店与另一学生，模拟资金仅在显式测试环境开启，故障复现开关见C指南；普通运行默认不注入网络故障。
