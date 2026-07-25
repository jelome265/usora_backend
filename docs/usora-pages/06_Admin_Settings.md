# USORA — Administration & Settings Pages

> **Scope:** The control plane of the platform. These pages are used by tenant administrators, platform operators, and super-admins to configure the system, manage users, define business rules, integrate with external systems, and govern the operational parameters that shape how KYC operations function within each tenant and across the platform.

---

## 6.1 Tenant Administration Dashboard

**Route:** `/admin/tenant`

**Purpose:** Central command center for tenant-level administration, providing overview of tenant health, configuration status, and administrative actions.

**Deep Details:**

- **Tenant Health Overview:**
  - **System Status:** Green/yellow/red indicators for all integrated services (document verification providers, sanctions lists, biometric services, email/SMS gateways).
  - **Usage Metrics:** Current month vs. quota: API calls, verifications processed, storage used, active users, cases created.
  - **License Status:** Subscription tier, renewal date, feature entitlements, and upgrade prompts.
  - **Recent Alerts:** System alerts, integration failures, or configuration issues requiring attention.
- **Quick Configuration Links:** Cards linking to most commonly accessed settings: User Management, Workflow Builder, Risk Rules, Integration Settings, Branding, and Billing.
- **Activity Feed:** Recent administrative actions: user additions, role changes, policy updates, integration changes, with who and when.
- **Onboarding Checklist:** For new tenants, a guided checklist of required setup steps: configure users, set up workflows, connect verification providers, configure risk rules, test end-to-end flow, go live.
- **Tenant Info Card:** Tenant name, ID, creation date, primary contact, support plan, data residency region, and environment (production/staging/sandbox).
- **Backup & Restore:** Status of last backup, backup schedule, and manual backup/restore controls.
- **Audit Summary:** Recent security events, failed login attempts, and access anomalies.

---

## 6.2 User Management / Directory Page

**Route:** `/admin/users`

**Purpose:** Complete user lifecycle management within the tenant: creation, modification, role assignment, deactivation, and audit.

**Deep Details:**

- **User Directory:** Table of all users with: name, email, role, department, status (active, invited, suspended, deactivated), last login, MFA status, and created date.
- **Advanced Filtering:** Filter by role, department, status, MFA enrollment, last login date, created date, and custom attributes.
- **User Creation:**
  - **Individual Invite:** Send email invitation with temporary password or SSO setup link.
  - **Bulk Invite:** Upload CSV with user details. Auto-send invitations.
  - **SSO Auto-Provisioning:** Configure JIT provisioning from identity provider.
- **User Profile Editor:**
  - **Basic Info:** Name, email, phone, job title, department, employee ID, timezone, language.
  - **Avatar:** Upload profile photo or use initials.
  - **Contact Preferences:** Email, SMS, in-app notification preferences.
  - **Custom Fields:** Tenant-defined custom user attributes.
- **Role Assignment:**
  - Assign primary role and secondary roles.
  - Role expiration (temporary access with auto-revoke).
  - Role conflict detection (mutually exclusive roles).
- **Permission Preview:** See exactly what permissions a user has based on their roles, with resource-level granularity.
- **MFA Management:**
  - View MFA enrollment status per method.
  - Force MFA enrollment.
  - Reset MFA (require re-enrollment).
  - View MFA backup codes (with audit logging).
- **Session Management:**
  - View all active sessions per user: device, browser, IP, location, last activity.
  - Force logout specific sessions or all sessions.
  - Revoke API keys and refresh tokens.
- **Account Actions:**
  - **Suspend:** Temporarily disable access with reason and auto-restore date.
  - **Deactivate:** Permanent disable with data retention.
  - **Reactivate:** Restore suspended/deactivated users.
  - **Impersonate:** Secure impersonation for support purposes (with full audit logging and user notification).
  - **Delete:** GDPR-compliant deletion with confirmation and data export option.
- **User Import/Export:** Bulk import from CSV/Excel or HR system. Export user directory for audit.
- **User Analytics:** Login frequency, feature usage, case processing metrics per user.

