# JARVIS Full-Project Completion Plan

This file is the implementation checklist for completing JARVIS in small, verifiable phases. A phase is only marked complete when its code is committed and its relevant automated checks pass. A successful APK build alone does not prove runtime features work.

## Operating rules
- Work from the repository's current `main` state; inspect before editing.
- Make small, reversible changes; never commit API keys, tokens, signing passwords, or user data.
- Keep phone-control actions permission-gated and require explicit user intent for consequential actions (calls, messages, purchases, deleting data, or changing security settings).
- After each code change: run the narrowest available test, then the full Android debug/release build and web deployment checks where relevant.
- Do not label a feature "working" until it is verified on an actual configured service/device. If credentials or hardware permissions are missing, document the exact blocker rather than bypassing it.
- This repository cannot self-run an AI coding loop merely because a workflow exists. Any unattended coding worker requires a trusted runner, scoped credentials, limits, logs, and an approval boundary.

## Phase 1 — Repository baseline and reproducible builds
- [x] Android debug APK build succeeds in GitHub Actions.
- [x] Android release APK build succeeds in GitHub Actions.
- [x] Web console deploys to GitHub Pages and the updated status text is live.
- [ ] Record build, signing, dependency, and artifact results for each run.
- [x] Add automated static checks for web assets and secret-like strings; Android unit-test/lint tasks added to CI and pending first verified result.

## Phase 2 — Authentication and secrets
- [ ] Audit Firebase project configuration and Google Sign-In OAuth client IDs.
- [x] Remove cached-account fallback after a failed Google Sign-In result.\n- [ ] Verify login success/failure/cancel flows on a configured Firebase project and real device.
- [ ] Confirm Firebase Authentication is enabled and authorized domains are correct.
- [ ] Keep provider/API secrets server-side; verify public repository and APK contain no private secrets.
- [ ] Test unauthenticated and unauthorized access.

## Phase 3 — Android runtime and permission health
- [ ] Build and install on a real Android device.
- [ ] Verify startup, crash-free navigation, microphone, notifications, accessibility, overlay, and battery-optimization flows.
- [ ] Ensure permissions are requested transparently and can be revoked.
- [ ] Test safe, reversible device actions before enabling higher-impact actions.

## Phase 4 — AI voice and tool execution
- [ ] Verify Gemini configuration and connection lifecycle, reconnect, timeout, and error handling.
- [ ] Test speech input/output and interruption behavior on-device.
- [ ] Validate every model tool call against an explicit allowlist and argument schema.
- [ ] Require user confirmation for external side effects.

## Phase 5 — Secure device bridge
- [ ] Select and configure a trusted backend/transport; do not expose device-control credentials in the static web app.
- [ ] Authenticate both user and enrolled device; enforce per-user ownership and least privilege.
- [ ] Add command expiry, replay protection, idempotency, audit logs, and safe cancellation.
- [ ] Verify phone-to-console status and command acknowledgements with an actual device.

## Phase 6 — Web console and PWA
- [x] Responsive static shell and offline fallback.
- [x] Clear preview messaging; no fake live-auth or device-connected status.
- [ ] Add CSP/security headers and browser smoke tests.
- [ ] Connect real-time status only after Phase 5 authorization is implemented.
- [ ] Test mobile/desktop layouts, offline behavior, and service-worker upgrades.

## Phase 7 — End-to-end reliability
- [ ] Test sign-in → device enrollment → command → acknowledgement → audit trail.
- [ ] Test offline/reconnect, duplicate commands, expired credentials, revoked permissions, and backend outage.
- [ ] Publish a release checklist and known limitations.
- [ ] Mark production-ready only after all required end-to-end tests pass.

## Current known constraints
- The web console is a static preview until a secure backend is implemented and configured.
- CI proves compilation and packaging, not successful runtime authentication, AI connectivity, or physical phone control.
- Access to Firebase/Gemini project settings, deployment secrets, and a real device session may be required for the integration phases.
