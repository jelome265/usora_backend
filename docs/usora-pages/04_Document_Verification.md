# USORA — Document & Biometric Verification Pages

> **Scope:** All interfaces related to the capture, processing, verification, and forensic analysis of identity documents, proof of address, proof of income, and biometric data. These pages power the document-centric trust layer of the KYC platform, integrating OCR, ML-based fraud detection, liveness detection, and manual review capabilities.

---

## 4.1 Document Upload / Capture Interface

**Route:** `/verify/upload` (customer-facing) and `/kyc/documents/upload` (analyst-facing)

**Purpose:** Primary interface for document image capture and upload across all channels (web, mobile, API). Optimized for quality, fraud prevention, and user experience.

**Deep Details:**

- **Multi-Channel Upload:**
  - **Web Drag-and-Drop:** Visual drop zone with file type icons, size limits, and progress animations. Supports multiple files simultaneously.
  - **Mobile Camera Capture:** Optimized camera interface with guides (document border overlay, corner markers), auto-capture on focus, and real-time quality feedback.
  - **API Upload:** Programmatic upload for partner integrations with webhook notifications.
  - **Email Upload:** Dedicated email address per case where customers can email documents (parsed and attached automatically).
  - **SFTP/Cloud Storage:** Bulk import from secure file drops for enterprise integrations.
- **Document Classification:** Auto-detection of document type from image content using ML models. Suggests: Passport, National ID, Driver's License, Residence Permit, Utility Bill, Bank Statement, Tax Document, Business Registration, etc.
- **Real-Time Quality Analysis:** Before submission, analyze image quality and provide immediate feedback:
  - **Blur Detection:** Laplacian variance analysis. Reject if below threshold.
  - **Glare Detection:** Highlight overexposed regions. Suggest re-capture angle.
  - **Truncation Detection:** Verify all four document edges are visible.
  - **Resolution Check:** Minimum DPI requirements per document type (e.g., 300 DPI for IDs).
  - **Color/BW Check:** Ensure color documents are submitted in color (security feature visibility).
  - **Skew Correction:** Detect and auto-correct document rotation up to 15 degrees.
  - **Contrast Analysis:** Ensure text is legible against background.
- **Guided Capture Flow:**
  - Step-by-step wizard: Select Document Type → Position Document → Capture/Upload → Quality Check → Submit.
  - Animated instructions showing correct positioning (lighting, distance, background).
  - Countdown before auto-capture to allow user to steady the document.
  - Retake option with comparison between previous and new capture.
- **Security Features:**
  - **Client-Side Hashing:** SHA-256 hash computed client-side for integrity verification.
  - **Virus Scanning:** ClamAV or cloud scanning on upload.
  - **File Type Validation:** Strict MIME type and magic number checking. Reject executable files.
  - **Metadata Stripping:** Remove EXIF data (GPS, device info) to protect privacy.
  - **Encryption:** Files encrypted in transit (TLS 1.3) and at rest (AES-256).
- **Accessibility:** Screen reader announcements for each step, keyboard-only navigation, high contrast mode, and voice-guided capture for visually impaired users.
- **Localization:** Instructions and UI in customer's preferred language. RTL support for Arabic/Hebrew documents.
- **Fallback Handling:** If auto-capture fails repeatedly, offer manual upload or "Email me a link" options.

---

## 4.2 Document Viewer / Forensic Review Page

**Route:** `/kyc/documents/:documentId`

**Purpose:** Advanced document review interface for analysts to examine uploaded documents with forensic tools, side-by-side comparisons, and verification result overlays.

**Deep Details:**

### Viewer Layout (Split-Screen Design)

**Left Panel — Document Image (60% width):**
- **High-Resolution Viewer:** Pan and zoom capabilities (up to 400%) with smooth rendering. Supports multi-page documents (PDF, TIFF) with page navigation.
- **Layer Overlays:** Toggleable annotation layers:
  - **Security Feature Layer:** Highlights detected security features (holograms, watermarks, microprinting, UV features, RFID chip indicators) with confidence scores.
  - **OCR Text Layer:** Overlay extracted text directly on the image with bounding boxes. Click text to edit or verify.
  - **Fraud Detection Layer:** Color-coded heatmap showing areas flagged as potentially fraudulent (red = high suspicion, yellow = review needed, green = verified).
  - **Template Match Layer:** Overlay of expected document template showing deviations.
  - **Comparison Layer:** When comparing two documents, show differences highlighted.
