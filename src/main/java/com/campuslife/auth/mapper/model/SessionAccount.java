package com.campuslife.auth.mapper.model;

import java.time.LocalDateTime;

public record SessionAccount(long accountId, String username, String nickname, String role, String status,
                             long version, LocalDateTime expiresAt, LocalDateTime revokedAt) {
}
