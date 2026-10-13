package com.library.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @org.apache.ibatis.annotations.Select("SELECT * FROM sys_user WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    User lockById(@org.apache.ibatis.annotations.Param("id") Long id);


    IPage<User> selectPageWithKeyword(Page<User> page,
                                      @Param("keyword") String keyword,
                                      @Param("role") String role,
                                      @Param("status") Integer status);
}
