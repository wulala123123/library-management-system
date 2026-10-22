package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.entity.BorrowRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BorrowRecordMapper extends BaseMapper<BorrowRecord> {

    @org.apache.ibatis.annotations.Select("SELECT * FROM borrow_record WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    BorrowRecord lockById(@org.apache.ibatis.annotations.Param("id") Long id);


    IPage<BorrowRecord> selectPageWithDetail(Page<BorrowRecord> page,
                                             @Param("keyword") String keyword,
                                             @Param("status") String status,
                                             @Param("userId") Long userId);
}
