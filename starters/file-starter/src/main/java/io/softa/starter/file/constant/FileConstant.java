package io.softa.starter.file.constant;

import org.apache.poi.ss.usermodel.IndexedColors;

/**
 * Constants for file starter.
 */
public interface FileConstant {

    String DEFAULT_EXCEL_FONT_NAME = "Calibri";

    int DEFAULT_EXCEL_COLUMN_WIDTH = 24;
    short DEFAULT_EXCEL_HEAD_ROW_HEIGHT = 22;
    short DEFAULT_EXCEL_BODY_FONT_SIZE = 11;
    short DEFAULT_EXCEL_HEAD_FONT_SIZE = 12;

    short DEFAULT_EXCEL_HEAD_FONT_COLOR = IndexedColors.WHITE.getIndex();
    short DEFAULT_EXCEL_HEAD_BACKGROUND_COLOR = IndexedColors.DARK_TEAL.getIndex();

    // ── Import sheets (templates and result files) ──

    /** Header row height of an import sheet, in points: two wrapped lines of the bold 11pt header. */
    float IMPORT_HEAD_ROW_HEIGHT = 32f;
    /** Height of one wrapped line of an 11pt body cell, in points: a result row grows by this per line. */
    float IMPORT_RESULT_LINE_HEIGHT = 15f;
    short IMPORT_HEAD_FONT_SIZE = 11;
    /** Column width bounds of an import sheet, in characters; the width follows the header text. */
    int IMPORT_MIN_COLUMN_WIDTH = 14;
    int IMPORT_MAX_COLUMN_WIDTH = 40;

    /** Prefix of a required column's header comment. */
    String IMPORT_REQUIRED_NOTE = "Required.";

    String IMPORT_HEAD_TEXT_COLOR = "1B1F24";
    String IMPORT_OPTIONAL_HEAD_FILL = "EEF1F5";
    String IMPORT_OPTIONAL_HEAD_BORDER = "9AA4B2";
    String IMPORT_REQUIRED_HEAD_FILL = "FFE7C2";
    String IMPORT_REQUIRED_HEAD_BORDER = "D98A1C";
    String IMPORT_RESULT_HEAD_FILL = "5B6B80";
    String IMPORT_RESULT_HEAD_BORDER = "3D4859";
    String IMPORT_RESULT_HEAD_TEXT_COLOR = "FFFFFF";
    String IMPORT_GRID_BORDER = "DDE1E6";
    String IMPORT_BODY_FILL = "FFFFFF";
    String IMPORT_SUCCESS_TEXT_COLOR = "1E7B34";
    String IMPORT_FAILED_TEXT_COLOR = "B42318";
    String IMPORT_FAILED_REASON_FILL = "FDECEC";

    // ── Import result file ──

    /** Suffix of the result file of an import, and the name of its sheet. */
    String IMPORT_RESULT = "Import Result";
    /** Suffix of the result file of a validation-only run, and the name of its sheet. */
    String VALIDATION_RESULT = "Validation Result";
    /** Header (and row key) of the per-row outcome column of a result file. */
    String RESULT = "Result";
    String RESULT_SUCCESS = "Success";
    String RESULT_FAILED = "Failed";
}
