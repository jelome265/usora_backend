# USORA — KYC Operations Pages

> **Scope:** The operational heart of the platform where KYC analysts, compliance officers, and automated systems process verification requests, manage cases, review documents, conduct due diligence, and make approval decisions. These pages handle the full lifecycle of a KYC case from submission to resolution.

---

## 2.1 KYC Dashboard / Operations Command Center

**Route:** `/kyc/dashboard`

**Purpose:** Centralized operational dashboard providing real-time visibility into all KYC activities, queues, performance metrics, and actionable items requiring human attention.

**Deep Details:**

- **Real-Time Metrics Cards:** Top row displays live-updating KPIs: Total Cases Today, Pending Review Queue, Auto-Approved Rate, Auto-Rejected Rate, Average Processing Time, SLA Breach Count, and Risk Alert Count. Each card is clickable to drill down into the relevant filtered view.
- **Queue Overview Widget:** Visual representation of case distribution across statuses: New Submissions, Awaiting Documents, In Review, Pending Customer Response, Escalated, Approved, Rejected, On Hold. Shows count and percentage. Color-coded (green for approved, red for rejected, yellow for pending, orange for escalated).
- **My Tasks / Work Queue:** Personalized list of cases assigned to the logged-in analyst. Sorted by priority (SLA deadline, risk score, customer tier). Columns: Case ID, Customer Name, Submission Time, SLA Deadline (with countdown), Risk Score, Status, Assigned By, and Quick Actions (Open, Reassign, Escalate).
- **SLA Monitor:** Visual gauge showing current SLA performance vs. target. Breakdown by case type, priority, and analyst. Highlights cases approaching SLA breach (yellow at 80%, red at 95% of SLA time).
- **Recent Activity Feed:** Chronological stream of significant events: new submissions, status changes, escalations, approvals, rejections, customer messages, system alerts. Filterable by event type, user, and time range.
- **Performance Leaderboard:** Optional gamification showing analyst productivity metrics (cases processed, accuracy rate, average handling time, customer satisfaction) for the current day/week/month. Can be toggled on/off per tenant.
- **Risk Alert Banner:** Prominent banner when high-risk cases or suspicious patterns are detected. Includes alert severity, description, and "Investigate Now" CTA.
- **Shift Handoff Notes:** Area for outgoing analysts to leave notes for incoming shifts. Tagged by time and user.
- **Quick Actions Bar:** Buttons for common tasks: "Start Next Case" (pulls highest priority from queue), "Bulk Assign", "Run Report", "Escalation Matrix".
- **Calendar Integration:** Shows upcoming scheduled reviews, periodic re-KYC deadlines, and compliance audit dates.
- **Customizable Layout:** Users can drag-and-drop widgets, resize panels, and save layouts. Multiple layout presets (Analyst View, Manager View, Auditor View).
- **Auto-Refresh:** Configurable refresh interval (default 30 seconds) with visual indicator of last update time.
- **Export:** One-click export of dashboard data to PDF, CSV, or Excel for shift reports.
- **Drill-Down Navigation:** Every metric and chart element is clickable to navigate to the relevant detailed view with pre-applied filters.

---

## 2.2 Case Management / Case List Page

**Route:** `/kyc/cases`

**Purpose:** Comprehensive listing and management of all KYC cases across the tenant. The primary interface for finding, filtering, and bulk-operating on cases.

**Deep Details:**

- **Advanced Filter Bar:** Multi-criteria filtering system with:
  - **Status:** Multi-select dropdown (New, Awaiting Documents, In Review, Pending Customer, Escalated, Approved, Rejected, On Hold, Withdrawn, Expired)
  - **Date Range:** Calendar picker with presets (Today, Yesterday, Last 7 Days, Last 30 Days, Custom Range). Filters by submission date, last updated, or SLA deadline.
  - **Risk Score:** Range slider (0-100) or preset buckets (Low 0-30, Medium 31-70, High 71-100)
  - **Customer:** Searchable dropdown with autocomplete
  - **Case Type:** Individual, Business, Beneficial Owner, PEP, Sanctions Screening
  - **Priority:** Critical, High, Normal, Low
  - **Assigned To:** User dropdown with "Unassigned" option
  - **Source:** API, Web Portal, Bulk Upload, Manual Entry, Third-Party Integration
  - **Country:** Multi-select of customer/jurisdiction countries
  - **Tags:** Custom tags applied by analysts or automated rules
  - **Document Status:** All Received, Missing, Expired, Under Review
  - **Has Alerts:** Toggle for cases with active risk alerts
  - **SLA Status:** On Track, At Risk, Breached
