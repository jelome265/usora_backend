# USORA — Customer Management Pages

> **Scope:** The complete customer lifecycle management interface, from prospect and onboarding through active relationship, offboarding, and data retention. These pages manage the customer entity as a persistent profile that transcends individual KYC cases, supporting CRM-like functionality tailored for KYC/AML contexts.

---

## 3.1 Customer Directory / Customer List Page

**Route:** `/customers`

**Purpose:** Master listing of all customers (individuals and businesses) within the tenant. The primary interface for customer discovery, segmentation, and bulk operations.

**Deep Details:**

- **Advanced Search & Filter Bar:**
  - **Text Search:** Full-text search across name, email, phone, ID numbers, company name, registration numbers, addresses. Supports fuzzy matching, phonetic matching (Soundex/Metaphone), and wildcard patterns.
  - **Customer Type Filter:** Individual, Business, Sole Proprietorship, Partnership, Trust, Foundation, Government Entity, Non-Profit.
  - **Status Filter:** Active, Pending Verification, Under Review, Restricted, Suspended, Closed, Blacklisted, Deceased.
  - **Risk Level Filter:** Low, Medium, High, Critical. Range slider for numeric risk scores.
  - **KYC Status Filter:** Verified, Pending, Expired, Rejected, Re-KYC Due, Exempt.
  - **Date Filters:** Account creation date, last activity date, next review date, verification date. Preset ranges and custom date pickers.
  - **Geographic Filters:** Country of residence/incorporation, region, city, postal code radius search.
  - **Segment Filters:** Customer tier (VIP, Standard, Basic), industry, acquisition channel, referral source.
  - **Tag Filters:** Custom tags applied by users or automated rules.
  - **Document Status:** Valid documents, expired documents, missing documents, pending documents.
  - **Has Alerts:** Toggle for customers with active risk alerts, sanctions matches, PEP status, or adverse media.
  - **Transaction Activity:** Filter by transaction volume, frequency, or last transaction date (if integrated with transaction monitoring).
- **Column Customization:** 30+ available columns including: Customer ID, Name, Type, Status, Risk Score, KYC Status, Email, Phone, Country, Created Date, Last Updated, Assigned Manager, Tags, Document Count, Case Count, Alert Count, Last Activity, Next Review Date, Source, Verification Date.
- **Saved Views:** Save filter/sort/column configurations as named views. Share views with team members. Set default view per role.
- **Smart Views:** Pre-built views: "High Risk Customers", "Re-KYC Due This Month", "Pending Verification", "Restricted Accounts", "VIP Customers", "Recently Onboarded", "Dormant Accounts".
- **Bulk Operations:** Select multiple customers for: export, tag assignment, status change, risk reassessment trigger, communication blast, case creation, or manager reassignment.
- **Customer Preview Drawer:** Click any row to open a summary drawer with key info, recent activity, active alerts, and quick actions without navigating away.
- **Pagination & Virtual Scrolling:** Handle tenants with millions of customers efficiently. Virtual scrolling for smooth performance with large datasets.
- **Export:** Export filtered results to CSV, Excel, or PDF. Scheduled exports. API export for data warehouse integration.
- **Import:** Bulk customer import with CSV/Excel templates, validation, and duplicate detection.
- **Duplicate Detection:** Fuzzy matching algorithm identifies potential duplicate customers based on name, DOB, address, ID number similarity. Shows confidence scores and merge suggestions.
- **Customer Creation:** "Add Customer" button opens the manual customer creation form (see Customer Creation page).
- **Real-Time Updates:** Customer status and risk scores update in real-time as underlying cases or alerts change.

---

## 3.2 Customer Profile / Customer Detail Page

**Route:** `/customers/:customerId`

**Purpose:** The comprehensive 360-degree view of a single customer. This is the most data-dense page in the customer management suite, consolidating all information, history, relationships, and activity.

**Deep Details:**

### Page Layout (Tabbed Interface with Persistent Summary Header)

