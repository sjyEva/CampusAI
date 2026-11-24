package edu.campusai.rag;

import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTable;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Developer-authored fixtures, not teacher course material or measured results. */
public final class OfficeSampleFiles {
    static final String SOURCE_TEXT = "引用定位保留资料名与原文位置，支持核对检索依据。";
    static final String FIRST_SHEET = "课程资料";
    static final String SECOND_SHEET = "发布流程";

    private OfficeSampleFiles() {}

    public static byte[] create(String extension) throws Exception {
        return switch (extension) {
            case "ppt" -> oldPresentation();
            case "pptx" -> presentation();
            case "xls" -> workbook(false);
            case "xlsx" -> workbook(true);
            default -> throw new IllegalArgumentException("Unknown fixture format: " + extension);
        };
    }

    private static byte[] presentation() throws Exception {
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            show.setPageSize(new Dimension(960, 540));
            XSLFSlide first = show.createSlide();
            xText(first, "CampusAI 课程知识助手", 45, 45, 870, 65, 30.0);
            xText(first, "开发者自编资料 · 仅用于格式解析、检索和来源演示\n教师上传课程资料，解析成功并发布后，学生才能检索。", 45, 145, 870, 200, 22.0);
            XSLFSlide second = show.createSlide();
            xText(second, "来源核对流程", 45, 45, 870, 65, 30.0);
            XSLFTable table = second.createTable(3, 2);
            table.setAnchor(new Rectangle2D.Double(45, 145, 870, 240));
            table.setColumnWidth(0, 160);
            table.setColumnWidth(1, 710);
            String[][] content = tableContent();
            for (int row = 0; row < content.length; row++) {
                table.setRowHeight(row, 80);
                for (int col = 0; col < content[row].length; col++) {
                    table.getCell(row, col).setText(content[row][col]);
                    table.getCell(row, col).getTextParagraphs().forEach(p -> p.getTextRuns().forEach(r -> {
                        r.setFontFamily("Microsoft YaHei");
                        r.setFontSize(20.0);
                    }));
                }
            }
            show.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] oldPresentation() throws Exception {
        try (HSLFSlideShow show = new HSLFSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            show.setPageSize(new Dimension(960, 540));
            HSLFSlide first = show.createSlide();
            hText(first, "CampusAI 课程知识助手", 45, 45, 870, 65, 30.0);
            hText(first, "开发者自编资料 · 仅用于格式解析、检索和来源演示\n教师上传课程资料，解析成功并发布后，学生才能检索。", 45, 145, 870, 200, 22.0);
            HSLFSlide second = show.createSlide();
            hText(second, "来源核对流程", 45, 45, 870, 65, 30.0);
            HSLFTable table = second.createTable(3, 2);
            table.setAnchor(new Rectangle2D.Double(45, 145, 870, 240));
            table.setColumnWidth(0, 160);
            table.setColumnWidth(1, 710);
            String[][] content = tableContent();
            for (int row = 0; row < content.length; row++) {
                table.setRowHeight(row, 80);
                for (int col = 0; col < content[row].length; col++) {
                    table.getCell(row, col).setText(content[row][col]);
                    table.getCell(row, col).getTextParagraphs().forEach(p -> p.getTextRuns().forEach(r -> {
                        r.setFontFamily("Microsoft YaHei");
                        r.setFontSize(20.0);
                    }));
                }
            }
            show.write(output);
            return output.toByteArray();
        }
    }

    private static String[][] tableContent() {
        return new String[][] {
                {"功能", "说明"},
                {"引用定位", SOURCE_TEXT},
                {"模型回退", "模型不可用时保留相关课程原文和来源，不编造答案。"}
        };
    }

    private static void xText(XSLFSlide slide, String text, int x, int y, int width, int height, double size) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(x, y, width, height));
        box.setText(text);
        box.getTextParagraphs().forEach(p -> p.getTextRuns().forEach(r -> {
            r.setFontFamily("Microsoft YaHei");
            r.setFontSize(size);
        }));
    }

    private static void hText(HSLFSlide slide, String text, int x, int y, int width, int height, double size) {
        HSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(x, y, width, height));
        box.setText(text);
        box.getTextParagraphs().forEach(p -> p.getTextRuns().forEach(r -> {
            r.setFontFamily("Microsoft YaHei");
            r.setFontSize(size);
        }));
    }

    private static byte[] workbook(boolean modern) throws Exception {
        try (Workbook book = modern ? new XSSFWorkbook() : new HSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet first = book.createSheet(FIRST_SHEET);
            first.createRow(0).createCell(0).setCellValue("CampusAI 开发者自编样例；数字仅用于格式解析，不代表评测结果。");
            first.createRow(2).createCell(1).setCellValue(SOURCE_TEXT);
            first.createRow(4).createCell(1).setCellValue("示例计算输入");
            first.getRow(4).createCell(3).setCellValue(1);
            first.getRow(4).createCell(5).setCellValue(4);
            first.createRow(6).createCell(1).setCellValue("示例百分比，预期显示 25.00%");
            Cell ratio = first.getRow(6).createCell(5);
            ratio.setCellFormula("D5/F5");
            CellStyle percentage = book.createCellStyle();
            percentage.setDataFormat(book.createDataFormat().getFormat("0.00%"));
            ratio.setCellStyle(percentage);

            Sheet second = book.createSheet(SECOND_SHEET);
            second.createRow(0).createCell(0).setCellValue("上传、解析、发布和检索是不同阶段。");
            second.createRow(4).createCell(2).setCellValue("解析失败的资料不能发布；学生只能读取已加入课程中的已发布资料。");
            for (Sheet sheet : List.of(first, second)) {
                sheet.setColumnWidth(0, 24 * 256);
                sheet.setColumnWidth(1, 45 * 256);
                sheet.setColumnWidth(2, 45 * 256);
                sheet.setColumnWidth(3, 15 * 256);
                sheet.setColumnWidth(5, 25 * 256);
            }
            // Fixture authoring computes and saves the result once. Production parsing never evaluates it.
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            book.write(output);
            return output.toByteArray();
        }
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length == 0 ? "../docs/rag/samples" : args[0]);
        Files.createDirectories(directory);
        for (String extension : List.of("ppt", "pptx", "xls", "xlsx")) {
            Path file = directory.resolve("parsing-demo." + extension);
            Files.write(file, create(extension));
            System.out.println(file + " (" + Files.size(file) + " bytes)");
        }
    }
}