- **Saved Filters:** Users can save filter combinations with custom names for quick recall. Shared filters can be created by managers for team use.
- **Column Customization:** Users can show/hide columns, reorder them, and resize them. Available columns include: Case ID, Customer Name, Type, Status, Risk Score, Priority, Submission Date, SLA Deadline, Assigned To, Last Updated, Source, Country, Tags, Document Count, Alert Count, Processing Time.
- **Sorting:** Multi-column sort with primary, secondary, and tertiary sort keys. Click column header to sort; shift-click to add secondary sort.
- **Pagination:** Configurable page sizes (25, 50, 100, 250). Shows total count and current range. Jump-to-page input.
- **Bulk Actions:** Checkbox selection on each row with a sticky bulk action bar. Actions include: Assign To, Change Status, Add Tag, Remove Tag, Export Selected, Merge Cases, Archive, Send Bulk Message.
- **Row Actions:** Per-row dropdown menu with: Open Case, View Customer Profile, View Audit Log, Reassign, Escalate, Duplicate, Clone for Re-KYC, Print Case Summary.
- **Case Preview Drawer:** Clicking a row opens a slide-out drawer with case summary without leaving the list page. Includes key details, latest activity, and quick action buttons.
- **Export:** Export current filtered view to CSV, Excel, or PDF. Scheduled export option for recurring reports.
- **Real-Time Updates:** Cases update in real-time as status changes occur. New cases appear with a subtle highlight animation.
- **Keyboard Shortcuts:** `J`/`K` for row navigation, `Enter` to open, `E` to escalate, `A` to assign.
- **Empty States:** Contextual empty state messages based on active filters ("No cases match your criteria — try adjusting filters").

---

## 2.3 Case Detail / Case Review Page

**Route:** `/kyc/cases/:caseId`

**Purpose:** The single most important page in the KYC operations suite. Provides a 360-degree view of a single case with all evidence, documents, risk signals, and tools needed to make an approval decision.

**Deep Details:**

### Page Layout (Three-Column Design)

**Left Column — Case Timeline & Activity (25% width, collapsible):**
- **Chronological Timeline:** Vertical timeline of all events in the case lifecycle: submission, document uploads, automated checks, manual reviews, status changes, customer communications, risk alerts, and system events. Each event shows timestamp, user/system, description, and metadata.
- **Filterable Events:** Filter by event type (Document, Review, System, Communication, Risk, Status Change).
- **Event Detail Expansion:** Click any event to see full details, including raw API responses for automated checks.
- **Add Note:** Inline textarea to add analyst notes that are timestamped and signed. Notes can be internal-only or customer-visible.
- **Mention System:** `@username` mentions in notes trigger notifications to mentioned users.
- **Attachment Support:** Attach files to notes (screenshots, reference documents).

**Center Column — Evidence & Review Workspace (50% width):**
- **Tabbed Interface:**
  - **Overview Tab:** Case summary card with all key data at a glance: customer info, case type, status badge, risk score with gauge, SLA countdown, assigned analyst, source, submission channel, and priority.
  - **Documents Tab:** Document gallery with thumbnail grid. Click to open full document viewer (see Document Viewer page). Shows document type, upload date, verification status, OCR confidence, and fraud detection results. Supports side-by-side comparison of multiple documents.
  - **Biometric Tab:** Selfie image/video, liveness check results, face match score against ID document, 3D depth map visualization, and anti-spoofing analysis.
  - **Data Tab:** Structured data extracted from documents and forms. Editable fields for analyst correction with change tracking. Shows original OCR text, corrected value, confidence score, and field validation status.
  - **Risk Tab:** Risk assessment summary with score breakdown by category (identity, document, behavioral, geographic, PEP/sanctions, adverse media). Visual radar chart. Individual risk factor cards with severity, description, evidence, and recommended action.
  - **Checks Tab:** Results of all automated checks: document authenticity, watchlist screening, address verification, phone verification, email verification, device fingerprinting, IP geolocation, velocity checks. Each check shows status (pass/fail/manual review), raw data, and confidence.
  - **Network Tab:** Visual graph of related entities (same address, shared directors, family members, business associates). Powered by graph database. Shows risk propagation paths.
  - **Communication Tab:** All email/SMS/chat exchanges with the customer. Supports composing new messages with templates.