---

## 6.3 Role & Permission Management Page

**Route:** `/admin/roles`

**Purpose:** Define, configure, and manage role-based access control (RBAC) with fine-grained permissions at resource and action level.

**Deep Details:**

- **Role Directory:** List of all roles with: name, description, user count, permission count, inheritance, and status.
- **Pre-Built Roles:**
  - **Super Admin:** Full platform access.
  - **Tenant Admin:** Full tenant administration.
  - **Compliance Manager:** Risk, compliance, and policy management.
  - **Senior Analyst:** Case review, escalation authority, QA access.
  - **KYC Analyst:** Case processing, document review, customer communication.
  - **Junior Analyst:** Limited case processing with senior review requirement.
  - **Read-Only Auditor:** View-only access for audit and compliance review.
  - **API User:** Programmatic access only.
  - **Customer Support:** Customer communication and basic profile viewing.
  - **Risk Manager:** Risk scoring, alert management, and reporting.
- **Custom Role Builder:**
  - **Role Name & Description:** Identifying information.
  - **Permission Matrix:** Granular permissions organized by module:
    - **Core:** Login, password change, profile edit, notification preferences.
    - **Cases:** View, create, edit, delete, assign, escalate, approve, reject, export.
    - **Customers:** View, create, edit, delete, export, merge, close.
    - **Documents:** View, upload, verify, reject, download, delete.
    - **Risk:** View alerts, manage alerts, edit risk scores, configure rules, view reports.
    - **Compliance:** View policies, edit policies, manage training, regulatory reporting.
    - **Admin:** User management, role management, tenant settings, billing, integrations.
    - **API:** Generate keys, view usage, manage webhooks.
    - **Reports:** View, create, schedule, export reports.
  - **Resource-Level Permissions:** Restrict access to specific customers, cases, or segments based on attributes (e.g., "Can only view customers from Region X").
  - **Field-Level Permissions:** Hide or make read-only specific fields (e.g., hide financial data from junior analysts).
  - **Action Limits:** Set daily/hourly limits on actions (e.g., max 50 cases per day for training analysts).
- **Role Inheritance:** Create role hierarchies where child roles inherit parent permissions with optional overrides.
- **Role Comparison:** Side-by-side comparison of two roles showing permission differences.
- **Role Usage Analytics:** Which users have which roles, role assignment trends, and permission utilization (unused permissions flagged for cleanup).
- **Approval Workflow:** Role creation and modification require approval from security or compliance team.

---

## 6.4 Department & Team Management Page

**Route:** `/admin/teams`

**Purpose:** Organize users into departments, teams, and reporting hierarchies for case assignment, workload management, and organizational structure.

**Deep Details:**

- **Organization Chart:** Visual hierarchy showing departments, teams, and reporting lines. Drag-and-drop reorganization.
- **Team Creation:** Define teams with: name, description, parent department, team lead, default queue, specialization tags, and working hours.
- **Member Management:** Add/remove users from teams. Set team roles (lead, member, observer).
- **Queue Assignment:** Assign specific case queues to specific teams.
- **Workload Balancing:** Configure team capacity limits and auto-distribution rules.
- **Shift Management:** Define team shifts and schedules for 24/7 operations.
- **Team Performance:** Dashboard showing team metrics: cases processed, SLA compliance, quality scores, and backlog.
- **Escalation Paths:** Define escalation chains within and across teams.
- **Cross-Functional Teams:** Create temporary project teams for special initiatives (e.g., regulatory examination prep).

---

## 6.5 Tenant Branding & White-Label Configuration Page

**Route:** `/admin/branding`

**Purpose:** Customize the visual identity of the platform for each tenant, enabling white-label deployment.

**Deep Details:**

- **Logo Management:**
  - **Primary Logo:** For header, login page, and emails. SVG preferred, PNG fallback.
  - **Favicon:** For browser tabs.
  - **Email Logo:** Optimized for email clients.
  - **Dark Mode Logo:** Alternative logo for dark themes.
