package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.entity.BookCopy;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BookCopyMapper extends BaseMapper<BookCopy> {
    @Select("SELECT * FROM book_copy WHERE id = #{id} FOR UPDATE")
    BookCopy lockById(@Param("id") Long id);

    @Select("SELECT c.* FROM book_copy c WHERE c.book_id = #{bookId} AND CAST(c.status AS BINARY(256))=CAST('AVAILABLE' AS BINARY(256)) AND NOT EXISTS(SELECT 1 FROM borrow_record r WHERE r.copy_id=c.id AND r.deleted=0 AND r.status IN ('BORROWING','OVERDUE')) ORDER BY c.id LIMIT 1 FOR UPDATE")
    BookCopy lockAvailable(@Param("bookId") Long bookId);

    @Select("SELECT COUNT(*) FROM borrow_record WHERE copy_id=#{copyId} AND deleted=0 AND status IN ('BORROWING','OVERDUE')")
    long activeLoans(@Param("copyId")Long copyId);

    @Update("UPDATE book_copy SET status=#{next},action_version=action_version+1 WHERE id=#{id} AND action_version=#{version} AND action_version<9223372036854775807 AND CAST(status AS BINARY(256))=CAST(#{before} AS BINARY(256))")
    int changeCirculationState(@Param("id")Long id,@Param("version")Long version,@Param("before")String before,@Param("next")String next);
}