- **Decision Panel:** Sticky footer or floating panel with primary action buttons:
  - **Approve:** With optional approval category (Standard, Expedited, Exception). Requires confirmation modal with reason selection or free-text justification.
  - **Reject:** With rejection reason dropdown (Document Fraud, Identity Mismatch, Incomplete Information, Sanctions Match, High Risk, Policy Violation, Customer Request, Other) and mandatory detailed explanation.
  - **Request More Info:** Sends automated or custom message to customer requesting specific documents or clarifications. Sets case status to "Pending Customer Response" with configurable timeout.
  - **Escalate:** Routes to senior analyst, compliance manager, or specialized team (fraud, legal, PEP). Requires escalation reason and priority.
  - **On Hold:** Pauses processing with reason and expected resumption date.
  - **Refer:** Sends case to external party (legal counsel, regulator, law enforcement) with secure sharing.
- **Decision History:** List of all previous decisions on this case (for re-reviews, appeals, or escalations) with who, when, and why.
- **Confidence Scoring:** The system displays its confidence in the case outcome and flags when human review is strongly recommended.

**Right Column — Customer Context & Tools (25% width, collapsible):**
- **Customer Mini-Profile:** Photo, name, contact info, customer tier, account age, total cases, and risk history.
- **Quick Actions:** Buttons to: View Full Customer Profile, View Case History, Start Video Call (if integrated), Send Message, Block Customer, Flag for Review.
- **Related Cases:** List of other cases for the same customer (past and present). Status indicators and quick links.
- **Watchlist Matches:** If any, shows matching entries with similarity scores, source databases, and match details.
- **PEP Status:** Politically Exposed Person indicators with role, country, relationship, and risk level.
- **Adverse Media:** Recent negative news articles or public records with sentiment analysis and relevance scoring.
- **Sanctions Status:** Real-time sanctions list check results with list source, match confidence, and screening date.
- **Analyst Tools:**
  - **Calculator:** For financial threshold checks.
  - **Translator:** Inline translation for foreign language documents.
  - **Currency Converter:** Real-time exchange rates.
  - **Timer:** Case handling timer (starts when opened, pauses on inactivity).
  - **Checklist:** Configurable review checklist that analysts must complete before making a decision. Tracks completion percentage.
- **Collaboration:** See which other analysts are viewing this case (real-time presence). Live cursor indicators if multiple analysts are reviewing simultaneously.

---

## 2.4 Case Creation / Manual Case Entry Page

**Route:** `/kyc/cases/new`

**Purpose:** Allows authorized users to manually create KYC cases for customers who cannot or will not use self-service channels. Common for VIP customers, offline applications, or special circumstances.

**Deep Details:**

- **Case Type Selection:** Wizard step 1 — Choose case type: Individual, Business, Beneficial Owner, Corporate Structure, Trust, Foundation, or Custom.
- **Customer Lookup:** Search existing customer database to link the case. If not found, create new customer inline.
- **Form Sections (Dynamic based on case type):**
  - **Personal Information:** Full name, date of birth, nationality, gender, marital status, occupation, employer, income range.
  - **Contact Information:** Email, phone, address (with address verification integration), preferred language, preferred contact method.
  - **Identification:** ID document type, number, issuing country, issue date, expiry date, place of birth. Upload fields for document images.
  - **Business Information (if applicable):** Company name, registration number, incorporation date, jurisdiction, business type, industry code (NAICS/SIC), website, annual revenue, employee count, VAT/Tax ID.
  - **Ownership Structure:** UBO declaration with percentage ownership, control type, and verification status.
  - **Purpose of Relationship:** Intended use of service, expected transaction volume, expected transaction types, source of funds.
  - **Risk Assessment:** Preliminary risk rating based on customer type, jurisdiction, and business nature.
  - **PEP/Sanctions Self-Declaration:** Checkbox declarations with legal disclaimers.
  - **Terms Acceptance:** Digital signature capture or checkbox acceptance.
