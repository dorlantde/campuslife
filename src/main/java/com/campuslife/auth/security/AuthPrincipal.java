package com.campuslife.auth.security;

import com.campuslife.auth.vo.AccountView;

public record AuthPrincipal(long accountId, String jti, AccountView account) {
}