**Persistent Header (always visible):**
- **Customer Identity Card:** Photo/avatar, full name, customer type badge, status badge (color-coded), risk score with visual indicator, KYC status badge, customer tier, account age, and last activity timestamp.
- **Quick Action Bar:** Buttons for: Edit Profile, Create Case, Send Message, View Audit Log, Download Profile PDF, Flag Customer, Restrict Account, Close Account. Some actions require confirmation or additional authorization.
- **Alert Banner:** Prominent display if customer has active risk alerts, sanctions matches, PEP status, expired documents, or account restrictions.
- **Relationship Summary:** Key numbers: total cases, open cases, total documents, active alerts, linked entities, transaction count (if available).

**Tab 1 — Overview:**
- **Personal/Business Information:** All core data in read-only cards with edit buttons. Organized into sections: Identity, Contact, Address, Employment/Business, Financial, Preferences.
- **Verification Status:** Visual checklist of all verification components: identity verified, address verified, phone verified, email verified, documents valid, biometric matched, sanctions cleared, PEP cleared, adverse media checked. Green checkmarks, red X's, yellow pending indicators.
- **Risk Summary:** Current risk score with historical trend line chart. Risk category breakdown (geographic, customer type, behavioral, etc.). Risk change log with reasons.
- **Active Cases:** Mini-list of current open cases with status, type, and quick links.
- **Recent Activity:** Last 10 activities (case updates, document uploads, status changes, communications) with timestamps.
- **Notes:** Pinned analyst notes visible to all authorized users. Supports @mentions and attachments.

**Tab 2 — Cases & Reviews:**
- **Complete Case History:** Table of all KYC cases for this customer: case ID, type, status, submission date, resolution date, outcome, assigned analyst, and risk score at time of case. Click to open case detail.
- **Case Timeline:** Visual timeline showing all cases chronologically with status indicators.
- **Re-KYC Schedule:** Upcoming periodic review dates with countdown and status.
- **Review Outcomes:** Summary of all approval/rejection decisions with reasons.

**Tab 3 — Documents:**
- **Document Vault:** Gallery of all uploaded documents organized by type: ID, Proof of Address, Proof of Income, Business Registration, Bank Statements, Contracts, Other. Each document shows: thumbnail, filename, upload date, expiry date, verification status, OCR confidence, and fraud check result.
- **Document Detail Drawer:** Click to open full document viewer with metadata, extraction results, and verification history.
- **Document Upload:** Inline upload for additional documents with classification, expiry date setting, and notes.
- **Document Expiry Tracking:** Visual indicators for documents nearing expiry (yellow at 30 days, red at expired). Auto-reminder configuration.
- **Document Download:** Bulk download as ZIP or individual files.

**Tab 4 — Risk & Compliance:**
- **Risk Assessment History:** All risk assessments with scores, dates, assessors, and methodologies. Side-by-side comparison of assessments over time.
- **Watchlist Screening Results:** All sanctions, PEP, and adverse media screenings with: screening date, lists checked, match results, match confidence, resolution, and next screening date.
- **Sanctions Status:** Current status with list sources (OFAC, UN, EU, HMT, etc.), last check date, and monitoring frequency.
- **PEP Status:** PEP indicators with role, country, relationship type, risk level, and de-PEP date if applicable.
- **Adverse Media:** Curated list of negative news with relevance scores, sentiment analysis, source credibility, and date. Filter by category (fraud, corruption, terrorism, financial crime, litigation).
- **Transaction Monitoring:** If integrated, summary of transaction patterns, alerts, and suspicious activity reports (SARs).
- **Suspicious Activity Reports:** List of filed SARs with filing date, reference number, status, and narrative.

