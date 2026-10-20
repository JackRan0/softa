-- ============================================================
-- message-starter DDL — PostgreSQL. GENERATED from the @Model annotations,
-- do not hand-edit. The MySQL counterpart (message-starter.sql) is the
-- hand-written original and is intentionally left untouched.
--
-- Source of truth is the entity model, never this file. To regenerate, render the
-- @Model/@Field/@Index annotations through the PostgreSQL DDL dialect:
--   ClasspathScannerSupport -> AnnotationParser.parse
--     -> ReferenceColumnResolver.stampSysFields   (TO_ONE FKs mirror the referenced id)
--     -> SysDdlContextBuilder.forCreate
--     -> DdlDialectFactory.create(DatabaseType.POSTGRESQL, BuiltinDdlMetadataResolver.INSTANCE)
-- See apps/demo-app/src/test/java/io/softa/app/metadata/MetadataBaselineDdlGeneratorTest
-- for the same chain against MySQL. CREATE TABLE / CREATE INDEX are patched to
-- IF NOT EXISTS after rendering (the template stays untouched -- it also drives
-- runtime auto-DDL).
--
-- Not read at boot when scanner-scope is non-empty — the annotation
-- lane creates these tables itself. This file exists for runtimes
-- with an empty scanner-scope (production) and for DBA review.
-- ============================================================

