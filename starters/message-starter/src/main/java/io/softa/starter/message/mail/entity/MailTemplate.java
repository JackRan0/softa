package io.softa.starter.message.mail.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.enums.MailPriority;

/**
 * Email template.
 * <p>
 * Templates are identified by {@code code}. Resolution prefers a tenant
 * template, falling back to the platform template:
 * <ol>
 *   <li>Tenant template for {@code code}</li>
 *   <li>Platform template (tenant_id = 0) for {@code code}</li>
 * </ol>
 * tenant_id = 0  — platform-level, shared across all tenants.
 * tenant_id > 0  — tenant-level, ORM auto-fills and isolates.
 */
@Data
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        businessKey = {"code"},
        multiTenant = true
)
@Index(indexName = "uk_mail_template_tenant_code", fields = {"tenantId", "code"}, unique = true)
@EqualsAndHashCode(callSuper = true)
public class MailTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "0 = platform-level (shared across tenants); >0 = tenant-level. "
                    + "Auto-stamped by the ORM on writes when multi-tenancy is enabled.")
    private Long tenantId;

    @Field(required = true, length = 100, copyable = false,
            description = "Unique template code used for programmatic lookup, e.g. USER_WELCOME")
    private String code;

    @Field(required = true, length = 100, description = "Display name")
    private String name;

    @Field(length = 500, description = "Description")
    private String description;

    @Field(length = 500,
            description = "Email subject template, supports {{ variable }} placeholders")
    private String subject;

    @Field(label = "Body HTML",
            description = "HTML body template, supports {{ variable }} placeholders. "
                    + "Required for HTML / HTML_WITH_DERIVED_PLAIN / HTML_WITH_AUTHORED_PLAIN modes.")
    private String bodyHtml;

    /**
     * Front-end note: expose an "extract from HTML" button that populates this field
     * for HTML_WITH_AUTHORED_PLAIN; the operator may then edit the result.
     */
    @Field(description = "Plain-text body template, supports {{ variable }} placeholders. "
                    + "Required for PLAIN and HTML_WITH_AUTHORED_PLAIN modes. "
                    + "Ignored when bodyMode is HTML or HTML_WITH_DERIVED_PLAIN — there the plain "
                    + "part is absent or auto-derived from bodyHtml at send time.")
    private String bodyText;

    /**
     * HTML_WITH_AUTHORED_PLAIN with an empty bodyText falls back to derivation at send
     * time; the send record is stamped DERIVED so audit can tell the fallback fired.
     */
    @Field(required = true,
            description = "Body shape for this template. "
                    + "HTML — bodyHtml only (text/html). "
                    + "PLAIN — bodyText only (text/plain). "
                    + "HTML_WITH_DERIVED_PLAIN — bodyHtml only, multipart/alternative with the "
                    + "plain part derived at send time. "
                    + "HTML_WITH_AUTHORED_PLAIN — bodyHtml + bodyText, multipart/alternative.")
    private BodyMode bodyMode;

    @Field(description = "Whether this template is active")
    private Boolean isEnabled;

    @Field(description = "Default email priority for this template. "
                    + "When set, all emails sent via this template will use this priority unless overridden in SendMailDTO.")
    private MailPriority defaultPriority;

    @Field(length = 255,
            description = "Default Reply-To address for this template. Optional. "
                    + "Resolution chain at send time: dto.replyTo > template.replyTo > config.replyToAddress.")
    private String replyTo;

    @Field(fieldType = FieldType.MULTI_FILE,
            description = "Default attachments — files automatically attached to every email "
                    + "rendered from this template (e.g. compliance disclosures, branded brochures). "
                    + "Used only when SendMailDTO.attachments is empty; caller-supplied attachments "
                    + "override the template default entirely.")
    private List<FileInfo> attachments;

    @Field(label = "Preferred Server Config ID",
            description = "Preferred send server config. Resolution chain at send time: "
                    + "SendMailDTO.serverConfigId > MailTemplate.preferredServerConfigId > "
                    + "MailServerDispatcher default (tenant default → platform default). "
                    + "Routes template categories through dedicated SMTP "
                    + "(marketing / transactional / compliance).")
    private Long preferredServerConfigId;
}