- **Document Upload:** Multi-file upload with drag-and-drop, progress bars, virus scanning, and format validation (PDF, JPG, PNG, TIFF). Automatic document classification attempts.
- **Auto-Save:** Form state is auto-saved every 30 seconds to prevent data loss. Draft indicator with last saved timestamp.
- **Validation:** Real-time field validation with inline errors. Required field indicators. Cross-field validation (e.g., expiry date must be after issue date).
- **Preview Mode:** Before submission, a read-only preview of the entire case is shown for review.
- **Submission:** On submit, the case enters the normal processing queue. Confirmation page with case ID and estimated processing time.
- **Duplicate Detection:** Warns if similar cases or customers already exist based on name, DOB, ID number fuzzy matching.

---

## 2.5 Bulk Case Upload / Import Page

**Route:** `/kyc/cases/bulk-upload`

**Purpose:** Enables mass import of cases via structured files for large onboarding events, data migrations, or batch processing scenarios.

**Deep Details:**

- **Template Download:** Downloadable CSV, Excel, and JSON templates with field definitions, validation rules, and example rows. Templates are versioned and validated against the current schema.
- **File Upload:** Drag-and-drop or file picker. Supports CSV, XLSX, XLS, JSON, XML. File size limit (default 100MB). Virus scanning on upload.
- **Schema Validation:** Immediate validation of file structure: required columns, data types, format compliance, and enum value checking. Errors are reported with row numbers and specific field issues.
- **Data Preview:** First 10 rows displayed in a table for visual verification before processing.
- **Mapping Interface:** If uploaded columns don't match expected schema, a visual mapping interface allows drag-and-drop column matching.
- **Processing Options:**
  - **Validation Only:** Check file without creating cases. Generates a report of all errors and warnings.
  - **Create Drafts:** Create cases in "Draft" status for manual review before activation.
  - **Create and Submit:** Create cases and immediately submit them to the processing queue.
  - **Update Existing:** Match by customer ID or email and update existing cases rather than creating new ones.
- **Progress Tracking:** For large files, a progress bar shows processing status: parsing, validating, creating, submitting. Estimated time remaining.
- **Batch Results:** After processing, a detailed report shows: total rows processed, successful creations, failures with reasons, warnings, and a downloadable error report.
- **Rollback:** Option to undo a batch upload within 24 hours if errors are discovered.
- **Rate Limiting:** Configurable upload rate to prevent queue flooding. Enterprise tenants can schedule uploads for off-peak hours.
- **Audit Trail:** Full audit log of who uploaded what, when, and the results.

---

## 2.6 Case Assignment / Work Distribution Page

**Route:** `/kyc/cases/assignment`

**Purpose:** Manager interface for distributing cases among analysts, configuring auto-assignment rules, and balancing workload.

**Deep Details:**

- **Analyst Workload View:** Table showing all analysts in the team with: current open cases, cases completed today, average handling time, SLA compliance rate, current status (online, away, offline, on break), specialization tags, and capacity percentage.
- **Manual Assignment:** Drag-and-drop cases from an unassigned queue to specific analysts. Bulk assignment with multi-select. Override current assignments with reason logging.
- **Auto-Assignment Rules Engine:**
  - **Round-Robin:** Distribute cases evenly in rotation.
  - **Load-Based:** Assign to analyst with lowest current workload.
  - **Skill-Based:** Match case type/risk to analyst specialization (e.g., PEP cases to senior analysts, business cases to corporate specialists).
  - **Language-Based:** Match customer language to analyst language skills.
  - **Jurisdiction-Based:** Match customer country to analyst regional expertise.
  - **Time-Based:** Consider analyst working hours and time zones.
  - **Escalation Path:** Automatic escalation rules based on case age, risk score, or complexity.
