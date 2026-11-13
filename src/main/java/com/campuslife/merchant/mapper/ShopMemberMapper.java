package com.campuslife.merchant.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ShopMemberMapper {
    @Insert("INSERT INTO shop_member (shop_id, account_id, member_role, status) VALUES (#{shopId}, #{accountId}, 'OWNER', 'PENDING')")
    int createOwner(@Param("shopId") long shopId, @Param("accountId") long accountId);

    @Select("SELECT status FROM shop_member WHERE shop_id = #{shopId} AND member_role = 'OWNER' FOR UPDATE")
    String lockOwnerStatus(@Param("shopId") long shopId);

    @Select("SELECT status FROM shop_member WHERE shop_id = #{shopId} AND account_id = #{accountId} AND member_role = 'OWNER' FOR UPDATE")
    String lockOwnOwnerStatus(@Param("shopId") long shopId, @Param("accountId") long accountId);

    @Update("UPDATE shop_member SET status = 'ACTIVE' WHERE shop_id = #{shopId} AND member_role = 'OWNER' AND status = 'PENDING'")
    int activateOwner(@Param("shopId") long shopId);
}
