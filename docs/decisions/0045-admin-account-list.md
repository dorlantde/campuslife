# ADR-0045：管理员账号列表与只读权限快照

- 日期：2026-10-26。
- 状态：用户明确“采用并实现”。精确范围见[确认方案](../admin-account-list-design.md)，人工流程及实际验证见[指南](../admin-account-list.md)。

新增GET /api/v1/admin/accounts，仅当前ACTIVE/ADMIN及有效会话，查询平台account业务账号；包含本人、其他ADMIN及DISABLED，不按店铺/订单存在性或经营资格过滤。无筛选默认全部，role精确STUDENT/MERCHANT/ADMIN、status精确ACTIVE/DISABLED，两者交集；显式空白/空串/错误大小写或未知值400。

page默认1、最大INT，size默认20、1～100，只接受十进制数字，分页前导零允许；仅省略取默认值，空串/符号/小数/指数/溢出400。created_at DESC、id DESC，offset用long；没有命中200、total/totalPages=0，越界items空但保留真实数量。返回AdminAccountPage五字段，items复用AccountView的id/username/nickname/role/status/version，ID和版本字符串；不添加联系方式、会话、创建时间或关联业务明细。

查询Mapper明确投影六列，不加载含password_hash的AccountEntity；身份复核复用已有不带密码摘要的会话联表查询。一次count与一次page，不逐个账号补查会话/业务，不增加索引、分页插件、缓存或其他基础设施，不宣称未经测量的性能提升。

只读RR事务内复核当前账号角色/状态与会话撤销/期限，再同快照count/page，返回前再查Clock判断期限。普通读不取业务行锁或修改账号/会话；身份、资格、total与items单响应一致。快照后注册/昵称/角色/状态或撤销变化下一请求可见，在途旧快照可完成；期限仍在返回前校验。不保证跨请求/跨页持续同快照，不承诺中断已建立快照的在途读取。选择短RR是为了保证权限和列表响应一致，避免为只读分页锁住所有账号。

401/403沿原鉴权；400参数错误，数据库暂时故障503，其他内部500，不伪造空页，无新404/409。沿admin模块新增Controller/服务/Mapper/分页VO，复用AccountView，运行60→61、完整62/61/1，V1～V14/22表不变，无新迁移。

本轮不含停用/恢复、修改角色、创建/删除账号、重置密码、账号详情/搜索/导出/会话管理。ADMIN仍只受控初始化；后续写入口、自操作和最后管理员保护独立定稿。本次验证只使用独立临时MySQL与测试文件根，实际执行记录以指南为准。
