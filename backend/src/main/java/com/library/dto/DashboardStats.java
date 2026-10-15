package com.library.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DashboardStats extends StatisticsSummary {

    private Long totalBooks;
    private Long totalUsers;
    private Long totalBorrows;
    private Long availableBooks;
}