- **Image Enhancement Tools:**
  - **Brightness/Contrast:** Real-time adjustment sliders.
  - **Invert Colors:** For examining negative spaces and watermarks.
  - **Grayscale:** Remove color distractions.
  - **Sharpen:** Enhance edge definition.
  - **Edge Detection:** Highlight document edges and text boundaries.
  - **UV Simulation:** Simulate ultraviolet light examination for fluorescent security features.
  - **IR Simulation:** Simulate infrared examination for ink penetration checks.
- **Measurement Tools:** Ruler overlay for checking standard document dimensions (e.g., passport should be 125mm x 88mm).
- **Annotation Tools:** Draw, highlight, add text notes, and place markers directly on the image. Annotations are saved and shared with other analysts.
- **Rotation & Flip:** 90-degree rotation and horizontal/vertical flip for examining documents from different angles.
- **Fullscreen Mode:** Distraction-free full-screen viewing with minimal UI.

**Right Panel — Verification Data & Tools (40% width):**
- **Document Metadata Card:** File name, upload timestamp, uploader info, file size, format, dimensions, DPI, EXIF data (if retained), hash values.
- **Classification Results:** Detected document type, country of origin, document version, and classification confidence.
- **OCR Extraction Results:** Structured data table showing all extracted fields: field name, extracted value, confidence score, validation status, and manual correction input. Fields include: document number, name, DOB, expiry date, nationality, address, MRZ (Machine Readable Zone) data.
- **MRZ Parsing:** Dedicated MRZ section showing raw MRZ lines, parsed fields, checksum validation for each field, and overall MRZ validity.
- **Security Feature Checklist:** List of expected security features for this document type with detection status:
  - Hologram: Detected / Not Detected / Inconclusive
  - Watermark: Detected / Not Detected
  - Microprinting: Verified / Failed
  - UV Features: Present / Absent
  - Security Thread: Detected / Not Detected
  - Laser-Perforated Number: Match / Mismatch
  - RFID/Chip: Readable / Not Readable / N/A
- **Fraud Detection Results:**
  - **Digital Tampering:** ELA (Error Level Analysis) results, clone detection, copy-move detection, metadata inconsistency.
  - **Physical Tampering:** Signs of photo substitution, lamination peeling, font inconsistency, spacing anomalies.
  - **Template Deviation:** Comparison against known authentic template showing structural differences.
  - **Cross-Reference Checks:** Name/DOB consistency across multiple documents, address verification against external databases.
- **Verification History:** Timeline of all automated and manual checks performed on this document with timestamps, results, and system versions.
- **Analyst Actions:**
  - **Verify:** Mark document as verified with optional notes.
  - **Reject:** Mark as rejected with reason (fraudulent, illegible, wrong document, expired, mismatched data, other) and detailed explanation.
  - **Request Re-upload:** Send automated request to customer for better quality or different document.
  - **Escalate:** Send to fraud specialist or document expert.
  - **Compare:** Open side-by-side comparison with another document.
- **External Lookup:** Quick links to query external databases or government verification APIs (where available and legally permissible).

---

## 4.3 Document Comparison / Cross-Reference Page

**Route:** `/kyc/documents/compare`

**Purpose:** Side-by-side or overlay comparison of multiple documents to detect inconsistencies, verify identity across documents, and identify fraud patterns.

**Deep Details:**

- **Comparison Modes:**
  - **Side-by-Side:** Two documents displayed next to each other with synchronized zoom and pan.
  - **Overlay:** Semi-transparent overlay of one document on another with opacity slider. Align by document edges or specific features.
  - **Difference Highlight:** Automated pixel-level comparison highlighting differences between two images of the same document type.
  - **Data Comparison:** Tabular comparison of extracted data fields across multiple documents. Flag mismatches in red.