**Tab 5 — Relationships & Network:**
- **Entity Graph:** Interactive network visualization showing all related entities: beneficial owners, directors, signatories, family members, business associates, subsidiaries, parent companies, shareholders. Powered by graph database.
- **Relationship Table:** Tabular view of relationships with: related entity name, relationship type, strength, verification status, risk contribution, and link to related entity profile.
- **UBO Chain:** Visual ownership chain from the customer up to ultimate beneficial owners with percentage ownership at each level.
- **Corporate Structure:** Org chart for business customers showing subsidiaries, branches, and affiliates.
- **Shared Attributes:** Automatic detection of shared addresses, phone numbers, emails, or IP addresses with other customers (potential shell company or fraud ring indicators).

**Tab 6 — Communications:**
- **Communication Thread:** All emails, SMS, chat messages, and calls with this customer in chronological order. Supports filtering by channel and direction (inbound/outbound).
- **Compose Message:** Inline message composition with templates, attachments, and scheduling.
- **Call Log:** If phone integration exists, call recordings (with consent flags), duration, and transcripts.
- **Communication Preferences:** Customer's preferred channels, languages, and opt-out status.

**Tab 7 — Activity Log / Audit Trail:**
- **Comprehensive Audit Log:** Every action related to this customer: profile edits, document uploads, case creations, status changes, risk updates, communications, access by users. Each entry shows: timestamp, user/system, action, before/after values, IP address, and session ID.
- **Filter & Search:** Filter by action type, user, date range, or keyword search.
- **Export:** Export audit log for compliance or legal purposes.
- **Immutable:** Audit logs are tamper-evident (cryptographically signed or blockchain-anchored in high-security deployments).

**Tab 8 — Settings & Preferences:**
- **Customer Preferences:** Language, timezone, communication preferences, marketing consent, data sharing consent.
- **Account Settings:** Account restrictions, service tiers, feature flags, custom fields.
- **Data Subject Rights:** Interface for managing GDPR/CCPA requests: access requests, rectification requests, erasure requests, portability requests, restriction requests. Track request status and deadlines.

---

## 3.3 Customer Creation / Onboarding Form Page

**Route:** `/customers/new`

**Purpose:** Manual creation of a new customer record. Used when customers are onboarded through non-digital channels (in-person, phone, paper forms) or when admins need to pre-create records.

**Deep Details:**

- **Wizard Interface:** Multi-step wizard to prevent form overwhelm. Steps: Customer Type → Identity Information → Contact Details → Address → Documents → Review & Submit.
- **Customer Type Selection (Step 1):** Individual, Business, Trust, Foundation, Government, Non-Profit. Selection determines subsequent form fields.
- **Identity Information (Step 2):**
  - **Individual:** Full legal name (first, middle, last, suffix), former names/aliases, date of birth, place of birth, nationality, gender, marital status, occupation, employer name, job title, employment status, income range, source of wealth.
  - **Business:** Legal name, trading names/DBA, registration number, incorporation date, jurisdiction, legal form (LLC, Corporation, Partnership, etc.), industry code (NAICS/SIC), website, annual revenue, employee count, VAT/Tax ID, LEI (Legal Entity Identifier), stock exchange listing status, ticker symbol.
- **Contact Details (Step 3):** Primary and secondary email, phone (mobile, landline, work), preferred contact method, preferred language.
- **Address (Step 4):** Residential/business address with address autocomplete integration (Google Places, Loqate, etc.). Supports multiple addresses (registered, trading, correspondence, billing). Address verification check with confidence score.
- **Documents (Step 5):** Upload required documents based on customer type and jurisdiction. Document classification auto-detection. Expiry date capture. Document quality check (blur, glare, truncation detection).
- **Custom Fields:** Dynamic form fields based on tenant configuration and customer type. Conditional logic (e.g., show "Stock Exchange" only if "Publicly Listed" is selected).
- **Beneficial Ownership (Business):** UBO declaration form with name, DOB, nationality, address, ownership percentage, control type (direct, indirect, voting rights, appointment rights), and verification documents.
- **Risk Pre-Assessment:** Based on entered data, a preliminary risk score is calculated and displayed before submission. Flags high-risk characteristics.
- **Review & Submit (Step 6):** Read-only summary of all entered data. Edit any section inline. Checkbox for data accuracy declaration. Digital signature capture (draw or type).
- **Auto-Save:** Draft saved every 30 seconds. Can resume later from drafts list.
- **Duplicate Check:** Before final submission, system checks for potential duplicates and presents matches with confidence scores. User can confirm "This is a new customer" or link to existing.
- **Submission:** Creates customer record and optionally creates an initial KYC case. Confirmation with customer ID.

