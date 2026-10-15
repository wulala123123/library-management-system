package com.library.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("book_copy")
public class BookCopy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bookId;
    private String barcode;
    private String status;
    private String location;
}
