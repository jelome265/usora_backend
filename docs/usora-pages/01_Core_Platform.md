# USORA — Core Platform Pages

> **Scope:** Authentication, session management, landing surfaces, global navigation, and the foundational UI shell that every user interacts with before reaching functional modules. These pages form the backbone of the zero-trust, multi-tenant experience.

---

## 1.1 Login / Sign-In Page

**Route:** `/auth/login`

**Purpose:** Primary authentication entry point for all user roles across all tenants. Supports multiple authentication methods, tenant discovery, and session initialization.

**Deep Details:**

- **Tenant Discovery Input:** A dedicated field (often subdomain-based or tenant ID) that routes the user to the correct tenant's authentication realm. This is critical in multi-tenant architecture because credentials are scoped per tenant. The field may auto-detect from the URL subdomain (`tenant.usora.app`) or require manual entry.
- **Username/Password Form:** Standard email or username plus password fields with real-time validation, password visibility toggle, and strength indicators. Supports both personal and service account logins.
- **Multi-Factor Authentication (MFA) Challenge:** After primary credential validation, users with MFA enabled are redirected to an MFA challenge screen. Supports TOTP (authenticator apps), SMS OTP, email OTP, hardware security keys (WebAuthn/FIDO2), and push notifications.
- **Single Sign-On (SSO) Gateways:** Buttons for SAML 2.0, OAuth 2.0 / OpenID Connect, and enterprise identity providers (Azure AD, Okta, Google Workspace, Ping Identity, OneLogin). Each triggers the respective identity provider flow.
- **Biometric Login (WebAuthn):** Option to authenticate using fingerprint, Face ID, or hardware security keys for passwordless login. This is stored per-device and per-tenant.
- **Session Persistence:** "Remember this device" toggle that sets a secure, HTTP-only, SameSite=Strict cookie with a configurable TTL (default 30 days). Includes device fingerprinting to detect anomalies.
- **Forgot Password Link:** Routes to the password recovery flow.
- **Account Lockout Display:** If the account is locked due to failed attempts, the page shows lockout duration, reason, and a link to contact support or initiate an admin unlock request.
- **CAPTCHA Integration:** hCaptcha or reCAPTCHA v3 (invisible) triggers after N failed attempts to prevent brute-force attacks.
- **Audit Logging:** Every login attempt (success, failure, MFA challenge, lockout) is logged with IP, user agent, geolocation, and device fingerprint for compliance and security monitoring.
- **Responsive Design:** Fully responsive with mobile-optimized input fields, touch-friendly buttons, and biometric prompt support on mobile browsers.
- **Dark Mode Support:** Respects system preferences and tenant branding settings.
- **Tenant Branding:** The page dynamically loads the tenant's logo, primary color, background image, and custom CSS from the tenant configuration service. This ensures white-label capability.
- **Security Headers:** Strict CSP, X-Frame-Options, HSTS, and other security headers are enforced.
- **Rate Limiting:** Per-IP and per-tenant rate limiting to prevent credential stuffing and DDoS.
- **Accessibility:** WCAG 2.1 AA compliant with ARIA labels, keyboard navigation, screen reader support, and focus management.
- **Error States:** Granular error messages that don't leak sensitive info (e.g., "Invalid credentials" instead of "User not found"). Includes guidance for common issues (caps lock, browser compatibility).
- **Post-Login Redirect:** After successful authentication, users are redirected to their originally requested URL or their role-based default dashboard.

---

## 1.2 Multi-Factor Authentication (MFA) Challenge Page

**Route:** `/auth/mfa`

**Purpose:** Secondary authentication step that enforces MFA policies configured at tenant or user level.

**Deep Details:**

