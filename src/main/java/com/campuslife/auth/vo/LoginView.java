package com.campuslife.auth.vo;

import java.time.Instant;

public record LoginView(String accessToken, String tokenType, Instant expiresAt, AccountView account) {
    @Override
    public String toString() { return "LoginView[accessToken=REDACTED]"; }
}
