# USORA — Compliance & Regulatory Mapping Document

## 1. Document Overview

| Field | Value |
|---|---|
| **Version** | 1.0.0 |
| **Last Updated** | 2026-07-21 |
| **Author** | USORA Compliance & Legal Team |
| **Review Cycle** | Quarterly |
| **Classification** | Internal — Confidential |
| **Next Review** | 2026-10-21 |

---

## 2. Compliance Framework Inventory

### 2.1 Active Frameworks

USORA is designed and maintained to satisfy the requirements of the following regulatory and industry frameworks. Each framework is mapped to specific USORA platform capabilities, controls, and artifacts.

| Framework | Jurisdiction | Status | Certification Target | Owner |
|---|---|---|---|---|
| **SOC 2 Type II** | Global (US-centric) | Certified | Maintained | Security Team |
| **ISO 27001:2022** | Global | In Progress | Q4 2026 | Security Team |
| **ISO 27701** | Global | In Progress | Q4 2026 | Security Team |
| **PCI DSS Level 1** | Global | In Progress | Q1 2027 | Security Team |
| **GDPR** | EU | Compliant | N/A (legal) | Legal & DPO |
| **CCPA/CPRA** | California, US | Compliant | N/A (legal) | Legal & DPO |
| **LGPD** | Brazil | Compliant | N/A (legal) | Legal & DPO |
| **PIPEDA** | Canada | Compliant | N/A (legal) | Legal & DPO |
| **PDPA (Singapore)** | Singapore | Compliant | N/A (legal) | Legal & DPO |
| **FATF Recommendations** | Global | Compliant | N/A (legal) | Compliance Team |
| **EU AML5 / AML6** | EU | Compliant | N/A (legal) | Compliance Team |
| **US BSA / Patriot Act** | United States | Compliant | N/A (legal) | Compliance Team |
| **PSD2 / SCA** | EU | Compliant | N/A (legal) | Compliance Team |
| **MiFID II** | EU | Compliant | N/A (legal) | Compliance Team |
| **NIST Cybersecurity Framework** | Global (US-centric) | Aligned | N/A | Security Team |
| **CIS Controls v8** | Global | Aligned | N/A | Security Team |

---

## 3. Framework-by-Framework Control Mapping

### 3.1 SOC 2 Type II (Trust Services Criteria)

**Scope:** All USORA SaaS and Cloud Dedicated deployments.

**Audit Period:** Continuous monitoring with annual Type II attestation.

#### 3.1.1 Security (Common Criteria)

| SOC 2 CC | Control Description | USORA Implementation | Evidence |
|---|---|---|---|
| **CC6.1** | Logical access security | RBAC + ABAC with 50+ roles; JWT/mTLS auth; SPIFFE/SPIRE identity federation | Access policy configs, IAM audit logs |
| **CC6.2** | Prior to access, registration and authorization | Tenant onboarding with legal review, DPA, SLA; admin account creation with MFA | Onboarding checklists, DPA records |
| **CC6.3** | Access removal | Automated deprovisioning on contract termination; JIT access with 24h TTL | Offboarding logs, JIT access records |
| **CC6.4** | Access credentials | HashiCorp Vault dynamic credentials (1h TTL); API key rotation; certificate auto-rotation (24h) | Vault audit logs, credential rotation logs |
| **CC6.5** | Restriction of access | Network policies (deny-all default); Calico/Cilium eBPF; namespace isolation | Network policy manifests, Cilium Hubble logs |
| **CC6.6** | System components | Infrastructure as Code (GitOps); immutable artifacts; SLSA Level 3 | Terraform state, SBOMs, signed container images |
| **CC6.7** | System operations | Automated monitoring (Prometheus/Grafana); alerting (PagerDuty); runbook-driven response | Monitoring dashboards, alert history |
| **CC6.8** | System recovery | Multi-region active-active; RPO 5min / RTO 15min; quarterly DR drills | DR drill reports, failover test results |
| **CC7.1** | Detection of security events | Honeytokens; deceptive infrastructure; Falco runtime security; anomaly detection | Security incident logs, Falco alerts |
| **CC7.2** | Incident response | 4-tier on-call; war room protocol; blameless postmortems; 30s automated containment | Incident timelines, postmortem docs |
| **CC7.3** | System development | CI/CD with SAST/DAST; dependency scanning; >90% test coverage; signed artifacts | CI pipeline reports, vulnerability scans |
| **CC7.4** | Change management | GitOps with ArgoCD; canary/blue-green deployments; automatic rollback on error threshold | Deployment logs, ArgoCD sync history |
| **CC7.5** | System documentation | Architecture docs, runbooks, API specs, decision logs | Document repository, version control |
| **CC8.1** | Entity-level controls | Board oversight; risk assessment; vendor management; business continuity planning | Board minutes, risk registers |

#### 3.1.2 Availability

| SOC 2 A | Control Description | USORA Implementation | Evidence |
|---|---|---|---|
| **A1.1** | System availability | 99.99% SLA (SaaS); 99.95% (Dedicated); multi-region active-active; auto-failover | SLA reports, uptime dashboards |
| **A1.2** | System capacity | Horizontal pod autoscaling (HPA); KEDA event-driven scaling; cluster autoscaling | HPA metrics, capacity planning reports |
| **A1.3** | Environmental threats | AWS/Azure/GCP data centers with redundant power, cooling, fire suppression | Cloud provider SOC 2 reports |

#### 3.1.3 Confidentiality

| SOC 2 C | Control Description | USORA Implementation | Evidence |
|---|---|---|---|
| **C1.1** | Confidentiality commitments | DPA with every tenant; data classification policy; encryption requirements | DPA repository, classification labels |
| **C1.2** | Confidentiality procedures | AES-256-GCM at rest; TLS 1.3 in transit; tenant-specific encryption keys in HSM | Encryption key management logs |

#### 3.1.4 Processing Integrity

| SOC 2 PI | Control Description | USORA Implementation | Evidence |
|---|---|---|---|
| **PI1.1** | Entity processing | Input validation (OpenAPI schema); request transformation; protocol translation | API validation logs, schema compliance reports |
| **PI1.2** | Complete processing | Kafka at-least-once delivery; manual ACK; idempotency keys; saga pattern | Kafka offset logs, saga state records |
| **PI1.3** | Accurate processing | BPMN workflow engine (Camunda); state machine validation; audit trail | Workflow execution logs, state transitions |
| **PI1.4** | Timely processing | SLA tracking; queue depth monitoring; priority partitioning (P0/P1/P2) | SLA dashboards, queue metrics |

#### 3.1.5 Privacy

| SOC 2 P | Control Description | USORA Implementation | Evidence |
|---|---|---|---|
| **P1.1** | Privacy notice | Tenant-configurable privacy notices; consent management; data subject rights portal | Privacy notice templates, consent logs |
| **P2.1** | Choice and consent | Granular consent collection; opt-in/opt-out per data category; consent revocation | Consent database, revocation logs |
| **P3.1** | Collection | Data minimization; purpose limitation; only collect data required for KYC | Data inventory, collection purpose records |
| **P4.1** | Use, retention, and disposal | Tenant-configurable retention (1-10 years); auto-deletion on expiry; secure wiping | Retention policy configs, deletion logs |
| **P5.1** | Access | Data subject access request (DSAR) portal; automated data export | DSAR fulfillment logs, export records |
| **P6.1** | Disclosure | Third-party data sharing agreements; subprocessor list; cross-border transfer mechanisms (SCCs) | Subprocessor agreements, SCC records |
| **P7.1** | Quality | Data accuracy verification; document cross-reference; identity consistency checks | Data quality reports, verification logs |
| **P8.1** | Monitoring and enforcement | Privacy impact assessments (PIA); regular privacy audits; DPO oversight | PIA documents, audit reports |