- **Auto-Alignment:** Intelligent alignment of documents based on detected edges, text blocks, or security features.
- **Field Consistency Check:** Automatically compare key fields across documents:
  - Name consistency across ID, proof of address, and proof of income.
  - DOB consistency.
  - Address consistency between ID and utility bill.
  - Photo consistency across documents (face comparison).
  - Document number format validity per country.
- **Fraud Pattern Detection:**
  - **Same Photo, Different Names:** Detect if the same portrait photo appears on different identity documents.
  - **Same Document, Different Customers:** Detect if the same document number is used by multiple customers.
  - **Template Reuse:** Detect if a document template is reused across multiple forged documents.
  - **Font Analysis:** Compare fonts used across documents to detect substitutions.
- **Comparison Report:** Generate a PDF report of the comparison with highlighted differences, analyst notes, and conclusion.
- **Batch Comparison:** Compare one document against a database of all documents in the tenant to find matches or similarities.

---

## 4.4 Biometric Capture / Selfie Verification Page

**Route:** `/verify/biometric` (customer-facing) and `/kyc/biometric/:biometricId` (analyst-facing)

**Purpose:** Capture, process, and review biometric data (facial recognition, liveness detection) to verify that the person presenting the identity document is its legitimate owner.

**Deep Details:**

### Customer-Facing Capture Interface

- **Guided Selfie Flow:**
  - **Step 1 — Instructions:** Animated guide showing correct positioning (face centered, neutral expression, good lighting, plain background, remove glasses/hat if possible).
  - **Step 2 — Camera Permission:** Browser-based camera access with clear explanation of data usage and privacy protections.
  - **Step 3 — Positioning:** Real-time face detection with overlay guides (oval face outline, eye position markers). Feedback messages: "Move closer", "Center your face", "Too dark", "Look straight ahead".
  - **Step 4 — Capture:** Auto-capture when face is properly positioned, or manual capture button. Countdown (3-2-1) before capture.
  - **Step 5 — Liveness Challenge:** Randomized challenge-response test to prevent spoofing:
    - **Blink Detection:** "Please blink"
    - **Head Turn:** "Turn your head slowly to the left"
    - **Smile Detection:** "Please smile"
    - **Light Challenge:** "Move closer to the light" or "Tilt your phone"
    - **Texture Analysis:** Passive liveness detection analyzing skin texture, depth, and reflection.
  - **Step 6 — Video Option:** Optional short video recording (3-5 seconds) for enhanced liveness verification and future review.
  - **Step 7 — Review:** Preview captured image/video with retake option. Show quality score.
  - **Step 8 — Submit:** Upload with progress indicator and estimated processing time.
- **Quality Checks (Real-Time):**
  - Face detection confidence > 95%
  - Both eyes visible and open
  - No face occlusion (masks, sunglasses, hats)
  - Adequate lighting (no harsh shadows or overexposure)
  - Minimum resolution (e.g., 640x480)
  - Sharp focus (no motion blur)
  - Neutral expression or requested expression detected
  - Single face in frame (no multiple people)
- **Accessibility:** Audio instructions for visually impaired users, high contrast mode, and alternative verification methods for users unable to use camera.
- **Privacy:** Clear consent capture before biometric collection. Explanation of data retention and deletion policies. Option to delete biometric data post-verification (where legally permitted).
- **Mobile Optimization:** Native camera access on mobile browsers with optimal resolution. Portrait orientation lock. Torch/light control.

### Analyst-Facing Biometric Review Interface

- **Biometric Results Dashboard:**
  - **Captured Image/Video:** High-resolution display with zoom and frame-by-frame video navigation.
  - **Face Match Score:** Similarity score between selfie and ID document photo (0-100). Threshold indicators (pass > 80, review 60-80, fail < 60).
  - **Liveness Detection Results:**
    - Challenge-response results with timestamps.
    - Passive liveness score based on texture analysis, depth estimation, and reflection analysis.
    - 3D face map visualization showing depth data.
    - Spoofing attack detection: print photo, digital screen replay, 3D mask, deepfake probability scores.
  - **Demographic Analysis:** Estimated age, gender, and ethnicity from facial features (for consistency checking against declared data).
  - **Facial Landmark Map:** 68-point or 468-point facial landmark overlay showing detected features (eyes, nose, mouth, jawline, eyebrows).
