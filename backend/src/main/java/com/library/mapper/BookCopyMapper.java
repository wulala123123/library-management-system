package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.entity.BookCopy;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BookCopyMapper extends BaseMapper<BookCopy> {
    @Select("SELECT * FROM book_copy WHERE id = #{id} FOR UPDATE")
    BookCopy lockById(@Param("id") Long id);

    @Select("SELECT * FROM book_copy WHERE book_id = #{bookId} AND status = 'AVAILABLE' ORDER BY id LIMIT 1 FOR UPDATE")
    BookCopy lockAvailable(@Param("bookId") Long bookId);
}