---

### 3.2 ISO 27001:2022

**Scope:** Information Security Management System (ISMS) covering all USORA operations.

**Target Certification:** Q4 2026 (planned).

#### 3.2.1 Organizational Controls (Annex A)

| ISO 27001:2022 Ref | Control | USORA Implementation | Evidence |
|---|---|---|---|
| **A.5.1** | Policies for information security | Information Security Policy; Acceptable Use Policy; Data Classification Policy | Policy documents, training records |
| **A.5.2** | Information security roles | CISO role; Security Team; DPO; compliance officers; RACI matrix | Org chart, role definitions |
| **A.5.3** | Segregation of duties | No single individual has unrestricted access; approval workflows for sensitive ops | Access reviews, approval workflows |
| **A.5.4** | Management responsibilities | Security governance board; quarterly security reviews; risk acceptance process | Board minutes, risk registers |
| **A.5.5** | Contact with special interest groups | Industry memberships (FS-ISAC, Cloud Security Alliance); threat intelligence feeds | Membership records, TI subscriptions |
| **A.5.6** | Information security in project management | Security requirements in SDLC; security gates in CI/CD; threat modeling | Project security checklists, threat models |
| **A.5.7** | Threat intelligence | Commercial TI feeds (Mandiant, Recorded Future); open-source feeds (MISP) | TI platform logs, IOC databases |
| **A.5.8** | Information security in project management | Security requirements in SDLC; security gates in CI/CD; threat modeling | Project security checklists, threat models |
| **A.5.9** | Inventory of information and assets | CMDB; asset tagging; cloud asset inventory (AWS Config, Azure Resource Graph) | Asset inventory, CMDB exports |
| **A.5.10** | Acceptable use of information | AUP; monitoring of acceptable use; disciplinary procedures | AUP acknowledgments, monitoring logs |
| **A.5.11** | Return of assets | Asset return checklist; data wiping procedures; exit interview | Offboarding records, wiping certificates |
| **A.5.12** | Classification of information | Data classification labels (Public, Internal, Confidential, Restricted); handling procedures | Classification policy, labeled data samples |
| **A.5.13** | Labeling of information | Automated labeling (DLP); manual labeling for sensitive documents | DLP scan reports, label audit |
| **A.5.14** | Information transfer | Secure transfer protocols (TLS 1.3, SFTP); encryption in transit; DLP scanning | Transfer logs, DLP reports |
| **A.5.15** | Access control | RBAC/ABAC; least privilege; MFA; session management | IAM policies, access reviews |
| **A.5.16** | Identity management | Identity provider integration (Okta, Azure AD); SSO; lifecycle management | IdP logs, provisioning records |
| **A.5.17** | Authentication information | Password policy (NIST 800-63B); MFA enforcement; credential rotation | Password policy, MFA enrollment rates |
| **A.5.18** | Access rights | Access request workflow; quarterly access reviews; automated revocation | Access request tickets, review reports |
| **A.5.19** | Information security in supplier relationships | Vendor security assessments; contractual security requirements; SOC 2/ISO 27001 verification | Vendor assessments, contract clauses |
| **A.5.20** | Addressing information security within supplier agreements | Security SLAs; right-to-audit clauses; incident notification requirements | Contract security addendums |
| **A.5.21** | Managing information security in the ICT supply chain | SBOM generation; dependency vulnerability scanning; SLSA Level 3 | SBOMs, vulnerability scan reports |
| **A.5.22** | Monitoring, review and audit | Continuous monitoring; internal audits; external audits (annual) | Audit schedules, findings reports |
| **A.5.23** | Information security for use of cloud services | Cloud security posture management (CSPM); shared responsibility model documentation | CSPM reports, responsibility matrix |
| **A.5.24** | Planning and preparation for information security continuity | Business continuity plan; disaster recovery plan; tabletop exercises | BCP/DRP documents, exercise reports |
| **A.5.25** | ICT readiness for business continuity | Redundant infrastructure; data replication; automated failover | DR test results, replication metrics |
| **A.5.26** | Information security aspects of business continuity management | Security incident response plan; crisis communication plan; backup procedures | IR plans, crisis comms templates |
| **A.5.27** | Redundancy of information processing facilities | Multi-region deployment; load balancing; database replication | Architecture diagrams, failover tests |
| **A.5.28** | Requirements for availability of information systems | SLA definitions; availability monitoring; capacity planning | SLA dashboards, capacity reports |
| **A.5.29** | Requirements for verification of delivered software | Code review; SAST/DAST; penetration testing; signed releases | Security test reports, sign-off records |
| **A.5.30** | Requirements for cryptographic solutions | Cryptographic policy; approved algorithms; key management procedures | Cryptographic policy, key lifecycle docs |
| **A.5.31** | Legal, statutory, regulatory and contractual requirements | Compliance register; legal review of contracts; regulatory change tracking | Compliance register, legal review logs |
| **A.5.32** | Intellectual property rights | Open source license compliance; proprietary code protection; patent awareness | License inventory, legal clearance |
| **A.5.33** | Protection of records | Retention policies; WORM storage; immutable audit logs | Retention schedules, WORM verification |
| **A.5.34** | Privacy and protection of PII | GDPR/CCPA compliance; data minimization; consent management; DSAR portal | Privacy assessments, DSAR logs |
| **A.5.35** | Independent review of information security | External penetration testing (quarterly); red team exercises; bug bounty | Pen test reports, bug bounty metrics |
| **A.5.36** | Compliance with policies, rules and standards | Policy acknowledgment; compliance training; violation reporting | Training records, violation logs |
| **A.5.37** | Documented operating procedures | Runbooks; SOPs; operational documentation | Runbook repository, SOP library |

#### 3.2.2 People Controls

| ISO 27001:2022 Ref | Control | USORA Implementation | Evidence |
|---|---|---|---|
| **A.6.1** | Screening | Background checks for all employees; reference checks; role-specific screening | HR records, screening reports |
| **A.6.2** | Terms and conditions of employment | Security clauses in employment contracts; confidentiality agreements | Contract templates, signed copies |
| **A.6.3** | Information security awareness, education and training | Annual security training; phishing simulations; role-specific training | Training completion records, phishing metrics |
| **A.6.4** | Disciplinary process | Security violation policy; progressive discipline; termination procedures | Policy document, case records |
| **A.6.5** | Responsibilities after termination or change of employment | Asset return; access revocation; knowledge transfer checklist | Offboarding checklists, revocation logs |
| **A.6.6** | Confidentiality or non-disclosure agreements | NDA for all employees, contractors, vendors; annual re-acknowledgment | NDA records, re-acknowledgment logs |
| **A.6.7** | Remote working | VPN requirement; endpoint security; secure communication tools | Remote work policy, endpoint compliance |
| **A.6.8** | Information security event reporting | Security incident reporting portal; anonymous reporting; whistleblower protection | Reporting portal metrics, case records |

#### 3.2.3 Physical Controls