- **Rule Configuration:** Visual rule builder with conditions (IF case.type = 'Business' AND risk.score > 70) and actions (THEN assign to 'Senior Analyst Pool'). Supports nested logic, priorities, and exceptions.
- **Queue Management:** Create and manage multiple queues: General, High Risk, Business, PEP/Sanctions, Expedited, Training. Set queue priorities and analyst access permissions.
- **Shift Scheduling:** Integrate with shift schedules to only assign to analysts currently on duty. Handle handoffs between shifts.
- **Reassignment:** Bulk reassign cases when analysts go on leave, call in sick, or are overloaded. Automatic redistribution with notification.
- **Performance Balancing:** Algorithm suggests reassignments to balance workload and meet SLA targets. Manager can approve or override.
- **Audit Log:** All assignment changes logged with reason, previous assignee, new assignee, and timestamp.

---

## 2.7 Case Escalation / Escalation Management Page

**Route:** `/kyc/escalations`

**Purpose:** Centralized management of all escalated cases requiring senior attention, specialized expertise, or external consultation.

**Deep Details:**

- **Escalation Queue:** List of all escalated cases with: escalation reason, escalated by, escalated to, escalation time, priority, SLA, current status (Awaiting Response, In Review, Resolved, Re-escalated), and resolution target.
- **Escalation Types:**
  - **Senior Review:** Complex cases beyond junior analyst authority.
  - **Fraud Investigation:** Suspected fraudulent documents or identity.
  - **PEP/Sanctions:** High-profile politically exposed persons or sanctions matches requiring legal review.
  - **Legal/Compliance:** Cases with legal implications or regulatory questions.
  - **Technical:** System errors or data quality issues.
  - **Customer Complaint:** Escalated due to customer dispute or complaint.
  - **External Referral:** Referred to external counsel, regulator, or law enforcement.
- **Escalation Workflow:** Configurable approval chains. Example: Analyst → Team Lead → Compliance Manager → Legal → CRO. Each level can approve, reject, or escalate further.
- **SLA Tracking:** Escalated cases have tighter SLAs. Visual countdown with alerts at 50%, 75%, and 90% of SLA time.
- **Resolution Panel:** For reviewers, a dedicated interface to: review case history, add senior-level notes, request additional information, make final determination, and document rationale.
- **De-escalation:** Option to return case to original analyst or queue with instructions. Or resolve directly with override authority.
- **Escalation Analytics:** Metrics on escalation volume by type, average resolution time, escalation rate by analyst, and outcomes.
- **Notification System:** Real-time notifications to escalated parties via in-app, email, SMS, or Slack/Teams integration.
- **Escalation Templates:** Pre-written escalation request templates for common scenarios to ensure consistent communication.

---

## 2.8 Quality Assurance (QA) / Review Audit Page

**Route:** `/kyc/quality-assurance`

**Purpose:** Systematic review of completed cases to ensure accuracy, consistency, and compliance with policies. Critical for regulatory audits and continuous improvement.

**Deep Details:**

- **QA Queue:** Cases randomly or systematically selected for QA review. Selection criteria: percentage of daily volume, all rejections, all high-risk approvals, analyst-specific sampling, or risk-based selection.
- **QA Review Interface:** Side-by-side comparison of original case with reviewer notes. The QA analyst can see the original decision, rationale, and all evidence without knowing the original analyst (blind review option).
- **QA Checklist:** Configurable checklist covering: document verification thoroughness, data accuracy, risk assessment completeness, decision rationale quality, SLA compliance, communication quality, and policy adherence.
- **Scoring System:** Each checklist item scored (Pass, Minor Issue, Major Issue, Critical). Weighted scoring produces overall QA score (0-100).
- **Findings Documentation:** Free-text findings with severity, evidence screenshots, and recommendations.
- **Feedback Loop:** QA findings are linked back to original analysts for learning. Analysts can respond to findings or request re-review.
- **Calibration Sessions:** Schedule and document calibration meetings where multiple QA analysts review the same case to ensure inter-rater reliability. Track calibration scores over time.
- **QA Reports:**
  - **Analyst Scorecards:** Individual analyst QA scores over time, trend analysis, and benchmarking against team average.
  - **Error Trend Analysis:** Common errors by type, frequency, and analyst. Identifies training needs.
  - **Policy Gap Analysis:** Cases where policy was unclear or insufficient, feeding back to policy team.
  - **Regulatory Compliance:** Evidence of QA program existence and effectiveness for auditor review.
