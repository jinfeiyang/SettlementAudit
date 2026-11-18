package com.zjlt.preaudit.module.rule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjlt.preaudit.module.rule.entity.RuleFee;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RuleFeeMapper extends BaseMapper<RuleFee> {

    /** 含逻辑删除记录的 ID，供导入判重并恢复 */
    @Select("SELECT id FROM rule_fee WHERE fee_code = #{code} AND COALESCE(eng_category,'') = #{cat} AND version = #{version} LIMIT 1")
    Long findIdIncludingDeleted(@Param("code") String code, @Param("cat") String cat, @Param("version") String version);

    /** @TableLogic 下恢复已删除记录需绕过 updateById 的 deleted=0 条件 */
    @Update("UPDATE rule_fee SET deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") Long id);
}