- **Color Palette:**
  - **Primary Color:** Main brand color affecting buttons, links, and accents.
  - **Secondary Color:** Supporting color for secondary actions.
  - **Accent Color:** For highlights, badges, and special indicators.
  - **Background Colors:** Page background, card background, sidebar background.
  - **Text Colors:** Primary text, secondary text, muted text.
  - **Semantic Colors:** Success, warning, error, info (can be customized or use defaults).
  - **Real-Time Preview:** Live preview of color changes across key UI components.
- **Typography:**
  - **Font Family:** Choose from Google Fonts or upload custom web fonts.
  - **Font Weights:** Configure weights for headings and body text.
  - **Base Font Size:** Adjust overall text scale.
- **Custom CSS:** Advanced users can add custom CSS for fine-grained control.
- **Email Templates:**
  - Customize email header, footer, colors, and fonts.
  - Preview emails in desktop and mobile view.
  - Template variables documentation.
- **Portal Branding:** Customer-facing portal gets the same branding treatment with additional options:
  - **Hero Image:** Login page background.
  - **Welcome Message:** Custom text on customer dashboard.
  - **Custom Domain:** Configure CNAME for `kyc.yourcompany.com`.
  - **SSL Certificate:** Upload custom SSL cert or use auto-generated Let's Encrypt.
- **Language & Localization:**
  - Default language for tenant.
  - Custom terminology (e.g., replace "customer" with "client" or "member").
  - Custom legal text (disclaimers, privacy notices).
- **Favicon & App Icons:** Full icon set for PWA support.

---

## 6.6 Integration Management / Connected Apps Page

**Route:** `/admin/integrations`

**Purpose:** Configure, monitor, and manage all third-party integrations and API connections.

**Deep Details:**

- **Integration Catalog:** Browse available integrations by category:
  - **Identity Verification:** Onfido, Jumio, Veriff, IDnow, Trulioo, Shufti Pro, Sumsub, ComplyCube.
  - **Document OCR:** Google Cloud Vision, AWS Textract, Azure Form Recognizer.
  - **Biometrics:** FaceTec, iProov, Amazon Rekognition.
  - **Sanctions/PEP:** Dow Jones Risk & Compliance, Refinitiv World-Check, ComplyAdvantage, LexisNexis.
  - **Adverse Media:** Dow Jones, ComplyAdvantage, Ripjar.
  - **Communication:** SendGrid, Mailgun, Twilio, AWS SES/SNS.
  - **CRM:** Salesforce, HubSpot, Microsoft Dynamics, Pipedrive.
  - **Storage:** AWS S3, Google Cloud Storage, Azure Blob.
  - **SSO/Identity:** Azure AD, Okta, Google Workspace, Ping Identity, OneLogin, Auth0.
  - **Analytics:** Segment, Mixpanel, Amplitude, Google Analytics.
  - **SIEM:** Splunk, Datadog, Elastic Security.
  - **Ticketing:** Jira, ServiceNow, Zendesk.
- **Integration Configuration:**
  - **Connection Setup:** Form-based setup with API keys (encrypted), endpoint URLs, timeouts, and retry policies.
  - **Test Connection:** Validate credentials and connectivity before saving.
  - **Environment Separation:** Separate configs for production, staging, and sandbox.
  - **Webhook Configuration:** Set up inbound webhooks with signature verification.
- **Integration Health:**
  - Real-time status: Connected, Degraded, Disconnected.
  - Last successful call timestamp.
  - Error rate and latency trends.
  - Circuit breaker status.
- **Failover Configuration:** Primary/backup provider setup with automatic failover rules.
- **Usage & Cost Tracking:** Monthly usage per integration with cost estimates and billing alerts.
- **Custom Integration:** Webhook-based custom integration builder with schema mapping and transformation rules.
- **API Key Management:** Generate, rotate, and revoke API keys for outbound integrations.

---

## 6.7 Workflow & Automation Configuration Page

**Route:** `/admin/automation`

