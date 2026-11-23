package edu.campusai.rag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/** Bounded, deterministic text extraction. It does not perform OCR or infer page numbers. */
@Component
public class DocumentParser {
    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_CHUNK_CHARS = 700;
    public static final int MAX_EXTRACTED_CHARS = 1_000_000;
    public static final int MAX_PDF_PAGES = 200;
    private static final int MAX_PARAGRAPHS = 50_000;
    private static final long MAX_ZIP_ENTRY_BYTES = 8L * 1024 * 1024;
    private static final long MAX_ZIP_TOTAL_BYTES = 32L * 1024 * 1024;

    public List<ParsedChunk> parse(String filename, byte[] content) {
        if (filename == null || filename.isBlank()) {
            throw new ParsingException("缺少资料文件名");
        }
        if (content == null || content.length == 0) {
            throw new ParsingException("资料文件为空");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new ParsingException("资料大小不能超过 10 MB");
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        ChunkCollector chunks = new ChunkCollector();
        try {
            switch (extension) {
                case "pdf" -> parsePdf(content, chunks);
                case "docx" -> parseDocx(content, chunks);
                case "txt" -> parseTxt(content, chunks);
                default -> throw new ParsingException("仅支持文本型 PDF、DOCX 和 UTF-8 TXT 资料");
            }
            List<ParsedChunk> result = chunks.finish();
            if (result.isEmpty()) {
                throw new ParsingException("未提取到可检索文字；扫描 PDF 需先进行 OCR 后重新上传");
            }
            return result;
        } catch (ParsingException exception) {
            throw exception;
        } catch (InvalidPasswordException exception) {
            throw new ParsingException("不支持加密 PDF，请先提供未加密的文本资料", exception);
        } catch (IOException | RuntimeException exception) {
            throw new ParsingException("资料解析失败，请确认文件未损坏且扩展名与内容一致", exception);
        }
    }

    private void parsePdf(byte[] content, ChunkCollector chunks) throws IOException {
        if (!startsWith(content, "%PDF-")) {
            throw new ParsingException("PDF 文件内容与扩展名不一致");
        }
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                throw new ParsingException("不支持加密 PDF，请先提供未加密的文本资料");
            }
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new ParsingException("PDF 不能超过 200 页，请按章节拆分后上传");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int extracted = 0;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                BoundedWriter output = new BoundedWriter(MAX_EXTRACTED_CHARS - extracted);
                stripper.writeText(document, output);
                String text = output.toString();
                extracted += text.length();
                chunks.add(text, page, null, null);
            }
        }
    }

    private void parseDocx(byte[] content, ChunkCollector chunks) throws IOException {
        validateDocxZip(content);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            DocxPosition position = new DocxPosition();
            visitBody(document.getBodyElements(), document, position, chunks, 0);
        }
    }

    private void visitBody(List<IBodyElement> elements, XWPFDocument document,
                           DocxPosition position, ChunkCollector chunks, int depth) {
        if (depth > 32) {
            throw new ParsingException("DOCX 表格嵌套过深，请简化资料后上传");
        }
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                if (++position.paragraph > MAX_PARAGRAPHS) {
                    throw new ParsingException("资料段落数量过多，请按章节拆分后上传");
                }
                String text = paragraph.getText();
                if (isHeading(paragraph, document) && !text.isBlank()) {
                    String title = normalize(text);
                    int end = Math.min(200, title.length());
                    if (end < title.length() && Character.isHighSurrogate(title.charAt(end - 1))) {
                        end--;
                    }
                    position.heading = title.substring(0, end);
                }
                chunks.add(text, null, position.paragraph, position.heading);
            } else if (element instanceof XWPFTable table) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        visitBody(cell.getBodyElements(), document, position, chunks, depth + 1);
                    }
                }
            }
        }
    }

    private boolean isHeading(XWPFParagraph paragraph, XWPFDocument document) {
        String styleId = paragraph.getStyle();
        if (styleId == null) {
            return false;
        }
        XWPFStyle style = document.getStyles() == null ? null : document.getStyles().getStyle(styleId);
        String styleName = style == null ? "" : style.getName();
        return isHeadingName(styleId) || isHeadingName(styleName);
    }

    private boolean isHeadingName(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).replace(" ", "")
                .matches("(?:heading|标题)[1-9]");
    }

    private void parseTxt(byte[] content, ChunkCollector chunks) {
        if (startsWith(content, "%PDF-") || startsWith(content, "PK\u0003\u0004")) {
            throw new ParsingException("TXT 文件内容与扩展名不一致");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException exception) {
            throw new ParsingException("TXT 必须使用 UTF-8 编码，请转换编码后重新上传", exception);
        }
        if (text.length() > MAX_EXTRACTED_CHARS) {
            throw new ParsingException("资料文字超过 100 万字符，请按章节拆分后上传");
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (text.chars().anyMatch(character -> Character.isISOControl(character)
                && character != '\n' && character != '\r' && character != '\t' && character != '\f')) {
            throw new ParsingException("TXT 包含二进制控制字符，请上传纯文本资料");
        }
        // Each physical line is a paragraph; blank lines still occupy a source position.
        String[] paragraphs = text.split("\\R", -1);
        if (paragraphs.length > MAX_PARAGRAPHS) {
            throw new ParsingException("资料段落数量过多，请按章节拆分后上传");
        }
        for (int index = 0; index < paragraphs.length; index++) {
            chunks.add(paragraphs[index], null, index + 1, null);
        }
    }

    private void validateDocxZip(byte[] content) throws IOException {
        if (!startsWith(content, "PK\u0003\u0004")) {
            throw new ParsingException("DOCX 文件内容与扩展名不一致");
        }
        boolean hasTypes = false;
        boolean hasDocument = false;
        int entries = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 512) {
                    throw new ParsingException("DOCX 内部文件过多，请简化资料后上传");
                }
                long entryBytes = 0;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    entryBytes += count;
                    totalBytes += count;
                    if (entryBytes > MAX_ZIP_ENTRY_BYTES || totalBytes > MAX_ZIP_TOTAL_BYTES) {
                        throw new ParsingException("DOCX 解压内容过大，请按章节拆分后上传");
                    }
                }
                hasTypes |= "[Content_Types].xml".equals(entry.getName());
                hasDocument |= "word/document.xml".equals(entry.getName());
                zip.closeEntry();
            }
        }
        if (!hasTypes || !hasDocument) {
            throw new ParsingException("文件不是有效的 DOCX 文档");
        }
        // Apache POI's own ZIP inflation-ratio protections remain enabled as a second boundary.
    }

    private static boolean startsWith(byte[] content, String prefix) {
        byte[] expected = prefix.getBytes(StandardCharsets.ISO_8859_1);
        if (content.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (content[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f ]+", " ").replaceAll(" *\n *", "\n").strip();
    }

    public record ParsedChunk(String text, Integer pageNumber, Integer paragraphStart,
                              Integer paragraphEnd, String heading) {
    }

    public static class ParsingException extends RuntimeException {
        public ParsingException(String message) {
            super(message);
        }

        public ParsingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class DocxPosition {
        private int paragraph;
        private String heading;
    }

    private static class BoundedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;

        BoundedWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] value, int offset, int length) {
            if (text.length() + length > limit) {
                throw new ParsingException("资料文字超过 100 万字符，请按章节拆分后上传");
            }
            text.append(value, offset, length);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    private static class ChunkCollector {
        private final List<ParsedChunk> result = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private Integer page;
        private Integer startParagraph;
        private Integer endParagraph;
        private String heading;
        private int extractedChars;

        void add(String rawText, Integer pageNumber, Integer paragraph, String currentHeading) {
            extractedChars += rawText.length();
            if (extractedChars > MAX_EXTRACTED_CHARS) {
                throw new ParsingException("资料文字超过 100 万字符，请按章节拆分后上传");
            }
            String text = normalize(rawText);
            if (text.isBlank()) {
                return;
            }
            int offset = 0;
            while (offset < text.length()) {
                int end = splitEnd(text, offset);
                String part = text.substring(offset, end).strip();
                offset = end;
                if (part.isEmpty()) {
                    continue;
                }
                if (current.length() > 0 && (!java.util.Objects.equals(page, pageNumber)
                        || !java.util.Objects.equals(heading, currentHeading)
                        || current.length() + 1 + part.length() > MAX_CHUNK_CHARS)) {
                    flush();
                }
                if (current.isEmpty()) {
                    page = pageNumber;
                    startParagraph = paragraph;
                    heading = currentHeading;
                } else {
                    current.append('\n');
                }
                current.append(part);
                endParagraph = paragraph;
            }
        }

        private int splitEnd(String text, int start) {
            int hardEnd = Math.min(start + MAX_CHUNK_CHARS, text.length());
            if (hardEnd == text.length()) {
                return hardEnd;
            }
            // Avoid dividing a Unicode supplementary character into invalid UTF-16 halves.
            if (Character.isHighSurrogate(text.charAt(hardEnd - 1))) {
                hardEnd--;
            }
            for (int index = hardEnd - 1; index >= start + MAX_CHUNK_CHARS / 2; index--) {
                char character = text.charAt(index);
                if ("。！？.!?;；\n".indexOf(character) >= 0) {
                    return index + 1;
                }
            }
            for (int index = hardEnd - 1; index >= start + MAX_CHUNK_CHARS / 2; index--) {
                if (Character.isWhitespace(text.charAt(index))) {
                    return index + 1;
                }
            }
            return hardEnd;
        }

        private void flush() {
            if (!current.isEmpty()) {
                result.add(new ParsedChunk(current.toString(), page, startParagraph, endParagraph, heading));
                current.setLength(0);
            }
        }

        List<ParsedChunk> finish() {
            flush();
            return List.copyOf(result);
        }
    }
}