- **Re-review Triggers:** If QA finds critical errors, automatically trigger re-review of all cases processed by that analyst in a time window, or all cases with similar characteristics.
- **QA Settings:** Configure sampling rate, checklist items, scoring weights, blind review percentage, and minimum QA score thresholds.

---

## 2.9 Re-KYC / Periodic Review Management Page

**Route:** `/kyc/periodic-reviews`

**Purpose:** Manage scheduled re-verification of existing customers based on risk level, regulatory requirements, or time-based triggers.

**Deep Details:**

- **Review Schedule Calendar:** Visual calendar showing upcoming re-KYC deadlines by customer. Color-coded by risk level and days until deadline.
- **Review Triggers:**
  - **Time-Based:** Standard intervals (e.g., low-risk annually, medium-risk every 6 months, high-risk every 3 months, PEP every 6 months).
  - **Event-Based:** Triggered by significant changes (address change, new beneficial owner, transaction pattern change, adverse media alert, sanctions list update).
  - **Risk-Based:** Triggered when customer's risk score crosses thresholds.
  - **Regulatory:** Mandated by new regulations or auditor findings.
- **Review Queue:** List of customers due for re-KYC with: customer name, last review date, next review due, risk level, days overdue, review type, and status (Scheduled, In Progress, Completed, Overdue).
- **Automated Outreach:** Configurable email/SMS sequences reminding customers of upcoming re-KYC requirements. Includes direct links to self-service portal.
- **Review Scope:** Define what needs to be re-verified: full KYC, document refresh only, risk reassessment only, or targeted checks.
- **Review Case Creation:** One-click creation of re-KYC case from the review queue. Pre-populates with existing customer data. Customer is notified.
- **Grace Periods:** Configurable grace periods before a customer is restricted for non-compliance.
- **Exemptions:** Process for granting temporary exemptions with approval workflow, reason documentation, and expiration dates.
- **Overdue Management:** Automated escalation of overdue reviews. Restriction of customer services (e.g., freeze transactions, limit withdrawals) until re-KYC is complete.
- **Review Analytics:** Metrics on review completion rates, average time to complete, overdue rates by risk category, and customer churn due to re-KYC friction.
- **Regulatory Reporting:** Evidence of ongoing monitoring and periodic review program for regulatory submissions.

---

## 2.10 Customer Communication / Messaging Center

**Route:** `/kyc/communications`

**Purpose:** Centralized hub for all customer communications related to KYC cases. Supports email, SMS, in-app messaging, and chat.

**Deep Details:**

- **Unified Inbox:** All customer messages across all channels in a single threaded view. Messages are linked to specific cases and customers.
- **Message Composition:** Rich text editor with templates, variables (customer name, case ID, deadline), attachments, and language selection. Preview before sending.
- **Template Library:** Pre-approved message templates for common scenarios: document request, clarification needed, approval notification, rejection notification, escalation notice, reminder, welcome. Templates are version-controlled and require approval for changes.
- **Automated Messages:** Configure trigger-based automated communications: welcome on submission, reminder after 24 hours of no response, escalation warning, approval/rejection notifications.
- **Multi-Language Support:** Automatic translation suggestions for outgoing messages. Detects customer language preference.
- **Delivery Tracking:** See when messages were sent, delivered, opened, and clicked. Bounce handling and retry logic.
- **Customer Response Handling:** Incoming replies are automatically parsed and linked to the correct case. Document attachments from replies are extracted and added to the case.
- **SLA on Responses:** Track response time SLAs for customer communications. Alerts when customer is waiting too long for a response.
- **Communication History:** Complete audit trail of all communications with full text, timestamps, and sender info. Exportable for legal discovery.
- **Opt-Out Management:** Respect customer communication preferences and regulatory opt-out requirements.
- **Chat Integration:** If live chat is enabled, embedded chat widget for real-time customer support during KYC process.
- **Video Call Scheduling:** Integration with Zoom, Teams, or custom video call for high-touch KYC scenarios. Schedule, send invites, and record (with consent).

---

## 2.11 SLA Management / Service Level Agreement Page

**Route:** `/kyc/sla-management`

