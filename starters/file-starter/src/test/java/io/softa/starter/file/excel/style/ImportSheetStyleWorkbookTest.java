package io.softa.starter.file.excel.style;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.handler.WriteHandler;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.apache.fesod.sheet.write.handler.SheetWriteHandler;
import org.apache.fesod.sheet.write.handler.context.SheetWriteHandlerContext;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.excel.export.support.ExcelWriterFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import sheet look, read back out of real bytes written through {@link ExcelWriterFactory} — the
 * shared handlers it registers style every cell first, and this handler has to win over them.
 */
class ImportSheetStyleWorkbookTest {

    private static ImportFieldDTO field(String header, boolean required, String description) {
        ImportFieldDTO field = new ImportFieldDTO();
        field.setHeader(header);
        field.setFieldName(header);
        field.setRequired(required);
        field.setDescription(description);
        return field;
    }

    private static final List<ImportFieldDTO> FIELDS = List.of(
            field("Name", true, "Full legal name."),
            field("Employee Code", true, "Required. Unique per company."),
            field("A rather long column header that goes well past the widest a column may be", false, "  "),
            field("Nickname", false, "Shown in the directory."));

    private static List<String> headers(List<ImportFieldDTO> fields) {
        return fields.stream().map(ImportFieldDTO::getHeader).toList();
    }

