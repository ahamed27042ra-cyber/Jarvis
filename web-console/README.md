# JARVIS Web Console — starter

Standalone responsive web companion for the Android JARVIS project. The upstream README describes `/admin-panel`, but that directory is not tracked in the current repository tree; this implementation is isolated under `web-console/`.

## Included
- Responsive dark dashboard shell
- Sign-in preview only (no real authentication enabled)
- PWA manifest, SVG icon, offline service-worker shell

## Run locally
Serve this folder with any static HTTP server. Service workers and PWA install prompts require HTTPS (or localhost).

## Required before production
1. Configure a Firebase Web app and Firebase Authentication provider; restrict authorized domains.
2. Enforce authenticated per-user access with Firebase Realtime Database rules, then test them with the Firebase emulator.
3. Keep Gemini / OpenRouter / other secret keys on a trusted server, never in browser JS or public config.
4. Do not enable phone command dispatch until identity, device ownership, permission scopes, replay prevention, and audit logs are implemented.
5. Enable HTTPS hosting, add CSP/security headers, and test authentication, mobile/desktop routes, API rules, and console errors.

## Current limitations
The sign-in panel is deliberately a preview and discards the entered password. It is not a live login. No real-time device mirroring, command delivery, or autonomous coding worker is connected. These need a secured backend and a verified device/database integration.
