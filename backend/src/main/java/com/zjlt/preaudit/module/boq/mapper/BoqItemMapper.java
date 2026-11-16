package com.zjlt.preaudit.module.boq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjlt.preaudit.module.boq.entity.BoqItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface BoqItemMapper extends BaseMapper<BoqItem> {

    @Select("SELECT COALESCE(SUM(total_price), 0) FROM boq_item WHERE project_id = #{projectId} AND deleted = 0")
    BigDecimal sumTotalPrice(@Param("projectId") Long projectId);
}
