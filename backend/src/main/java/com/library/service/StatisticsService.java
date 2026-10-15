package com.library.service;

import com.library.dto.InventoryReconciliation;
import com.library.dto.StatisticsSummary;
import com.library.mapper.StatisticsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class StatisticsService {
    private final StatisticsMapper statisticsMapper;
    private static final ZoneId STATISTICS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CATALOG_SCOPE = "仅未删除且启用的书目（book.deleted=0 AND book.status=1）";

    public StatisticsSummary getSummary() {
        LocalDate date = LocalDate.now(STATISTICS_ZONE);
        StatisticsSummary summary = statisticsMapper.selectCatalogSummary(date);
        StatisticsSummary members = statisticsMapper.selectMemberSummary(date);
        summary.setAccountCount(members.getAccountCount());
        summary.setMemberCount(members.getMemberCount());
        summary.setValidMemberCount(members.getValidMemberCount());
        summary.setUnknownValidityMemberCount(members.getUnknownValidityMemberCount());
        summary.setEnabledReaderAccounts(members.getEnabledReaderAccounts());
        summary.setAsOfDate(date);
        summary.setStatisticalScope(scope());
        return summary;
    }

    public InventoryReconciliation getReconciliation() {
        List<InventoryReconciliation.Item> rows = statisticsMapper.selectReconciliation();
        InventoryReconciliation result = new InventoryReconciliation();
        result.setAsOfDate(LocalDate.now(STATISTICS_ZONE));
        result.setStatisticalScope(scope());
        result.setTotalCopies(rows.stream().mapToLong(InventoryReconciliation.Item::getTotalCopies).sum());
        result.setAvailableCopies(rows.stream().mapToLong(InventoryReconciliation.Item::getAvailableCopies).sum());
        result.setBorrowedCopies(rows.stream().mapToLong(InventoryReconciliation.Item::getBorrowedCopies).sum());
        result.setRegisteredCopies(rows.stream().mapToLong(InventoryReconciliation.Item::getRegisteredCopies).sum());
        result.setRegisteredAvailableCopies(rows.stream().mapToLong(InventoryReconciliation.Item::getRegisteredAvailableCopies).sum());
        result.setRegisteredBorrowedCopies(rows.stream().mapToLong(InventoryReconciliation.Item::getRegisteredBorrowedCopies).sum());
        result.setRegisteredOtherCopies(rows.stream().mapToLong(InventoryReconciliation.Item::getRegisteredOtherCopies).sum());
        List<InventoryReconciliation.Item> differences = rows.stream().filter(row -> !row.isConsistent()).toList();
        result.setMismatchedTitles(differences.size());
        result.setItems(differences);
        return result;
    }

    public String exportCsv() {
        StatisticsSummary summary = getSummary();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("catalogTitles", "图书种数");
        labels.put("totalCopies", "实体册总数");
        labels.put("availableCopies", "可借册数");
        labels.put("borrowedCopies", "在借册数");
        labels.put("availableTitles", "可借种数");
        labels.put("lowStockTitles", "低库存种数");
        labels.put("accountCount", "账号总数");
        labels.put("memberCount", "会员人数");
        labels.put("validMemberCount", "有效会员人数");
        labels.put("unknownValidityMemberCount", "有效期待核实会员人数");
        Map<String, Object> values = summary.toMap();
        StringBuilder csv = new StringBuilder("\uFEFFmetric,label,value,unit,asOfDate,scope\r\n");
        labels.forEach((key, label) -> {
            String unit = key.endsWith("Titles") ? "种" : key.endsWith("Copies") ? "册" : "人";
            if ("accountCount".equals(key)) unit = "个";
            csv.append(csvCell(key)).append(',').append(csvCell(label)).append(',')
                    .append(values.get(key)).append(',').append(csvCell(unit)).append(',')
                    .append(summary.getAsOfDate()).append(',')
                    .append(csvCell(summary.getStatisticalScope().get(key))).append("\r\n");
        });
        return csv.toString();
    }

    private static String csvCell(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static Map<String, String> scope() {
        Map<String, String> scope = new LinkedHashMap<>();
        scope.put("asOfDate", "Asia/Shanghai 当天；有效期按日期闭区间统计");
        scope.put("catalogTitles", CATALOG_SCOPE + "的记录数，单位：种");
        scope.put("totalCopies", CATALOG_SCOPE + "的 stock_total 合计，单位：册；不以已注册逐册数替代汇总库存");
        scope.put("availableCopies", CATALOG_SCOPE + "的 stock_available 合计，单位：册");
        scope.put("borrowedCopies", CATALOG_SCOPE + "关联的未删除 BORROWING/OVERDUE 借阅明细数，单位：册");
        scope.put("availableTitles", CATALOG_SCOPE + "中 stock_available>0 的种数");
        scope.put("lowStockTitles", CATALOG_SCOPE + "中 stock_available<=2 的种数（含零库存）");
        scope.put("accountCount", "全部未删除账号，包含所有角色、启用和禁用账号");
        scope.put("memberCount", "全部未删除 READER，包含禁用及已到期会员；不含管理员和图书管理员");
        scope.put("validMemberCount", "未删除且启用的 READER，有 member_profile，服务起止日均已知，且 service_start<=当天<=service_end；仅用于统计，不启用借阅限制");
        scope.put("unknownValidityMemberCount", "全部未删除 READER 中，缺会员档案或任一服务起止日为空的人数，包含禁用账号；空日期不推断为不限");
        scope.put("todayBorrows", CATALOG_SCOPE + "关联的未删除且 borrow_date 为当天的借阅明细数（含已归还），非订单次数");
        scope.put("overdueCount", CATALOG_SCOPE + "关联的未删除 OVERDUE 明细数");
        scope.put("borrowRecordCount", CATALOG_SCOPE + "关联的全部未删除借阅明细数，不与原系统累计计数相加，非订单次数");
        scope.put("reconciliation", CATALOG_SCOPE + "；逐册注册总数为 book_copy 数量，AVAILABLE 为注册可借，BORROWED 为注册在借，其他状态单列；差值为汇总库存/在借明细数减注册册数，stockBalanceDifference=总库存-可借库存-在借明细数；仅列有差异书目，无逐册的汇总库存仍报差异；核对数值不代表已逐册核对借阅关联");
        return scope;
    }
}
