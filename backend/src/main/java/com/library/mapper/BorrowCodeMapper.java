package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.entity.BorrowCode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BorrowCodeMapper extends BaseMapper<BorrowCode> {

    @org.apache.ibatis.annotations.Select("SELECT * FROM borrow_code WHERE id = #{id} FOR UPDATE")
    BorrowCode lockById(@org.apache.ibatis.annotations.Param("id") Long id);

}