**Purpose:** Configure, monitor, and report on service level agreements for KYC processing times.

**Deep Details:**

- **SLA Configuration:**
  - **Tier-Based SLAs:** Different targets per customer tier (VIP: 2 hours, Standard: 24 hours, Basic: 72 hours).
  - **Case Type SLAs:** Different targets for individual vs. business vs. PEP cases.
  - **Risk-Based SLAs:** High-risk cases may have longer SLAs due to depth of review, or shorter SLAs due to urgency.
  - **Channel SLAs:** Different targets for API submissions vs. manual entries vs. bulk uploads.
  - **Business Hours:** Define working hours, time zones, weekends, and holidays for SLA calculation. Support multiple regional business hour definitions.
  - **Clock Start/Stop Rules:** Define when SLA clock starts (submission, document complete, payment received) and stops (approval, rejection, customer response pending).
- **SLA Dashboard:** Real-time view of SLA performance: overall compliance percentage, cases on track, at risk, breached. Trend charts over time.
- **Breach Management:** List of all SLA breaches with: case details, breach duration, reason, resolution, and corrective action. Root cause analysis workflow.
- **Penalty Tracking:** If SLAs have contractual penalties, track breach counts and calculate penalty amounts.
- **Escalation Rules:** Automatic escalation when cases approach SLA breach (e.g., notify manager at 80% of SLA time).
- **SLA Reporting:** Scheduled and on-demand reports for internal management and customer-facing SLA attestations.
- **What-If Analysis:** Simulate SLA performance under different resource allocation scenarios.

---

## 2.12 Workflow Builder / KYC Process Designer Page

**Route:** `/kyc/workflow-builder`

**Purpose:** Visual, no-code interface for designing and customizing KYC workflows, decision trees, and approval chains per tenant or customer segment.

**Deep Details:**

- **Visual Canvas:** Drag-and-drop workflow designer with nodes (steps) and edges (transitions). Zoom, pan, and auto-layout.
- **Node Types:**
  - **Start Node:** Trigger conditions (new submission, re-KYC due, risk change).
  - **Data Collection Node:** Forms, document requests, API calls.
  - **Decision Node:** Branching logic based on rules (risk score, document type, country, customer tier).
  - **Action Node:** Automated actions (send email, update CRM, create ticket, notify team).
  - **Review Node:** Human review step with assignment rules.
  - **Approval Node:** Approval gate with quorum rules.
  - **Integration Node:** Call external APIs or webhooks.
  - **End Node:** Final statuses (Approved, Rejected, On Hold, Escalated).
- **Rule Engine:** Visual rule builder with conditions (field operators, regex, range checks, list membership) and actions. Supports AND/OR logic, nested conditions.
- **Version Control:** Workflows are versioned. Active version runs new cases; old versions continue running in-flight cases. Rollback capability.
- **Testing Environment:** Sandbox mode to test workflows with sample data without affecting production. Step-through debugging.
- **A/B Testing:** Run two workflow versions simultaneously with traffic splitting to measure outcomes.
- **Workflow Library:** Pre-built workflow templates for common scenarios: Standard Individual KYC, Business KYC, Crypto Exchange Onboarding, Gambling License Application, Banking Account Opening.
- **Workflow Analytics:** Performance metrics per workflow: average completion time, drop-off rates by step, automation rate, error rate.
- **Import/Export:** Workflows can be exported as JSON/YAML and imported across environments or tenants.
- **Permissions:** Workflow editing restricted to specific roles. Change approval workflow for production deployments.

---

## 2.13 KYC Policy / Rules Engine Configuration Page

**Route:** `/kyc/policy-configuration`

**Purpose:** Define the business rules, thresholds, and policies that drive automated KYC decisions and risk scoring.

**Deep Details:**

- **Policy Categories:**
  - **Document Acceptance:** Which document types are accepted per country, age requirements for documents, expiry handling.
  - **Risk Scoring Rules:** Point-based or matrix-based risk scoring. Rules for geographic risk, customer type risk, business nature risk, transaction pattern risk.
  - **Approval Thresholds:** Automatic approval criteria (e.g., risk score < 20, all checks passed, no watchlist matches).
  - **Rejection Rules:** Automatic rejection criteria (e.g., sanctions match, document fraud detected, banned country).
  - **Manual Review Triggers:** Conditions that force human review (e.g., risk score 21-70, document unclear, name mismatch, high-risk country).
  - **Enhanced Due Diligence (EDD) Triggers:** Conditions requiring deeper investigation (e.g., PEP, high net worth, complex ownership, high-risk jurisdiction).
