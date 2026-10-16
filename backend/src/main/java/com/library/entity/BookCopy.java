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
    private Long actionVersion;
    private Long lastStateActionId;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    private Long registeredLocationId;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    private Long lastLocationActionId;
    @TableField(exist=false) @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    private com.library.dto.LocationContracts.Placement placement;
    @TableField(exist=false) private boolean locationReferenceAnomaly;
    @TableField(exist=false) private boolean unassignedLocation;
}
