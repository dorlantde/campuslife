package com.campuslife.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.auth.entity.AccountEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AccountMapper extends BaseMapper<AccountEntity> {
    @Select("SELECT id, username, password_hash, nickname, role, status, version FROM account WHERE id = #{id} FOR UPDATE")
    AccountEntity lockById(@Param("id") long id);

    @Update("UPDATE account SET password_hash = #{hash}, version = version + 1 WHERE id = #{id}")
    int updatePassword(@Param("id") long id, @Param("hash") String hash);

    @Update("""
            UPDATE account SET nickname = #{nickname}, version = version + 1
            WHERE id = #{id} AND version = #{version} AND status = 'ACTIVE'
            """)
    int updateNickname(@Param("id") long id, @Param("nickname") String nickname, @Param("version") long version);
}