- **Comparison Tools:**
  - Side-by-side selfie and ID photo with synchronized zoom.
  - Feature-by-feature comparison: eye distance, nose shape, face width, ear shape.
  - Age progression analysis: Account for time between ID photo and selfie.
- **Anti-Spoofing Forensics:**
  - **Texture Analysis:** Magnified skin texture view to detect printed photos (lack of pore detail) or screen moiré patterns.
  - **Depth Map:** Visual depth representation showing 3D structure. Flat images show uniform depth.
  - **Reflection Analysis:** Eye reflection patterns to detect screen-based attacks.
  - **Frame Analysis:** For video, analyze frame-to-frame consistency to detect deepfakes.
- **Analyst Decision:**
  - **Approve Biometric:** Confirm identity match and liveness.
  - **Reject:** Reason selection (not a match, spoofing detected, poor quality, different person, other).
  - **Request Re-capture:** Send specific instructions for better capture.
  - **Escalate to Biometric Specialist:** For edge cases or advanced spoofing attempts.
- **Biometric Data Management:** View storage location, encryption status, retention schedule, and deletion options.

---

## 4.5 Document Template Library / Management Page

**Route:** `/kyc/documents/templates`

**Purpose:** Manage the library of known authentic document templates used for automated verification, fraud detection, and analyst reference.

**Deep Details:**

- **Template Database:** Comprehensive collection of document templates organized by:
  - **Document Type:** Passport, National ID, Driver's License, Residence Permit, etc.
  - **Country:** 200+ countries and territories.
  - **Issuing Authority:** Specific government agencies or departments.
  - **Version/Edition:** Document versions with issue date ranges.
  - **Security Features:** Catalog of known security features per template.
- **Template Detail View:** For each template:
  - High-resolution reference images (front, back, UV, IR views).
  - Document dimensions and specifications.
  - Security feature catalog with images and descriptions.
  - MRZ format specification.
  - Known fraud patterns and vulnerabilities.
  - Validity period rules.
  - Sample images (anonymized) for training.
- **Template Upload:** Interface for adding new templates or updating existing ones. Requires admin approval.
  - Upload reference images.
  - Draw bounding boxes for data fields.
  - Mark security feature locations.
  - Define MRZ format.
  - Set validation rules.
- **Template Versioning:** Track template updates as issuing authorities change document designs. Maintain historical templates for verifying older documents.
- **Fraud Pattern Database:** For each template, catalog known counterfeit patterns, common forgery techniques, and detection methods.
- **Template Coverage Report:** Show which countries/document types have templates vs. gaps requiring manual review.
- **API Access:** Templates accessible via API for partner integrations and automated verification systems.
- **Analyst Training Mode:** Quiz mode where analysts practice identifying authentic vs. fraudulent documents using template library.

---

## 4.6 OCR Engine / Data Extraction Results Page

**Route:** `/kyc/documents/:documentId/ocr`

**Purpose:** Detailed view of OCR (Optical Character Recognition) and data extraction results with manual correction capabilities.

**Deep Details:**

- **Extraction Results Table:**
  - **Field Name:** Standardized field names (document_number, given_names, surname, date_of_birth, date_of_expiry, nationality, sex, etc.).
  - **Extracted Value:** Raw text extracted by OCR.
  - **Normalized Value:** Cleaned and formatted value (e.g., date standardized to ISO 8601, name title-cased).
  - **Confidence Score:** OCR confidence (0-100) per field. Color-coded: green > 90, yellow 70-90, red < 70.
  - **Validation Status:** Pass, Fail, or Warning based on format rules and cross-field validation.
  - **Source Region:** Bounding box coordinates on the document image showing where text was extracted.
  - **Manual Correction:** Editable field for analyst to correct OCR errors. Correction is tracked with before/after and reason.
