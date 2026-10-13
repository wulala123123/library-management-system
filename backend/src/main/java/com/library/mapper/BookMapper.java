package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.entity.Book;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BookMapper extends BaseMapper<Book> {

    @org.apache.ibatis.annotations.Select("SELECT * FROM book WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Book lockById(@org.apache.ibatis.annotations.Param("id") Long id);


    IPage<Book> selectPageWithCategory(Page<Book> page,
                                       @Param("keyword") String keyword,
                                       @Param("categoryId") Long categoryId,
                                       @Param("status") Integer status);
    @org.apache.ibatis.annotations.Select("SELECT id FROM inventory_mutex WHERE id = 1 FOR UPDATE")
    Long lockCatalog();

    @org.apache.ibatis.annotations.Select("SELECT * FROM book WHERE isbn = #{isbn} FOR UPDATE")
    Book lockByIsbn(@org.apache.ibatis.annotations.Param("isbn") String isbn);
}