| ISO 27001:2022 Ref | Control | USORA Implementation | Evidence |
|---|---|---|---|
| **A.7.1** | Physical security perimeters | Cloud provider data center security; biometric access; 24/7 monitoring | Cloud provider certifications, audit reports |
| **A.7.2** | Physical entry controls | Badge access; visitor management; mantrap entry | Access logs, visitor records |
| **A.7.3** | Securing offices, rooms and facilities | Locked server rooms; CCTV; environmental controls | Facility audit reports, CCTV logs |
| **A.7.4** | Physical security monitoring | Intrusion detection; alarm systems; security patrols | Alarm logs, patrol records |
| **A.7.5** | Protecting against physical and environmental threats | Fire suppression; UPS; climate control; seismic bracing | Facility inspection reports |
| **A.7.6** | Working in secure areas | Clean desk policy; screen privacy; restricted zones | Policy, area audits |
| **A.7.7** | Clear desk and clear screen | Clean desk policy; automatic screen lock; document shredding | Policy, audit observations |
| **A.7.8** | Equipment siting and protection | Equipment placement; cable protection; power conditioning | Facility layout, inspection reports |
| **A.7.9** | Security of assets off-premises | Laptop encryption; remote wipe; asset tracking | MDM logs, wipe confirmations |
| **A.7.10** | Storage media | Media labeling; secure storage; disposal procedures | Media inventory, disposal certificates |
| **A.7.11** | Supporting utilities | Redundant power; backup generators; dual ISP connections | Utility test reports |
| **A.7.12** | Cabling security | Cable protection; segregation of power/data; labeling | Infrastructure audits |
| **A.7.13** | Equipment maintenance | Maintenance schedules; vendor access controls; spare parts inventory | Maintenance logs |
| **A.7.14** | Secure disposal or re-use of equipment | Data wiping (NIST 800-88); physical destruction for failed drives | Wiping certificates, destruction records |

#### 3.2.4 Technological Controls

| ISO 27001:2022 Ref | Control | USORA Implementation | Evidence |
|---|---|---|---|
| **A.8.1** | User endpoint devices | MDM enrollment; disk encryption; antivirus; patch management | MDM compliance reports, patch metrics |
| **A.8.2** | Privileged access rights | PAM solution; session recording; JIT access; break-glass procedures | PAM logs, session recordings |
| **A.8.3** | Information access restriction | Application-level access control; database RLS; API authorization | Access control matrices, RLS policies |
| **A.8.4** | Access to source code | Git repository access control; branch protection; code review requirements | Git audit logs, branch protection rules |
| **A.8.5** | Secure authentication | MFA; passwordless options (FIDO2); biometric auth for admin portal | Auth logs, MFA enrollment rates |
| **A.8.6** | Capacity management | Resource monitoring; capacity forecasting; auto-scaling | Capacity reports, scaling events |
| **A.8.7** | Protection against malware | EDR (CrowdStrike/SentinelOne); email filtering; sandboxing | EDR alerts, malware detection logs |
| **A.8.8** | Management of technical vulnerabilities | Vulnerability scanning (Trivy, Snyk); patch management; SLAs for remediation | Vulnerability scan reports, patch SLAs |
| **A.8.9** | Configuration management | Infrastructure as Code; configuration drift detection; baseline enforcement | Terraform state, drift detection reports |
| **A.8.10** | Deletion of information | Secure deletion procedures; data retention automation; tenant-configurable expiry | Deletion logs, retention policy configs |
| **A.8.11** | Data masking | Dynamic data masking; PII tokenization; field-level encryption | Masking rules, tokenization logs |
| **A.8.12** | Data leakage prevention | DLP solution; egress monitoring; USB/port controls | DLP alerts, egress monitoring logs |
| **A.8.13** | Information backup | Automated backups (PostgreSQL WAL, S3 versioning); cross-region replication; restore testing | Backup logs, restore test reports |
| **A.8.14** | Redundancy of information processing facilities | Multi-region deployment; active-active; database replication | Architecture docs, failover tests |
| **A.8.15** | Logging | Centralized logging (ELK/Loki); structured JSON; correlation IDs; 7-year retention | Log architecture, retention verification |
| **A.8.16** | Monitoring activities | SIEM (Splunk/Datadog); anomaly detection; UEBA | SIEM dashboards, anomaly alerts |
| **A.8.17** | Clock synchronization | NTP across all systems; synchronized timestamps in all logs | NTP config, timestamp audit |
| **A.8.18** | Use of privileged utility programs | Restricted access to admin tools; audit logging; approval workflows | Tool access logs, approval records |
| **A.8.19** | Installation of software on operational systems | Approved software list; application whitelisting; package signing | Software inventory, whitelisting policies |
| **A.8.20** | Network security | Network segmentation; zero-trust; mTLS; WAF; DDoS protection | Network diagrams, policy configs |
| **A.8.21** | Security of network services | Service hardening; port scanning; penetration testing | Hardening guides, pen test findings |
| **A.8.22** | Segregation in networks | Network segmentation; VPC per tenant; micro-segmentation | Network architecture, segmentation rules |
| **A.8.23** | Web filtering | URL filtering; content inspection; proxy logs | Web filter logs, blocked requests |
| **A.8.24** | Use of cryptography | Cryptographic policy; AES-256-GCM; TLS 1.3; HSM-backed keys | Cryptographic inventory, key lifecycle |
| **A.8.25** | Secure development lifecycle | Secure SDLC; threat modeling; security requirements; code review | SDL documentation, threat model library |
| **A.8.26** | Application security requirements | Security requirements in user stories; OWASP Top 10 mitigation | Security requirements, test results |
| **A.8.27** | Secure system architecture and engineering | Defense in depth; secure by design; principle of least privilege | Architecture review records |
| **A.8.28** | Secure coding | Secure coding guidelines; SAST; code review; linting | Coding standards, SAST reports |
| **A.8.29** | Security testing in development | SAST (SonarQube); DAST (OWASP ZAP); IAST; penetration testing | Security test reports, vulnerability metrics |
| **A.8.30** | Outsourced development | Vendor security assessments; contractual security requirements; code review | Vendor assessments, contract clauses |
| **A.8.31** | Separation of development, test and production environments | Environment isolation; separate credentials; data masking in non-prod | Environment isolation verification |
| **A.8.32** | Change management | Change advisory board; impact assessment; rollback procedures | CAB minutes, change records |
| **A.8.33** | Test information | Test data generation; data masking; no production data in test | Test data procedures, masking verification |
| **A.8.34** | Protection of information systems during audit testing | Audit scope definition; read-only access; audit logs for audit activities | Audit scope docs, audit access logs |

---

### 3.3 GDPR (General Data Protection Regulation)

**Scope:** All processing of personal data of EU data subjects, regardless of where processing occurs.

**Data Protection Officer (DPO):** dpo@usora.io

#### 3.3.1 Lawful Basis Mapping

| GDPR Article | Requirement | USORA Basis | Implementation |
|---|---|---|---|
| **Art. 6(1)(b)** | Contract necessity | KYC verification as contractual obligation | Verification workflow is core service delivery |
| **Art. 6(1)(c)** | Legal obligation | AML/CFT compliance | Mandatory identity verification under AML laws |
| **Art. 6(1)(f)** | Legitimate interest | Fraud prevention, security | Balanced against data subject rights; documented LIA |

#### 3.3.2 Data Subject Rights Mapping

| GDPR Right | Article | USORA Implementation | SLA |
|---|---|---|---|
| **Right to be informed** | Art. 13-14 | Privacy notice at collection; layered notice design; tenant-configurable | Real-time |
| **Right of access** | Art. 15 | DSAR portal; automated data export; structured JSON/PDF response | 30 days |
| **Right to rectification** | Art. 16 | Data correction workflow; re-verification trigger; audit trail of changes | 30 days |
| **Right to erasure** | Art. 17 | "Right to be forgotten" workflow; cascading deletion; verification of identity | 30 days |
| **Right to restrict processing** | Art. 18 | Processing restriction flag; data quarantine; exception for legal obligations | 30 days |
| **Right to data portability** | Art. 20 | Export in machine-readable format (JSON, CSV); structured data package | 30 days |
| **Right to object** | Art. 21 | Objection workflow; automated processing halt; human review | 30 days |
| **Right not to be subject to automated decision-making** | Art. 22 | Human review option for all automated decisions; explainability dashboard | Real-time |
| **Right to lodge a complaint** | Art. 77 | Complaint portal; escalation to DPO; supervisory authority notification | 72 hours |

