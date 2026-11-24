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
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.sl.usermodel.GroupShape;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
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
    public static final int MAX_SLIDES = 200;
    public static final int MAX_WORKSHEETS = 50;
    public static final int MAX_SPREADSHEET_ROWS = 50_000;
    public static final int MAX_SPREADSHEET_CELLS = 100_000;
    private static final int MAX_SLIDE_SHAPES = 100_000;
    private static final int MAX_SLIDE_TABLE_CELLS = 100_000;
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
                case "ppt", "pptx" -> parsePresentation(content, extension, chunks);
                case "xls", "xlsx" -> parseSpreadsheet(content, extension, chunks);
                default -> throw new ParsingException("仅支持文本型 PDF、DOCX、UTF-8 TXT、PPT/PPTX 和 XLS/XLSX 资料");
            }
            List<ParsedChunk> result = chunks.finish();
            if (result.isEmpty()) {
                throw new ParsingException("未提取到可检索文字；扫描 PDF 或图片型课件需先进行 OCR，隐藏幻灯片和隐藏单元格不参与提取");
            }
            return result;
        } catch (ParsingException exception) {
            throw exception;
        } catch (InvalidPasswordException exception) {
            throw new ParsingException("不支持加密 PDF，请先提供未加密的文本资料", exception);
        } catch (EncryptedDocumentException exception) {
            throw new ParsingException("不支持加密 Office 文件，请先解密并另存后上传", exception);
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
        validateOfficeZip(content, "DOCX", "word/document.xml");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            DocxPosition position = new DocxPosition();
            visitBody(document.getBodyElements(), document, position, chunks, 0);
        }
    }

    private void parsePresentation(byte[] content, String extension, ChunkCollector chunks) throws IOException {
        if ("pptx".equals(extension)) {
            validateOfficeZip(content, "PPTX", "ppt/presentation.xml");
            try (XMLSlideShow presentation = new XMLSlideShow(new ByteArrayInputStream(content))) {
                parseSlides(presentation.getSlides(), chunks);
            }
        } else {
            validateOle(content, "PPT", "PowerPoint Document");
            try (HSLFSlideShow presentation = new HSLFSlideShow(new ByteArrayInputStream(content))) {
                if (presentation.getEncryptionInfo() != null) {
                    throw new ParsingException("不支持加密 Office 文件，请先解密并另存后上传");
                }
                parseSlides(presentation.getSlides(), chunks);
            }
        }
    }

    private void parseSlides(List<? extends Slide<?, ?>> slides, ChunkCollector chunks) {
        if (slides.size() > MAX_SLIDES) {
            throw new ParsingException("PPT/PPTX 不能超过 200 张幻灯片，请按章节拆分后上传");
        }
        PresentationBudget budget = new PresentationBudget();
        for (int index = 0; index < slides.size(); index++) {
            Slide<?, ?> slide = slides.get(index);
            // Position is the original slide index, including hidden and empty slides.
            visitShapes(slide.getShapes(), index + 1, !slide.isHidden(), chunks, budget, 0);
        }
    }

    private void visitShapes(List<? extends Shape<?, ?>> shapes, int slideNumber, boolean visible,
                             ChunkCollector chunks, PresentationBudget budget, int depth) {
        if (depth > 32) {
            throw new ParsingException("PPT/PPTX 形状嵌套过深，请简化资料后上传");
        }
        for (Shape<?, ?> shape : shapes) {
            if (++budget.shapes > MAX_SLIDE_SHAPES) {
                throw new ParsingException("PPT/PPTX 形状数量过多，请拆分后上传");
            }
            boolean shapeVisible = visible && !isShapeHidden(shape);
            // HSLFTable is also a group: handle tables before generic groups to avoid duplicates.
            if (shape instanceof TableShape<?, ?> table) {
                long cells = (long) table.getNumberOfRows() * table.getNumberOfColumns();
                budget.cells += cells;
                if (budget.cells > MAX_SLIDE_TABLE_CELLS) {
                    throw new ParsingException("PPT/PPTX 表格单元格数量过多，请拆分后上传");
                }
                if (shapeVisible) {
                    for (int row = 0; row < table.getNumberOfRows(); row++) {
                        for (int column = 0; column < table.getNumberOfColumns(); column++) {
                            TextShape<?, ?> cell = table.getCell(row, column);
                            if (cell != null && !isShapeHidden(cell)) {
                                chunks.addSlide(cell.getText(), slideNumber);
                            }
                        }
                    }
                }
            } else if (shape instanceof GroupShape<?, ?> group) {
                visitShapes(group.getShapes(), slideNumber, shapeVisible, chunks, budget, depth + 1);
            } else if (shapeVisible && shape instanceof TextShape<?, ?> textShape) {
                chunks.addSlide(textShape.getText(), slideNumber);
            }
        }
    }

    private boolean isShapeHidden(Shape<?, ?> shape) {
        if (shape instanceof HSLFShape hslf) {
            // MS-ODRAW 2.3.4.44: fHidden is bit 1, and is meaningful only with fUsefHidden (bit 17).
            int flags = hslf.getEscherProperty(EscherPropertyTypes.GROUPSHAPE__FLAGS, 0);
            return (flags & 0x00020002) == 0x00020002;
        }
        if (shape instanceof XSLFShape xslf) {
            XmlObject[] properties = xslf.getXmlObject().selectPath(
                    "declare namespace p='http://schemas.openxmlformats.org/presentationml/2006/main'; ./*/p:cNvPr");
            for (XmlObject property : properties) {
                try (XmlCursor cursor = property.newCursor()) {
                    String hidden = cursor.getAttributeText(new javax.xml.namespace.QName("hidden"));
                    if ("true".equals(hidden) || "1".equals(hidden)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void parseSpreadsheet(byte[] content, String extension, ChunkCollector chunks) throws IOException {
        boolean xlsx = "xlsx".equals(extension);
        if (xlsx) {
            validateOfficeZip(content, "XLSX", "xl/workbook.xml");
        } else {
            validateOle(content, "XLS", "Workbook", "Book");
        }
        try (Workbook workbook = xlsx
                ? new XSSFWorkbook(new ByteArrayInputStream(content))
                : new HSSFWorkbook(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() > MAX_WORKSHEETS) {
                throw new ParsingException("Excel 不能超过 50 张工作表，请拆分后上传");
            }
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            formatter.setUseCachedValuesForFormulaCells(true);
            int rows = 0;
            int cells = 0;
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                boolean sheetVisible = !workbook.isSheetHidden(index) && !workbook.isSheetVeryHidden(index);
                for (Row row : sheet) {
                    if (++rows > MAX_SPREADSHEET_ROWS) {
                        throw new ParsingException("Excel 实际行数量超过 50000，请拆分后上传");
                    }
                    for (Cell cell : row) {
                        if (++cells > MAX_SPREADSHEET_CELLS) {
                            throw new ParsingException("Excel 实际单元格数量超过 100000，请拆分后上传");
                        }
                        if (!sheetVisible || row.getZeroHeight() || sheet.isColumnHidden(cell.getColumnIndex())) {
                            continue;
                        }
                        validateCachedFormula(cell, sheet.getSheetName());
                        // No FormulaEvaluator: never execute workbook formulas or resolve external links.
                        String text = formatter.formatCellValue(cell);
                        if (!text.isBlank()) {
                            chunks.addCell(cell.getAddress().formatAsString() + ": " + text,
                                    sheet.getSheetName(), cell.getRowIndex(), cell.getColumnIndex());
                        }
                    }
                }
            }
        }
    }

    private void validateCachedFormula(Cell cell, String sheetName) {
        if (cell.getCellType() == CellType.FORMULA && cell instanceof XSSFCell xssf
                && (!xssf.getCTCell().isSetV() || (cell.getCachedFormulaResultType() == CellType.NUMERIC
                && xssf.getCTCell().getV().isBlank()))) {
            throw new ParsingException("Excel 公式缺少已保存的计算结果（" + sheetName + "!"
                    + cell.getAddress().formatAsString() + "），请在 Excel/WPS 中重新计算并保存后上传");
        }
        // BIFF/XLS always stores a formula result, and cannot distinguish an uncomputed zero from
        // a genuine zero. Treat the saved result as authoritative; users should recalculate first.
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

    private void validateOfficeZip(byte[] content, String type, String documentEntry) throws IOException {
        rejectEncryptedOfficePackage(content);
        if (!startsWith(content, "PK\u0003\u0004")) {
            throw new ParsingException(type + " 文件内容与扩展名不一致");
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
                    throw new ParsingException(type + " 内部文件过多，请简化资料后上传");
                }
                long entryBytes = 0;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    entryBytes += count;
                    totalBytes += count;
                    if (entryBytes > MAX_ZIP_ENTRY_BYTES || totalBytes > MAX_ZIP_TOTAL_BYTES) {
                        throw new ParsingException(type + " 解压内容过大，请按章节拆分后上传");
                    }
                }
                hasTypes |= "[Content_Types].xml".equals(entry.getName());
                hasDocument |= documentEntry.equals(entry.getName());
                zip.closeEntry();
            }
        }
        if (!hasTypes || !hasDocument) {
            throw new ParsingException("文件不是有效的 " + type + " 文档");
        }
        // Apache POI's own ZIP inflation-ratio protections remain enabled as a second boundary.
    }

    private void validateOle(byte[] content, String type, String... documentEntries) throws IOException {
        if (!isOle(content)) {
            throw new ParsingException(type + " 文件内容与扩展名不一致");
        }
        try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(content))) {
            if (filesystem.getRoot().hasEntry("EncryptedPackage")) {
                throw new ParsingException("不支持加密 Office 文件，请先解密并另存后上传");
            }
            for (String entry : documentEntries) {
                if (filesystem.getRoot().hasEntry(entry)) {
                    return;
                }
            }
            throw new ParsingException("文件不是有效的 " + type + " 文档，内容与扩展名不一致");
        }
    }

    private void rejectEncryptedOfficePackage(byte[] content) throws IOException {
        if (isOle(content)) {
            try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(content))) {
                if (filesystem.getRoot().hasEntry("EncryptedPackage")) {
                    throw new ParsingException("不支持加密 Office 文件，请先解密并另存后上传");
                }
            }
        }
    }

    private boolean isOle(byte[] content) {
        return startsWith(content, "\u00d0\u00cf\u0011\u00e0\u00a1\u00b1\u001a\u00e1");
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
                              Integer paragraphEnd, String heading, Integer slideNumber,
                              String sheetName, String cellRange) {
        public ParsedChunk(String text, Integer pageNumber, Integer paragraphStart,
                           Integer paragraphEnd, String heading) {
            this(text, pageNumber, paragraphStart, paragraphEnd, heading, null, null, null);
        }
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

    private static class PresentationBudget {
        private int shapes;
        private long cells;
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
        private Integer slide;
        private String sheet;
        private CellRangeAddress cells;
        private int extractedChars;

        void add(String rawText, Integer pageNumber, Integer paragraph, String currentHeading) {
            add(rawText, pageNumber, paragraph, currentHeading, null, null, null);
        }

        void addSlide(String rawText, int slideNumber) {
            add(rawText, null, null, null, slideNumber, null, null);
        }

        void addCell(String rawText, String sheetName, int row, int column) {
            // A split cell keeps a unique source address on every part. Do not merge its tail
            // with a later cell, which would make an unlabelled continuation ambiguous.
            boolean longCell = rawText.length() > MAX_CHUNK_CHARS;
            if (longCell) {
                flush();
            }
            add(rawText, null, null, null, null, sheetName,
                    new CellRangeAddress(row, row, column, column));
            if (longCell) {
                flush();
            }
        }

        private void add(String rawText, Integer pageNumber, Integer paragraph, String currentHeading,
                         Integer slideNumber, String sheetName, CellRangeAddress cell) {
            if (rawText == null) {
                return;
            }
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
                        || !java.util.Objects.equals(slide, slideNumber)
                        || !java.util.Objects.equals(sheet, sheetName)
                        || current.length() + 1 + part.length() > MAX_CHUNK_CHARS)) {
                    flush();
                }
                if (current.isEmpty()) {
                    page = pageNumber;
                    startParagraph = paragraph;
                    heading = currentHeading;
                    slide = slideNumber;
                    sheet = sheetName;
                    cells = cell == null ? null : cell.copy();
                } else {
                    current.append('\n');
                }
                current.append(part);
                endParagraph = paragraph;
                if (cell != null) {
                    cells.setFirstRow(Math.min(cells.getFirstRow(), cell.getFirstRow()));
                    cells.setLastRow(Math.max(cells.getLastRow(), cell.getLastRow()));
                    cells.setFirstColumn(Math.min(cells.getFirstColumn(), cell.getFirstColumn()));
                    cells.setLastColumn(Math.max(cells.getLastColumn(), cell.getLastColumn()));
                }
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
                result.add(new ParsedChunk(current.toString(), page, startParagraph, endParagraph, heading,
                        slide, sheet, cells == null ? null : cells.formatAsString()));
                current.setLength(0);
            }
        }

        List<ParsedChunk> finish() {
            flush();
            return List.copyOf(result);
        }
    }
}