    private static XSSFSheet write(List<String> headers, List<List<Object>> rows, WriteHandler... handlers) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = FesodSheet.write(out).build()) {
            WriteSheet sheet = new ExcelWriterFactory()
                    .createSheetBuilder(0, "Sheet", headers.stream().map(List::of).toList(), handlers)
                    .build();
            writer.write(rows, sheet);
            writer.finish();
        }
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray())).getSheetAt(0);
    }

    private static String rgb(XSSFColor color) {
        return color == null ? null : color.getARGBHex().substring(2);
    }

    private static XSSFCellStyle styleAt(XSSFSheet sheet, int row, int column) {
        return sheet.getRow(row).getCell(column).getCellStyle();
    }

    // ───────────────────────── template ─────────────────────────

    @Test
    void requiredAndOptionalHeadersAreLightWithDarkBoldText() throws Exception {
        XSSFSheet sheet = write(headers(FIELDS), List.of(), ImportSheetStyleHandler.forTemplate(FIELDS));

        XSSFCellStyle required = styleAt(sheet, 0, 0);
        assertThat(rgb(required.getFillForegroundColorColor())).isEqualTo("FFE7C2");
        assertThat(required.getBorderBottom()).isEqualTo(BorderStyle.MEDIUM);
        assertThat(rgb(required.getBottomBorderXSSFColor())).isEqualTo("D98A1C");
        assertThat(required.getFont().getBold()).isTrue();
        assertThat(rgb(required.getFont().getXSSFColor())).isEqualTo("1B1F24");
        assertThat(required.getFont().getFontName()).isEqualTo("Calibri");
        assertThat(required.getFont().getFontHeightInPoints()).isEqualTo((short) 11);
        assertThat(required.getWrapText()).isTrue();

        XSSFCellStyle optional = styleAt(sheet, 0, 3);
        assertThat(rgb(optional.getFillForegroundColorColor())).isEqualTo("EEF1F5");
        assertThat(optional.getBorderBottom()).isEqualTo(BorderStyle.MEDIUM);
        assertThat(rgb(optional.getBottomBorderXSSFColor())).isEqualTo("9AA4B2");
        assertThat(rgb(optional.getFont().getXSSFColor())).isEqualTo("1B1F24");

        // The text is what the importer matches columns by — no marker added.
        assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Name");
        assertThat(sheet.getRow(0).getHeightInPoints()).isEqualTo(32f);
    }

    @Test
    void descriptionsGoIntoHeaderCommentsAndRequiredOnesSaySo() throws Exception {
        XSSFSheet sheet = write(headers(FIELDS), List.of(), ImportSheetStyleHandler.forTemplate(FIELDS));

        assertThat(sheet.getRow(0).getCell(0).getCellComment().getString().getString())
                .isEqualTo("Required. Full legal name.");
        assertThat(sheet.getRow(0).getCell(1).getCellComment().getString().getString())
                .as("not doubled when the description already says it")
                .isEqualTo("Required. Unique per company.");
        assertThat(sheet.getRow(0).getCell(2).getCellComment())
                .as("an optional column with nothing to say gets no comment").isNull();
        assertThat(sheet.getRow(0).getCell(3).getCellComment().getString().getString())
                .isEqualTo("Shown in the directory.");
    }

    @Test
    void commentTextRules() {
        assertThat(ImportSheetStyleHandler.commentText(true, null)).isEqualTo("Required.");
        assertThat(ImportSheetStyleHandler.commentText(true, "required. Lower case")).isEqualTo("required. Lower case");
        assertThat(ImportSheetStyleHandler.commentText(false, null)).isNull();
        assertThat(ImportSheetStyleHandler.commentText(false, " Text ")).isEqualTo("Text");
    }

    @Test
    void theHeaderRowFreezesAndIsFiltered() throws Exception {
        XSSFSheet sheet = write(headers(FIELDS), List.of(), ImportSheetStyleHandler.forTemplate(FIELDS));

        PaneInformation pane = sheet.getPaneInformation();
        assertThat(pane.isFreezePane()).isTrue();
        assertThat(pane.getVerticalSplitPosition()).as("no column freezes").isEqualTo((short) 0);
        assertThat(pane.getHorizontalSplitPosition()).isEqualTo((short) 1);
        assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:D1");
    }

    @Test
    void columnsAreSizedFromTheirHeaderWithinBounds() throws Exception {
        XSSFSheet sheet = write(headers(FIELDS), List.of(), ImportSheetStyleHandler.forTemplate(FIELDS));

        assertThat(sheet.getColumnWidth(0)).as("short header: the minimum").isEqualTo(14 * 256);
        assertThat(sheet.getColumnWidth(1)).as("bold text and the filter button")
                .isEqualTo(((int) Math.ceil("Employee Code".length() * 1.2) + 5) * 256);
        assertThat(sheet.getWorkbook().getFontAt(0).getFontName()).as("typed cells use the body font").isEqualTo("Calibri");
        assertThat(sheet.getColumnWidth(2)).as("long header: the maximum").isEqualTo(40 * 256);
    }

    @Test
    void theEmptyDataAreaCarriesALightGrid() throws Exception {
        XSSFSheet sheet = write(headers(FIELDS), List.of(), ImportSheetStyleHandler.forTemplate(FIELDS));

        XSSFCellStyle column = (XSSFCellStyle) sheet.getColumnStyle(0);
        assertThat(column.getBorderLeft()).isEqualTo(BorderStyle.THIN);
        assertThat(rgb(column.getLeftBorderXSSFColor())).isEqualTo("DDE1E6");
        assertThat(rgb(column.getFillForegroundColorColor())).isEqualTo("FFFFFF");
    }

    @Test
    void aColumnThatAlreadyHasAFormatKeepsItUnderTheGrid() throws Exception {
        // A date column's default carries its format (set by the temporal column handler, registered
        // before this one); the grid is added to a copy of that style, not put in its place.
        SheetWriteHandler dateColumn = new SheetWriteHandler() {
            @Override
            public void afterSheetCreate(SheetWriteHandlerContext context) {
                Sheet sheet = context.getWriteSheetHolder().getSheet();
                CellStyle style = sheet.getWorkbook().createCellStyle();
                style.setDataFormat(sheet.getWorkbook().createDataFormat().getFormat("yyyy-MM-dd"));
                sheet.setDefaultColumnStyle(3, style);
            }
        };
        XSSFSheet sheet = write(headers(FIELDS), List.of(), dateColumn, ImportSheetStyleHandler.forTemplate(FIELDS));

        CellStyle column = sheet.getColumnStyle(3);
        assertThat(column.getDataFormatString()).isEqualTo("yyyy-MM-dd");
        assertThat(column.getBorderBottom()).isEqualTo(BorderStyle.THIN);
    }

    @Test
    void aCompanionSheetSharesTheHeaderLookButNotTheMainSheetExtras() throws Exception {
        XSSFSheet sheet = write(headers(FIELDS), List.of(List.of("a", "b", "c", "d")),
                ImportSheetStyleHandler.forCompanionSheet(FIELDS));

        assertThat(rgb(styleAt(sheet, 0, 0).getFillForegroundColorColor())).isEqualTo("FFE7C2");
        assertThat(sheet.getRow(0).getCell(0).getCellComment()).isNull();
        assertThat(sheet.getPaneInformation()).isNull();
        assertThat(styleAt(sheet, 1, 0).getBorderTop()).isEqualTo(BorderStyle.THIN);
    }

    @Test
    void wrappedLinesCountWideCharactersTwiceAndBreakOnNewlines() {
        assertThat(ImportSheetStyleHandler.wrappedLines("a".repeat(38), 38)).isEqualTo(1);
        assertThat(ImportSheetStyleHandler.wrappedLines("a".repeat(39), 38)).isEqualTo(2);
        assertThat(ImportSheetStyleHandler.wrappedLines("字".repeat(20), 38)).as("40 units wide").isEqualTo(2);
        assertThat(ImportSheetStyleHandler.wrappedLines("a\nb", 38)).isEqualTo(2);
    }

    // ───────────────────────── result file ─────────────────────────

    @Test
    void theResultColumnsStandApartAndEachOutcomeIsColoured() throws Exception {
        List<ImportFieldDTO> fields = List.of(field("Employee Code", true, "Key."), field("Name", false, null));
        List<String> headers = new ArrayList<>(headers(fields));
        headers.add("Result");
        headers.add("Failed Reason");
        List<List<Object>> rows = List.of(
                List.of("E1", "Ann", "Success", ""),
                List.of("E2", "Bob", "Failed", "Name is too long"));
        XSSFSheet sheet = write(headers, rows,
                ImportSheetStyleHandler.forResult(fields, "Result", "Failed Reason", "Success", "Failed", rows.size()));

        // Template columns keep the template look.
        assertThat(rgb(styleAt(sheet, 0, 0).getFillForegroundColorColor())).isEqualTo("FFE7C2");
        assertThat(rgb(styleAt(sheet, 0, 1).getFillForegroundColorColor())).isEqualTo("EEF1F5");
        // The two result columns: dark fill, white bold text.
        for (int column : new int[]{2, 3}) {
            XSSFCellStyle head = styleAt(sheet, 0, column);
            assertThat(rgb(head.getFillForegroundColorColor())).isEqualTo("5B6B80");
            assertThat(rgb(head.getFont().getXSSFColor())).isEqualTo("FFFFFF");
            assertThat(head.getFont().getBold()).isTrue();
        }
        assertThat(sheet.getRow(0).getCell(0).getCellComment()).as("comments are a template thing").isNull();

        XSSFCell success = sheet.getRow(1).getCell(2);
        assertThat(success.getStringCellValue()).isEqualTo("Success");
        assertThat(rgb(success.getCellStyle().getFont().getXSSFColor())).isEqualTo("1E7B34");
        assertThat(rgb(styleAt(sheet, 1, 3).getFillForegroundColorColor())).isEqualTo("FFFFFF");

        XSSFCell failed = sheet.getRow(2).getCell(2);
        assertThat(rgb(failed.getCellStyle().getFont().getXSSFColor())).isEqualTo("B42318");
        assertThat(rgb(styleAt(sheet, 2, 3).getFillForegroundColorColor())).isEqualTo("FDECEC");

        // Body cells: white, thin light grid.
        XSSFCellStyle body = styleAt(sheet, 1, 0);
        assertThat(body.getBorderBottom()).isEqualTo(BorderStyle.THIN);
        assertThat(rgb(body.getBottomBorderXSSFColor())).isEqualTo("DDE1E6");
        assertThat(body.getFont().getFontName()).isEqualTo("Calibri");

        assertThat(sheet.getPaneInformation().getVerticalSplitPosition()).isEqualTo((short) 0);
        assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
        assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:D3");
        assertThat(sheet.getColumnWidth(3)).as("room for the reason").isEqualTo(40 * 256);
    }

    @Test
    void aLongFailureReasonWrapsAndItsRowGrowsToShowIt() throws Exception {
        List<ImportFieldDTO> fields = List.of(field("Employee Code", true, "Key."));
        List<String> headers = new ArrayList<>(headers(fields));
        headers.add("Result");
        headers.add("Failed Reason");
        String longReason = "The field `Gender` is required; The field `Race` is required when Residence Status is "
                + "Singaporean or PR; The field `Religion` is required when Residence Status is Singaporean or PR";
        List<List<Object>> rows = List.of(
                List.of("E1", "Failed", "Name is too long"),
                List.of("E2", "Failed", longReason));
        XSSFSheet sheet = write(headers, rows,
                ImportSheetStyleHandler.forResult(fields, "Result", "Failed Reason", "Success", "Failed", rows.size()));

        assertThat(styleAt(sheet, 2, 2).getWrapText()).isTrue();
        assertThat(styleAt(sheet, 2, 0).getVerticalAlignment()).as("the row's other cells stay level")
                .isEqualTo(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
        int lines = (int) Math.ceil(longReason.length() / 38.0);
        assertThat(sheet.getRow(2).getHeightInPoints()).isEqualTo(lines * 15f);
        assertThat(sheet.getRow(1).getHeightInPoints()).as("a one-line reason keeps the default height")
                .isLessThan(30f);
    }

    @Test
    void stylesAreSharedNotCreatedPerCell() throws Exception {
        List<ImportFieldDTO> fields = List.of(field("Code", true, null));
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            rows.add(List.of("C" + i, i % 2 == 0 ? "Success" : "Failed", i % 2 == 0 ? "" : "bad"));
        }
        XSSFSheet sheet = write(List.of("Code", "Result", "Failed Reason"), rows,
                ImportSheetStyleHandler.forResult(fields, "Result", "Failed Reason", "Success", "Failed", rows.size()));

        assertThat(sheet.getWorkbook().getNumCellStyles()).isLessThan(40);
    }
}