#### 3.3.3 GDPR Technical and Organizational Measures (TOMs)

| Measure Category | Implementation | Evidence |
|---|---|---|
| **Pseudonymization** | Tenant-specific identifiers; internal UUIDs; no direct PII in logs | Data architecture docs |
| **Encryption** | AES-256-GCM at rest; TLS 1.3 in transit; tenant-specific keys in HSM | Encryption policy, key management docs |
| **Confidentiality** | RBAC/ABAC; need-to-know; NDA; training | Access reviews, training records |
| **Integrity** | Immutable audit logs; hash chains; blockchain anchoring; checksums | Audit architecture, verification procedures |
| **Availability** | Multi-region active-active; RPO 5min; RTO 15min; backups | DR plans, test results |
| **Resilience** | Auto-scaling; circuit breakers; graceful degradation; chaos engineering | Resilience test reports |
| **Data minimization** | Only collect data required for KYC; configurable fields per tenant | Data inventory, collection purpose docs |
| **Purpose limitation** | Data used only for stated purposes; no secondary use without consent | Privacy policy, purpose records |
| **Storage limitation** | Tenant-configurable retention (1-10 years); auto-deletion on expiry | Retention policies, deletion schedules |
| **Accuracy** | Document verification; cross-reference; identity consistency checks | Verification accuracy metrics |

#### 3.3.4 GDPR Cross-Border Data Transfers

| Transfer Mechanism | Applicability | Implementation |
|---|---|---|
| **Standard Contractual Clauses (SCCs)** | EU to non-adequate countries | Module 1 (Controller-Controller) and Module 2 (Controller-Processor) SCCs with all tenants and subprocessors |
| **Adequacy decisions** | EU to adequate countries | Rely on EU Commission adequacy decisions for UK, Canada, Japan, South Korea, etc. |
| **Binding Corporate Rules (BCRs)** | Intra-group transfers | Planned for Q1 2027 |
| **Data localization** | Tenant preference | EU data stays in EU region; tenant-configurable data residency |

---

### 3.4 AML/CFT (Anti-Money Laundering / Countering the Financing of Terrorism)

**Scope:** All verification workflows for financial services tenants.

#### 3.4.1 FATF Recommendations Mapping

| FATF Rec. | Requirement | USORA Implementation | Evidence |
|---|---|---|---|
| **R.10** | Customer due diligence (CDD) | Identity verification; document validation; biometric matching; address verification | Verification reports, CDD records |
| **R.10A** | Beneficial ownership | Business verification module; UBO identification; registry cross-checks | BO verification reports |
| **R.11** | Record keeping | 7-year retention (configurable); immutable audit trail; blockchain anchoring | Retention policies, audit logs |
| **R.12** | PEPs | Real-time PEP screening; risk-based enhanced due diligence; ongoing monitoring | PEP screening results, EDD records |
| **R.13** | Correspondent banking | N/A (not a bank) | — |
| **R.15** | New technologies | ML-powered risk scoring; adaptive fraud detection; explainable AI | Model documentation, risk reports |
| **R.16** | Wire transfers | N/A (not a payment processor) | — |
| **R.17** | Reliance on third parties | Vendor due diligence; contractual AML obligations; monitoring | Vendor assessments |
| **R.20** | Reporting of suspicious transactions | SAR filing integration; automated alert generation; case management | SAR records, alert logs |
| **R.21** | Tipping-off | Restricted access to investigation data; audit trail of access | Access logs, data segregation |
| **R.24** | Transparency and beneficial ownership | Business verification; UBO identification; registry checks | BO verification reports |
| **R.25** | Trustees and nominees | Enhanced due diligence for trust structures; source of funds verification | EDD reports, SOF records |
| **R.34** | Guidance and feedback | Regulatory update monitoring; compliance advisory; tenant guidance | Advisory records, update logs |
| **R.35** | Sanctions | Real-time sanctions screening; OFAC, UN, EU lists; fuzzy matching | Screening results, match records |

#### 3.4.2 EU AML5 / AML6 Mapping

| AML5/6 Requirement | USORA Implementation | Evidence |
|---|---|---|
| **Risk-based approach** | ML-powered risk scoring; dynamic risk thresholds; risk-based workflow branching | Risk scoring reports, workflow configs |
| **Simplified due diligence (SDD)** | Low-risk workflow variant; reduced verification steps; automated approval | SDD workflow definitions |
| **Enhanced due diligence (EDD)** | EDD module; source of funds; beneficial ownership; ongoing monitoring | EDD reports, monitoring logs |
| **Ongoing monitoring** | Continuous watchlist monitoring; transaction pattern analysis; periodic re-verification | Monitoring schedules, re-verification logs |
| **Record keeping** | 7-year retention; immutable audit trail; blockchain anchoring | Retention policies, audit verification |
| **Suspicious activity reporting** | SAR filing integration; automated alert generation; case management | SAR records, case logs |
| **Data protection** | GDPR compliance; data minimization; secure storage; access controls | Privacy assessments, access logs |

#### 3.4.3 US BSA / Patriot Act Mapping

| BSA Requirement | USORA Implementation | Evidence |
|---|---|---|
| **Customer Identification Program (CIP)** | Document verification; biometric matching; identity validation | CIP verification records |
| **Customer Due Diligence (CDD)** | Risk scoring; identity verification; beneficial ownership | CDD records, risk assessments |
| **Enhanced Due Diligence (EDD)** | EDD module; source of funds; ongoing monitoring | EDD reports |
| **Suspicious Activity Report (SAR)** | SAR filing to FinCEN; automated alert generation; case management | SAR filings, alert logs |
| **Currency Transaction Report (CTR)** | N/A (not a bank) | — |
| **Record keeping** | 7-year retention; immutable audit trail | Retention policies, audit logs |
| **OFAC screening** | Real-time OFAC sanctions screening; fuzzy matching; false positive management | Screening results, match records |

---

### 3.5 PCI DSS Level 1

**Scope:** All systems that process, store, or transmit cardholder data (for tenants with card payment integration).

**Target Certification:** Q1 2027 (planned).

| PCI DSS Requirement | USORA Implementation | Evidence |
|---|---|---|
| **Req. 1** | Firewall configuration | Network segmentation; VPC isolation; security groups; deny-all default | Network diagrams, firewall rules |
| **Req. 2** | System hardening | CIS benchmarks; hardened base images; minimal attack surface | Hardening guides, scan results |
| **Req. 3** | Stored cardholder data | Tokenization; no raw PAN storage; encryption of stored data | Data flow diagrams, tokenization logs |
| **Req. 4** | Encryption in transit | TLS 1.3; perfect forward secrecy; certificate pinning | SSL/TLS scan reports |
| **Req. 5** | Anti-virus | EDR on all endpoints; centralized management; signature updates | EDR reports, update logs |
| **Req. 6** | Secure development | Secure SDLC; code review; SAST/DAST; vulnerability management | SDL documentation, test reports |
| **Req. 7** | Restrict access to CHD | RBAC; least privilege; need-to-know; access reviews | Access control matrices, review records |
| **Req. 8** | Identify and authenticate access | MFA; strong passwords; unique IDs; session management | Auth policies, MFA enrollment |
| **Req. 9** | Restrict physical access | Cloud provider physical security; data center certifications | Provider certifications |
| **Req. 10** | Track and monitor access | Comprehensive logging; SIEM; audit trail; log integrity | Log architecture, SIEM dashboards |
| **Req. 11** | Regularly test security | Vulnerability scanning (quarterly); penetration testing (annual); ASV scans | Scan reports, pen test findings |
| **Req. 12** | Information security policy | Information security policy; risk assessment; incident response | Policy documents, risk registers |