- **Raw OCR Output:** View the complete raw OCR text output (unstructured) for reference and debugging.
- **MRZ Parsing Detail:**
  - Raw MRZ lines displayed.
  - Field-by-field parsing with checksum validation.
  - Cross-check between MRZ data and visual zone data. Flag discrepancies.
- **Handwriting Recognition:** If document contains handwritten text, separate section showing handwriting OCR results with lower confidence thresholds.
- **Barcode/QR Parsing:** If document contains barcodes or QR codes, parsed data displayed with format identification (PDF417, Code 128, QR, DataMatrix).
- **Extraction Method:** Indicator of which extraction method produced the result: Tesseract, cloud OCR (Google Vision, AWS Textract, Azure Form Recognizer), proprietary ML model, or manual entry.
- **Batch OCR Processing:** For bulk documents, status dashboard showing processing queue, completion rates, and error reports.
- **OCR Model Performance:** Analytics on OCR accuracy by document type, language, and field. Identify fields with frequent extraction errors for model improvement.

---

## 4.7 Fraud Detection / Forensic Analysis Dashboard

**Route:** `/kyc/fraud-detection`

**Purpose:** Centralized dashboard for monitoring, investigating, and responding to document fraud and identity fraud across the platform.

**Deep Details:**

- **Fraud Alert Queue:** Real-time list of fraud alerts with severity levels (Critical, High, Medium, Low).
  - Alert types: Document tampering, identity theft, synthetic identity, template fraud, photo substitution, deepfake biometric, velocity attack, device spoofing.
  - Each alert shows: case/customer, alert type, detection method, confidence score, triggered rules, and quick actions.
- **Fraud Investigation Workspace:**
  - **Case-Centric View:** All fraud indicators for a single case aggregated.
  - **Document Forensics:** Access to all forensic tools (ELA, clone detection, metadata analysis, template matching).
  - **Network Analysis:** Visualize connections between fraudulent cases (shared attributes, common upload patterns).
  - **Behavioral Analysis:** Unusual behavior patterns (rapid submissions, multiple failed attempts, bot-like interactions).
- **Detection Methods Dashboard:**
  - **Digital Forensics:** ELA heatmaps, clone detection maps, noise analysis, compression artifact detection.
  - **Physical Forensics:** Template deviation scoring, font consistency analysis, security feature verification.
  - **Biometric Forensics:** Liveness attack detection, deepfake probability, face morphing detection.
  - **Behavioral Forensics:** Device fingerprinting, IP analysis, velocity checks, session pattern analysis.
- **Fraud Trends:** Charts showing fraud attempt volume, success rates, methods used, geographic origin, and trends over time.
- **Fraud Ring Detection:** ML-powered clustering to identify organized fraud operations. Visual network graphs showing connected fraudulent accounts.
- **False Positive Management:** Review and tune fraud detection rules to reduce false positives. Track false positive rate by rule.
- **Fraud Report Generation:** Generate detailed fraud investigation reports for law enforcement, regulators, or internal audit.
- **Integration with Law Enforcement:** Secure portal for sharing fraud evidence with authorized law enforcement agencies.
- **Fraud Intelligence Feed:** Subscribe to industry fraud intelligence feeds (shared document images, known fraud patterns, emerging threats).

---

## 4.8 Document Expiry & Renewal Management Page

**Route:** `/kyc/documents/expiry`

**Purpose:** Track document expiration dates, manage renewal workflows, and ensure continuous compliance.

**Deep Details:**

- **Expiry Dashboard:**
  - **Summary Cards:** Documents expiring in 30 days, 60 days, 90 days, already expired.
  - **Calendar View:** Visual calendar showing expiry dates across all customers.
  - **Risk-Based Prioritization:** High-risk customers with expiring documents prioritized.
- **Renewal Queue:** List of customers requiring document renewal with: customer name, document type, expiry date, days until expiry, risk level, last reminder sent, and status.
- **Automated Reminder Campaigns:**
  - **Email Sequences:** Configurable reminder emails at 90, 60, 30, 14, 7, and 1 day before expiry.
  - **SMS Reminders:** Text message reminders for critical documents.
  - **In-App Notifications:** Portal notifications for logged-in customers.
  - **Escalation:** If no response by expiry date, escalate to analyst and restrict services.
