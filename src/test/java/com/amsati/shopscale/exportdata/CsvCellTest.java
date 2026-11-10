package com.amsati.shopscale.exportdata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CsvCellTest {
    @Test void quotesAndEscapes() { assertEquals("\"a \"\"b\"\", c\"", CsvExportController.cell("a \"b\", c")); }
    @Test void neutralisesFormulas() { assertEquals("\"'=SUM(A1)\"", CsvExportController.cell("=SUM(A1)")); }
    @Test void keepsNegativeNumbers() { assertEquals("\"-5\"", CsvExportController.cell(-5)); }
    @Test void nullIsEmpty() { assertEquals("", CsvExportController.cell(null)); }
}