---

### 3.6 PSD2 / SCA (Payment Services Directive 2)

**Scope:** EU tenants providing payment services.

| PSD2 Requirement | USORA Implementation | Evidence |
|---|---|---|
| **Strong Customer Authentication (SCA)** | Multi-factor authentication; biometric verification; step-up authentication | SCA implementation docs, auth logs |
| **Dynamic linking** | Transaction-specific authentication codes; amount binding; payee verification | Dynamic linking implementation |
| **Exemptions** | Low-risk exemption based on USORA risk score; transaction risk analysis | Exemption logic, risk thresholds |
| **RTS on SCA** | Compliance with EBA Regulatory Technical Standards | RTS compliance assessment |
| **AISP/PISP support** | OAuth2 consent; API access for TPPs; certificate validation | TPP integration docs, consent logs |

---

### 3.7 NIST Cybersecurity Framework

**Scope:** Alignment with NIST CSF 2.0 for all USORA operations.

| CSF Function | Category | USORA Implementation | Evidence |
|---|---|---|---|
| **GOVERN (GV)** | Organizational context; risk management; roles; policy | Governance framework; risk register; security policy; RACI | Governance docs, risk registers |
| **IDENTIFY (ID)** | Asset management; risk assessment; supply chain; improvement | CMDB; threat modeling; vendor assessments; gap analysis | Asset inventory, threat models |
| **PROTECT (PR)** | Identity management; awareness; data security; platform security; resilience | IAM; training; encryption; hardening; backup; DR | IAM policies, training records |
| **DETECT (DE)** | Anomalies; continuous monitoring; detection processes | SIEM; anomaly detection; UEBA; automated alerts | SIEM dashboards, detection rules |
| **RESPOND (RS)** | Incident management; analysis; mitigation; reporting; communication | IR plan; war room protocol; postmortems; status page | IR plans, incident records |
| **RECOVER (RC)** | Recovery planning; improvements; communication | DRP; BCP; lessons learned; crisis communication | DR plans, recovery test results |
| **SUPPLY CHAIN (SC)** | Supply chain risk management; third-party security | Vendor assessments; SBOMs; contractual security | Vendor records, SBOMs |

---

### 3.8 CIS Controls v8

**Scope:** Implementation of CIS Controls v8 for all USORA infrastructure.

| CIS Control | Implementation | Evidence |
|---|---|---|
| **CSC 1** — Inventory and Control of Enterprise Assets | CMDB; cloud asset inventory; automated discovery | Asset inventory, discovery logs |
| **CSC 2** — Inventory and Control of Software Assets | SBOM generation; software inventory; license management | SBOMs, software inventory |
| **CSC 3** — Data Protection | Encryption; DLP; data classification; retention | Data protection policy, DLP reports |
| **CSC 4** — Secure Configuration of Enterprise Assets | Hardened base images; CIS benchmarks; configuration management | Hardening guides, benchmark scores |
| **CSC 5** — Account Management | IAM lifecycle; access reviews; MFA; privileged access management | IAM policies, access review records |
| **CSC 6** — Access Control Management | RBAC/ABAC; least privilege; application access control | Access control matrices |
| **CSC 7** — Continuous Vulnerability Management | Vulnerability scanning; patch management; remediation SLAs | Scan reports, patch metrics |
| **CSC 8** — Audit Log Management | Centralized logging; structured logs; correlation; retention | Log architecture, retention verification |
| **CSC 9** — Email and Web Browser Protections | Email filtering; URL filtering; sandboxing; browser security | Email security reports, filter logs |
| **CSC 10** — Malware Defenses | EDR; antivirus; behavioral detection; sandboxing | EDR alerts, detection metrics |
| **CSC 11** — Data Recovery | Backup; disaster recovery; restore testing; RPO/RTO | DR test results, restore reports |
| **CSC 12** — Network Infrastructure Management | Network segmentation; zero-trust; mTLS; WAF | Network architecture, policy configs |
| **CSC 13** — Network Monitoring | Network traffic analysis; IDS/IPS; flow monitoring | Network monitoring dashboards |
| **CSC 14** — Security Awareness and Skills Training | Security training; phishing simulations; role-specific training | Training records, phishing metrics |
| **CSC 15** — Service Provider Management | Vendor assessments; contractual security; monitoring | Vendor assessments, contract clauses |
| **CSC 16** — Application Software Security | Secure SDLC; SAST/DAST; code review; dependency scanning | Security test reports, scan results |
| **CSC 17** — Incident Response Management | IR plan; war room; postmortems; automated containment | IR plans, incident records |
| **CSC 18** — Penetration Testing | Quarterly pen tests; red team exercises; bug bounty | Pen test reports, bug bounty metrics |

---

## 4. Jurisdiction-Specific Compliance

### 4.1 European Union

| Regulation | Key Requirements | USORA Implementation | Evidence |
|---|---|---|---|
| **GDPR** | Data protection; privacy by design; DSAR; breach notification | Full implementation per Section 3.3 | Privacy assessments, DSAR logs |
| **eIDAS 2.0** | Electronic identification; trust services; digital wallets | Roadmap: Q2 2027 for eIDAS integration | Roadmap document |
| **DORA** | Digital operational resilience; ICT risk management; incident reporting | Multi-region resilience; RPO/RTO; incident reporting | Resilience reports, incident records |
| **NIS2** | Network and information security; supply chain security; incident reporting | Security architecture; vendor management; incident reporting | Security architecture, vendor records |
| **AI Act** | AI risk classification; transparency; human oversight; data governance | Risk scoring model documentation; explainability; human review | Model docs, explainability reports |
| **EU AML5/6** | Risk-based CDD; EDD; PEP screening; sanctions; record keeping | Full implementation per Section 3.4.2 | AML compliance reports |
| **PSD2/SCA** | Strong authentication; dynamic linking; TPP access | Full implementation per Section 3.6 | SCA implementation docs |
| **MiFID II** | Client identification; transaction reporting; record keeping | Identity verification; audit trail; retention | Verification records, audit logs |

### 4.2 United States

| Regulation | Key Requirements | USORA Implementation | Evidence |
|---|---|---|---|
| **CCPA/CPRA** | Consumer privacy rights; opt-out; data deletion; disclosure | DSAR portal; opt-out mechanism; data deletion; privacy notice | Privacy assessments, DSAR logs |
| **US BSA / Patriot Act** | CIP; CDD; EDD; SAR; OFAC screening; record keeping | Full implementation per Section 3.4.3 | AML compliance reports |
| **GLBA** | Financial privacy; safeguards; pretexting protection | Privacy safeguards; access controls; training | Safeguards plan, training records |
| **FCRA** | Consumer reporting accuracy; permissible purpose; adverse action notices | Data accuracy verification; permissible purpose validation; adverse action workflow | Accuracy reports, adverse action records |
| **Section 508** | Accessibility for federal agencies | WCAG 2.1 AA compliance; screen reader support; keyboard navigation | Accessibility audit, VPAT |
| **State Privacy Laws (Virginia, Colorado, Connecticut, Utah, etc.)** | Consumer rights; data minimization; purpose limitation; security | Unified privacy framework; state-specific configurations | Privacy framework docs |

