package com.campuslife.admin.mapper;

import com.campuslife.auth.vo.AccountView;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminAccountMapper {
    String FILTER=" WHERE (#{role} IS NULL OR role=#{role}) AND (#{status} IS NULL OR status=#{status})";
    @Select("SELECT COUNT(*) FROM account"+FILTER)
    long count(String role,String status);
    @Select("SELECT CAST(id AS CHAR) AS id,username,nickname,role,status,CAST(version AS CHAR) AS version FROM account"+FILTER
            +" ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<AccountView> page(String role,String status,int size,long offset);
    @Select("SELECT CAST(id AS CHAR) AS id,username,nickname,role,status,CAST(version AS CHAR) AS version FROM account WHERE id=#{id} FOR UPDATE")
    AccountView lockView(long id);
    @Update("UPDATE account SET status=#{to},version=version+1 WHERE id=#{id} AND status=#{from} AND version=#{version}")
    int updateStatus(long id,String from,String to,long version);
}
