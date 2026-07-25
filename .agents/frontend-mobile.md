# Agent: Frontend Mobile

## Metadata
- **Agent ID**: `usora-agent-frontend-mobile`
- **Tier**: 5 — Frontend & Experience
- **Owner**: Mobile Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Frontend Mobile agent provides native mobile SDKs and applications for iOS and Android that enable document capture, biometric verification (face matching, liveness detection), and KYC form submission with offline capability, hardware-accelerated image processing, and platform-native UX patterns.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Framework | React Native | 0.75+ |
| Language | TypeScript | 5.6+ |
| Navigation | React Navigation | 7.0+ |
| State Management | Zustand | 5.0+ |
| Camera | react-native-vision-camera | 4.0+ |
| Biometrics | react-native-biometrics | latest |
| ML Inference | TensorFlow Lite / Core ML | latest |
| Storage | MMKV / AsyncStorage | latest |
| Networking | React Native Networking + custom | — |
| Build | Expo EAS / native | latest |
| Testing | Jest + Detox | latest |

## API Surface

### Native Modules
| Module | Platform | Purpose |
|--------|----------|---------|
| `DocumentScanner` | iOS/Android | Auto-crop, perspective correction, glare detection |
| `FaceCapture` | iOS/Android | Real-time face detection, quality scoring |
| `LivenessDetection` | iOS/Android | Challenge-response liveness (blink, turn, smile) |
| `ImageProcessing` | iOS/Android | Hardware-accelerated resize, compress, encrypt |
| `SecureStorage` | iOS/Android | Keychain/Keystore for tokens and keys |
| `NetworkMonitor` | iOS/Android | Offline detection, queue management |

### Internal APIs (consumed)
| Source | Purpose |
|--------|---------|
| `orchestrator-core` | KYC submission, status tracking |
| `compute-identity-verification` | Document upload, biometric processing |
| `platform-identity` | Anonymous session, token refresh |
| `integration-webhook` | Progress notifications |

## Tenant Isolation Strategy
- **Bundle config**: Tenant ID embedded in app bundle at build time
- **Deep linking**: `usora://{tenantId}/kyc/start`
- **Session isolation**: Secure storage scoped to tenant bundle
- **Feature gating**: Per-tenant feature flags from remote config
- **Branding**: Per-tenant splash screen, colors, logo from remote config
- **Localization**: Per-tenant default language and supported locales

## Security Boundaries
- All network traffic over TLS 1.3 with certificate pinning
- Document images encrypted on-device before upload (AES-256-GCM)
- Biometric templates never stored; processed in secure enclave (iOS) / TEE (Android)
- Session tokens in Keychain (iOS) / Keystore (Android)
- Root/jailbreak detection: app refuses to run on compromised devices
- Screenshot prevention on sensitive screens (document capture, biometric)
- App attestation: DeviceCheck (iOS) / SafetyNet (Android) on startup
- Code obfuscation: R8/ProGuard for Android, LLVM obfuscation for iOS

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Crashlytics + structured logs → Loki |
| Metrics | `mobile_session_started`, `mobile_document_captured`, `mobile_biometric_completed`, `mobile_kyc_submitted`, `mobile_crash_rate` |
| Traces | OpenTelemetry mobile SDK → Tempo |
| Alerts | Crash rate > 1%, ANR rate > 0.5%, upload failure rate > 5% |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Camera initialization failure | Error callback | Show manual upload guide, log error |
| Network unavailable | Reachability API | Queue submission, retry when online, notify user |
| Biometric capture timeout | Timer expiry | Retry with guidance, allow manual fallback |
| Secure storage failure | Keychain/Keystore error | Clear and re-initialize, force re-authentication |
| App attestation failure | DeviceCheck/SafetyNet error | Block app usage, show security warning |
| Memory pressure | OS notification | Compress images, clear cache, alert user |
| Upload failure | Network error | Resumable upload, auto-retry, background retry |

## Configuration
```yaml
frontend_mobile:
  build:
    platforms: ["ios", "android"]
    min_ios_version: "15.0"
    min_android_version: "26"
    hermes_enabled: true
    new_architecture_enabled: true
  camera:
    resolution: "1920x1080"
    auto_focus: true
    flash_mode: "auto"
    document_detection: true
    quality_threshold: 0.85
  biometric:
    liveness_challenges: ["blink", "turn_left", "turn_right"]
    max_attempts: 3
    timeout_seconds: 30
    hardware_acceleration: true
  storage:
    encryption: true
    max_cache_size_mb: 100
    auto_cleanup: true
  network:
    timeout: 30000
    retry_attempts: 5
    retry_backoff: exponential
    offline_queue_max_size: 50
  security:
    certificate_pinning: true
    root_detection: true
    screenshot_prevention: true
    app_attestation: true
    code_obfuscation: true
  performance:
    target_fps: 60
    max_memory_mb: 512
    background_task_timeout: 300
```

## Dependencies
- `platform-gateway` — API proxy
- `platform-identity` — Session management
- `orchestrator-core` — KYC submission
- `compute-identity-verification` — Biometric processing
- `platform-observability` — Crash reporting, analytics