- **Rule Editor:** Visual rule builder or code editor (DSL) for complex rules. Syntax highlighting, validation, and testing.
- **Rule Testing:** Test rules against historical cases or synthetic data to see outcomes before deployment.
- **Rule Versioning:** Rules are versioned with effective dates. Scheduled rule changes for future implementation.
- **Rule Simulation:** Run "what-if" scenarios: "If we change the high-risk country threshold from 60 to 70, how many more cases would require manual review?"
- **Policy Documentation:** Auto-generated documentation from rules for compliance teams and auditors.
- **Change Approval:** Rules require approval from compliance manager or legal before activation. Approval workflow with audit trail.
- **Rule Conflict Detection:** System detects contradictory or overlapping rules and warns before deployment.
- **Regulatory Mapping:** Map each rule to specific regulatory requirements (e.g., "4th AML Directive Article 13"), generating compliance evidence.

---

## 2.14 Case Appeal / Dispute Resolution Page

**Route:** `/kyc/appeals`

**Purpose:** Manage customer appeals against KYC rejections or adverse decisions. Ensures fair process and regulatory compliance.

**Deep Details:**

- **Appeal Submission:** Customer-facing form (also accessible via API) to submit an appeal. Fields: case reference, grounds for appeal, new evidence upload, detailed explanation.
- **Appeal Queue:** List of all appeals with: appeal ID, original case, customer, submission date, status (Received, Under Review, Approved, Rejected, Escalated), assigned reviewer, and priority.
- **Review Interface:** Side-by-side view of original case decision and appeal submission. All original evidence plus new evidence.
- **Independent Review:** Appeals are assigned to reviewers who were not involved in the original decision to ensure impartiality.
- **Decision Options:**
  - **Uphold Original Decision:** With detailed explanation sent to customer.
  - **Overturn and Approve:** With updated risk assessment and rationale.
  - **Request Additional Information:** Send back to customer with specific requests.
  - **Escalate to Senior Management:** For complex or high-stakes appeals.
- **Customer Communication:** Automated status updates to customer at each stage. Final decision letter with reasoning.
- **SLA:** Appeals have dedicated SLAs (e.g., acknowledge within 24 hours, resolve within 5 business days).
- **Appeal Analytics:** Track appeal rates by rejection reason, analyst, and time period. Identify patterns suggesting training needs or policy issues.
- **Regulatory Reporting:** Evidence of fair and transparent appeal process for regulatory examinations.
- **Ombudsman Integration:** For regulated industries, integration with external ombudsman or dispute resolution services.

---

## 2.15 KYC Operations Settings Page

**Route:** `/kyc/settings`

**Purpose:** Tenant-level configuration for KYC operations, queues, notifications, and operational preferences.

**Deep Details:**

- **Queue Settings:** Default queue behaviors, auto-assignment rules, queue priorities, and capacity limits.
- **Notification Preferences:** Configure which events trigger notifications, to whom, and via which channels. Event types: new case, SLA breach, escalation, risk alert, customer response, approval required.
- **Operational Hours:** Define business hours per region for SLA calculation and auto-assignment.
- **Holiday Calendar:** Add regional holidays where SLA clocks pause. Import from standard holiday calendars.
- **Case Numbering:** Configure case ID format (prefix, date format, sequential number, random component).
- **Default Risk Settings:** Default risk scores for new customers by type and jurisdiction.
- **Review Checklist Templates:** Create and manage default checklists for different case types.
- **Auto-Close Rules:** Automatically close cases that have been pending customer response for N days. Auto-archive closed cases after M days.
- **Data Retention:** Configure how long case data is retained post-closure per regulatory requirement and data type.
- **Integration Settings:** Configure connections to external verification providers, CRMs, and communication platforms.
- **Operational Analytics:** Toggle collection of operational metrics (analyst productivity, handling times) for performance management.
