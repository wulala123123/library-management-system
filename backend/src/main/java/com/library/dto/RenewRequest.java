package com.library.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class RenewRequest {

    @Min(value = 1, message = "续借天数至少1天")
    @Max(value = 90, message = "续借天数最多90天")
    private Integer renewDays;
}