- **Method Selection:** If multiple MFA methods are enrolled, the user can choose which to use (TOTP, SMS, Email, Push, WebAuthn).
- **TOTP Entry:** 6-digit code input with auto-focus on each digit, auto-submission when complete, and a countdown timer showing remaining time for the current code window (30 seconds).
- **SMS/Email OTP:** Displays the masked destination (e.g., `+1 *** *** 1234` or `j***@example.com`). Includes a "Resend Code" button with cooldown timer (30-60 seconds). Rate-limited to prevent abuse.
- **Push Notification:** Shows a pending state with animation, waiting for the user to approve the push on their enrolled mobile device. Includes timeout handling (typically 60 seconds) and fallback to other methods.
- **WebAuthn / Security Key:** Triggers the browser's native WebAuthn prompt for fingerprint, Face ID, or hardware key tap. Handles errors gracefully (key not present, user cancellation, unsupported browser).
- **Backup Codes:** Option to use one of the pre-generated backup codes if primary MFA methods are unavailable. Warns the user that each backup code is single-use.
- **Trust This Device:** Checkbox to skip MFA on this device for a configurable period (default 30 days). Stored as a secure cookie with device fingerprint binding.
- **Enrollment Prompt:** If the user hasn't enrolled MFA but the tenant policy requires it, they are redirected to MFA enrollment instead of the challenge.
- **Emergency Access:** Link to initiate emergency access procedures (e.g., contacting an admin, using a break-glass account) for users who lost all MFA methods.
- **Real-Time Validation:** Immediate feedback on code validity without full page reload. Shows "Invalid code" with shake animation and allows retry.
- **Session Binding:** Upon successful MFA, the session is upgraded to an authenticated state with MFA claims embedded in the JWT or session token.

---

## 1.3 Password Recovery / Reset Page

**Route:** `/auth/forgot-password`

**Purpose:** Self-service password reset flow that balances security with user convenience.

**Deep Details:**

- **Identity Verification:** User enters their email or username. The system sends a reset link/OTP without confirming whether the account exists (to prevent user enumeration attacks).
- **Multi-Channel Delivery:** Reset links can be sent via email (primary), SMS (if phone is verified), or alternative email (if configured). The user selects the channel.
- **Reset Token Mechanics:** Tokens are cryptographically random, single-use, time-bound (typically 15-60 minutes), and bound to the user ID and tenant. Stored in a secure cache (Redis) with TTL.
- **Reset Link Page:** `/auth/reset-password?token=xyz` — Validates the token in real-time. If invalid or expired, shows an appropriate error with a link to restart the flow.
- **New Password Form:** Enforces tenant password policy (minimum length, complexity, history check, dictionary check against breached passwords via Have I Been Pwned API or local database). Real-time strength meter with visual feedback (weak/medium/strong).
- **Password History Check:** Prevents reuse of the last N passwords (configurable, default 12).
- **Concurrent Session Invalidation:** Upon successful reset, all existing sessions for the user are invalidated globally (across all devices) to prevent unauthorized access with old credentials.
- **Notification:** User receives an email/SMS notification that their password was changed, including timestamp, IP, and device info. Includes a "Wasn't you?" link to initiate an account lock.
- **Audit Trail:** Full audit log entry for password reset request, token generation, token validation, and password change events.
- **Rate Limiting:** Maximum 3 reset requests per hour per email to prevent abuse.

---

## 1.4 Account Registration / Sign-Up Page

**Route:** `/auth/register`

**Purpose:** Onboarding new users into the platform. May be public (for self-service tenants) or invite-only (for enterprise tenants).

**Deep Details:**

- **Tenant Context:** Registration is always scoped to a tenant. The tenant is determined via subdomain, invite token, or explicit selection. Users cannot register without a valid tenant context.
- **Registration Modes:**
  - **Open Registration:** Anyone with the tenant URL can sign up. Common for SaaS tiers.
  - **Invite-Only:** Requires a valid invitation token sent by an admin. Token is bound to email and tenant, with expiration.
  - **Admin-Created:** Admins create accounts; users receive a "set your password" email.
- **Registration Form Fields:**
  - Email (validated via MX record check and disposable email blacklist)
  - Password (with policy enforcement)
  - First Name, Last Name
  - Phone Number (optional, but required if SMS MFA is tenant policy)
  - Job Title / Department (for RBAC scoping)
  - Time Zone and Language preferences
  - Terms of Service and Privacy Policy acceptance (with version tracking)
- **Email Verification:** After submission, a verification email is sent. The account remains in "pending verification" state until the link is clicked. The verification token follows the same security model as password reset tokens.
- **Email Verification Page:** `/auth/verify-email?token=xyz` — Validates token, activates account, and redirects to login or onboarding wizard.
- **CAPTCHA:** Always required for open registration to prevent bot signups.
- **Duplicate Prevention:** Checks for existing email within the tenant. Cross-tenant duplicates are allowed (same person can have accounts in different tenants).
- **Auto-Provisioning:** If SSO is configured for the tenant, registration may be skipped in favor of JIT (Just-In-Time) provisioning on first SSO login.
- **Welcome Email:** Post-verification, a branded welcome email is sent with links to documentation, support, and the login page.
- **Onboarding Redirect:** After verification, new users are redirected to the onboarding wizard (see Onboarding section) or directly to their dashboard if onboarding is disabled.