---

## 3.4 Customer Edit / Profile Update Page

**Route:** `/customers/:customerId/edit`

**Purpose:** Modify existing customer information with full audit tracking and approval workflows for sensitive changes.

**Deep Details:**

- **Field-Level Permissions:** Different fields have different edit permissions based on user role. Some fields (ID number, date of birth) may require senior approval or documentation.
- **Change Tracking:** Every edit is tracked with: old value, new value, who made the change, when, and why (mandatory reason field for sensitive changes).
- **Approval Workflow:** For high-sensitivity fields, changes enter a pending approval state. Original value remains active until approved. Approver gets notification with change details and approve/reject buttons.
- **Documentary Evidence:** Changes to key fields (name, DOB, address) require supporting document upload (e.g., marriage certificate for name change, utility bill for address change).
- **Validation:** Real-time validation with cross-field checks. Address verification on address changes. Email/phone verification on contact changes.
- **Bulk Edit:** For mass updates (e.g., updating all customers in a specific region), bulk edit interface with CSV upload or spreadsheet-like inline editing.
- **Revert Capability:** Ability to revert to previous values with full audit trail of the reversion.
- **Customer Notification:** Optional automatic notification to customer when certain fields are changed (e.g., email, phone, address).
- **Data Integrity Checks:** Warn if changes would create inconsistencies (e.g., changing country to one where current ID document type is not accepted).

---

## 3.5 Customer Segmentation / Smart Lists Page

**Route:** `/customers/segments`

**Purpose:** Create, manage, and analyze customer segments for targeted operations, marketing, risk management, and reporting.

**Deep Details:**

- **Segment Builder:** Visual query builder to define segments using AND/OR logic across all customer attributes: demographics, risk scores, KYC status, transaction behavior, geographic, temporal, and custom fields.
  - **Conditions:** Equals, Not Equals, Contains, Starts With, Greater Than, Less Than, Between, In List, Not In List, Is Empty, Is Not Empty, Regex Match.
  - **Date Conditions:** Before, After, Between, In Last N Days, In Next N Days, On Date, Relative (today, yesterday, this week, etc.).
  - **Behavioral Conditions:** Has done X in last N days, has not done Y, frequency of Z.
- **Dynamic vs. Static Segments:**
  - **Dynamic:** Membership updates automatically as customer data changes. Real-time member count.
  - **Static:** Snapshot of customers at creation time. Useful for campaign targeting or audit samples.
- **Segment Preview:** Before saving, preview the first 50 matching customers and total count.
- **Segment Actions:**
  - **Export:** Export segment members to CSV/Excel.
  - **Bulk Operation:** Apply bulk actions to all members (tag, create case, send message, update field).
  - **Report:** Generate analytics report on segment characteristics.
  - **Alert:** Create monitoring alert when customers enter or leave the segment.
  - **Integration:** Sync segment to external systems (CRM, marketing platform, email tool).
- **Pre-Built Segments:** "High Risk", "VIP", "Re-KYC Due", "Dormant", "New This Month", "Document Expiring", "Sanctions Match", "PEP", "Crypto Users", "High Transaction Volume".
- **Segment Analytics:** Dashboard showing segment composition: risk distribution, geographic distribution, age distribution, activity levels, and trend over time.
- **Segment Overlap:** Visual Venn diagram showing overlap between segments. Identify customers in multiple high-risk segments.
- **Sharing & Permissions:** Segments can be private, shared with team, or public. Role-based access to segment data.