### 4.3 United Kingdom

| Regulation | Key Requirements | USORA Implementation | Evidence |
|---|---|---|---|
| **UK GDPR** | Data protection; post-Brexit GDPR alignment | Same as EU GDPR with UK-specific adjustments | Privacy assessments |
| **UK Data Protection Act 2018** | Law enforcement processing; intelligence services; exemptions | Law enforcement processing procedures | DPA compliance records |
| **UK AML Regulations 2017** | CDD; EDD; PEP screening; sanctions; record keeping | Same as EU AML5/6 with UK-specific lists | AML compliance reports |
| **FCA Handbook** | Financial conduct; client assets; systems and controls | Compliance with FCA requirements for financial services tenants | FCA compliance records |

### 4.4 Asia-Pacific

| Regulation | Key Requirements | USORA Implementation | Evidence |
|---|---|---|---|
| **Singapore PDPA** | Consent; purpose limitation; access; correction; protection; retention | Consent management; DSAR portal; data protection; retention policies | PDPA compliance records |
| **Australia Privacy Act 1988** | APPs; OAIC notification; credit reporting | APP compliance; notification procedures; credit reporting safeguards | Privacy compliance records |
| **Japan APPI** | Personal information protection; cross-border transfers; anonymization | APPI compliance; cross-border transfer measures; anonymization | APPI compliance records |
| **South Korea PIPA** | Personal information protection; consent; purpose limitation; security | PIPA compliance; consent management; security measures | PIPA compliance records |
| **India DPDP Act 2023** | Data protection; consent; rights of data principals; data fiduciaries | DPDP compliance roadmap; consent framework | DPDP roadmap |
| **China PIPL** | Personal information protection; cross-border transfers; sensitive PI | PIPL compliance assessment; data localization options | PIPL assessment |

### 4.5 Latin America

| Regulation | Key Requirements | USORA Implementation | Evidence |
|---|---|---|---|
| **Brazil LGPD** | Data protection; consent; rights; security; accountability | LGPD compliance; DSAR portal; consent management; DPO | LGPD compliance records |
| **Mexico LFPDPPP** | Data protection; consent; rights; security | LFPDPPP compliance; privacy notice; consent | LFPDPPP records |
| **Argentina PDPA** | Data protection; consent; rights; cross-border transfers | PDPA compliance; transfer mechanisms | PDPA records |
| **Colombia Law 1581** | Data protection; consent; rights; security | Law 1581 compliance; privacy policies | Law 1581 records |

### 4.6 Middle East & Africa

| Regulation | Key Requirements | USORA Implementation | Evidence |
|---|---|---|---|
| **UAE PDPL** | Data protection; consent; rights; cross-border transfers; health data | PDPL compliance assessment; health data safeguards | PDPL assessment |
| **Saudi Arabia PDPL** | Data protection; consent; localization; cross-border transfers | PDPL compliance; localization options | PDPL records |
| **South Africa POPIA** | Data protection; consent; rights; security; accountability | POPIA compliance; DSAR portal; consent | POPIA records |
| **Nigeria NDPR** | Data protection; consent; rights; security; data localization | NDPR compliance; localization options | NDPR records |
| **Kenya Data Protection Act** | Data protection; consent; rights; security; DPC registration | Kenya DPA compliance; registration | DPA records |

---

## 5. Control-to-Feature Mapping Matrix

### 5.1 Identity Verification → Compliance Controls

| USORA Feature | Compliance Frameworks | Controls Satisfied |
|---|---|---|
| **Document Verification (OCR + Forensics)** | AML5/6, BSA, FATF, PCI DSS | CDD (R.10), CIP, document authenticity |
| **Biometric Verification (Liveness + Matching)** | GDPR, PSD2/SCA, PCI DSS | SCA, consent, data accuracy, strong authentication |
| **Risk Scoring (ML-powered)** | AML5/6, BSA, FATF, AI Act | Risk-based approach, model explainability, human oversight |
| **PEP/Sanctions Screening** | AML5/6, BSA, FATF, OFAC | PEP screening (R.12), sanctions (R.35), SDN lists |
| **Enhanced Due Diligence (EDD)** | AML5/6, BSA, FATF | EDD (R.10), beneficial ownership (R.24), SOF |
| **Ongoing Monitoring** | AML5/6, BSA, FATF | Continuous monitoring, periodic re-verification |
| **SAR Filing Integration** | BSA, AML5/6, FATF | Suspicious activity reporting (R.20) |
| **Immutable Audit Trail** | GDPR, SOC 2, ISO 27001, PCI DSS | Accountability, integrity, non-repudiation, log protection |
| **Tenant Isolation** | SOC 2, ISO 27001, GDPR, PCI DSS | Data segregation, confidentiality, access control |
| **Data Retention & Deletion** | GDPR, CCPA, LGPD, PCI DSS | Storage limitation, right to erasure, retention policies |
| **DSAR Portal** | GDPR, CCPA, LGPD, PDPA | Right of access, right to portability, right to erasure |
| **Consent Management** | GDPR, CCPA, LGPD, PDPA | Lawful basis, opt-in/opt-out, consent revocation |
| **Encryption (AES-256-GCM + TLS 1.3)** | GDPR, SOC 2, ISO 27001, PCI DSS | Data protection, confidentiality, secure transmission |
| **Multi-Factor Authentication** | PSD2/SCA, PCI DSS, NIST CSF | Strong authentication, SCA, access control |
| **Zero-Trust Network** | NIST CSF, CIS Controls, ISO 27001 | Network security, segmentation, mTLS |

### 5.2 Platform Architecture → Compliance Controls

| USORA Architecture Component | Compliance Frameworks | Controls Satisfied |
|---|---|---|
| **Schema-per-Tenant PostgreSQL + RLS** | SOC 2, ISO 27001, GDPR, PCI DSS | Data isolation, tenant segregation, access control |
| **HashiCorp Vault (Dynamic Credentials)** | SOC 2, ISO 27001, PCI DSS | Secret management, credential rotation, least privilege |
| **Blockchain-Anchored Audit Logs** | GDPR, SOC 2, ISO 27001 | Integrity, non-repudiation, tamper evidence |
| **Multi-Region Active-Active** | SOC 2, ISO 27001, GDPR, DORA | Availability, resilience, data sovereignty |
| **Kafka Event Bus (Immutable)** | SOC 2, ISO 27001, GDPR | Event sourcing, audit trail, data integrity |
| **Camunda BPMN Workflows** | SOC 2, ISO 27001, AML | Process integrity, workflow audit, compliance automation |
| **SLSA Level 3 Build Pipeline** | SOC 2, ISO 27001, CIS Controls | Supply chain security, artifact integrity, provenance |
| **Falco Runtime Security** | ISO 27001, NIST CSF, CIS Controls | Runtime threat detection, container security |
| **OpenTelemetry Distributed Tracing** | SOC 2, ISO 27001, PCI DSS | Observability, incident investigation, accountability |
| **HSM-Backed Encryption Keys** | PCI DSS, SOC 2, ISO 27001 | Key protection, cryptographic security |

---

## 6. Compliance Automation & Continuous Monitoring

### 6.1 Automated Compliance Checks