-- DeadLetterMessage
/* Create table for model: Dead Letter Message */
CREATE TABLE IF NOT EXISTS dead_letter_message (
    id BIGINT NOT NULL,
    source_tenant_id BIGINT,
    source VARCHAR(64),
    original_topic VARCHAR(255),
    dlq_topic VARCHAR(255),
    subscription_name VARCHAR(255),
    event_type VARCHAR(100),
    event_id BIGINT,
    payload TEXT,
    status VARCHAR(64),
    last_error_msg VARCHAR(64),
    resolved_remark VARCHAR(64),
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN dead_letter_message.source_tenant_id IS 'Tenant that owned the original send/outbox row';
COMMENT ON COLUMN dead_letter_message.source IS 'Source: BrokerPoison (Pulsar DLQ) or SendExhausted (mail/sms retry exhausted)';
COMMENT ON COLUMN dead_letter_message.payload IS 'Archived message payload';
COMMENT ON COLUMN dead_letter_message.status IS 'Processing status of this dead-letter row';
COMMENT ON COLUMN dead_letter_message.last_error_msg IS 'Last error captured when archiving / resolving';
COMMENT ON COLUMN dead_letter_message.resolved_remark IS 'Operator note when marking resolved';

-- InboxNotification
/* Create table for model: Inbox Notification */
CREATE TABLE IF NOT EXISTS inbox_notification (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    recipient_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL DEFAULT '',
    content VARCHAR(64),
    notification_type VARCHAR(64),
    source_model VARCHAR(50),
    source_id BIGINT,
    is_read BOOLEAN,
    read_at TIMESTAMP,
    expired_at TIMESTAMP,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN inbox_notification.tenant_id IS '0 = platform-level (shared across tenants); >0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.';
COMMENT ON COLUMN inbox_notification.content IS 'Notification body content';
COMMENT ON COLUMN inbox_notification.notification_type IS 'Source category: System, Workflow, or Manual';
COMMENT ON COLUMN inbox_notification.source_model IS 'Source metadata model name (matches SysModel.modelName), e.g. FlowInstance (nullable)';
COMMENT ON COLUMN inbox_notification.source_id IS 'Source object ID (nullable)';
COMMENT ON COLUMN inbox_notification.is_read IS 'Whether the recipient has read this notification';
COMMENT ON COLUMN inbox_notification.read_at IS 'Timestamp when the notification was read';
COMMENT ON COLUMN inbox_notification.expired_at IS 'Optional expiry time after which the notification is no longer shown';
CREATE INDEX IF NOT EXISTS idx_recipient_read ON inbox_notification (recipient_id, is_read);
CREATE INDEX IF NOT EXISTS idx_inbox_notif_tenant ON inbox_notification (tenant_id);

-- MailFetchImapWatermark
/* Create table for model: Mail Fetch IMAP Watermark */
CREATE TABLE IF NOT EXISTS mail_fetch_imap_watermark (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    server_config_id BIGINT NOT NULL,
    folder_name VARCHAR(100) NOT NULL DEFAULT '',
    uid_validity BIGINT,
    last_seen_uid BIGINT NOT NULL,
    last_fetched_at TIMESTAMP,
    in_progress_since TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN mail_fetch_imap_watermark.tenant_id IS '0 = platform-level (shared across tenants); >0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.';
COMMENT ON COLUMN mail_fetch_imap_watermark.server_config_id IS 'FK → mail_receive_server_config.id';
COMMENT ON COLUMN mail_fetch_imap_watermark.folder_name IS 'Folder name on the IMAP server (e.g. INBOX, Junk)';
COMMENT ON COLUMN mail_fetch_imap_watermark.uid_validity IS 'IMAP UIDVALIDITY observed when last_seen_uid was set; if the server reports a different value on the next fetch, the UID space has reset (mailbox rebuild) and last_seen_uid is reset to 0.';
COMMENT ON COLUMN mail_fetch_imap_watermark.last_seen_uid IS 'Highest IMAP UID processed for this (config, folder). Next fetch starts at last_seen_uid + 1. Advances monotonically only.';
COMMENT ON COLUMN mail_fetch_imap_watermark.last_fetched_at IS 'Timestamp of the most recent successful fetch (diagnostics).';
COMMENT ON COLUMN mail_fetch_imap_watermark.in_progress_since IS 'When a worker started fetching this (config, folder). Set on lease acquisition, cleared on completion. Stale leases (older than the configured lease-timeout) are reclaimable by other workers.';
COMMENT ON COLUMN mail_fetch_imap_watermark.version IS 'Optimistic-lock version. Bumped on every lease transition; a superseded worker''s late release/advance fails the CAS and no-ops.';
CREATE UNIQUE INDEX IF NOT EXISTS uk_config_folder ON mail_fetch_imap_watermark (server_config_id, folder_name);
CREATE INDEX IF NOT EXISTS idx_watermark_tenant ON mail_fetch_imap_watermark (tenant_id);

-- MailReceiveRecord
/* Create table for model: Mail Receive Record */
CREATE TABLE IF NOT EXISTS mail_receive_record (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    server_config_id BIGINT,
    message_id VARCHAR(255),
    from_address VARCHAR(255),
    to_addresses VARCHAR(256),
    cc_addresses VARCHAR(256),
    subject VARCHAR(500),
    body_text TEXT,
    body_html TEXT,
    body_mode VARCHAR(64),
    attachments VARCHAR(1024),
    status VARCHAR(64) NOT NULL DEFAULT '',
    received_at TIMESTAMP,
    fetched_at TIMESTAMP,
    folder_name VARCHAR(100),
    mail_type VARCHAR(64),
    is_mailing_list BOOLEAN,
    is_encrypted BOOLEAN,
    is_spam BOOLEAN,
    original_message_id VARCHAR(255),
    smtp_reply_code VARCHAR(10),
    enhanced_status_code VARCHAR(20),
    diagnostic_message VARCHAR(64),
    failed_recipients VARCHAR(256),
    eml_file_id BIGINT,
    truncation_reason VARCHAR(32),
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN mail_receive_record.tenant_id IS '0 = platform-level (shared across tenants); >0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.';
COMMENT ON COLUMN mail_receive_record.server_config_id IS 'Mail server config used to fetch this email';
COMMENT ON COLUMN mail_receive_record.message_id IS 'RFC 5322 Message-ID header value (preserved across SMTP/IMAP/POP3); falls back to a ''synthetic:'' SHA-256 key when the email lacks a Message-ID header. Used together with serverConfigId as the dedup key on re-fetch.';
COMMENT ON COLUMN mail_receive_record.from_address IS 'Sender address';
COMMENT ON COLUMN mail_receive_record.to_addresses IS 'To addresses';
COMMENT ON COLUMN mail_receive_record.cc_addresses IS 'CC addresses';
COMMENT ON COLUMN mail_receive_record.subject IS 'Email subject';
COMMENT ON COLUMN mail_receive_record.body_text IS 'Plain-text body for list preview, search, and any consumer that wants a single guaranteed string. Stored verbatim when the email had a real text/plain part; derived from bodyHtml at write time when the email is HTML-only. Use bodyMode to tell the two apart for audit/forensics.';
COMMENT ON COLUMN mail_receive_record.body_html IS 'Sender''s HTML body, taken verbatim from the text/html MIME part. Null for plain-text-only emails.';
COMMENT ON COLUMN mail_receive_record.body_mode IS 'Original wire MIME shape, before any derivation. HTML — text/html only (bodyText derived from bodyHtml). PLAIN — text/plain only. HTML_WITH_PLAIN_ALT — multipart/alternative with both (bodyText is the sender''s own). Null when the email has no text body (pure attachments / calendar invites).';
COMMENT ON COLUMN mail_receive_record.attachments IS 'Per-attachment metadata — one item per non-inline MIME part extracted from the email and uploaded to object storage via file-starter. Null/empty when the email has no attachments or the file service is unavailable.';
COMMENT ON COLUMN mail_receive_record.status IS 'Read status';
COMMENT ON COLUMN mail_receive_record.received_at IS 'Original timestamp from the mail server';
COMMENT ON COLUMN mail_receive_record.fetched_at IS 'Timestamp when this system fetched the email';
COMMENT ON COLUMN mail_receive_record.folder_name IS 'Source folder name (e.g. INBOX)';
COMMENT ON COLUMN mail_receive_record.mail_type IS 'Primary mutually-exclusive content type: Normal / ReadReceipt / Bounce / AutoReply / CalendarInvite / Unknown. Only ReadReceipt and Bounce trigger downstream actions; the others are recorded for observability. Mailing-list, encryption and spam signals are orthogonal boolean flags (isMailingList / isEncrypted / isSpam).';
COMMENT ON COLUMN mail_receive_record.is_mailing_list IS 'True when the message bears List-Id / List-Unsubscribe / Precedence:bulk markers indicating distribution via a mailing list. Orthogonal to mailType — a mailing-list bounce or calendar invite has both signals.';
COMMENT ON COLUMN mail_receive_record.is_encrypted IS 'True when the body is wrapped in PGP-MIME (multipart/encrypted) or S/MIME (application/pkcs7-mime, smime-type=enveloped-data). Tells the inbox UI the body is opaque without a key, regardless of mailType.';
COMMENT ON COLUMN mail_receive_record.is_spam IS 'True when standard anti-spam markers are present (X-Spam-Flag: YES, X-Spam-Status: Yes, Exchange SCL ≥ 5). Reputation overlay; orthogonal to mailType — backscatter spam has mailType=Bounce + isSpam=true.';
COMMENT ON COLUMN mail_receive_record.original_message_id IS 'Original sent Message-ID that this email refers to (for receipt/bounce linking)';
COMMENT ON COLUMN mail_receive_record.smtp_reply_code IS 'SMTP reply code from bounce, e.g. 550';
COMMENT ON COLUMN mail_receive_record.enhanced_status_code IS 'Enhanced status code from bounce (RFC 3463), e.g. 5.1.1';
COMMENT ON COLUMN mail_receive_record.diagnostic_message IS 'Full bounce diagnostic message';
COMMENT ON COLUMN mail_receive_record.failed_recipients IS 'Failed recipient addresses extracted from the DSN report.';
COMMENT ON COLUMN mail_receive_record.eml_file_id IS 'EML original file ID (file-starter)';
COMMENT ON COLUMN mail_receive_record.truncation_reason IS 'Why this email was processed in a degraded way; null when fully processed. BodyTooLarge — only the envelope persisted. AttachmentTooLarge — parts skipped, body intact. MimeDepthExceeded / MimePartsExceeded — parsing aborted. ParseFailed — unexpected JavaMail error. Orthogonal to mailType — a bounce can also be truncated.';
CREATE UNIQUE INDEX IF NOT EXISTS uk_server_msg ON mail_receive_record (server_config_id, message_id);
CREATE INDEX IF NOT EXISTS idx_mail_recv_tenant_status ON mail_receive_record (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_truncation ON mail_receive_record (truncation_reason);

-- MailReceiveServerConfig
/* Create table for model: Mail Receive Server Config */
CREATE TABLE IF NOT EXISTS mail_receive_server_config (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    name VARCHAR(100) NOT NULL DEFAULT '',
    description VARCHAR(500),
    protocol VARCHAR(64) NOT NULL DEFAULT '',
    host VARCHAR(255) NOT NULL DEFAULT '',
    port INT NOT NULL,
    ssl_enabled BOOLEAN,
    username VARCHAR(255),
    password VARCHAR(255),
    fetch_folders VARCHAR(255),
    is_default BOOLEAN,
    is_enabled BOOLEAN,
    sequence INT,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN mail_receive_server_config.tenant_id IS '0 = platform-level (shared across tenants); >0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.';
COMMENT ON COLUMN mail_receive_server_config.protocol IS 'IMAP/IMAPS: non-destructive observation (recommended) — fetched mails stay on the server, incremental fetch tracks IMAP UID per folder. POP3/POP3S: destructive drain — each fetched message is deleted from the server.';
COMMENT ON COLUMN mail_receive_server_config.fetch_folders IS 'Comma-separated list of folders to fetch from (default: INBOX). Supports INBOX, Junk, and any custom folder name.';
COMMENT ON COLUMN mail_receive_server_config.is_default IS 'Whether this is the default receiving config for this tenant';
COMMENT ON COLUMN mail_receive_server_config.is_enabled IS 'Whether this config is enabled';
COMMENT ON COLUMN mail_receive_server_config.sequence IS 'Polling order for cron-driven fetch and display order in admin UIs. Ascending — lower = polled first / shown first. NOT a failover priority: all enabled configs get polled each tick.';
CREATE INDEX IF NOT EXISTS idx_mail_recv_cfg_default ON mail_receive_server_config (tenant_id, is_default);

-- MailSendRecord
/* Create table for model: Mail Send Record */
CREATE TABLE IF NOT EXISTS mail_send_record (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    server_config_id BIGINT,
    from_address VARCHAR(255),
    to_addresses VARCHAR(256),
    cc_addresses VARCHAR(256),
    bcc_addresses VARCHAR(256),
    subject VARCHAR(500),
    body_mode VARCHAR(64) NOT NULL DEFAULT '',
    body_html TEXT,
    body_text TEXT,
    attachments VARCHAR(1024),
    status VARCHAR(64) NOT NULL DEFAULT '',
    retry_count INT,
    version BIGINT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP,
    error_code VARCHAR(100),
    error_message VARCHAR(1000),
    sent_at TIMESTAMP,
    attempt_started_at TIMESTAMP,
    send_duration_ms INT,
    message_id VARCHAR(255),
    read_receipt_requested BOOLEAN,
    priority VARCHAR(64),
    reply_to VARCHAR(255),
    read_receipt_received BOOLEAN,
    read_receipt_received_at TIMESTAMP,
    bounced BOOLEAN,
    bounce_code VARCHAR(20),
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN mail_send_record.tenant_id IS '0 = platform-level (shared across tenants); >0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.';
COMMENT ON COLUMN mail_send_record.server_config_id IS 'Mail server config used to send this email';
COMMENT ON COLUMN mail_send_record.body_mode IS 'Body shape used to send. HTML — bodyHtml only. PLAIN — bodyText only. HTML_WITH_DERIVED_PLAIN — bodyHtml + bodyText (plain derived at send time). HTML_WITH_AUTHORED_PLAIN — bodyHtml + bodyText (plain hand-authored). DERIVED vs AUTHORED tells audit whether the plain text was human-reviewed.';
COMMENT ON COLUMN mail_send_record.body_html IS 'HTML body persisted verbatim for retry fidelity. Null for PLAIN mode.';
COMMENT ON COLUMN mail_send_record.body_text IS 'Plain-text body persisted verbatim for retry fidelity. Null for HTML mode; populated for PLAIN / HTML_WITH_DERIVED_PLAIN / HTML_WITH_AUTHORED_PLAIN.';
COMMENT ON COLUMN mail_send_record.attachments IS 'Attachment FileInfo list. Persisted as fileIds (List<Long> CSV) by ORM; resolved back to FileInfo on read so consumers can stream attachment bytes from file-starter at SMTP send time. Null/empty when the email has no attachments.';
COMMENT ON COLUMN mail_send_record.status IS 'Send status';
COMMENT ON COLUMN mail_send_record.retry_count IS 'Number of send attempts';
COMMENT ON COLUMN mail_send_record.version IS 'Optimistic-lock version. Bumped on every state transition.';
COMMENT ON COLUMN mail_send_record.next_retry_at IS 'Earliest time at which the next retry should be attempted';
COMMENT ON COLUMN mail_send_record.error_code IS 'Provider-specific error code on failure';
COMMENT ON COLUMN mail_send_record.error_message IS 'Error message on failure';
COMMENT ON COLUMN mail_send_record.sent_at IS 'Timestamp when the email was accepted by the SMTP server';
COMMENT ON COLUMN mail_send_record.attempt_started_at IS 'When the latest delivery attempt started (claimed as Sending)';
COMMENT ON COLUMN mail_send_record.send_duration_ms IS 'Milliseconds the SMTP call of the successful attempt took';
COMMENT ON COLUMN mail_send_record.message_id IS 'SMTP Message-ID header value';
COMMENT ON COLUMN mail_send_record.read_receipt_requested IS 'Whether a read receipt was requested for this email';
COMMENT ON COLUMN mail_send_record.priority IS 'Priority level used when sending: HIGH / NORMAL / LOW';
COMMENT ON COLUMN mail_send_record.reply_to IS 'Reply-To address actually used when sending — final value after the dto > template > config fallback chain. Persisted so retry replays the same Reply-To.';
COMMENT ON COLUMN mail_send_record.read_receipt_received IS 'Whether a read receipt has been received for this email';
COMMENT ON COLUMN mail_send_record.read_receipt_received_at IS 'Timestamp when the read receipt was received';
COMMENT ON COLUMN mail_send_record.bounced IS 'Whether this email bounced (rejection / NDR received)';
COMMENT ON COLUMN mail_send_record.bounce_code IS 'Bounce code summary, e.g. ''550 5.1.1''';
CREATE INDEX IF NOT EXISTS idx_mail_send_tenant_status ON mail_send_record (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_mail_send_sent_at ON mail_send_record (sent_at);
CREATE INDEX IF NOT EXISTS idx_mail_send_status_updated ON mail_send_record (status, updated_time);
CREATE INDEX IF NOT EXISTS idx_mail_send_status_retry ON mail_send_record (status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_message_id ON mail_send_record (message_id);

-- MailSendServerConfig
/* Create table for model: Mail Send Server Config */
CREATE TABLE IF NOT EXISTS mail_send_server_config (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    name VARCHAR(100) NOT NULL DEFAULT '',
    description VARCHAR(500),
    protocol VARCHAR(64) NOT NULL DEFAULT '',
    host VARCHAR(255) NOT NULL DEFAULT '',
    port INT NOT NULL,
    ssl_enabled BOOLEAN,
    starttls_enabled BOOLEAN,
    username VARCHAR(255),
    password VARCHAR(500),
    from_address VARCHAR(255),
    from_name VARCHAR(100),
    reply_to_address VARCHAR(255),
    daily_send_limit INT,
    rate_limit_per_minute INT,
    is_default BOOLEAN,
    is_enabled BOOLEAN,
    sequence INT,
    read_receipt_enabled BOOLEAN,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN mail_send_server_config.tenant_id IS '0 = platform-level (shared across tenants); >0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.';
COMMENT ON COLUMN mail_send_server_config.protocol IS 'Protocol: SMTP or SMTPS';
COMMENT ON COLUMN mail_send_server_config.ssl_enabled IS 'Use implicit TLS / SMTPS — the TLS handshake happens immediately after TCP; the entire session is encrypted from the first byte. Set this OR starttlsEnabled, not both. Choose this when the provider''s docs say ''SSL/TLS'' or list port 465.';
COMMENT ON COLUMN mail_send_server_config.starttls_enabled IS 'Use STARTTLS / explicit TLS — the connection starts as plaintext SMTP, then upgrades to TLS via the STARTTLS command. Typical ports: 587 (submission) or 25 (legacy). Set this OR sslEnabled, not both. Choose this when the provider''s docs say ''STARTTLS'' or list port 587.';
COMMENT ON COLUMN mail_send_server_config.from_address IS 'From address displayed in outgoing emails';
COMMENT ON COLUMN mail_send_server_config.from_name IS 'From display name';
COMMENT ON COLUMN mail_send_server_config.reply_to_address IS 'Default Reply-To address for emails sent through this server config. Resolution chain at send time (highest to lowest priority): SendMailDTO.replyTo > MailTemplate.replyTo > MailSendServerConfig.replyToAddress.';
COMMENT ON COLUMN mail_send_server_config.daily_send_limit IS 'Maximum emails sent per day (null = unlimited)';
COMMENT ON COLUMN mail_send_server_config.rate_limit_per_minute IS 'Rate limit: max emails per minute';
COMMENT ON COLUMN mail_send_server_config.is_default IS 'Whether this is the default sending config for this tenant';
COMMENT ON COLUMN mail_send_server_config.is_enabled IS 'Whether this config is enabled';
COMMENT ON COLUMN mail_send_server_config.sequence IS 'Tie-break order among multiple isDefault=true configs and display order in admin UIs. Ascending — lower wins. NOT a failover priority: the dispatcher picks the first matching default and stops; other defaults are never tried as backup.';
COMMENT ON COLUMN mail_send_server_config.read_receipt_enabled IS 'Whether to request read receipts by default (Disposition-Notification-To header)';
CREATE INDEX IF NOT EXISTS idx_mail_send_cfg_default ON mail_send_server_config (tenant_id, is_default);

-- MailTemplate
/* Create table for model: Mail Template */
CREATE TABLE IF NOT EXISTS mail_template (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    code VARCHAR(100) NOT NULL DEFAULT '',
    name VARCHAR(100) NOT NULL DEFAULT '',
    description VARCHAR(500),
    subject VARCHAR(500),
    body_html TEXT,
    body_text TEXT,
    body_mode VARCHAR(64) NOT NULL DEFAULT '',
    is_enabled BOOLEAN,
    default_priority VARCHAR(64),
    reply_to VARCHAR(255),
    attachments VARCHAR(1024),
    preferred_server_config_id BIGINT,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN mail_template.tenant_id IS '0 = platform-level (shared across tenants); >0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.';
COMMENT ON COLUMN mail_template.code IS 'Unique template code used for programmatic lookup, e.g. USER_WELCOME';
COMMENT ON COLUMN mail_template.name IS 'Display name';
COMMENT ON COLUMN mail_template.description IS 'Description';
COMMENT ON COLUMN mail_template.subject IS 'Email subject template, supports {{ variable }} placeholders';
COMMENT ON COLUMN mail_template.body_html IS 'HTML body template, supports {{ variable }} placeholders. Variable output is HTML-escaped; embed a trusted HTML fragment with {{ fragment | raw }}. Required for HTML / HTML_WITH_DERIVED_PLAIN / HTML_WITH_AUTHORED_PLAIN modes.';
COMMENT ON COLUMN mail_template.body_text IS 'Plain-text body template, supports {{ variable }} placeholders. Required for PLAIN and HTML_WITH_AUTHORED_PLAIN modes. Ignored when bodyMode is HTML or HTML_WITH_DERIVED_PLAIN — there the plain part is absent or auto-derived from bodyHtml at send time.';
COMMENT ON COLUMN mail_template.body_mode IS 'Body shape for this template. HTML — bodyHtml only (text/html). PLAIN — bodyText only (text/plain). HTML_WITH_DERIVED_PLAIN — bodyHtml only, multipart/alternative with the plain part derived at send time. HTML_WITH_AUTHORED_PLAIN — bodyHtml + bodyText, multipart/alternative.';
COMMENT ON COLUMN mail_template.is_enabled IS 'Whether this template is active';
COMMENT ON COLUMN mail_template.default_priority IS 'Default email priority for this template. When set, all emails sent via this template will use this priority unless overridden in SendMailDTO.';
COMMENT ON COLUMN mail_template.reply_to IS 'Default Reply-To for this template — one or more addresses separated by comma / semicolon / newline (normalized to an RFC822 comma list at send time; display-name form allowed). Optional. Resolution chain at send time: dto.replyTo > template.replyTo > config.replyToAddress.';
COMMENT ON COLUMN mail_template.attachments IS 'Default attachments — files automatically attached to every email rendered from this template (e.g. compliance disclosures, branded brochures). Used only when SendMailDTO.attachments is empty; caller-supplied attachments override the template default entirely.';
COMMENT ON COLUMN mail_template.preferred_server_config_id IS 'Preferred send server config. Resolution chain at send time: SendMailDTO.serverConfigId > MailTemplate.preferredServerConfigId > MailServerDispatcher default (tenant default → platform default). Routes template categories through dedicated SMTP (marketing / transactional / compliance).';
CREATE UNIQUE INDEX IF NOT EXISTS uk_mail_template_tenant_code ON mail_template (tenant_id, code);

-- OutboxEntry
/* Create table for model: Outbox Entry */
CREATE TABLE IF NOT EXISTS message_outbox (
    id BIGINT NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL DEFAULT '',
    aggregate_id BIGINT NOT NULL,
    route VARCHAR(64),
    payload VARCHAR(512) NOT NULL DEFAULT '',
    status VARCHAR(64),
    attempts INT NOT NULL,
    last_error VARCHAR(500),
    next_attempt_at TIMESTAMP,
    published_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN message_outbox.aggregate_type IS 'Aggregate type, e.g. MailSendRecord / SmsSendRecord (for diagnostics)';
COMMENT ON COLUMN message_outbox.aggregate_id IS 'Aggregate primary key the message refers to';
COMMENT ON COLUMN message_outbox.route IS 'Logical delivery route name (MAIL_SEND / SMS_SEND)';
COMMENT ON COLUMN message_outbox.payload IS 'Thin claim-check envelope (recordId / tenantId / traceId as JSON), NOT the message body — bodies live on the aggregate record. The cap is a deliberate guard: a payload that outgrows it should be redesigned as a reference, not widened.';
COMMENT ON COLUMN message_outbox.status IS 'Lifecycle status';
COMMENT ON COLUMN message_outbox.attempts IS 'Number of publish attempts so far';
COMMENT ON COLUMN message_outbox.last_error IS 'Last publish error message (for dead rows)';
COMMENT ON COLUMN message_outbox.next_attempt_at IS 'Earliest time the publisher should pick this row up next';
COMMENT ON COLUMN message_outbox.published_at IS 'Timestamp when the row was published to the broker';
COMMENT ON COLUMN message_outbox.version IS 'Optimistic-lock version';
CREATE INDEX IF NOT EXISTS idx_status_next ON message_outbox (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_aggregate ON message_outbox (aggregate_type, aggregate_id);

-- SmsProviderConfig
/* Create table for model: SMS Provider Config */
CREATE TABLE IF NOT EXISTS sms_provider_config (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    name VARCHAR(100) NOT NULL DEFAULT '',
    description VARCHAR(500),
    provider_type VARCHAR(64) NOT NULL DEFAULT '',
    api_key VARCHAR(255),
    api_secret VARCHAR(64),
    api_endpoint VARCHAR(500),
    account_id VARCHAR(255),
    ext_config VARCHAR(64),
    sender_number VARCHAR(50),
    sender_id VARCHAR(50),
    rate_limit_per_minute INT,
    daily_send_limit INT,
    is_default BOOLEAN,
    is_enabled BOOLEAN,
    priority INT,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN sms_provider_config.tenant_id IS '0 = platform-level (shared across tenants); >0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.';
COMMENT ON COLUMN sms_provider_config.provider_type IS 'Provider type: Twilio / Infobip / Aliyun / Tencent / Custom';
COMMENT ON COLUMN sms_provider_config.api_key IS 'Primary credential (Twilio=accountSid, Infobip=apiKey, Aliyun=accessKeyId)';
COMMENT ON COLUMN sms_provider_config.api_secret IS 'Secondary credential (Twilio=authToken, Infobip=apiSecret, Aliyun=accessKeySecret) — stored encrypted, mark MetaField.encrypted=true';
COMMENT ON COLUMN sms_provider_config.api_endpoint IS 'Provider API base URL (null = adapter default)';
COMMENT ON COLUMN sms_provider_config.account_id IS 'Extra provider identifier (e.g. Twilio Account SID when using API keys)';
COMMENT ON COLUMN sms_provider_config.ext_config IS 'Provider-specific JSON config (e.g. regionId, appId)';
COMMENT ON COLUMN sms_provider_config.sender_number IS 'Sender phone number (E.164 format)';
COMMENT ON COLUMN sms_provider_config.sender_id IS 'Alphanumeric sender ID (alternative to phone number)';
COMMENT ON COLUMN sms_provider_config.rate_limit_per_minute IS 'Max SMS per minute';
COMMENT ON COLUMN sms_provider_config.daily_send_limit IS 'Max SMS per day (null = unlimited)';
COMMENT ON COLUMN sms_provider_config.is_default IS 'Marks this provider as a catchall for second-tier dispatch, used when no enabled sms_provider_region route matches the recipient''s country. Multiple defaults are ordered by priority before one is selected and persisted. Orthogonal to region routing — a provider can be both a region route and a catchall.';
COMMENT ON COLUMN sms_provider_config.is_enabled IS 'Whether this config is active';
COMMENT ON COLUMN sms_provider_config.priority IS 'Lower = higher priority. Used as selection ordering among isDefault=true providers in the catchall dispatch tier, and as the list-display order in admin UIs. Defaults to 100 so new configs sort after explicitly-prioritised ones.';
CREATE INDEX IF NOT EXISTS idx_sms_provider_cfg_default ON sms_provider_config (tenant_id, is_default);

-- SmsProviderRegion
/* Create table for model: SMS Provider Region */
CREATE TABLE IF NOT EXISTS sms_provider_region (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    provider_config_id BIGINT NOT NULL,
    region_code VARCHAR(2) NOT NULL DEFAULT '',
    dial_code VARCHAR(64),
    priority INT NOT NULL,
    is_enabled BOOLEAN NOT NULL,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN sms_provider_region.tenant_id IS '0 = platform-level (shared across tenants); >0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.';
COMMENT ON COLUMN sms_provider_region.provider_config_id IS 'FK → sms_provider_config.id';
COMMENT ON COLUMN sms_provider_region.region_code IS 'Routed country — FK to country_region.id (ISO 3166-1 alpha-2, code-as-id). Mainland China (CN), Taiwan (TW), Hong Kong (HK), Macau (MO) are four distinct codes — configure each explicitly if needed on different providers. No magic values like ''*''; catchall is via SmsProviderConfig.isDefault.';
COMMENT ON COLUMN sms_provider_region.dial_code IS 'ITU-T E.164 dial code (digits only, no leading +). Stored cascade derived from country_region.dial_code via the regionCode relation — framework-maintained, readonly.';
COMMENT ON COLUMN sms_provider_region.priority IS 'Lower = higher priority. Ordered ascending within the same region for provider selection; ties broken by SmsProviderConfig.priority asc. Defaults to 100 so new rows sort after any explicitly-prioritised ones.';
COMMENT ON COLUMN sms_provider_region.is_enabled IS 'Row enable switch — false disables this (provider, region) route without deleting the row, useful for temporary fault isolation.';
CREATE INDEX IF NOT EXISTS idx_region_enabled ON sms_provider_region (region_code, is_enabled);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_provider_region ON sms_provider_region (tenant_id, provider_config_id, region_code);

-- SmsSendRecord
/* Create table for model: SMS Send Record */
CREATE TABLE IF NOT EXISTS sms_send_record (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    provider_config_id BIGINT,
    provider_type VARCHAR(64),
    phone_number VARCHAR(50),
    template_code VARCHAR(100),
    content VARCHAR(2000),
    sign_name VARCHAR(64),
    external_template_id VARCHAR(100),
    status VARCHAR(64) NOT NULL DEFAULT '',
    retry_count INT,
    version BIGINT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP,
    error_message VARCHAR(1000),
    error_code VARCHAR(100),
    provider_message_id VARCHAR(255),
    sent_at TIMESTAMP,
    attempt_started_at TIMESTAMP,
    send_duration_ms INT,
    delivery_status VARCHAR(64),
    delivery_status_updated_at TIMESTAMP,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN sms_send_record.tenant_id IS '0 = platform-level (shared across tenants); >0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.';
COMMENT ON COLUMN sms_send_record.provider_config_id IS 'SMS provider config used to send this message';
COMMENT ON COLUMN sms_send_record.provider_type IS 'Provider type used for this send (Twilio, Infobip, etc.)';
COMMENT ON COLUMN sms_send_record.phone_number IS 'Recipient phone number';
COMMENT ON COLUMN sms_send_record.template_code IS 'Template code if sent via template';
COMMENT ON COLUMN sms_send_record.content IS 'Rendered SMS content';
COMMENT ON COLUMN sms_send_record.sign_name IS 'SMS signature (sign name) actually used at send time. Persisted for retry fidelity — if SmsTemplateProviderBinding.signName is edited between first send and retry, retries still use the original value.';
COMMENT ON COLUMN sms_send_record.external_template_id IS 'Provider-side pre-registered template ID actually used at send time (e.g. Aliyun SMS_12345678, Tencent 1234567). Persisted for retry fidelity — see signName for the same reasoning.';
COMMENT ON COLUMN sms_send_record.status IS 'Send status';
COMMENT ON COLUMN sms_send_record.retry_count IS 'Number of send attempts';
COMMENT ON COLUMN sms_send_record.version IS 'Optimistic-lock version. Bumped on every state transition.';
COMMENT ON COLUMN sms_send_record.next_retry_at IS 'Earliest time at which the next retry should be attempted';
COMMENT ON COLUMN sms_send_record.error_message IS 'Error message on failure';
COMMENT ON COLUMN sms_send_record.error_code IS 'Provider-specific error code on failure (e.g. Twilio 21211, Aliyun isv.BUSINESS_LIMIT_CONTROL)';
COMMENT ON COLUMN sms_send_record.provider_message_id IS 'External message ID from the SMS provider';
COMMENT ON COLUMN sms_send_record.sent_at IS 'Timestamp when the message was accepted by the provider';
COMMENT ON COLUMN sms_send_record.attempt_started_at IS 'When the latest delivery attempt started (claimed as Sending)';
COMMENT ON COLUMN sms_send_record.send_duration_ms IS 'Milliseconds the provider call of the successful attempt took';
COMMENT ON COLUMN sms_send_record.delivery_status IS 'Delivery status reported by the provider';
COMMENT ON COLUMN sms_send_record.delivery_status_updated_at IS 'Last delivery status update time';
CREATE INDEX IF NOT EXISTS idx_sms_send_tenant_status ON sms_send_record (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_sms_send_sent_at ON sms_send_record (sent_at);
CREATE INDEX IF NOT EXISTS idx_sms_send_status_updated ON sms_send_record (status, updated_time);
CREATE INDEX IF NOT EXISTS idx_sms_send_status_retry ON sms_send_record (status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_provider_msg_id ON sms_send_record (provider_message_id);

-- SmsTemplate
/* Create table for model: SMS Template */
CREATE TABLE IF NOT EXISTS sms_template (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    code VARCHAR(100) NOT NULL DEFAULT '',
    name VARCHAR(100) NOT NULL DEFAULT '',
    description VARCHAR(500),
    content TEXT,
    is_enabled BOOLEAN,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN sms_template.tenant_id IS '0 = platform-level (shared across tenants); >0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.';
COMMENT ON COLUMN sms_template.code IS 'Unique template code used for programmatic lookup, e.g. VERIFY_CODE';
COMMENT ON COLUMN sms_template.content IS 'SMS body template with {{ variable }} placeholders';
COMMENT ON COLUMN sms_template.is_enabled IS 'Whether this template is active';
CREATE UNIQUE INDEX IF NOT EXISTS uk_sms_template_tenant_code ON sms_template (tenant_id, code);

-- SmsTemplateProviderBinding
/* Create table for model: SMS Template Provider Binding */
CREATE TABLE IF NOT EXISTS sms_template_provider_binding (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    template_id BIGINT NOT NULL,
    provider_config_id BIGINT NOT NULL,
    region_code VARCHAR(2),
    external_template_id VARCHAR(100),
    sign_name VARCHAR(50),
    priority INT,
    is_enabled BOOLEAN,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN sms_template_provider_binding.tenant_id IS '0 = platform-level (shared across tenants); >0 = tenant-level. Auto-stamped by the ORM on writes when multi-tenancy is enabled.';
COMMENT ON COLUMN sms_template_provider_binding.template_id IS 'FK → sms_template.id';
COMMENT ON COLUMN sms_template_provider_binding.provider_config_id IS 'FK → sms_provider_config.id';
COMMENT ON COLUMN sms_template_provider_binding.region_code IS 'Optional ISO 3166-1 alpha-2 region override. Blank = generic binding for this provider.';
COMMENT ON COLUMN sms_template_provider_binding.external_template_id IS 'Pre-registered template ID for providers that require it (e.g. Aliyun SMS_12345678)';
COMMENT ON COLUMN sms_template_provider_binding.sign_name IS 'SMS signature for this provider (e.g. Chinese providers require a sign name)';
COMMENT ON COLUMN sms_template_provider_binding.priority IS 'Template-aware provider selection priority (lower = preferred)';
COMMENT ON COLUMN sms_template_provider_binding.is_enabled IS 'Whether this binding is active';
CREATE INDEX IF NOT EXISTS idx_template_priority ON sms_template_provider_binding (template_id, priority);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_tmpl_provider_region ON sms_template_provider_binding (tenant_id, template_id, provider_config_id, region_code);
CREATE INDEX IF NOT EXISTS idx_template_region ON sms_template_provider_binding (template_id, region_code);

-- TenantMessageQuota
/* Create table for model: Tenant Message Quota */
CREATE TABLE IF NOT EXISTS tenant_message_quota (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    mail_monthly_limit BIGINT,
    sms_monthly_limit BIGINT,
    description VARCHAR(500),
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN tenant_message_quota.tenant_id IS 'The governed tenant; -1 = the platform''s own quota. Plain column — this model is platform-owned and not tenant-isolated.';
COMMENT ON COLUMN tenant_message_quota.mail_monthly_limit IS 'Maximum accepted mail sends per calendar month. Null = use the deployment default (message.quota.mail-monthly-default; null there = unlimited).';
COMMENT ON COLUMN tenant_message_quota.sms_monthly_limit IS 'Maximum accepted SMS sends per calendar month. Null = use the deployment default (message.quota.sms-monthly-default; null there = unlimited).';
COMMENT ON COLUMN tenant_message_quota.description IS 'Operations note, e.g. the plan or contract behind this ceiling';
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_message_quota_tenant ON tenant_message_quota (tenant_id);

-- TenantMessageUsage
/* Create table for model: Tenant Message Usage */
CREATE TABLE IF NOT EXISTS tenant_message_usage (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    month VARCHAR(7) NOT NULL DEFAULT '',
    mail_monthly_limit BIGINT,
    mail_used BIGINT NOT NULL,
    sms_monthly_limit BIGINT,
    sms_used BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN tenant_message_usage.tenant_id IS 'The quota bucket; -1 = the platform''s own sends.';
COMMENT ON COLUMN tenant_message_usage.month IS 'Calendar month of this ledger row, format yyyy-MM (server default zone).';
COMMENT ON COLUMN tenant_message_usage.mail_monthly_limit IS 'Snapshot of the mail ceiling in force at the last accepted mail send of this month (quota row or deployment default); null = unlimited.';
COMMENT ON COLUMN tenant_message_usage.mail_used IS 'Accepted mail sends this month. Incremented once per accepted message; delivery retries never touch it.';
COMMENT ON COLUMN tenant_message_usage.sms_monthly_limit IS 'Snapshot of the SMS ceiling in force at the last accepted SMS send of this month; null = unlimited.';
COMMENT ON COLUMN tenant_message_usage.sms_used IS 'Accepted SMS sends this month. Incremented once per accepted message; delivery retries never touch it.';
COMMENT ON COLUMN tenant_message_usage.version IS 'Optimistic-lock version. Bumped on every increment.';
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_message_usage_bucket ON tenant_message_usage (tenant_id, month);
