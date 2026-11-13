package com.campuslife.admin.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;

public interface AccountStatusChangeMapper {
    @Insert("INSERT INTO account_status_change(actor_account_id,target_account_id,from_status,to_status,from_version,to_version,changed_at)"
            +" VALUES (#{actor},#{target},#{from},#{to},#{version},#{nextVersion},#{at})")
    int append(long actor,long target,String from,String to,long version,long nextVersion,LocalDateTime at);
}