| Check Category | Frequency | Tooling | Owner |
|---|---|---|---|
| **Vulnerability scanning** | Continuous (CI/CD) + Weekly (production) | Trivy, Snyk, OWASP ZAP | Security Team |
| **Infrastructure compliance** | Continuous | Terraform Compliance (OPA), Cloud Custodian, CSPM | Platform Team |
| **Access control review** | Weekly (automated) + Quarterly (manual) | Custom scripts, IAM analyzer | Security Team |
| **Encryption verification** | Daily | Custom scripts, Vault audit | Security Team |
| **Data retention enforcement** | Daily | Scheduled jobs, retention policy engine | Data Team |
| **Audit log integrity** | Hourly | Hash chain verification, blockchain anchor check | Security Team |
| **Tenant isolation verification** | Weekly | Cross-tenant data leakage tests | Security Team |
| **Certificate expiry** | Daily | cert-manager, custom monitoring | Platform Team |
| **Backup integrity** | Weekly | Restore tests, checksum verification | Data Team |
| **Policy drift detection** | Continuous | OPA/Rego, configuration drift detection | Platform Team |

### 6.2 Compliance Dashboards

| Dashboard | Purpose | Data Sources | Refresh Rate |
|---|---|---|---|
| **Regulatory Status** | Real-time compliance posture across all frameworks | Compliance register, audit findings, control status | Real-time |
| **Control Effectiveness** | Control performance metrics; pass/fail rates | Automated checks, manual assessments, test results | Daily |
| **Audit Trail Health** | Audit log completeness, integrity, retention | Kafka, ClickHouse, blockchain anchor | Real-time |
| **Data Subject Rights** | DSAR volume, SLA compliance, resolution time | DSAR portal, ticketing system | Real-time |
| **Tenant Compliance** | Per-tenant compliance posture; framework coverage | Tenant configs, verification records, audit logs | Hourly |
| **Third-Party Risk** | Vendor assessment status; risk ratings; expirations | Vendor management system | Daily |
| **Incident Response** | Open incidents; SLA compliance; resolution time | PagerDuty, incident tracking | Real-time |
| **Training & Awareness** | Training completion rates; phishing simulation results | LMS, phishing platform | Weekly |

### 6.3 Compliance Reporting

| Report Type | Frequency | Audience | Content |
|---|---|---|---|
| **Executive Compliance Summary** | Monthly | C-Suite, Board | High-level compliance posture; risk heat map; key metrics |
| **SOC 2 Continuous Monitoring** | Continuous | Auditors, Security Team | Control testing results; evidence collection; exceptions |
| **ISO 27001 Internal Audit** | Quarterly | ISMS Manager, Auditors | Control effectiveness; non-conformities; corrective actions |
| **GDPR Compliance Report** | Quarterly | DPO, Legal | DSAR metrics; breach notifications; privacy impact assessments |
| **AML Compliance Report** | Monthly | Compliance Officer, Regulators | Verification volumes; SAR filings; screening results; risk metrics |
| **PCI DSS Self-Assessment** | Quarterly | QSA, Security Team | Requirement compliance; scan results; penetration test findings |
| **Vendor Risk Report** | Quarterly | Procurement, Security | Vendor assessment status; risk ratings; remediation tracking |
| **Incident Summary** | Monthly | All stakeholders | Incident count; severity distribution; root cause analysis; trends |

---

## 7. Data Sovereignty & Residency

### 7.1 Supported Data Residency Regions

| Region | Data Centers | Jurisdictions Served | Certifications |
|---|---|---|---|
| **EU (Frankfurt, Ireland, Stockholm)** | AWS eu-central-1, eu-west-1, eu-north-1 | EU, EEA, UK, Switzerland | GDPR, EU AML5/6, PSD2, eIDAS |
| **US (Virginia, Oregon, Ohio)** | AWS us-east-1, us-west-2, us-east-2 | United States, Canada | CCPA, BSA, GLBA, FCRA, PCI DSS |
| **APAC (Singapore, Sydney, Tokyo)** | AWS ap-southeast-1, ap-southeast-2, ap-northeast-1 | Singapore, Australia, Japan, South Korea | PDPA, Privacy Act, APPI, PIPA |
| **LATAM (Sao Paulo, Mexico City)** | AWS sa-east-1, planned | Brazil, Mexico, Colombia, Argentina | LGPD, LFPDPPP, Law 1581, PDPA |
| **Middle East (UAE, planned)** | AWS me-central-1, planned | UAE, Saudi Arabia, Bahrain | PDPL, PDPL, CBUAE |
| **Africa (South Africa, planned)** | AWS af-south-1, planned | South Africa, Nigeria, Kenya | POPIA, NDPR, Data Protection Act |

### 7.2 Data Sovereignty Controls

| Control | Implementation | Evidence |
|---|---|---|
| **Region-locked data storage** | Tenant data never leaves designated region; S3 bucket policies; database replication within region | Region policies, replication configs |
| **Cross-border transfer controls** | SCCs for EU data; adequacy decisions; BCRs (planned); data localization options | Transfer mechanism records |
| **Regional encryption keys** | Per-region HSM clusters; keys never exported from region | Key management policies, HSM audit logs |
| **Regional audit logs** | Audit logs stored in region of data generation; no cross-border log aggregation | Log architecture, retention policies |
| **Regional backup** | Backups stored within region; cross-region replication only with explicit tenant consent | Backup policies, replication configs |
| **Regional compute** | Compute workers deployed in region of data; no cross-border processing | Deployment topology, worker assignments |
| **Regional personnel access** | Regional support teams; no cross-border data access without authorization | Access policies, personnel assignments |

---

## 8. Third-Party & Subprocessor Compliance

### 8.1 Subprocessor Inventory

| Subprocessor | Service | Data Processed | Location | Certifications | DPA/SCCs |
|---|---|---|---|---|---|
| **Amazon Web Services (AWS)** | Cloud infrastructure | All tenant data | Global (tenant-selected) | SOC 2, ISO 27001, PCI DSS | Yes (SCCs) |
| **Cloudflare** | DDoS protection, CDN, WAF | Traffic metadata, logs | Global | SOC 2, ISO 27001 | Yes (SCCs) |
| **HashiCorp** | Vault (secrets management) | Encryption keys, credentials | Tenant region | SOC 2, ISO 27001 | Yes (SCCs) |
| **Datadog** | Observability, monitoring | Metrics, logs (redacted) | EU/US | SOC 2, ISO 27001 | Yes (SCCs) |
| **PagerDuty** | Incident alerting | Incident metadata | US | SOC 2, ISO 27001 | Yes (SCCs) |
| **Twilio** | SMS notifications | Phone numbers, message content | Global | SOC 2, ISO 27001 | Yes (SCCs) |
| **SendGrid** | Email delivery | Email addresses, message content | US | SOC 2, ISO 27001 | Yes (SCCs) |
| **Refinitiv (LSEG)** | Watchlist screening | Names, DOB, nationality | Global | SOC 2, ISO 27001 | Yes (SCCs) |
| **Dow Jones** | Adverse media screening | Names, DOB | Global | SOC 2, ISO 27001 | Yes (SCCs) |
| **ComplyAdvantage** | Risk intelligence | Names, DOB, addresses | Global | SOC 2, ISO 27001 | Yes (SCCs) |
| **Experian** | Credit bureau verification | Identity data | US/UK | FCRA, ICO | Yes (SCCs) |
| **Equifax** | Credit bureau verification | Identity data | US/UK | FCRA, ICO | Yes (SCCs) |
| **TransUnion** | Credit bureau verification | Identity data | US/UK | FCRA, ICO | Yes (SCCs) |

### 8.2 Subprocessor Due Diligence