**Purpose:** Configure automated workflows, triggers, and business process automation across the platform.

**Deep Details:**

- **Trigger Library:** Define event triggers:
  - **Case Events:** New case created, status changed, SLA breach, document uploaded.
  - **Customer Events:** New customer, risk score changed, document expired, account closed.
  - **Risk Events:** Sanctions match, PEP match, adverse media alert, transaction alert.
  - **System Events:** Integration failure, API error, storage threshold reached.
  - **Scheduled:** Time-based triggers (daily, weekly, monthly, cron expressions).
- **Action Library:** Define actions:
  - **Notifications:** Send email, SMS, push, Slack, Teams.
  - **Case Actions:** Create case, assign case, change status, add tag.
  - **Customer Actions:** Update field, add tag, change status, send message.
  - **External Actions:** Call webhook, create CRM record, create ticket, send to SIEM.
  - **Data Actions:** Export data, run report, archive records.
- **Workflow Builder:** Visual drag-and-drop interface connecting triggers to actions with conditional logic.
  - **Conditions:** IF/ELSE branches based on field values, risk scores, or custom rules.
  - **Loops:** Iterate over collections (e.g., notify all team members).
  - **Delays:** Wait N minutes/hours/days before next action.
  - **Parallel Actions:** Execute multiple actions simultaneously.
- **Workflow Templates:** Pre-built workflows: New Case Auto-Assignment, SLA Breach Escalation, Document Expiry Reminders, High-Risk Customer Alert, Sanctions Match Freeze.
- **Workflow Testing:** Test mode to simulate trigger and preview actions without affecting production.
- **Workflow Analytics:** Execution counts, success/failure rates, average execution time, and error logs.
- **Version Control:** Workflow versioning with rollback capability.

---

## 6.8 Notification & Communication Settings Page

**Route:** `/admin/notifications`

**Purpose:** Configure system-wide notification preferences, templates, and delivery channels.

**Deep Details:**

- **Notification Channels:**
  - **Email:** SMTP configuration, sender address, reply-to, signature, and template management.
  - **SMS:** Twilio or other provider configuration, sender ID, and compliance settings.
  - **Push:** Web push notification configuration for browser notifications.
  - **In-App:** Toast notification settings, banner settings, and notification center configuration.
  - **Slack/Teams:** Workspace connection, channel mapping, and bot configuration.
  - **Webhooks:** Custom webhook endpoints for external notification systems.
- **Notification Templates:**
  - **Template Editor:** Rich text and HTML editor with variable insertion (`{{customer.name}}`, `{{case.id}}`).
  - **Template Types:** Welcome, case update, document request, approval, rejection, reminder, alert, password reset.
  - **Multi-Language:** Separate templates per language.
  - **A/B Testing:** Test different template versions for engagement optimization.
  - **Preview:** Send test emails to verify rendering across email clients.
- **Notification Rules:**
  - **Event-Based:** Which events trigger which notifications to which recipients.
  - **Frequency:** Immediate, digest (hourly, daily, weekly), or batched.
  - **Quiet Hours:** Respect time zones and working hours.
  - **Priority:** Critical notifications bypass quiet hours and Do Not Disturb.
- **Delivery Tracking:** Track email opens, clicks, bounces, and SMS delivery status.
- **Unsubscribe Management:** Handle opt-outs per channel and notification type.

---

## 6.9 Data Retention & Privacy Settings Page

**Route:** `/admin/data-retention`

**Purpose:** Configure data retention policies, privacy settings, and compliance with data protection regulations.

**Deep Details:**

- **Retention Policies:**
  - **By Data Type:** Configure retention for: customer profiles, documents, case data, communications, audit logs, biometric data, transaction data.
  - **By Jurisdiction:** Different retention periods per regulatory requirement (EU: GDPR, US: state laws, APAC: local privacy laws).
  - **By Customer Type:** Different retention for individuals vs. businesses.
  - **By Case Outcome:** Different retention for approved vs. rejected cases.