---

## 1.5 Session Expiration / Timeout Warning Page

**Route:** `/auth/session-expired` (or modal overlay)

**Purpose:** Handles idle session timeouts and concurrent session conflicts gracefully.

**Deep Details:**

- **Idle Timeout Warning:** A modal appears 2 minutes before session expiry (configurable, default 15-minute idle timeout). Shows countdown timer. User can "Stay Logged In" which pings the session refresh endpoint.
- **Session Expired State:** If the user doesn't respond, they are redirected to `/auth/session-expired` with a message explaining the timeout. Includes a "Log In Again" button that preserves the current URL for post-login redirect.
- **Concurrent Session Conflict:** If the same user logs in from another device/browser and the tenant policy limits concurrent sessions, the older session receives a modal: "You've been logged out because you signed in elsewhere." Options to "Log In Again" or "View Active Sessions" (if session management is enabled for the role).
- **Security Event Logging:** Session timeout and concurrent session termination are logged as security events.
- **Data Loss Prevention:** Warns users if they have unsaved form data before the session expires. Attempts to preserve draft data in localStorage (encrypted) for restoration after re-login.

---

## 1.6 Landing Page (Pre-Auth Marketing Surface)

**Route:** `/` (root, when unauthenticated)

**Purpose:** Marketing and conversion surface for prospects evaluating the platform. Distinct from the authenticated dashboard.

**Deep Details:**

- **Hero Section:** Value proposition headline, subheadline, and primary CTA ("Request Demo", "Start Free Trial", "Contact Sales"). Background may be a subtle animation or high-quality image.
- **Feature Highlights:** Grid or carousel showcasing core capabilities: AI-powered verification, global compliance coverage, real-time risk scoring, developer-friendly APIs, white-label capability.
- **Trust Signals:** Customer logos, security certifications (SOC 2, ISO 27001, GDPR compliance badges), industry analyst mentions (Gartner, Forrester), and testimonial quotes.
- **Use Case Sections:** Tailored content for different industries (fintech, crypto, gaming, healthcare, sharing economy) with relevant imagery and statistics.
- **Integration Showcase:** Logos of integrated identity providers, document verification vendors, watchlist providers, and data sources.
- **Pricing Teaser:** Summary of pricing tiers with "See Full Pricing" CTA linking to the pricing page.
- **Developer CTA:** "Explore API Docs" section with code snippet preview (syntax-highlighted) showing a simple KYC API call.
- **Compliance Badges:** Visual display of supported regulatory frameworks (GDPR, CCPA, KYC/AML directives, PCI DSS, etc.).
- **Blog/Resources Preview:** Latest 3 blog posts, whitepapers, or case studies with thumbnails and excerpts.
- **Footer:** Links to legal pages (Privacy Policy, Terms of Service, Cookie Policy, Security), social media, contact info, and regional office addresses.
- **Analytics:** Heavy instrumentation with Segment, Google Analytics, or similar for conversion tracking. Heatmaps and session recording (with consent) for UX optimization.
- **A/B Testing Framework:** The page is built to support rapid A/B testing of headlines, CTAs, layouts, and imagery.
- **SEO:** Comprehensive meta tags, structured data (JSON-LD), Open Graph tags, canonical URLs, and sitemap integration.
- **Performance:** Optimized for Core Web Vitals (LCP < 2.5s, FID < 100ms, CLS < 0.1) with lazy loading, image optimization, and code splitting.
- **Localization:** Full i18n support with language switcher (detects browser locale, supports 20+ languages).
- **Accessibility:** WCAG 2.1 AA compliant with skip links, semantic HTML, alt text, and keyboard navigation.

---

## 1.7 Pricing Page

**Route:** `/pricing`

**Purpose:** Transparent pricing information for prospects and existing customers considering upgrades.

**Deep Details:**

