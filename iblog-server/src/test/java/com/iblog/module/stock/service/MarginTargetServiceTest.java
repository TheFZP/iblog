package com.iblog.module.stock.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarginTargetServiceTest {

    private static final String SHEET = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
            <row r="1">
              <c r="A1" t="inlineStr"><is><t>证券代码</t></is></c>
              <c r="B1" t="inlineStr"><is><t>证券简称</t></is></c>
              <c r="C1" t="inlineStr"><is><t>融资标的</t></is></c>
              <c r="D1" t="inlineStr"><is><t>融券标的</t></is></c>
            </row>
            <row r="2">
              <c r="A2" t="inlineStr"><is><t>000001</t></is></c>
              <c r="B2" t="inlineStr"><is><t>平安银行</t></is></c>
              <c r="C2" t="inlineStr"><is><t>Y</t></is></c>
              <c r="D2" t="inlineStr"><is><t>N</t></is></c>
            </row>
            <row r="3">
              <c r="A3" t="inlineStr"><is><t>300750</t></is></c>
              <c r="B3" t="inlineStr"><is><t>宁德时代</t></is></c>
              <c r="C3" t="inlineStr"><is><t>Y</t></is></c>
              <c r="D3" t="inlineStr"><is><t>Y</t></is></c>
            </row>
            <row r="4">
              <c r="A4" t="inlineStr"><is><t>000002</t></is></c>
              <c r="B4" t="inlineStr"><is><t>万科A</t></is></c>
              <c r="C4" t="inlineStr"><is><t>N</t></is></c>
              <c r="D4" t="inlineStr"><is><t>N</t></is></c>
            </row>
            </sheetData></worksheet>
            """;

    @Test
    void parseXlsxKeepsOnlyMarginTargets() throws Exception {
        Set<String> codes = MarginTargetService.parseXlsx(zip(SHEET));
        assertEquals(Set.of("000001", "300750"), codes);
    }

    @Test
    void parseXlsxRejectsBadPayload() {
        assertThrows(IllegalStateException.class, () -> MarginTargetService.parseXlsx(new byte[]{1, 2, 3}));
    }

    @Test
    void parseShanghaiExtractsCodesAndTotal() {
        String json = """
                {"result":{"count":2019,"data":[
                  {"SCODE":"600519","DATE":"2026-11-13 00:00:00"},
                  {"SCODE":"688981","DATE":"2026-11-13 00:00:00"},
                  {"SCODE":"","DATE":"2026-11-13 00:00:00"}
                ]}}
                """;
        MarginTargetService.ShanghaiPage page = MarginTargetService.parseShanghai(json);
        assertEquals(2019, page.total());
        assertEquals(Set.of("600519", "688981"), page.codes());
    }

    @Test
    void parseShanghaiToleratesBadPayload() {
        MarginTargetService.ShanghaiPage page = MarginTargetService.parseShanghai("not json");
        assertEquals(0, page.total());
        assertTrue(page.codes().isEmpty());
    }

    private static byte[] zip(String sheetXml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(sheetXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
