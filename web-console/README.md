# JARVIS Web Console — starter

This folder is a standalone, responsive web companion for the existing Android JARVIS app.
The upstream repository's README describes an admin-panel, but the current tracked tree does not include that folder. This web companion is intentionally isolated from the Android app source.

## Current status
- Responsive static dashboard shell
- Login UI shell (not connected to Firebase Authentication yet)
- PWA manifest/service worker for installability
- Explicit notice that live device control requires a secured backend/validated Firebase rules

## Security requirements before production
1. Configure Firebase Authentication using a dedicated web app and allowed domains.
2. Use Firebase Realtime Database rules that enforce authenticated per-user access.
3. Never expose Gemini/OpenRouter/server secrets in browser JavaScript.
4. Do not wire device command dispatch until command authorization, replay protection, device ownership, and audit logging are implemented.
5. Use HTTPS hosting and test login, database rules, and mobile/desktop flows.

This is a front-end starter, not a claim that live login or phone control is already working.