- **Tier Comparison Table:** Side-by-side comparison of tiers (Starter, Professional, Enterprise, Custom). Rows include: monthly verification volume, supported countries, API rate limits, document types, biometric checks, AML screenings, support level, SLA, custom integrations, white-label options, dedicated account manager.
- **Interactive Calculator:** Slider or input fields for estimated monthly verifications, document types, and AML checks. Real-time price calculation with breakdown.
- **Volume Discounts:** Visual representation of per-check cost decreasing with volume. Enterprise tier shows "Contact Sales" instead of fixed pricing.
- **Billing Models:** Explanation of pay-per-check vs. subscription vs. hybrid models. Overage pricing for subscription tiers.
- **Feature Gating:** Clear indicators (checkmarks, crosses, "Add-on" labels) for what's included vs. excluded in each tier.
- **Currency Selector:** Prices displayed in USD, EUR, GBP, and local currencies with real-time conversion rates.
- **Billing Cycle Toggle:** Monthly vs. Annual pricing with savings percentage highlighted.
- **FAQ Accordion:** Common pricing questions (cancellation, upgrades/downgrades, overages, custom contracts, invoicing).
- **CTAs:** "Start Free Trial" (Starter/Pro), "Contact Sales" (Enterprise), "Schedule Demo" (all tiers).
- **Testimonials:** Quote from a customer in each tier segment validating ROI.
- **Guarantee:** Money-back guarantee or credit policy for unsatisfactory results.
- **Compliance Note:** Pricing includes all necessary compliance features — no hidden fees for GDPR, SOC 2, or audit logs.

---

## 1.8 Contact / Sales Inquiry Page

**Route:** `/contact` or `/contact-sales`

**Purpose:** Lead capture for sales, partnerships, and enterprise inquiries.

**Deep Details:**

- **Multi-Tab Form:** Tabs for "Sales Inquiry", "Partnership", "Press", "General", and "Support" (redirects to support portal).
- **Sales Inquiry Form Fields:**
  - Full Name, Work Email, Phone
  - Company Name, Industry, Company Size
  - Estimated Monthly Verification Volume
  - Countries of Operation
  - Use Case Description (textarea)
  - Current Solution (if any) and Pain Points
  - Preferred Contact Method and Time
  - Budget Range (dropdown)
  - How did you hear about us?
- **Form Validation:** Real-time validation with inline error messages. Email domain check to block personal emails (Gmail, Yahoo) for sales inquiries.
- **CAPTCHA:** Required for submission.
- **CRM Integration:** Form submissions are automatically routed to Salesforce, HubSpot, or the internal CRM with lead scoring based on company size, volume, and industry.
- **Auto-Response:** Immediate acknowledgment email with estimated response time (typically 24 hours for sales, 4 hours for enterprise).
- **Meeting Scheduler:** Embedded Calendly or custom scheduling widget for booking demo calls directly.
- **Live Chat:** Drift, Intercom, or custom live chat widget for real-time qualification.
- **Regional Offices:** Map with office locations, local phone numbers, and regional sales contacts.
- **SLA:** "We respond to all sales inquiries within 24 hours" guarantee.

---

## 1.9 Legal Pages Suite

**Routes:** `/legal/privacy-policy`, `/legal/terms-of-service`, `/legal/cookie-policy`, `/legal/data-processing-agreement`, `/legal/security-whitepaper`

**Purpose:** Regulatory compliance, transparency, and trust building.

**Deep Details:**

- **Privacy Policy:** Comprehensive GDPR/CCPA-compliant privacy policy covering: data collection scope, legal basis for processing, data subject rights (access, rectification, erasure, portability, restriction), retention periods, international transfers (SCCs), subprocessor list, DPO contact, complaint procedures.
- **Terms of Service:** Legally binding terms covering: service description, acceptable use, account responsibilities, payment terms, termination clauses, limitation of liability, indemnification, governing law, dispute resolution, and amendment procedures.
- **Cookie Policy:** Detailed breakdown of all cookies and tracking technologies used (essential, functional, analytics, marketing). Interactive cookie preference center allowing granular consent management. Records consent with timestamp and version for audit purposes.
- **Data Processing Agreement (DPA):** GDPR Article 28 compliant DPA for enterprise customers. Covers: processing instructions, subprocessor authorization, security measures, audit rights, breach notification, data return/deletion on termination.
- **Security Whitepaper:** Public-facing document detailing the security architecture: encryption at rest and in transit, key management (HSM), network security, access controls, vulnerability management, penetration testing schedule, incident response, certifications (SOC 2 Type II, ISO 27001), and compliance frameworks.
- **Version History:** Each legal document shows version number, effective date, and change history with diff view.
- **Acceptance Tracking:** User acceptance of ToS and Privacy Policy is tracked per version. Users must re-accept when material changes occur. Blocks platform access until accepted.
- **Downloadable PDFs:** Each document available as branded PDF for legal review and contract attachments.
- **Multi-Jurisdiction:** Content adapts based on detected region (EU, US, UK, APAC) to show relevant regulatory references.

