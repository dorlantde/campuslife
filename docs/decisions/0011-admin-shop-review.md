# ADR-0011：受控管理员初始化与商家审核实现

- 日期：2026-10-19（Asia/Shanghai）。
- 状态：沿用已确认的 ADR-0010；用户授权“进行下一项”，本次落实管理员与审核闭环。
- 范围：本机创建 ADMIN 业务账号，管理员店铺列表/详情/批准/拒绝；不包含被拒后重提及其他后台管理能力。

## 实现取舍

管理员通过 scripts/init-admin.ps1 交互填写业务用户名、昵称、密码及确认密码；MySQL 专用账号密码沿用 database.ps1 的本机安全提示。凭据只进入子进程环境，不写文件、命令参数或日志。重复用户名拒绝创建，不覆盖摘要或改变角色。

AdminBootstrap 使用单独的 Spring 配置与非 Web 上下文，仅导入数据库迁移、账号 Mapper、密码编码器和初始化服务；不扫描普通 HTTP Controller，不需要 JWT 密钥，不启动 HTTP，完成即退出。普通 CampusLife 启动不激活 admin-bootstrap 配置，也不会自动创建账号。POM 固定默认主类为 CampusLifeApplication，AdminInit 才显式选择命令行主类，无新增依赖。

HTTP 管理入口要求 ADMIN。会话过滤器每次读取数据库中的当前角色，权限规则不信任客户端提交的角色。只审核 PENDING，版本不匹配或达到 BIGINT 上限返回 CONFIG_VERSION_CONFLICT，状态不符返回 SHOP_STATE_CONFLICT。

审核锁顺序：管理员账号 → 当前会话 → 店铺 → OWNER 成员。持锁后复核账号 ACTIVE、角色 ADMIN、会话未撤销且未过期；可能等待店铺锁，因此写入前再次检查有效期。成功时店铺版本 + 1；批准激活 OWNER、保持停业；拒绝保留 PENDING OWNER，保存规范化原因。

店铺更新、成员激活和审核记录追加在同一事务。V3 建立 shop_review，以 (shop_id, reviewed_version) 唯一约束防止一版多次审核，记录审核前版本、结果、原因、操作者、UTC 毫秒时间。任何失败整体回滚。旧请求重试返回冲突，不另做幂等成功回放。

列表默认 created_at DESC、id DESC，page=1、size=20，size 最多 100；固定 SQL 排序与参数绑定，状态筛选只能使用既定枚举。读取详情只返回基础店铺、分类、审核和成员状态，不返回账号摘要及会话字段。

## 验证与边界

独立真实 MySQL 验证批准/拒绝、并发裁决、审计外键失败回滚、旧版本、版本上限、角色/会话、Unicode 原因、分页和审核记录唯一性。独立 JVM 验证管理员初始化无 HTTP/无 JWT、重复拒绝且不改密码。实际结果见 [管理员验收](../admin-shop-review.md)，不在用户现有库上代为创建管理员。