---

## 3.6 Customer Offboarding / Account Closure Page

**Route:** `/customers/:customerId/close` or `/customers/offboarding`

**Purpose:** Manage the complete customer offboarding lifecycle including account closure, data retention, and regulatory reporting.

**Deep Details:**

- **Closure Reasons:** Dropdown with: Customer Request, Business Decision, Fraud Detected, Sanctions Match, Regulatory Order, Inactivity, Deceased, Duplicate Account, Other. Mandatory detailed explanation.
- **Closure Workflow:**
  - **Initiation:** Request submitted by authorized user or automated trigger.
  - **Review:** Compliance review of closure reason and regulatory implications.
  - **Approval:** Multi-level approval based on closure reason and customer risk level.
  - **Pre-Closure Actions:** System-generated checklist: settle outstanding cases, export data for customer (if requested), notify relevant teams, revoke API keys, disable services.
  - **Execution:** Account status changed to "Closing" then "Closed". All active services suspended.
  - **Post-Closure:** Data retention policy applied. Some data retained for regulatory periods; personal data anonymized or deleted per GDPR/CCPA.
- **Data Retention Configuration:** Define retention periods by data type and regulatory requirement. Automatic scheduling of data deletion/anonymization.
- **Customer Communication:** Automated closure notification with reason (if appropriate), data retention notice, and contact information for disputes.
- **Right to Erasure (GDPR):** Handle "forget me" requests with verification workflow, data deletion confirmation, and certificate of deletion.
- **Reinstatement:** Process for reopening closed accounts with approval workflow, data restoration, and re-verification requirements.
- **Offboarding Analytics:** Track closure reasons, volumes, trends, and customer lifetime value at closure.
- **Regulatory Reporting:** Generate closure reports for regulators (e.g., suspicious closure reports for fraud cases).

---

## 3.7 Customer Data Export / Portability Page

**Route:** `/customers/:customerId/export` or `/customers/data-portability`

**Purpose:** Facilitate data subject access requests (DSAR) and data portability requirements under GDPR, CCPA, and other privacy regulations.

**Deep Details:**