- **Retention Actions:**
  - **Anonymization:** Remove PII but retain statistical data.
  - **Pseudonymization:** Replace identifiers with tokens.
  - **Deletion:** Permanent removal with cryptographic proof.
  - **Archival:** Move to cold storage with restricted access.
- **Legal Holds:**
  - Create legal holds with reason, scope, and expiration.
  - Override retention policies for litigation or investigation.
  - Track held records and notify when hold is lifted.
- **Privacy Settings:**
  - **Consent Management:** Default consent settings, consent expiry, and re-consent workflows.
  - **Data Minimization:** Configure which data fields are required vs. optional.
  - **Cross-Border Transfer:** Configure SCCs (Standard Contractual Clauses) and transfer mechanisms.
  - **DPO Settings:** Data Protection Officer contact info and notification rules.
- **DSAR Automation:**
  - Auto-acknowledge data subject requests.
  - Auto-generate data exports.
  - Track request deadlines with escalation.
- **Privacy Impact Assessments:** Track DPIA completion status and review dates.
- **Breach Response:** Configure breach notification workflows with regulatory deadline tracking.

---

## 6.10 Billing & Subscription Management Page

**Route:** `/admin/billing`

**Purpose:** Manage tenant subscription, usage, invoicing, and payment methods.

**Deep Details:**

- **Subscription Overview:**
  - Current plan: name, features, limits, and price.
  - Billing cycle: monthly or annual.
  - Next billing date and amount.
  - Usage vs. limits: API calls, verifications, storage, users.
- **Plan Comparison:** Side-by-side comparison of available plans with feature matrix.
- **Upgrade/Downgrade:** Self-service plan changes with prorated billing calculation and confirmation.
- **Usage Analytics:**
  - Daily/weekly/monthly usage charts.
  - Usage by feature, API endpoint, or user.
  - Projected usage and forecasted overages.
- **Invoices:**
  - Invoice history with download (PDF).
  - Invoice details: line items, quantities, rates, taxes, discounts.
  - Payment status: paid, pending, overdue.
- **Payment Methods:**
  - Credit/debit card management (add, update, remove).
  - Bank account (ACH/wire) configuration.
  - PayPal, Stripe, or other payment gateway integration.
  - Billing address and tax ID (VAT/GST number).
- **Billing Contacts:** Configure who receives invoices and billing notifications.
- **Cost Allocation:** Tag usage by department, project, or cost center for internal chargeback.
- **Quotes & Contracts:** Request custom enterprise quotes. View and sign contracts digitally.
- **Cancellation:** Self-service cancellation with data export option and retention period explanation.

---

## 6.11 System Health & Diagnostics Page

**Route:** `/admin/system-health`

**Purpose:** Monitor the technical health of the tenant's platform instance, integrations, and data pipelines.

**Deep Details:**

- **System Status Board:**
  - **Services:** Status of all platform services (API, database, cache, queue, search, storage).
  - **Integrations:** Status of all connected third-party services.
  - **Background Jobs:** Queue depth, processing rate, failed jobs, and retry status.
- **Performance Metrics:**
  - API response times (p50, p95, p99).
  - Database query performance.
  - Cache hit rates.
  - Search index health.
- **Error Logs:** Recent errors with: timestamp, severity, error message, stack trace (for admins), affected user/case, and resolution status.
- **Resource Usage:**
  - Storage utilization with breakdown by data type.
  - API rate limit consumption.
  - Concurrent user count.
  - Memory and CPU (for dedicated instances).
- **Diagnostic Tools:**
  - **API Test:** Test API endpoints with sample requests.
  - **Webhook Test:** Send test webhook payloads.
  - **Email Test:** Send test emails to verify delivery.
  - **Integration Test:** Run connectivity tests on all integrations.
- **Incident History:** Log of past incidents with: start/end time, severity, description, root cause, and resolution.
- **Maintenance Windows:** Schedule and view upcoming maintenance with user notifications.

---

## 6.12 Audit Log / System Audit Page

**Route:** `/admin/audit-log`

