package edu.campusai.rag;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import edu.campusai.rag.DocumentParser.ParsedChunk;
import edu.campusai.rag.DocumentParser.ParsingException;
import org.apache.poi.ddf.EscherContainerRecord;
import org.apache.poi.ddf.EscherOptRecord;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.hslf.usermodel.HSLFGroupShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTable;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.sl.usermodel.GroupShape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.presentationml.x2006.main.CTGroupShape;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficeDocumentParserTest {
    private final DocumentParser parser = new DocumentParser();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void presentationsIncludeTablesAndGroupsAtOriginalSlideNumbers(boolean modern) throws Exception {
        byte[] bytes;
        try (SlideShow<?, ?> presentation = presentation(modern)) {
            Slide<?, ?> first = presentation.createSlide();
            first.createTextBox().setText("残差连接缓解深层网络训练困难。");
            TableShape<?, ?> table = first.createTable(1, 2);
            table.getCell(0, 0).setText("BM25 关键词检索");
            table.getCell(0, 1).setText("保留课程来源");
            GroupShape<?, ?> group = first.createGroup();
            group.createTextBox().setText("分组文本也能检索。");
            presentation.createSlide(); // An empty slide must not renumber later citations.
            Slide<?, ?> hidden = presentation.createSlide();
            hidden.createTextBox().setText("隐藏幻灯片中的内容不应被索引");
            hidden.setHidden(true);
            presentation.createSlide().createTextBox().setText("模型不可用时保留检索原文。");
            bytes = bytes(presentation);
        }
        List<ParsedChunk> chunks = parser.parse("课程." + (modern ? "PPTX" : "PPT"), bytes);
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).slideNumber());
        assertEquals(4, chunks.get(1).slideNumber());
        assertTrue(chunks.get(0).text().contains("BM25 关键词检索"));
        assertTrue(chunks.get(0).text().contains("保留课程来源"));
        assertTrue(chunks.get(0).text().contains("分组文本也能检索"));
        assertFalse(chunks.stream().anyMatch(chunk -> chunk.text().contains("隐藏幻灯片")));
        assertNull(chunks.get(0).pageNumber());
        assertNull(chunks.get(0).paragraphStart());
        assertNull(chunks.get(0).sheetName());
        assertEquals(chunks, parser.parse("课程." + (modern ? "pptx" : "ppt"), bytes));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void spreadsheetsKeepFormattedValuesSparseAddressesAndSheetBoundaries(boolean modern) throws Exception {
        byte[] bytes;
        try (Workbook workbook = workbook(modern)) {
            Sheet first = workbook.createSheet("课程大纲");
            first.createRow(0).createCell(0).setCellValue("课程名称");
            first.getRow(0).createCell(1).setCellValue("深度学习");
            Cell percentage = first.createRow(4).createCell(2);
            percentage.setCellValue(0.125);
            CellStyle percentStyle = workbook.createCellStyle();
            percentStyle.setDataFormat(workbook.createDataFormat().getFormat("0.0%"));
            percentage.setCellStyle(percentStyle);
            Cell date = first.createRow(8).createCell(3);
            date.setCellValue(LocalDateTime.of(2026, 11, 24, 0, 0));
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(dateStyle);
            first.createRow(12).createCell(4).setCellValue(true);
            first.createRow(13).createCell(5).setCellValue("隐藏列内容");
            first.setColumnHidden(5, true);
            first.createRow(14).createCell(0).setCellValue("隐藏行内容");
            first.getRow(14).setZeroHeight(true);
            workbook.createSheet("隐藏工作表").createRow(0).createCell(0).setCellValue("隐藏表内容");
            workbook.setSheetHidden(1, true);
            workbook.createSheet("深度隐藏工作表").createRow(0).createCell(0).setCellValue("深度隐藏表内容");
            workbook.setSheetVisibility(2, SheetVisibility.VERY_HIDDEN);
            workbook.createSheet("第二门课程").createRow(19).createCell(25).setCellValue("数据库关系代数");
            bytes = bytes(workbook);
        }
        List<ParsedChunk> chunks = parser.parse("课程." + (modern ? "xlsx" : "xls"), bytes);
        assertEquals(2, chunks.size());
        ParsedChunk first = chunks.get(0);
        assertEquals("课程大纲", first.sheetName());
        assertEquals("A1:E13", first.cellRange());
        assertTrue(first.text().contains("A1: 课程名称"));
        assertTrue(first.text().contains("B1: 深度学习"));
        assertTrue(first.text().contains("C5: 12.5%"));
        assertTrue(first.text().contains("D9: 2026-11-24"));
        assertTrue(first.text().contains("E13: TRUE"));
        assertFalse(chunks.stream().anyMatch(chunk -> chunk.text().contains("隐藏")));
        assertEquals("第二门课程", chunks.get(1).sheetName());
        assertEquals("Z20", chunks.get(1).cellRange());
        assertEquals("Z20: 数据库关系代数", chunks.get(1).text());
        assertNull(first.pageNumber());
        assertNull(first.paragraphStart());
        assertNull(first.slideNumber());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void formulasUseOnlySavedResultsAndNeverRecalculate(boolean modern) throws Exception {
        byte[] bytes;
        try (Workbook workbook = workbook(modern)) {
            Sheet sheet = workbook.createSheet("成绩");
            sheet.createRow(0).createCell(0).setCellValue(3);
            Cell total = sheet.createRow(1).createCell(1);
            total.setCellFormula("A1*2");
            // Test fixture generation only; the production parser must not evaluate formulas.
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(total);
            sheet.getRow(0).getCell(0).setCellValue(20);
            bytes = bytes(workbook);
        }
        List<ParsedChunk> chunks = parser.parse("成绩." + (modern ? "xlsx" : "xls"), bytes);
        assertTrue(chunks.get(0).text().contains("A1: 20"));
        assertTrue(chunks.get(0).text().contains("B2: 6"));
        assertFalse(chunks.get(0).text().contains("B2: 40"));
        assertFalse(chunks.get(0).text().contains("A1*2"));
    }

    @Test
    void xlsxMissingFormulaCacheFailsWithAnActionableCellLocation() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFCell formula = workbook.createSheet("课程成绩").createRow(2).createCell(3);
            formula.setCellFormula("SUM(A1:A2)");
            if (formula.getCTCell().isSetV()) {
                formula.getCTCell().unsetV();
            }
            byte[] bytes = bytes(workbook);
            assertFailure("成绩.xlsx", bytes, "课程成绩!D3");
            assertFailure("成绩.xlsx", bytes, "重新计算并保存");
        }
    }

    @Test
    void xlsxCachedExternalFormulaDoesNotResolveItsExternalSource() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFCell formula = workbook.createSheet("引用").createRow(0).createCell(0);
            // Direct OOXML fixture: no external file or network access is needed to read the cache.
            formula.getCTCell().addNewF().setStringValue("'[1]Sheet1'!A1");
            formula.getCTCell().setV("42");
            assertEquals("A1: 42", parser.parse("引用.xlsx", bytes(workbook)).get(0).text());
        }
    }

    @Test
    void xlsxUsesIts1904DateSystem() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.getCTWorkbook().getWorkbookPr().setDate1904(true);
            Cell cell = workbook.createSheet("日期").createRow(0).createCell(0);
            cell.setCellValue(1);
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            cell.setCellStyle(style);
            assertEquals("A1: 1904-01-02", parser.parse("date.xlsx", bytes(workbook)).get(0).text());
        }
    }

    @Test
    void pptxHiddenShapesAndGroupsAreNotIndexed() throws Exception {
        try (XMLSlideShow presentation = new XMLSlideShow()) {
            XSLFSlide slide = presentation.createSlide();
            slide.createTextBox().setText("visible text");
            XSLFTextBox hiddenText = slide.createTextBox();
            hiddenText.setText("hidden standalone text");
            ((CTShape) hiddenText.getXmlObject()).getNvSpPr().getCNvPr().setHidden(true);
            XSLFGroupShape hiddenGroup = slide.createGroup();
            hiddenGroup.createTextBox().setText("hidden group text");
            ((CTGroupShape) hiddenGroup.getXmlObject()).getNvGrpSpPr().getCNvPr().setHidden(true);
            assertEquals("visible text", parser.parse("hidden.pptx", bytes(presentation)).get(0).text());
        }
    }

    @Test
    void pptHiddenShapesAndGroupsRespectTheUseHiddenFlag() throws Exception {
        try (HSLFSlideShow presentation = new HSLFSlideShow()) {
            HSLFSlide slide = presentation.createSlide();
            slide.createTextBox().setText("visible text");
            HSLFTextBox hiddenText = slide.createTextBox();
            hiddenText.setText("hidden standalone text");
            hiddenText.setEscherProperty(EscherPropertyTypes.GROUPSHAPE__FLAGS, 0x00020002);
            HSLFGroupShape hiddenGroup = slide.createGroup();
            hiddenGroup.createTextBox().setText("hidden group text");
            EscherOptRecord groupProperties = new EscherOptRecord();
            groupProperties.setRecordId(EscherOptRecord.RECORD_ID);
            ((EscherContainerRecord) hiddenGroup.getSpContainer().getChild(0)).addChildRecord(groupProperties);
            hiddenGroup.setEscherProperty(EscherPropertyTypes.GROUPSHAPE__FLAGS, 0x00020002);
            HSLFTextBox unusedFlag = slide.createTextBox();
            unusedFlag.setText("visible because hidden override is unused");
            unusedFlag.setEscherProperty(EscherPropertyTypes.GROUPSHAPE__FLAGS, 0x00000002);
            HSLFTable table = slide.createTable(1, 2);
            table.getCell(0, 0).setText("hidden cell text");
            table.getCell(0, 0).setEscherProperty(EscherPropertyTypes.GROUPSHAPE__FLAGS, 0x00020002);
            table.getCell(0, 1).setText("visible cell text");
            String text = parser.parse("hidden.ppt", bytes(presentation)).get(0).text();
            assertTrue(text.contains("visible text"));
            assertTrue(text.contains("visible because hidden override is unused"));
            assertFalse(text.contains("hidden standalone text"));
            assertFalse(text.contains("hidden group text"));
            assertFalse(text.contains("hidden cell text"));
            assertTrue(text.contains("visible cell text"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void longOfficeTextKeepsUnicodeAndTheSameSourcePosition(boolean modern) throws Exception {
        String text = "a".repeat(699) + "😀".repeat(800);
        try (SlideShow<?, ?> presentation = presentation(modern)) {
            presentation.createSlide().createTextBox().setText(text);
            List<ParsedChunk> chunks = parser.parse("long." + (modern ? "pptx" : "ppt"), bytes(presentation));
            assertEquals(text, String.join("", chunks.stream().map(ParsedChunk::text).toList()));
            assertTrue(chunks.size() > 1);
            assertValidUnicodeChunks(chunks);
            assertTrue(chunks.stream().allMatch(chunk -> Integer.valueOf(1).equals(chunk.slideNumber())));
        }
        try (Workbook workbook = workbook(modern)) {
            Sheet sheet = workbook.createSheet("长文本");
            sheet.createRow(7).createCell(6).setCellValue(text);
            sheet.createRow(8).createCell(7).setCellValue("后续单元格");
            List<ParsedChunk> chunks = parser.parse("long." + (modern ? "xlsx" : "xls"), bytes(workbook));
            List<ParsedChunk> longCell = chunks.subList(0, chunks.size() - 1);
            assertEquals("G8: " + text, String.join("", longCell.stream().map(ParsedChunk::text).toList()));
            assertValidUnicodeChunks(chunks);
            assertTrue(longCell.stream().allMatch(chunk -> "G8".equals(chunk.cellRange())));
            assertEquals("H9", chunks.get(chunks.size() - 1).cellRange());
        }
    }

    @Test
    void rejectsMislabeledOfficeContainersAndDamagedInputs() throws Exception {
        byte[] text = "这不是 Office 文件".getBytes(StandardCharsets.UTF_8);
        for (String extension : List.of("ppt", "pptx", "xls", "xlsx")) {
            assertFailure("wrong." + extension, text, "不一致");
        }
        try (Workbook spreadsheet = new XSSFWorkbook(); SlideShow<?, ?> slides = new HSLFSlideShow()) {
            spreadsheet.createSheet().createRow(0).createCell(0).setCellValue("spreadsheet");
            slides.createSlide().createTextBox().setText("presentation");
            assertFailure("wrong.pptx", bytes(spreadsheet), "有效的 PPTX");
            assertFailure("wrong.ppt", bytes(spreadsheet), "不一致");
            assertFailure("wrong.xls", bytes(slides), "不一致");
            assertFailure("wrong.xlsx", bytes(slides), "不一致");
        }
        byte[] truncatedOle = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
        assertFailure("broken.xls", truncatedOle, "解析失败");
        assertFailure("broken.ppt", truncatedOle, "解析失败");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pptx", "xlsx"})
    void rejectsEncryptedOfficePackages(String extension) throws Exception {
        byte[] plain;
        if ("pptx".equals(extension)) {
            try (SlideShow<?, ?> slides = new XMLSlideShow()) {
                slides.createSlide().createTextBox().setText("encrypted lecture");
                plain = bytes(slides);
            }
        } else {
            try (Workbook workbook = new XSSFWorkbook()) {
                workbook.createSheet().createRow(0).createCell(0).setCellValue("encrypted workbook");
                plain = bytes(workbook);
            }
        }
        try (POIFSFileSystem filesystem = new POIFSFileSystem()) {
            EncryptionInfo encryption = new EncryptionInfo(EncryptionMode.agile);
            Encryptor encryptor = encryption.getEncryptor();
            encryptor.confirmPassword("fixture-only-password");
            try (OutputStream encrypted = encryptor.getDataStream(filesystem)) {
                encrypted.write(plain);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            filesystem.writeFilesystem(output);
            assertFailure("protected." + extension, output.toByteArray(), "加密 Office");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ppt", "xls"})
    void rejectsEncryptedLegacyOffice(String extension) throws Exception {
        byte[] encrypted;
        try {
            Biff8EncryptionKey.setCurrentUserPassword("fixture-only-password");
            if ("ppt".equals(extension)) {
                try (HSLFSlideShow presentation = new HSLFSlideShow()) {
                    presentation.createSlide().createTextBox().setText("private lecture");
                    encrypted = bytes(presentation);
                }
            } else {
                try (Workbook workbook = new HSSFWorkbook()) {
                    workbook.createSheet().createRow(0).createCell(0).setCellValue("private workbook");
                    encrypted = bytes(workbook);
                }
            }
        } finally {
            Biff8EncryptionKey.setCurrentUserPassword(null);
        }
        assertFailure("protected." + extension, encrypted, "加密 Office");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyOfficeDocumentsDoNotBecomeSearchable(boolean modern) throws Exception {
        try (SlideShow<?, ?> presentation = presentation(modern)) {
            presentation.createSlide();
            assertFailure("empty." + (modern ? "pptx" : "ppt"), bytes(presentation), "未提取");
        }
        try (Workbook workbook = workbook(modern)) {
            workbook.createSheet();
            assertFailure("empty." + (modern ? "xlsx" : "xls"), bytes(workbook), "未提取");
        }
    }

    @Test
    void boundedOfficeCountsFailBeforeReturningPartialChunks() throws Exception {
        try (SlideShow<?, ?> presentation = new HSLFSlideShow()) {
            for (int index = 0; index <= DocumentParser.MAX_SLIDES; index++) {
                presentation.createSlide();
            }
            assertFailure("too-many.ppt", bytes(presentation), "200 张");
        }
        try (Workbook workbook = new HSSFWorkbook()) {
            for (int index = 0; index <= DocumentParser.MAX_WORKSHEETS; index++) {
                workbook.createSheet();
            }
            assertFailure("too-many.xls", bytes(workbook), "50 张");
        }
        try (Workbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            for (int index = 0; index <= DocumentParser.MAX_SPREADSHEET_ROWS; index++) {
                sheet.createRow(index);
            }
            assertFailure("too-many-rows.xls", bytes(workbook), "行数量超过");
        }
        try (Workbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            for (int index = 0; index <= DocumentParser.MAX_SPREADSHEET_CELLS; index++) {
                int row = index / 250;
                if (sheet.getRow(row) == null) {
                    sheet.createRow(row);
                }
                sheet.getRow(row).createCell(index % 250);
            }
            assertFailure("too-many-cells.xls", bytes(workbook), "单元格数量超过");
        }
    }

    @Test
    void officeZipExpansionAndEntryCountAreBounded() throws Exception {
        ByteArrayOutputStream oversized = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(oversized)) {
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            byte[] block = new byte[8192];
            for (int index = 0; index < 1025; index++) {
                zip.write(block);
            }
            zip.closeEntry();
        }
        assertFailure("large.xlsx", oversized.toByteArray(), "解压内容过大");
        ByteArrayOutputStream crowded = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(crowded)) {
            for (int index = 0; index < 513; index++) {
                zip.putNextEntry(new ZipEntry("item" + index));
                zip.closeEntry();
            }
        }
        assertFailure("crowded.pptx", crowded.toByteArray(), "内部文件过多");
        assertEquals("恢复正常", parser.parse("normal.txt", "恢复正常".getBytes(StandardCharsets.UTF_8)).get(0).text());
    }

    private void assertValidUnicodeChunks(List<ParsedChunk> chunks) {
        for (ParsedChunk chunk : chunks) {
            assertTrue(chunk.text().length() <= DocumentParser.MAX_CHUNK_CHARS);
            assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1)));
            assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)));
        }
    }

    private void assertFailure(String filename, byte[] content, String message) {
        ParsingException failure = assertThrows(ParsingException.class, () -> parser.parse(filename, content));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    private Workbook workbook(boolean modern) {
        return modern ? new XSSFWorkbook() : new HSSFWorkbook();
    }

    private SlideShow<?, ?> presentation(boolean modern) {
        return modern ? new XMLSlideShow() : new HSLFSlideShow();
    }

    private byte[] bytes(Workbook workbook) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }

    private byte[] bytes(SlideShow<?, ?> presentation) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        presentation.write(output);
        return output.toByteArray();
    }
}
