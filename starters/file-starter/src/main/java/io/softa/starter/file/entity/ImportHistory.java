package io.softa.starter.file.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.file.enums.ImportRule;
import io.softa.starter.file.enums.ImportStatus;
import io.softa.starter.file.enums.ImportType;

/**
 * ImportHistory Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(multiTenant = true, copyable = false)
@Index(fields = {"tenantId", "createdTime"})
public class ImportHistory extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Template ID", fieldType = FieldType.MANY_TO_ONE, relatedModel = ImportTemplate.class)
    private Long templateId;

    @Field
    private String modelName;

    @Field(label = "Original File ID", fieldType = FieldType.FILE)
    private Long originalFileId;

    @Field
    private ImportType importType;

    @Field
    private ImportRule importRule;

    @Field(label = "Import Status")
    private ImportStatus status;

    /**
     * The result file of the run: every row of the uploaded file with its outcome and, for a failed
     * row, the reason. Written for every import and every validation run, not only failed ones.
     *
     * <p>The field keeps its earlier name, from when only failed rows were written back, so stored
     * rows and API consumers carry over unchanged; the label says what it holds now.
     */
    @Field(label = "Import Result File", fieldType = FieldType.FILE)
    private Long failedFileId;

    @Field
    private Integer totalRows;

    @Field
    private Integer successRows;

    @Field
    private Integer failedRows;

    @Field(label = "Duration in seconds")
    private Double duration;

    @Field(length = 1000)
    private String errorMessage;

    @Field
    private Boolean deleted;

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    /**
     * Set error message, truncating to {@value MAX_ERROR_MESSAGE_LENGTH} characters if it exceeds the limit.
     *
     * @param errorMessage the error message
     */
    public void setErrorMessage(String errorMessage) {
        if (errorMessage != null && errorMessage.length() > MAX_ERROR_MESSAGE_LENGTH) {
            this.errorMessage = errorMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH);
        } else {
            this.errorMessage = errorMessage;
        }
    }
}
