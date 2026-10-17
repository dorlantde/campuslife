# ADR-0006：修改密码与全部会话撤销

- 日期：2026-10-15（Asia/Shanghai）
- 状态：用户授权推进此前推荐的改密任务；实现与独立 MySQL 验证通过
- 范围：POST /api/v1/me/password，复用 account / auth_session，不修改 Flyway V1

已登录用户提供 currentPassword、newPassword，身份只来自认证上下文。密码规则复用已确认的注册策略，不添加密码历史、必须不同或强制大小写组合规则。当前密码错误返回 400 / CURRENT_PASSWORD_INCORRECT，表示输入校验失败，原有效会话保持可用。成功返回 200 / OK、data=null，更新 BCrypt 摘要、account.version + 1，并撤销本人全部会话，包括当前会话；用户需要重新登录。

选择短事务配合账号行锁：BCrypt 匹配与新摘要计算在事务外完成，通过独立 bean 的 @Transactional 方法进入写事务；先锁 account，再锁当前会话，重新检查账号状态、会话撤销与到期、已验证摘要是否仍有效。摘要已变返回 409 / ACCOUNT_CREDENTIALS_CHANGED，避免覆盖其他凭据操作。已有撤销时间不覆盖，其他账号不受影响。任一写入异常与摘要、版本、会话一起回滚。

账号锁与登录创建会话的首个锁一致，建立两者之间的先后关系：先完成的登录被改密统一撤销；后提交的旧密码登录不能创建新会话。同账号改密串行，不为此引入 Redis、分布式锁或新表。普通在途请求仍遵循既有认证边界，不能把全部撤销描述为取消所有已开始的请求。

代价是相同账号的登录提交与改密提交需要等待同一行锁。V1 选择简单且可解释的正确性协议，未进行性能压测。以后加入重置密码、账号停用写接口时，要沿用锁顺序与事务内复核，不能仅修改摘要或只在请求入口查状态。

Java 21、真实 MySQL 8.0.45 上的全套 120 个用例通过；AuthenticationMysqlIT 41 个。覆盖双端撤销、账号隔离、输入边界、并发改密、过期/撤销后提交拒绝、旧摘要登录拒绝及真实 CHECK 异常后的整笔回滚。mysql 模式 jar 启动检查通过。复现步骤与验证边界见 [改密验收](../password-change.md)。

参考：[MySQL 锁定读](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)、[Spring 事务回滚](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)。