| Due Diligence Activity | Frequency | Evidence |
|---|---|---|
| **Security assessment questionnaire** | Annually | Completed questionnaires, risk ratings |
| **SOC 2 / ISO 27001 report review** | Annually | Report review, gap analysis |
| **Contractual security requirements** | At onboarding | Security addendums, SLAs |
| **Right-to-audit clause** | As needed | Audit rights, exercise records |
| **Incident notification** | Ongoing | Notification procedures, SLA |
| **Data processing agreement** | At onboarding | DPA with SCCs, data flow mapping |
| **Subprocessor notification** | At change | Tenant notification, 30-day objection period |

---

## 9. Incident Response & Breach Notification

### 9.1 Data Breach Response Playbook

| Phase | Action | Timeline | Owner | Evidence |
|---|---|---|---|---|
| **Detection** | Automated detection (SIEM, Falco, anomaly detection); manual reporting | Immediate | Security Team | Alert logs, detection records |
| **Triage** | Severity assessment; impact scope; data classification; regulatory trigger analysis | 1 hour | Incident Commander | Triage records, impact assessment |
| **Containment** | Isolate affected systems; revoke compromised credentials; block attacker access | 2 hours | Security Team | Containment actions, access logs |
| **Investigation** | Forensic analysis; root cause identification; evidence preservation; chain of custody | 24-72 hours | Security Team | Forensic reports, evidence logs |
| **Notification** | Supervisory authority notification (GDPR: 72h); affected individual notification; tenant notification | 72 hours (regulatory) | Legal / DPO | Notification records, delivery confirmation |
| **Remediation** | Vulnerability patching; control enhancement; process improvement; verification | 7-30 days | Security Team | Remediation records, verification tests |
| **Postmortem** | Blameless postmortem; lessons learned; action items; policy updates | 48 hours after resolution | Incident Commander | Postmortem document, action items |

### 9.2 Regulatory Notification Requirements

| Regulation | Notification Timeline | To Whom | Content |
|---|---|---|---|
| **GDPR Art. 33** | 72 hours to supervisory authority | Lead SA (where main establishment) | Nature, categories, approximate number, likely consequences, measures taken |
| **GDPR Art. 34** | Without undue delay to data subjects | Affected individuals | Nature, DPO contact, consequences, measures |
| **CCPA/CPRA** | Without unreasonable delay | California Attorney General + affected consumers | Nature, types of PI, steps taken |
| **LGPD** | Reasonable time to ANPD | ANPD (Brazil) + affected individuals | Nature, measures, consequences |
| **PDPA (Singapore)** | As soon as practicable | PDPC + affected individuals | Nature, measures, consequences |
| **PCI DSS** | Immediate to card brands + acquiring bank | Card brands, acquiring bank | Nature, CHD involved, containment |
| **State breach laws (US)** | Varies by state (typically 72h) | State AG + affected residents | Nature, types of data, credit monitoring |

---

## 10. Compliance Roadmap

### 10.1 Certification Timeline

| Quarter | Milestone | Framework | Status |
|---|---|---|---|
| **Q3 2026** | SOC 2 Type II (renewal) | SOC 2 | Certified (maintained) |
| **Q3 2026** | GDPR compliance framework operational | GDPR | Compliant |
| **Q4 2026** | ISO 27001:2022 certification | ISO 27001 | In Progress |
| **Q4 2026** | ISO 27701 certification | ISO 27701 | In Progress |
| **Q1 2027** | PCI DSS Level 1 certification | PCI DSS | In Progress |
| **Q1 2027** | Binding Corporate Rules (BCRs) approval | GDPR | Planned |
| **Q2 2027** | eIDAS 2.0 integration | eIDAS | Roadmap |
| **Q2 2027** | AI Act compliance assessment | AI Act | Planned |
| **Q3 2027** | India DPDP Act compliance | DPDP | Roadmap |
| **Q4 2027** | China PIPL compliance | PIPL | Assessment |

### 10.2 Regulatory Change Management

| Process | Description | Frequency | Owner |
|---|---|---|---|
| **Regulatory monitoring** | Subscription to regulatory updates (Thomson Reuters, Wolters Kluwer); government gazette monitoring | Daily | Legal Team |
| **Impact assessment** | Analysis of regulatory changes on USORA operations; gap identification; control mapping | Per change | Compliance Team |
| **Implementation planning** | Roadmap for control changes; resource allocation; timeline; risk assessment | Per change | Compliance Team |
| **Control update** | Policy updates; configuration changes; feature development; training updates | Per change | Relevant Teams |
| **Verification** | Testing of updated controls; audit; certification update | Per change | Security Team |
| **Communication** | Tenant notification; documentation updates; training | Per change | Compliance Team |

---

## 11. Glossary

| Term | Definition |
|---|---|
| **ABAC** | Attribute-Based Access Control |
| **AML** | Anti-Money Laundering |
| **BCP** | Business Continuity Plan |
| **BCR** | Binding Corporate Rules |
| **CCPA** | California Consumer Privacy Act |
| **CDD** | Customer Due Diligence |
| **CHD** | Cardholder Data |
| **CIP** | Customer Identification Program |
| **CSPM** | Cloud Security Posture Management |
| **DAST** | Dynamic Application Security Testing |
| **DPA** | Data Processing Agreement |
| **DRP** | Disaster Recovery Plan |
| **DSAR** | Data Subject Access Request |
| **EDD** | Enhanced Due Diligence |
| **EDR** | Endpoint Detection and Response |
| **FATF** | Financial Action Task Force |
| **FCRA** | Fair Credit Reporting Act |
| **GDPR** | General Data Protection Regulation |
| **HSM** | Hardware Security Module |
| **IAST** | Interactive Application Security Testing |
| **ISMS** | Information Security Management System |
| **JIT** | Just-in-Time (access) |
| **LIA** | Legitimate Interest Assessment |
| **LGPD** | Lei Geral de Proteção de Dados (Brazil) |
| **mTLS** | Mutual TLS |
| **NDA** | Non-Disclosure Agreement |
| **OFAC** | Office of Foreign Assets Control |
| **PAM** | Privileged Access Management |
| **PCI DSS** | Payment Card Industry Data Security Standard |
| **PDPA** | Personal Data Protection Act |
| **PEP** | Politically Exposed Person |
| **PIA** | Privacy Impact Assessment |
| **PII** | Personally Identifiable Information |
| **PKI** | Public Key Infrastructure |
| **P0/P1/P2** | Priority levels (Express, Standard, Batch) |
| **RBAC** | Role-Based Access Control |
| **RLS** | Row-Level Security |
| **RPO** | Recovery Point Objective |
| **RTO** | Recovery Time Objective |
| **SAR** | Suspicious Activity Report |
| **SAST** | Static Application Security Testing |
| **SBOM** | Software Bill of Materials |
| **SCC** | Standard Contractual Clause |
| **SCA** | Strong Customer Authentication |
| **SDD** | Simplified Due Diligence |
| **SDL** | Secure Development Lifecycle |
| **SIEM** | Security Information and Event Management |
| **SLSA** | Supply Chain Levels for Software Artifacts |
| **SOF** | Source of Funds |
| **SPIFFE** | Secure Production Identity Framework for Everyone |
| **TPP** | Third-Party Provider (PSD2) |
| **UBO** | Ultimate Beneficial Owner |
| **UEBA** | User and Entity Behavior Analytics |
| **WORM** | Write Once Read Many |

---

## 12. Document Information

| Field | Value |
|---|---|
| **Document Version** | 1.0.0 |
| **Last Updated** | 2026-07-21 |
| **Author** | USORA Compliance & Legal Team |
| **Review Cycle** | Quarterly |
| **Classification** | Internal — Confidential |
| **Next Review** | 2026-10-21 |
| **Related Documents** | product.md, design.md, api-spec.md, runbook.md |

---

*USORA — Compliance by Design, Trust by Default*
