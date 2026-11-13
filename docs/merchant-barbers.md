# 理发师管理与绑定：Swagger / Apifox 验收

已确认规则见 [ADR-0017](decisions/0017-merchant-barbers.md)。四个接口位于“06 商家经营配置”；V7 新建 barber、barber_service。理发师是人员资料，不是登录账号。按日排班已在后续按 ADR-0018 实现，见[排班指南](merchant-schedule.md)；匿名人员查询已按 ADR-0020 实现，见[公开验收](public-barbers.md)；预约尚未实现。

## 准备

1. 用原 VS Code 配置重启项目，Flyway 自动应用尚未执行的 V7；不改写旧迁移、不重新建库。
2. 打开 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)，用已批准且店主有效的 HAIRCUT 商家登录，将实际 data.accessToken 填入 Authorize。需要账号时按 [入驻](merchant-onboarding.md)和[审核](admin-shop-review.md)完成准备。
3. 按 [服务指南](merchant-service-catalog.md) 创建或查询本店 HAIRCUT 服务，记录实际服务 ID。下架服务也允许绑定；店铺停业、未配置营业时间不妨碍本轮验收。

以下示例中的 "201"、"202" 必须替换为本店实际服务 ID。理发师版本与服务、店铺版本独立；姓名/启停、绑定和所有日期排班共用同一个人员版本。

## 新增与查询

先执行 GET /api/v1/merchant/barbers，没有人员时 data=[]。再执行 POST 同一路径：

```json
{
  "displayName": "模拟理发师小林",
  "serviceIds": ["201", "202"]
}
```

预期 201 / OK，返回新人员 id、enabled=false、version="0"，serviceIds 为数值 ID 升序。也可显式传 serviceIds=[]，先建人员后再补服务。POST 不接受 enabled；允许重名，重复请求会创建不同 ID，勿把网络重发当作自动去重。

再次 GET，返回本店所有启用/停用人员，按创建时间、ID 倒序，不分页。每项包含 id、displayName、enabled、serviceIds、version；已绑定的下架服务仍在 serviceIds 中。

## 修改姓名并启用

在 PATCH /api/v1/merchant/barbers/{barberId} 填刚才返回的人员 ID，提交：

```json
{
  "displayName": "模拟理发师林老师",
  "enabled": true,
  "expectedVersion": "0"
}
```

预期 200，版本变为 "1"，服务绑定保持不变。只传需要修改的字段，省略保留，显式 null 拒绝。enabled 仅接受 true / false，不接受字符串或数字。也允许启用空服务人员，这仅表示准备状态，不表示已有可预约时间。

## 完整替换绑定

PUT /api/v1/merchant/barbers/{barberId}/services，使用该人员的最新版本：

```json
{
  "serviceIds": ["201"],
  "expectedVersion": "1"
}
```

预期版本变为 "2"，仅保留 201，原来的 202 被解绑；姓名与 enabled 不变。这是完整替换，不能只提交想追加的一项。与打印价目“遗漏保留”的接口不同。

保持旧 "1" 再提交，应为 409 / CONFIG_VERSION_CONFLICT。冲突时重新查询、核对后决定修改，不自动换版本覆盖。用当前 "2" 提交相同集合应成功且仍为 "2"；数组只改变顺序也不加版本，重复 ID 仍返回 400。

使用当前版本传 serviceIds=[] 清空绑定，实际变化时版本加一。停用则使用 PATCH，传 enabled=false 和当前理发师版本；不会自动清空绑定。没有删除接口。

## 边界与回滚

| 场景 | 预期 |
|---|---|
| 姓名去首尾空白后 1～40 Unicode 码点 | 接受；允许重名，拒绝空姓名、超长及非法 Unicode |
| serviceIds 必传，0～50 项，字符串 ID，无重复 | 接受；数字 ID、null、重复、前导零、超限数组为 400 |
| 服务不存在或属于其他店铺 | 404 / RESOURCE_NOT_FOUND，整个新增/绑定失败 |
| 本店非 HAIRCUT 服务、PRINT 分类商家 | 400 / VALIDATION_FAILED |
| 非商家、账号/成员无效或店铺未批准 | 沿用已有 403 错误 |
| 通过经营资格与分类检查后，理发师不存在或属于他店 | 404 / RESOURCE_NOT_FOUND |
| 旧版本，或版本上限时实际修改 | 409 / CONFIG_VERSION_CONFLICT |
| 没有实际变化、但权限和当前版本正确 | 成功，版本和更新时间不变 |
| PATCH 传 serviceIds，或任何请求传 shopId/role/version 等未知字段 | 400，不允许修改归属 |

可在 serviceIds 中混入一个不存在的合法字符串 ID，预期 404；再 GET 确认原人员资料、版本和绑定完全不变。新增时任何绑定失败也不能留下一个空人员。不要用真实商家或他人账号测试。

修改姓名/启停和替换绑定共用人员版本：两个页面从同一个旧版本分别保存时，只能有一个成功，另一个应冲突；不会影响另一个理发师的版本。

服务下架不自动解绑，理发师停用不自动清空排班。目前没有预约表；本指南不验收预约存量保护，预约接入前需另行实现相关保护。

Apifox 重新导入 [运行快照](openapi/campuslife.openapi.json)，或同步本机 /v3/api-docs。当前含排班的运行版为 32 个操作，完整规划仍为 62 个（32 已实现、30 待开发）。

## 验证记录

2026-10-21，Java 21 + 独立 MySQL 8.0：首次全回归执行普通 42 + 集成 563，共 605 个用例；新增 BarberManagementMysqlIT 共 108 个，其中一项锁等待测试的辅助事务出现死锁，其余通过。读取临时实例的 InnoDB 死锁记录，确认辅助事务先锁 barber、再修改索引字段 enabled，外键检查反向请求 shop 锁，而应用已按 shop → barber 持锁等待，形成环路。

修正测试辅助事务：在单独持有人员行锁时只更新非索引姓名/版本及绑定，由应用事务按正式顺序更新启停；保留实际等待、最新姓名/版本/绑定及启停结果断言。没有为通过测试改动业务规则或应用锁顺序。随后定向复测全部 108 个理发师集成用例及普通 42 个用例，Maven verify 成功；其他已通过集成用例未重复执行。最新各类报告合计 605、0 failures / errors / skipped。

验证覆盖 Unicode/姓名边界、默认停用、50 项与空集合、重复/非法 ID、下架服务绑定、分类字典停用边界、完整替换与清空、未变关系身份保留、账号/角色/店铺/成员/跨店权限、共享人员版本、无变化及版本上限、姓名与绑定并发、不同人员版本独立、实际 SQL 后整笔回滚、写入后及锁等待后的会话过期、当前读与列表快照一致性、批量关系查询及数据库唯一键/外键/CHECK。

V1～V7 在独立临时库迁移成功；最终 mysql 模式 JAR 启动、健康、UTF-8、trace 与访问边界检查通过。OpenApiMysqlIT 实际导出 26 个操作；完整规划 62 个（26 已实现、36 待开发）、9 分组、76 个 schemas。生成器一致性、官方 OpenAPI 3.0 schema、11 个请求示例及全部模型/参数引用校验通过。

验证只用合成账号与独立临时库，不读取真实密码、不访问已有业务数据；临时实例和目录已清理，原有 MySQL80 保持运行。未代用户在现有业务库执行 V7 或操作 Swagger / Apifox，也没有排班/预约存量保护的验收。日志为 .cache/barbers-mysql.log、.cache/barbers-final.log；报告在 target/surefire-reports、target/failsafe-reports。
