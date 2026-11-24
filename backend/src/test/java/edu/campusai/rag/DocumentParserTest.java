package edu.campusai.rag;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import edu.campusai.rag.DocumentParser.ParsedChunk;
import edu.campusai.rag.DocumentParser.ParsingException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParserTest {
    private final DocumentParser parser = new DocumentParser();

    @Test
    void textPdfKeepsRealPagesWithoutInventingParagraphs() throws Exception {
        byte[] bytes = pdf("BM25 retrieves course evidence.", "Residual connections improve gradient flow.");
        List<ParsedChunk> chunks = parser.parse("lecture.PDF", bytes);

        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).pageNumber());
        assertEquals(2, chunks.get(1).pageNumber());
        assertTrue(chunks.get(0).text().contains("BM25 retrieves course evidence."));
        assertTrue(chunks.get(1).text().contains("Residual connections improve gradient flow."));
        assertNull(chunks.get(0).paragraphStart());
        assertNull(chunks.get(0).paragraphEnd());
        assertEquals(chunks, parser.parse("lecture.pdf", bytes));
    }

    @Test
    void docxIncludesTablesInBodyOrderAndUsesStableParagraphRanges() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("第一章 课程检索");
            document.createParagraph().createRun().setText("首先上传课程资料。");
            document.createParagraph(); // Source paragraph 3 must still occupy a position.
            XWPFTable table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("表格左侧：BM25 关键词检索。");
            table.getRow(0).getCell(1).setText("表格右侧：引用保留来源。");
            document.createParagraph().createRun().setText("最后检查引用位置。");
            XWPFParagraph secondHeading = document.createParagraph();
            secondHeading.setStyle("Heading2");
            secondHeading.createRun().setText("第二节 模型故障");
            document.createParagraph().createRun().setText("模型超时时返回原文。");
            bytes = docxBytes(document);
        }

        List<ParsedChunk> chunks = parser.parse("notes.docx", bytes);
        assertEquals(2, chunks.size());
        ParsedChunk first = chunks.get(0);
        assertEquals(1, first.paragraphStart());
        assertEquals(6, first.paragraphEnd());
        assertNull(first.pageNumber());
        assertEquals("第一章 课程检索", first.heading());
        assertTrue(first.text().indexOf("首先") < first.text().indexOf("表格左侧"));
        assertTrue(first.text().indexOf("表格左侧") < first.text().indexOf("表格右侧"));
        assertTrue(first.text().indexOf("表格右侧") < first.text().indexOf("最后"));
        assertEquals(7, chunks.get(1).paragraphStart());
        assertEquals(8, chunks.get(1).paragraphEnd());
        assertEquals("第二节 模型故障", chunks.get(1).heading());
        assertEquals(chunks, parser.parse("notes.docx", bytes));
    }

    @Test
    void utf8BomAndBlankLinesKeepSourceLineNumbers() {
        byte[] bytes = "\uFEFF\r\n\r\n残差连接缓解深层网络训练困难。\r\n\r\n保留原始来源以便核对。\r\n"
                .getBytes(StandardCharsets.UTF_8);
        List<ParsedChunk> chunks = parser.parse("lecture.txt", bytes);

        assertEquals(1, chunks.size());
        assertEquals("残差连接缓解深层网络训练困难。\n保留原始来源以便核对。", chunks.get(0).text());
        assertEquals(3, chunks.get(0).paragraphStart());
        assertEquals(5, chunks.get(0).paragraphEnd());
        assertNull(chunks.get(0).pageNumber());
        assertNull(chunks.get(0).heading());
    }

    @Test
    void longParagraphIsSplitAtSentenceBoundariesWithoutLosingCharacters() {
        String text = "资料切分需要保留来源位置，以便学生核对引用。".repeat(90);
        List<ParsedChunk> chunks = parser.parse("long.txt", text.getBytes(StandardCharsets.UTF_8));

        assertTrue(chunks.size() > 1);
        assertEquals(text, String.join("", chunks.stream().map(ParsedChunk::text).toList()));
        for (ParsedChunk chunk : chunks) {
            assertTrue(chunk.text().length() <= DocumentParser.MAX_CHUNK_CHARS);
            assertTrue(chunk.text().endsWith("。"));
            assertEquals(1, chunk.paragraphStart());
            assertEquals(1, chunk.paragraphEnd());
        }
    }

    @Test
    void longTokenNeverSplitsSurrogatePairs() {
        String text = "a".repeat(699) + "😀".repeat(400);
        List<ParsedChunk> chunks = parser.parse("unicode.txt", text.getBytes(StandardCharsets.UTF_8));
        assertEquals(text, String.join("", chunks.stream().map(ParsedChunk::text).toList()));
        for (ParsedChunk chunk : chunks) {
            assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1)));
            assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)));
            assertTrue(chunk.text().length() <= DocumentParser.MAX_CHUNK_CHARS);
        }
    }

    @Test
    void unsupportedMalformedAndBinaryFilesFailClearly() {
        assertFailure("empty.txt", new byte[0], "为空");
        assertFailure("blank.txt", " \n \t\n".getBytes(StandardCharsets.UTF_8), "未提取");
        assertFailure("legacy.doc", new byte[]{1}, "仅支持");
        assertFailure("bad.txt", new byte[]{(byte) 0xC3, (byte) 0x28}, "UTF-8");
        assertFailure("binary.txt", new byte[]{0, 1, 2}, "二进制");
        assertFailure("wrong.pdf", "这是普通文本".getBytes(StandardCharsets.UTF_8), "不一致");
        assertFailure("wrong.docx", "这是普通文本".getBytes(StandardCharsets.UTF_8), "不一致");
        assertFailure("fake.txt", "%PDF-1.7".getBytes(StandardCharsets.UTF_8), "不一致");
        assertFailure("broken.pdf", "%PDF-1.7\nbroken".getBytes(StandardCharsets.UTF_8), "解析失败");
    }

    @Test
    void scannedAndEncryptedPdfDoNotBecomeSearchable() throws Exception {
        try (PDDocument blank = new PDDocument()) {
            blank.addPage(new PDPage());
            assertFailure("scan.pdf", pdfBytes(blank), "未提取");
        }
        try (PDDocument encrypted = Loader.loadPDF(pdf("private course text"))) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "student", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            encrypted.protect(policy);
            assertFailure("protected.pdf", pdfBytes(encrypted), "加密");
        }
        try (PDDocument encrypted = Loader.loadPDF(pdf("empty password document"))) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            encrypted.protect(policy);
            assertFailure("protected-empty-password.pdf", pdfBytes(encrypted), "加密");
        }
    }

    @Test
    void boundedInputsFailBeforeReturningPartialChunks() throws Exception {
        assertFailure("oversize.txt", new byte[DocumentParser.MAX_FILE_BYTES + 1], "10 MB");
        assertFailure("many-characters.txt", "a".repeat(DocumentParser.MAX_EXTRACTED_CHARS + 1)
                .getBytes(StandardCharsets.UTF_8), "100 万");
        assertFailure("many-paragraphs.txt", "\n".repeat(50_001).getBytes(StandardCharsets.UTF_8), "段落数量");
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page <= DocumentParser.MAX_PDF_PAGES; page++) {
                document.addPage(new PDPage());
            }
            assertFailure("many-pages.pdf", pdfBytes(document), "200 页");
        }
    }

    @Test
    void rejectsNonDocxZipAndOversizedDecompression() throws Exception {
        ByteArrayOutputStream invalid = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(invalid)) {
            zip.putNextEntry(new ZipEntry("not-a-document.txt"));
            zip.write("plain text".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertFailure("not-docx.docx", invalid.toByteArray(), "有效的 DOCX");

        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bomb)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] block = new byte[8192];
            for (int index = 0; index < 1025; index++) {
                zip.write(block);
            }
            zip.closeEntry();
        }
        assertFailure("compressed.docx", bomb.toByteArray(), "解压内容过大");
        // A failed parse is isolated; the same stateless parser still handles subsequent input.
        assertEquals("恢复正常", parser.parse("valid.txt", "恢复正常".getBytes(StandardCharsets.UTF_8)).get(0).text());
    }

    private void assertFailure(String filename, byte[] content, String message) {
        ParsingException exception = assertThrows(ParsingException.class, () -> parser.parse(filename, content));
        assertTrue(exception.getMessage().contains(message), exception.getMessage());
    }

    private byte[] pdf(String... pageTexts) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(40, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            return pdfBytes(document);
        }
    }

    private byte[] pdfBytes(PDDocument document) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }

    private byte[] docxBytes(XWPFDocument document) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.write(output);
        return output.toByteArray();
    }

}
