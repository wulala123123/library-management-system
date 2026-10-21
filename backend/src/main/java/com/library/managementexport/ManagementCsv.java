package com.library.managementexport;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Complete CSV records, RFC4180 quoting and explicit unknown values. */
final class ManagementCsv {
    record Column<T>(String title, Function<T,Object> value) {}
    private ManagementCsv() {}
    static <T> Column<T> column(String title, Function<T,Object> value) { return new Column<>(title,value); }
    static <T> byte[] render(Map<String,Object> metadata,List<T> rows,List<Column<T>> columns) {
        StringBuilder out=new StringBuilder("\uFEFF");
        metadata.forEach((key,value)->line(out,key,value));
        line(out,"空值说明","\\N表示NULL或未登记，空字符串保持为空，0为已知零；实际字符串均带CSV引号");
        line(out,"文本安全","可能被表格软件执行的文本增加前置单引号；卡号与ISBN按原字符串保留");
        out.append("\r\n");
        line(out,columns.stream().map(Column::title).toArray());
        for(T row:rows)line(out,columns.stream().map(c->c.value.apply(row)).toArray());
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void line(StringBuilder out,Object... cells) {
        for(int i=0;i<cells.length;i++) {
            if(i>0)out.append(',');
            if(cells[i]==null) { out.append("\\N"); continue; }
            String value=cells[i].toString();
            if(cells[i] instanceof String && formula(value))value="'"+value;
            out.append('"').append(value.replace("\"","\"\"")).append('"');
        }
        out.append("\r\n");
    }
    private static boolean formula(String value) {
        if(value.isEmpty())return false;
        if(value.charAt(0)=='\t'||value.charAt(0)=='\r'||value.charAt(0)=='\n')return true;
        int i=0;
        while(i<value.length()&&(Character.isWhitespace(value.charAt(i))||Character.isSpaceChar(value.charAt(i))))i++;
        return i<value.length()&&"=+-@".indexOf(value.charAt(i))>=0;
    }
}
