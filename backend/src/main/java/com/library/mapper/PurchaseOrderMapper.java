package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.entity.PurchaseOrder;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PurchaseOrderMapper extends BaseMapper<PurchaseOrder> {

    @org.apache.ibatis.annotations.Select("SELECT * FROM purchase_order WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    PurchaseOrder lockById(@org.apache.ibatis.annotations.Param("id") Long id);

}
