package com.zjlt.preaudit.module.rule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjlt.preaudit.module.rule.entity.RuleQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RuleQuotaMapper extends BaseMapper<RuleQuota> {

    /** 查包含逻辑删除记录的 ID（唯一索引不含 deleted，导入需据此恢复而非重复插入） */
    @Select("SELECT id FROM rule_quota WHERE quota_code = #{code} AND version = #{version} LIMIT 1")
    Long findIdIncludingDeleted(@Param("code") String code, @Param("version") String version);

    /** @TableLogic 会让 updateById 自动带 deleted=0 条件，恢复已删除记录需绕过该条件 */
    @Update("UPDATE rule_quota SET deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") Long id);
}