- **Export Request Form:** Customer or authorized user requests data export. Fields: request type (full profile, specific categories, time range), format (JSON, CSV, PDF, XML), delivery method (secure download link, encrypted email, physical media).
- **Identity Verification:** Before processing, verify requester identity through multi-step verification (email confirmation, ID re-upload, security questions) to prevent data breaches.
- **Data Scope Selection:** Granular selection of data categories: personal information, documents, case history, communications, risk assessments, transaction data, audit logs, third-party data.
- **Processing Queue:** Export requests enter a queue with SLA tracking (GDPR requires response within 30 days). Status tracking: Received, Processing, Ready, Delivered, Expired.
- **Data Packaging:** Automated compilation of all requested data into structured, machine-readable format. Metadata included (data source, collection date, processing purpose).
- **Security:** Exports are encrypted with password or PGP. Download links expire after 7 days. Access logging.
- **Redaction:** Automatically redact third-party personal data that the customer is not entitled to receive (e.g., other customers' data in shared transaction records).
- **Delivery Notification:** Customer notified when export is ready with secure access instructions.
- **Audit Trail:** Full log of all export requests, what was exported, to whom, and when. Immutable record for regulatory compliance.
- **Recurring Exports:** Schedule periodic data exports for customers who want regular backups.
- **API Endpoint:** Programmatic data export for customers using API integration.

---

## 3.8 Customer Merge / Duplicate Resolution Page

**Route:** `/customers/merge` or `/customers/duplicates`

**Purpose:** Identify and resolve duplicate customer records to maintain data integrity and prevent fraud.

**Deep Details:**

- **Duplicate Detection Engine:**
  - **Exact Match:** Identical email, phone, ID number, or registration number.
  - **Fuzzy Match:** Similar names (Levenshtein distance, Jaro-Winkler), similar addresses, same DOB with slight name variations, same phone with different formatting.
  - **Graph Match:** Shared relationships, shared IP addresses, shared devices, shared bank accounts.
  - **ML-Based:** Machine learning model trained on historical duplicates to identify non-obvious matches.
- **Duplicate Queue:** List of potential duplicate pairs/groups with match confidence score, match reasons, and suggested primary record.
- **Merge Interface:** Side-by-side comparison of duplicate records. For each field, choose which value to keep (left, right, or custom). Preview merged result before confirmation.
- **Merge Rules:** Configurable rules for automatic field selection (e.g., always keep most recent address, always keep highest risk score).
- **Data Consolidation:** On merge: consolidate cases, documents, communications, and activity logs under the primary record. Maintain references to original record IDs in audit trail.
- **Relationship Preservation:** Ensure all relationships (UBOs, directors, family) are correctly mapped post-merge.
- **Undo Capability:** Merge operations can be undone within 30 days with full restoration of original records.
- **Bulk Merge:** For large duplicate sets, bulk merge with rule-based field selection.
- **False Positive Handling:** Mark potential duplicates as "Not Duplicates" with reason. System learns from these decisions to improve matching.
- **Audit Trail:** Complete record of all merge operations with before/after state and who performed the merge.

---

## 3.9 Customer Communication Preferences Page

**Route:** `/customers/:customerId/preferences` or `/customers/preferences`

**Purpose:** Manage how and when the platform communicates with each customer, ensuring compliance with marketing and privacy regulations.

**Deep Details:**

- **Channel Preferences:** Email, SMS, Phone Call, Push Notification, Postal Mail. Toggle each on/off independently.
- **Purpose Preferences:**
  - **Transactional:** Account notifications, case updates, security alerts (cannot be disabled).
  - **Marketing:** Product updates, promotions, newsletters (opt-in required).
  - **Service:** Re-KYC reminders, document expiry warnings, service announcements.
  - **Legal:** Regulatory notices, terms updates, privacy policy changes.
- **Frequency Settings:** Daily digest, weekly summary, immediate, or quiet hours (do not disturb time windows).
- **Language Preference:** Primary and secondary language for communications.
- **Format Preference:** HTML email vs. plain text. PDF attachments vs. inline.
- **Consent Management:**
  - **Consent History:** Log of when and how consent was given, modified, or withdrawn. Includes consent text version and IP address.
  - **Granular Consent:** Separate consent for different data processing purposes.
  - **Withdrawal:** One-click withdrawal of consent with confirmation. Immediate effect.
  - **Re-Consent:** Automated re-consent campaigns when consent is about to expire or processing purposes change.
- **Regulatory Compliance:** Automatic enforcement of regional communication laws (CAN-SPAM, GDPR, CASL, PDPA).
- **Preference Center:** Customer-facing self-service portal for managing their own preferences.

---

## 3.10 Customer Tagging & Categorization Page

**Route:** `/customers/tags` or inline within customer profile

**Purpose:** Apply flexible, user-defined labels to customers for segmentation, workflow routing, and operational organization.

**Deep Details:**

- **Tag Management:** Create, edit, and delete tags. Tags have: name, color, description, category (system-defined or custom), and rules for auto-application.
- **Auto-Tagging Rules:** Configure rules to automatically apply tags based on customer attributes or events. Example: "Auto-tag as 'High Value' if annual revenue > $1M" or "Auto-tag as 'Crypto' if business type is cryptocurrency exchange."
- **Manual Tagging:** Apply/remove tags on individual customers or in bulk. Tag suggestions based on customer profile.
- **Tag Categories:** Organize tags into categories: Risk, Segment, Source, Status, Workflow, Compliance, Custom.
- **Tag Analytics:** Report on customer distribution by tag, tag trend over time, and correlation between tags and outcomes.
- **Tag-Based Permissions:** Restrict access to customers with certain tags (e.g., only senior analysts can view "VIP" tagged customers).
- **Tag-Based Workflows:** Trigger workflows when tags are applied or removed (e.g., apply "Escalated" tag → auto-create escalation case).
- **Tag Search:** Search and filter customers by tags across the platform.
- **Tag Governance:** Tag creation permissions, approval workflow for new tags, and periodic tag cleanup recommendations.

---

## 3.11 Customer Import / Bulk Onboarding Page

**Route:** `/customers/import`

**Purpose:** Mass import of customer records for large-scale onboarding, data migration, or integration with external systems.

**Deep Details:**

- **Template System:** Downloadable templates for different customer types with all required and optional fields. Templates include data validation rules and example rows.
- **File Upload:** Support CSV, Excel, JSON, XML. Drag-and-drop with progress indication. File size up to 500MB.
- **Data Mapping:** Visual interface to map uploaded columns to system fields. Auto-mapping based on column header names. Handle custom fields.
- **Validation Engine:**
  - **Structure Validation:** Required fields present, correct data types, valid enum values.
  - **Content Validation:** Email format, phone format by country, date validity, ID number checksums (where applicable), address validation.
  - **Reference Validation:** Foreign key references exist (e.g., assigned manager ID exists).
  - **Duplicate Detection:** Flag potential duplicates within the import file and against existing database.
- **Preview Mode:** Show first 100 rows with validation status per row (valid, warning, error). Color-coded indicators.
- **Import Modes:**
  - **Validate Only:** Check without importing. Generate detailed error report.
  - **Import Valid Rows:** Import only rows passing validation. Failed rows in separate report.
  - **Import All:** Import all rows, marking failed rows for manual review.
  - **Update Existing:** Match by key field (email, ID) and update existing records rather than create new.
  - **Upsert:** Create new if not exists, update if exists.
- **Progress Tracking:** Real-time progress bar with count of processed, succeeded, failed rows. Estimated time remaining.
- **Post-Import Report:** Summary statistics, error details with row numbers, and downloadable log.
- **Rollback:** Undo import within 24 hours if issues discovered.
- **API Import:** Programmatic bulk import via API with webhook notifications for completion.
- **Scheduled Imports:** Configure recurring imports from SFTP, S3, or API endpoints for continuous synchronization.

---

## 3.12 Customer Activity & Engagement Page

**Route:** `/customers/:customerId/activity` or `/customers/activity`

**Purpose:** Track and analyze customer interactions with the KYC platform and related services.

**Deep Details:**

- **Activity Timeline:** Chronological feed of all customer activities: logins, document uploads, form submissions, case status checks, message reads, video call participation, consent changes.
- **Activity Filters:** Filter by activity type, date range, channel, and outcome.
- **Engagement Metrics:**
  - **Login Frequency:** How often the customer logs into the portal.
  - **Document Upload Speed:** Time from request to document upload.
  - **Form Completion Rate:** Percentage of forms started that are completed.
  - **Response Time:** Average time to respond to analyst requests.
  - **Session Duration:** How long customers spend on each step.
  - **Drop-off Points:** Where in the process customers abandon.
- **Engagement Scoring:** Composite score of customer engagement (0-100). Low engagement triggers proactive outreach.
- **Behavioral Analytics:**
  - **Device Fingerprinting:** Devices used, browser, OS, screen resolution, timezone.
  - **Geolocation:** IP-based location vs. declared address. Flag discrepancies.
  - **Velocity Patterns:** Unusual patterns like rapid form completion (bot-like) or excessive retries.
  - **Copy-Paste Detection:** Flag fields filled via copy-paste vs. manual typing (potential fraud indicator).
- **Customer Journey Map:** Visual representation of the customer's path through KYC with time spent at each stage and exit points.
- **Proactive Alerts:** Alert analysts when customer engagement drops or unusual behavior is detected.
- **Export:** Export activity data for behavioral analysis or machine learning model training.

---

## 3.13 Customer Risk Reassessment Page

**Route:** `/customers/:customerId/risk-reassessment` or `/customers/risk-reassessment`

**Purpose:** Trigger and conduct periodic or event-driven risk reassessments of existing customers.

**Deep Details:**

- **Reassessment Triggers:**
  - **Scheduled:** Based on risk-based review calendar (low risk annually, high risk quarterly).
  - **Event-Driven:** Address change, new beneficial owner, adverse media alert, sanctions list update, transaction pattern change, regulatory change.
  - **Manual:** Analyst-initiated reassessment with reason.
- **Reassessment Wizard:**
  - **Step 1 — Data Refresh:** Pull latest customer data, updated documents, and refreshed external checks.
  - **Step 2 — Risk Factor Review:** Review each risk factor category with updated information. Confirm or update risk ratings.
  - **Step 3 — Enhanced Due Diligence (if triggered):** Additional questions and document requests for high-risk customers.
  - **Step 4 — Risk Score Calculation:** System calculates new risk score based on updated factors and tenant risk model.
  - **Step 5 — Decision:** Approve new risk level, escalate for review, or trigger EDD.
  - **Step 6 — Documentation:** Record rationale for risk change and any mitigating controls implemented.
- **Risk Change Impact Analysis:** Show what changes if risk score goes up or down: review frequency, monitoring intensity, approval authority, and regulatory reporting requirements.
- **Customer Notification:** If risk level changes, notify customer of any new requirements or restrictions.
- **Reassessment History:** Complete log of all reassessments with before/after risk scores, reasons, and outcomes.
- **Bulk Reassessment:** Trigger reassessment for all customers in a segment (e.g., all customers in a newly sanctioned country).

---

## 3.14 Customer Self-Service Portal (External-Facing)

**Route:** `/portal` (customer-facing subdomain: `customer.tenant.usora.app`)

**Purpose:** Branded, white-labeled portal where customers can complete KYC, upload documents, check status, and manage their own information.

**Deep Details:**

- **Branding:** Fully white-labeled with tenant logo, colors, fonts, and custom CSS. Custom domain support.
- **Registration / Login:** Customer self-registration or login via invitation link. SSO support for enterprise customers.
- **Dashboard:** Customer-facing summary: KYC status, pending actions, document status, case history, and messages from the compliance team.
- **KYC Form:** Step-by-step KYC questionnaire with progress bar. Auto-save, validation, and help tooltips. Mobile-optimized.
- **Document Upload:** Drag-and-drop document upload with real-time quality checks (blur, glare, truncation), format validation, and classification suggestions. Progress tracking for each document.
- **Biometric Capture:** Guided selfie capture with liveness instructions. Real-time feedback on lighting, face position, and clarity. Video recording option.
- **Status Tracking:** Real-time case status with estimated completion time. Visual progress indicator through KYC stages.
- **Messaging:** Two-way secure messaging with the compliance team. Attachment support. Read receipts.
- **Document Vault:** View all submitted documents with status, expiry dates, and re-upload options for rejected documents.
- **Profile Management:** Update contact information, addresses, and preferences. Some changes may require re-verification.
- **Consent Management:** View and manage data processing consents. Withdraw consent with consequences explained.
- **Data Export Request:** Self-service DSAR initiation with status tracking.
- **Notifications:** Email and push notifications for status changes, document requests, and approvals.
- **Accessibility:** WCAG 2.1 AA compliant. Screen reader support, keyboard navigation, and high contrast mode.
- **Mobile App:** Optional native iOS/Android app with camera-optimized document capture, push notifications, and offline form saving.
- **Multi-Language:** Full i18n support with 30+ languages. RTL language support.
- **Security:** End-to-end encryption for document uploads, secure session management, device fingerprinting, and fraud detection.
- **Help & Support:** In-portal help center with FAQs, chatbot, and contact options.