---

## 1.10 Global Navigation / App Shell

**Route:** Persistent across all authenticated routes

**Purpose:** The structural framework that hosts all authenticated pages. Not a page per se, but a critical UI component that defines the user experience.

**Deep Details:**

- **Top Navigation Bar:**
  - **Tenant Switcher:** Dropdown for users with access to multiple tenants (common for consultants, auditors, or parent company admins). Shows tenant name, logo, and environment indicator (Production, Staging, Sandbox).
  - **Global Search:** Omnibox search across entities (customers, cases, documents, settings, help articles). Supports fuzzy matching, filters, and recent searches. Keyboard shortcut (`Cmd+K` / `Ctrl+K`).
  - **Notifications Bell:** Real-time notification center with unread count badge. Categories: system alerts, case updates, risk alerts, approval requests, security events. Supports marking as read, archiving, and notification preferences.
  - **User Profile Dropdown:** Avatar, name, role, tenant info. Links to: Profile Settings, Account Settings, Notification Preferences, Theme Toggle (Light/Dark/System), Language Selector, Help & Support, Sign Out.
  - **Breadcrumb Trail:** Contextual breadcrumb showing current location within the app hierarchy. Clickable for quick navigation up the tree.
- **Sidebar Navigation:**
  - **Collapsible Sections:** Grouped by functional area (Dashboard, KYC Operations, Customers, Risk & Compliance, Admin, etc.).
  - **Role-Based Visibility:** Menu items are dynamically rendered based on the user's RBAC permissions. Hidden items are completely removed from DOM, not just visually hidden.
  - **Active State:** Visual indicator (background highlight, left border accent) for current page.
  - **Badge Counts:** Real-time counts on menu items (e.g., "Pending Reviews: 12", "Risk Alerts: 3").
  - **Pinned Items:** Users can pin frequently used pages to a "Favorites" section at the top of the sidebar.
  - **Tooltip Labels:** Icon-only collapsed mode shows tooltips on hover.
  - **Keyboard Navigation:** Full keyboard support with arrow keys, Enter, and Escape.
- **Main Content Area:**
  - **Page Header:** Title, subtitle, action buttons (primary and secondary), and contextual tabs.
  - **Content Container:** Responsive grid that adapts to viewport. Supports full-width, constrained, and split-pane layouts.
  - **Loading States:** Skeleton screens for initial load, spinners for async operations, and progress bars for multi-step processes.
  - **Empty States:** Illustration + message + CTA for pages with no data.
  - **Error Boundaries:** Graceful error handling with retry options and error reporting.
- **Right Context Panel (Collapsible):** Contextual information panel that slides in from the right. Used for: entity details, activity logs, related records, quick actions, and audit trails. Can be pinned open or dismissed.
- **Footer (Authenticated):** Minimal footer with app version, environment label, build timestamp, and links to legal pages.
- **Toast Notifications:** Non-blocking notifications for async operation results (success, error, warning, info). Auto-dismiss with progress bar, manual dismiss, and action buttons. Stacked with newest on top.
- **Modal System:** Layered modal management with backdrop blur, focus trapping, and escape-to-close. Supports confirmation dialogs, form modals, detail modals, and full-screen modals.
- **Drawer System:** Slide-out panels for complex workflows that need more space than a modal but don't warrant a full page navigation.
- **Keyboard Shortcuts:** Global shortcut system (`?` for help overlay) with contextual shortcuts per page.
- **Offline Detection:** Banner notification when connection is lost, with automatic retry and sync when restored.
- **Real-Time Connection:** WebSocket or SSE connection for live updates (notifications, case status changes, chat messages). Auto-reconnect with exponential backoff.
- **Tenant Branding:** Sidebar and top bar colors, logo, and favicon adapt to tenant branding configuration.
- **Performance:** Code-splitting per route, lazy-loaded components, virtual scrolling for long lists, and debounced search inputs.