- **Renewal Workflow:**
  - Customer uploads renewed document via portal or email.
  - Automated verification of new document.
  - Analyst review if automated checks flag issues.
  - Update customer record with new expiry date.
  - Confirm renewal to customer.
- **Grace Periods:** Configurable grace periods after expiry before account restrictions apply. Different grace periods per document type and customer risk level.
- **Account Restrictions:** Define what happens when documents expire: transaction limits, account freeze, service suspension, or notification only.
- **Bulk Renewal:** For enterprise customers with many employees, bulk renewal process with spreadsheet upload.
- **Expiry Analytics:** Track renewal rates, average time to renew, and customer churn due to document expiry.

---

## 4.9 Document Storage & Archive Page

**Route:** `/kyc/documents/archive`

**Purpose:** Long-term storage, retrieval, and lifecycle management of all verification documents with compliance-grade audit trails.

**Deep Details:**

- **Document Repository:** Searchable archive of all documents with metadata indexing.
  - Search by: customer name, document type, upload date, expiry date, verification status, analyst, case ID.
  - Advanced filters: date range, document type, status, country, risk level.
- **Storage Tiers:**
  - **Hot Storage:** Recently uploaded and active documents. Fast access.
  - **Warm Storage:** Documents from closed cases within retention period. Moderate access speed.
  - **Cold Storage:** Documents past retention period but requiring legal hold. Slow access, lower cost.
  - **Glacier/Deep Archive:** Long-term regulatory retention. Retrieval in hours.
- **Retention Policies:**
  - Configurable retention periods per document type, customer type, and jurisdiction.
  - Automatic lifecycle management: move to cheaper storage after N days, delete after M days (unless legal hold).
  - Legal hold management: Flag documents for indefinite retention due to litigation or investigation.
- **Document Integrity:**
  - Cryptographic hashing (SHA-256) stored separately from document.
  - Periodic integrity checks comparing current hash to stored hash.
  - Blockchain anchoring (optional) for tamper-evident storage.
- **Access Controls:**
  - Role-based access to document archive.
  - Just-in-time access requests for sensitive documents requiring manager approval.
  - Access logging: who accessed what document, when, from where, and why.
- **Bulk Operations:** Export, delete (with approval), transfer, or reclassify documents in bulk.
- **Compliance Reporting:** Generate reports proving document retention compliance for auditors.

---

## 4.10 Document Verification Provider Management Page

**Route:** `/kyc/documents/providers`

**Purpose:** Configure and manage integrations with third-party document verification services, OCR engines, and biometric providers.

**Deep Details:**

- **Provider Directory:** List of integrated providers with status, configuration, and usage metrics.
  - **Document Verification:** Onfido, Jumio, Veriff, IDnow, Trulioo, Shufti Pro, Sumsub, etc.
  - **OCR Engines:** Google Cloud Vision, AWS Textract, Azure Form Recognizer, Tesseract, proprietary models.
  - **Biometric Providers:** FaceTec, iProov, Onfido, Jumio, Amazon Rekognition, Microsoft Face API.
  - **Government APIs:** eIDAS, DBS (UK), Aadhaar (India), etc. (where available).
- **Provider Configuration:**
  - API credentials (encrypted storage).
  - Endpoint URLs and timeout settings.
  - Fallback rules: if Provider A fails, try Provider B.
  - Routing rules: route by country, document type, or customer tier.
  - Cost controls: spending limits and alerts.
- **Provider Performance Dashboard:**
  - Uptime and availability.
  - Average response time by document type.
  - Accuracy rates (where ground truth is known).
  - Cost per verification.
  - Error rates and failure reasons.
- **A/B Testing:** Run different providers against each other on a subset of traffic to compare performance.
- **Provider Health Status:** Real-time status indicators (green/yellow/red) with last checked timestamp.
- **Custom Provider Integration:** Interface for adding custom webhook-based providers with schema mapping.
- **Failover Configuration:** Automatic failover rules with retry logic, circuit breakers, and alerting.
- **Usage Analytics:** Monthly/quarterly usage reports by provider, document type, and country.
