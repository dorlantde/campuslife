package com.campuslife.auth.mapper.model;

import java.time.LocalDateTime;

public record AuthSessionState(long accountId, String jti, LocalDateTime expiresAt, LocalDateTime revokedAt) {
}