**Purpose:** Immutable, searchable log of all administrative and system-level actions for compliance, security, and troubleshooting.

**Deep Details:**

- **Log Entries:** Each entry contains: timestamp, actor (user or system), action, target resource, before/after values, IP address, user agent, session ID, and geolocation.
- **Filterable Categories:**
  - **Authentication:** Logins, logouts, MFA events, password changes, session management.
  - **User Management:** User creation, modification, deletion, role changes, activation/deactivation.
  - **Configuration:** Settings changes, policy updates, workflow modifications, integration changes.
  - **Data Access:** Who accessed what customer data, when, and why.
  - **Security:** Permission changes, API key rotation, encryption changes, security alerts.
  - **System:** Background job execution, data migrations, backups, restores.
- **Advanced Search:** Full-text search across all log fields. Filter by date range, actor, action, resource type, and severity.
- **Export:** Export filtered logs to CSV, JSON, or SIEM format (CEF, LEEF).
- **Retention:** Configure audit log retention (default 7 years for compliance).
- **Integrity:** Cryptographic signing of audit log entries to detect tampering.
- **Real-Time Stream:** Live feed of audit events for security operations centers.
- **Anomaly Detection:** ML-based detection of unusual audit patterns (e.g., mass data export at 3 AM, repeated failed access attempts).

---

## 6.13 Custom Fields & Data Schema Page

**Route:** `/admin/custom-fields`

**Purpose:** Extend the platform data model with tenant-specific custom fields for customers, cases, and users.

**Deep Details:**

- **Field Types:** Text, Number, Date, DateTime, Boolean, Dropdown (single/multi), URL, Email, Phone, File, JSON, Reference (to other records), Calculated (formula-based).
- **Entity Scoping:** Define custom fields for: Customers, Cases, Users, Documents, or global.
- **Field Configuration:**
  - **Label:** Display name (multi-language).
  - **API Name:** Machine-readable identifier.
  - **Description:** Help text for users.
  - **Required:** Mandatory or optional.
  - **Default Value:** Pre-populated value.
  - **Validation:** Regex patterns, min/max values, custom validation rules.
  - **Visibility:** Show/hide per role or condition.
  - **Editability:** Read-only or editable per role.
- **Conditional Logic:** Show/hide fields based on other field values (e.g., show "Business Registration Number" only if Customer Type = "Business").
- **Field Dependencies:** Cascade dropdowns (e.g., Country → State → City).
- **Data Migration:** Tools for importing data into custom fields and mapping from legacy systems.
- **Field Analytics:** Usage statistics, data quality scores, and completion rates per field.
- **API Exposure:** Custom fields automatically available via API with documentation.

---

## 6.14 Import / Export & Data Migration Page

**Route:** `/admin/data-migration`

**Purpose:** Manage bulk data import, export, and migration operations between systems.

**Deep Details:**

- **Import Jobs:**
  - **Job Creation:** Select entity type, upload file, map fields, set options.
  - **Job Monitoring:** Real-time progress with row-by-row status.
  - **Error Handling:** Detailed error reports with row numbers and correction suggestions.
  - **Scheduled Imports:** Recurring imports from SFTP, S3, or API.
- **Export Jobs:**
  - **Entity Selection:** Customers, cases, documents, audit logs, or custom queries.
  - **Filter & Scope:** Apply filters to limit export scope.
  - **Format:** CSV, Excel, JSON, XML, Parquet.
  - **Scheduling:** One-time or recurring exports.
  - **Delivery:** Download link, email, SFTP drop, S3 upload.
- **Data Transformation:**
  - **Mapping Interface:** Visual field mapping between source and target.
  - **Transformation Rules:** Apply formulas, lookups, and conversions during migration.
  - **Deduplication:** Configure deduplication rules during import.
- **Migration Templates:** Save and reuse migration configurations.
- **Data Validation:** Pre-migration validation to identify issues before committing.
- **Rollback:** Undo migration within configurable window.
- **Performance:** Handle millions of records with chunked processing and resume capability.
