package com.library.service.impl;

import com.library.dto.DashboardStats;
import com.library.dto.StatisticsSummary;
import com.library.service.DashboardService;
import com.library.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final StatisticsService statisticsService;

    @Override
    public DashboardStats getStats() {
        DashboardStats stats = new DashboardStats();
        StatisticsSummary summary = statisticsService.getSummary();
        BeanUtils.copyProperties(summary, stats);
        // Retain the old dashboard aliases while clients move to explicit names.
        stats.setTotalBooks(summary.getCatalogTitles());
        stats.setTotalUsers(summary.getEnabledReaderAccounts());
        stats.setTotalBorrows(summary.getBorrowedCopies());
        stats.setAvailableBooks(summary.getAvailableTitles());
        return stats;
    }
}
