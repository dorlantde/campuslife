package com.campuslife.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.file.entity.FileAssetEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface FileAssetMapper extends BaseMapper<FileAssetEntity> {
    @Update("UPDATE file_asset SET expires_at=#{expiry},updated_at=#{now},version=version+1 WHERE id=#{id} AND version=#{version} AND status='AVAILABLE'")
    int expiry(@Param("id") long id,@Param("version") long version,@Param("expiry") LocalDateTime expiry,@Param("now") LocalDateTime now);
    @Select("SELECT * FROM file_asset WHERE id=#{id} FOR UPDATE")
    FileAssetEntity lock(long id);
    @Update("UPDATE file_asset SET status='AVAILABLE',expires_at=#{expiry},updated_at=#{now},version=version+1 WHERE id=#{id} AND status='UPLOADING' AND version=#{version}")
    int publish(@Param("id") long id,@Param("version") long version,@Param("expiry") LocalDateTime expiry,@Param("now") LocalDateTime now);
    @Update("UPDATE file_asset SET status='DELETING',updated_at=#{now},version=version+1 WHERE id=#{id} AND version=#{version} AND status IN ('UPLOADING','AVAILABLE','DELETING')")
    int deleting(@Param("id") long id,@Param("version") long version,@Param("now") LocalDateTime now);
    @Update("UPDATE file_asset SET status='DELETED',deleted_at=#{now},updated_at=#{now},version=version+1 WHERE id=#{id} AND status='DELETING' AND version=#{version}")
    int deleted(@Param("id") long id,@Param("version") long version,@Param("now") LocalDateTime now);
    @Select("""
            SELECT id,storage_key FROM file_asset WHERE storage_id=#{storage} AND storage_kind=#{kind} AND id>#{after}
            AND (status='DELETING' OR (status IN ('UPLOADING','AVAILABLE') AND expires_at<=#{now}))
            ORDER BY id LIMIT 100
            """)
    List<Candidate> due(@Param("kind") String kind,@Param("storage") String storage,@Param("now") LocalDateTime now,@Param("after") long after);
    @Select("SELECT id,storage_key,version FROM file_asset WHERE storage_id=#{storage} AND storage_kind='S3' AND status='DELETED' AND id>#{after} ORDER BY id LIMIT 100")
    List<Tombstone> tombstones(@Param("storage") String storage,@Param("after") long after);
    record Candidate(long id,String storageKey) {}
    record Tombstone(long id,String storageKey,long version) {}
}
