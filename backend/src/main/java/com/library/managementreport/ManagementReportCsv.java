package com.library.managementreport;

import com.library.common.CsvWriter;
import java.nio.charset.StandardCharsets;
import com.library.managementreport.ManagementReportContracts.*;

final class ManagementReportCsv {
    private ManagementReportCsv() {}
    static byte[] render(Report r) {
        StringBuilder out=new StringBuilder("\uFEFF");
        line(out,"导出时点",r.asOf());line(out,"统计日期（上海）",r.asOfDate());
        line(out,"关键词",r.filters().keyword());line(out,"借阅状态",r.filters().status()==null?"全部":r.filters().status());
        line(out,"来源",r.filters().origin());line(out,"借出开始日期",r.filters().startDate());line(out,"借出结束日期",r.filters().endDate());
        line(out,"覆盖借阅明细数",r.totals().borrowDetails());line(out,"统计口径",r.scope());
        line(out,"汇总","指标","数量","单位");
        line(out,"汇总","借阅明细",r.totals().borrowDetails(),"条");
        line(out,"汇总","借出册次（按明细）",r.totals().borrowedCopyEvents(),"册次");
        line(out,"汇总","未还明细",r.totals().activeDetails(),"条");line(out,"汇总","已还明细",r.totals().returnedDetails(),"条");
        line(out,"汇总","逾期明细",r.totals().overdueDetails(),"条");line(out,"汇总","明确柜台订单（去重）",r.totals().knownCounterOrders(),"单");
        line(out,"来源","分类","数量","单位");
        line(out,"来源","迁入",r.origins().imported(),"条");line(out,"来源","新柜台",r.origins().counterOrder(),"条");
        line(out,"来源","本系统",r.origins().system(),"条");line(out,"来源","待核实",r.origins().unverified(),"条");
        line(out,"近7个上海自然日","日期","借阅明细（条）","明确柜台订单（单）");
        for(var row:r.trend()) line(out,"趋势",row.date(),row.borrowDetails(),row.knownCounterOrders());
        line(out,"书目排行TOP10","书目业务编号","书名","借阅明细（条）","明确柜台订单（单）");
        for(var row:r.hotBooks()) line(out,"排行",row.bookId(),row.title(),row.borrowDetails(),row.knownCounterOrders());
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void line(StringBuilder out,Object... values) {
        CsvWriter.appendLine(out, CsvWriter.NullValue.QUOTED_EMPTY, values);
    }
}
