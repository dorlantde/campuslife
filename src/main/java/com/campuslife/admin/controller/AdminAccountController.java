package com.campuslife.admin.controller;

import com.campuslife.admin.service.AdminAccountService;
import com.campuslife.admin.service.AdminAccountStatusService;
import com.campuslife.admin.dto.AccountStatusWrite;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.admin.vo.AdminAccountPage;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("mysql")
@Tag(name="08 管理员")
@SecurityRequirement(name="bearerAuth")
public class AdminAccountController {
    private final AdminAccountService accounts;
    private final AdminAccountStatusService statuses;
    public AdminAccountController(AdminAccountService accounts,AdminAccountStatusService statuses){this.accounts=accounts;this.statuses=statuses;}
    @PatchMapping(value="/api/v1/admin/accounts/{accountId}/status",consumes="application/json",produces="application/json")
    @Operation(summary="停用或恢复其他账号",description="仅锁后当前ACTIVE/ADMIN及有效会话，允许其他STUDENT/MERCHANT/ADMIN；禁止修改本人，含同状态请求409 ADMIN_SELF_MODIFICATION_FORBIDDEN。严格status/expectedVersion两字段，状态精确ACTIVE/DISABLED，版本规范非负BIGINT字符串。版本不匹配409 CONFIG_VERSION_CONFLICT；匹配且状态一致200无写入，MAX版本也可，实际改变需版本可递增。双账号按ID升序逐条主键锁，再锁操作者会话并复核；互停先提交者保留有效身份，后者失效拒绝，保留至少一个有效管理员。状态/version+1、目标全部旧会话撤销、一次历史记录原子提交，提交前会话到期全回滚。恢复不复活旧token，须重新登录；旧版本重试409，刷新列表确认。不改角色/密码/店铺/订单/资金/占用/文件期限，旧RR快照和已获准传输沿原边界。")
    public ApiResponse<AccountView> status(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(schema=@Schema(type="string",pattern="[1-9][0-9]{0,18}")) @PathVariable String accountId,
            @RequestBody AccountStatusWrite body){return ApiResponse.success(statuses.change(principal,accountId,body));}
    @GetMapping(value="/api/v1/admin/accounts",produces="application/json")
    @Operation(summary="分页查询平台业务账号",description="仅当前正常ADMIN及有效会话，包含本人、其他管理员与停用账号；不按店铺/订单资格隐藏。可选role/status精确大写交集筛选，空串或未知枚举400；page默认1、最大2147483647，size默认20、最大100，只接受数字，分页前导零允许。createdAt DESC、id DESC，偏移用long，越界空items但保留total。只返回id/username/nickname/role/status/version六字段，ID和版本字符串，SQL不加载密码摘要或会话资料。身份、会话、count/items同一短只读RR快照，返回前核对会话到期；快照后资格/会话变化下次请求可见，不取业务行锁或写入。本接口不含停用/恢复、角色修改或账号创建，管理员仍受控初始化。")
    public ApiResponse<AdminAccountPage> list(@Parameter(hidden=true) @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(schema=@Schema(allowableValues={"STUDENT","MERCHANT","ADMIN"})) @RequestParam(required=false) String role,
            @Parameter(schema=@Schema(allowableValues={"ACTIVE","DISABLED"})) @RequestParam(required=false) String status,
            @Parameter(schema=@Schema(type="integer",minimum="1",maximum="2147483647",defaultValue="1")) @RequestParam(required=false) String page,
            @Parameter(schema=@Schema(type="integer",minimum="1",maximum="100",defaultValue="20")) @RequestParam(required=false) String size){
        return ApiResponse.success(accounts.list(principal,role,status,page,size));
    }
}
