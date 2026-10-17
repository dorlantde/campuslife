package com.campuslife.auth.mapper;

import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.mapper.model.SessionAccount;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AuthSessionMapper {
    @Select("""
            SELECT account_id, jti, expires_at, revoked_at FROM auth_session
            WHERE account_id = #{id} AND jti = #{jti} FOR UPDATE
            """)
    AuthSessionState lockOwnSession(@Param("id") long id, @Param("jti") String jti);

    @Update("""
            UPDATE auth_session SET revoked_at = GREATEST(created_at, #{now})
            WHERE account_id = #{id} AND revoked_at IS NULL
            """)
    int revokeAll(@Param("id") long id, @Param("now") LocalDateTime now);

    @Insert("INSERT INTO auth_session (account_id, jti, created_at, expires_at) VALUES (#{id}, #{jti}, #{issuedAt}, #{expiresAt})")
    int create(@Param("id") long id, @Param("jti") String jti,
               @Param("issuedAt") LocalDateTime issuedAt, @Param("expiresAt") LocalDateTime expiresAt);

    @Select("""
            SELECT a.id AS accountId, a.username, a.nickname, a.role, a.status, a.version, s.expires_at, s.revoked_at
            FROM auth_session s JOIN account a ON a.id = s.account_id
            WHERE s.account_id = #{id} AND s.jti = #{jti}
            """)
    SessionAccount find(@Param("id") long id, @Param("jti") String jti);

    @Update("""
            UPDATE auth_session SET revoked_at = COALESCE(revoked_at, GREATEST(created_at, #{now}))
            WHERE account_id = #{id} AND jti = #{jti}
            """)
    int revoke(@Param("id") long id, @Param("jti") String jti, @Param("now") LocalDateTime now);
}
