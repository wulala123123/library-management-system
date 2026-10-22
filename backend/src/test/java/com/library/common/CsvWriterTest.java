package com.library.common;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvWriterTest {
    @Test void nullPoliciesKeepEmptyAndLiteralBackslashNDistinct() {
        assertEquals("\"\",\"\",\"\\N\",\"0\"\r\n",
                line(CsvWriter.NullValue.QUOTED_EMPTY, null, "", "\\N", 0));
        assertEquals("\\N,\"\",\"\\N\",\"0\"\r\n",
                line(CsvWriter.NullValue.UNQUOTED_BACKSLASH_N, null, "", "\\N", 0));
    }

    @ParameterizedTest @EnumSource(CsvWriter.NullValue.class)
    void quotesEveryCellEscapesQuotesAndPreservesEmbeddedLineEndings(CsvWriter.NullValue policy) {
        assertEquals("\"a,b\",\"a\"\"b\",\"a\nb\",\"a\rb\",\"a\r\nb\"\r\n",
                line(policy, "a,b", "a\"b", "a\nb", "a\rb", "a\r\nb"));
    }

    @ParameterizedTest
    @ValueSource(strings={"=1", "+1", "-1", "@cmd", "\ttext", "\rtext", "\ntext", " +1", "\u3000-1", "\u00a0=1", " \t@cmd"})
    void neutralizesFormulaPrefixesAndLeadingControlsWithoutTrimming(String text) {
        for (CsvWriter.NullValue policy : CsvWriter.NullValue.values())
            assertEquals("\"'" + text + "\"\r\n", line(policy, text));
    }

    @ParameterizedTest @EnumSource(CsvWriter.NullValue.class)
    void preservesIdentifiersWhitespaceAndAlreadySafeText(CsvWriter.NullValue policy) {
        assertEquals("\"00001\",\"000Aa \",\"  \",\" text\",\" \ttext\",\"'=1\",\"a=1\",\"\u200b=1\"\r\n",
                line(policy, "00001", "000Aa ", "  ", " text", " \ttext", "'=1", "a=1", "\u200b=1"));
    }

    @ParameterizedTest @EnumSource(CsvWriter.NullValue.class)
    void protectsStringsWhilePreservingNegativeNumbersAndOtherObjectTypes(CsvWriter.NullValue policy) {
        assertEquals("\"-1\",\"-1.20\",\"0\",\"false\",\"=object\",\"'-1\"\r\n",
                line(policy, -1, new BigDecimal("-1.20"), 0, false, new StringBuilder("=object"), "-1"));
    }

    @ParameterizedTest @EnumSource(CsvWriter.NullValue.class)
    void appendsCrLfToTheExistingOutputEvenForAnEmptyRow(CsvWriter.NullValue policy) {
        StringBuilder out = new StringBuilder("prefix\r\n");
        CsvWriter.appendLine(out, policy);
        CsvWriter.appendLine(out, policy, "next");
        assertEquals("prefix\r\n\r\n\"next\"\r\n", out.toString());
    }

    private String line(CsvWriter.NullValue policy, Object... cells) {
        StringBuilder out = new StringBuilder();
        CsvWriter.appendLine(out, policy, cells);
        return out.toString();
    }
}
