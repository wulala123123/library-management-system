package com.library.common;

/** RFC4180 rows with formula protection for String cells and an explicit NULL representation. */
public final class CsvWriter {
    public enum NullValue { QUOTED_EMPTY, UNQUOTED_BACKSLASH_N }

    private CsvWriter() {}

    public static void appendLine(StringBuilder out, NullValue nullValue, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            if (cells[i] == null && nullValue == NullValue.UNQUOTED_BACKSLASH_N) {
                out.append("\\N");
                continue;
            }
            String value = cells[i] == null ? "" : cells[i].toString();
            if (cells[i] instanceof String && formula(value)) value = "'" + value;
            out.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }

    private static boolean formula(String value) {
        if (value.isEmpty()) return false;
        if (value.charAt(0) == '\t' || value.charAt(0) == '\r' || value.charAt(0) == '\n') return true;
        int i = 0;
        while (i < value.length() && (Character.isWhitespace(value.charAt(i)) || Character.isSpaceChar(value.charAt(i)))) i++;
        return i < value.length() && "=+-@".indexOf(value.charAt(i)) >= 0;
    }
}
