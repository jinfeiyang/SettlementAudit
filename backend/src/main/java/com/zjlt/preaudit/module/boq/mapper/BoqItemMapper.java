package com.zjlt.preaudit.module.boq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjlt.preaudit.module.boq.entity.BoqItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface BoqItemMapper extends BaseMapper<BoqItem> {

    /**
     * 结算口径合计：甲供物资（材料/设备）由建设单位提供、决算送审不含，需排除；
     * 但按表七固定单价法重算过的光缆行是施工费，仍计入。
     */
    @Select("SELECT COALESCE(SUM(total_price), 0) FROM boq_item "
            + "WHERE project_id = #{projectId} AND deleted = 0 "
            + "AND (item_type NOT IN ('SUPPLY_MATERIAL', 'SUPPLY_EQUIPMENT') "
            + "     OR feature_desc LIKE '%固定单价法施工费%')")
    BigDecimal sumTotalPrice(@Param("projectId") Long projectId);
}
