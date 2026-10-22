package com.library.borrowquery;

import com.library.common.CsvWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.library.borrowquery.BorrowInquiryContracts.*;

/** RFC4180, with spreadsheet formula prefixes neutralized before quoting. */
final class BorrowInquiryCsv {
    private BorrowInquiryCsv() {}
    static byte[] render(List<Row> rows, Filters filters, String asOf, String scope) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        line(csv, "导出时点", asOf);
        line(csv, "关键词", filters.keyword());
        line(csv, "借阅状态", filters.status() == null ? "全部" : filters.status());
        line(csv, "来源", filters.origin());
        line(csv, "总量", rows.size());
        line(csv, "单位", "条");
        line(csv, "统计口径", scope);
        line(csv, "文本安全", "可能被表格软件执行的单元格已加前置单引号；ISBN和馆内条码按原字符串保留");
        csv.append("\r\n");
        line(csv, "借阅ID", "会员ID", "读者姓名", "书目ID", "书名", "ISBN", "实体册ID", "馆内条码",
                "柜台订单ID", "来源", "借阅状态", "借出日期", "期限类型", "应还日期", "归还日期", "续借次数", "备注", "罚款金额");
        for (Row r : rows) line(csv, r.id(), r.userId(), r.userRealName(), r.bookId(), r.bookTitle(), r.bookIsbn(),
                r.copyId(), r.copyBarcode(), r.counterOrderId(), r.origin(), r.status(), r.borrowDate(), r.dueDateKind(),
                r.dueDate(), r.returnDate(), r.renewCount(), r.remark(), r.fineAmount().toPlainString());
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void line(StringBuilder out, Object... values) {
        CsvWriter.appendLine(out, CsvWriter.NullValue.QUOTED_EMPTY, values);
    }
}
