# Play Console Data Safety form — draft answers

Draft answers for the Play Console "App content" → "Data safety" form,
based on what the app's code actually does (see `docs/PRIVACY_POLICY.md`'s
"How this was written" note for the sources). Play Console's exact question
wording shifts over time — use this as a mapping to the current form, not a
literal script. **You (the developer) must verify and submit this
yourself** — Google requires the account owner to complete this, and it's a
compliance document you're accountable for.

## Before you fill this out: one real gap

**Google Play requires apps that support account creation to also offer
account deletion, reachable both in-app and via a web page, without
requiring you to contact support first** (Play's Account Deletion policy,
in effect since 2023). Play Console's Data Safety form has a required
"Account deletion" section that links to this.

**Phase 13 update (2026-08-15):** this gap's practical weight changed — sync
(and therefore account creation) is now opt-in and strictly manual rather
than the app's default posture, so most users are expected to never create
an account at all. See `docs/handoff/PHASE-13-local-first.md`.

**Update (2026-08-15): the deletion path itself is implemented.** The
SongNotes web repo added a `/delete-account` page (sign-in, typed
confirmation, then a `delete_own_account` Postgres RPC that cascades through
all of the user's data — commit `afa651c`), and Android links out to it from
`MainActivity.kt`'s account row (`WebLinks.kt`'s `WEB_DELETE_ACCOUNT_URL`),
the same pattern already used for forgot-password.

**Update (2026-08-20): the web app is live.** Deployed to Vercel with a
custom domain — see the web repo's `docs/DEPLOYMENT.md` for the Namecheap
DNS setup. `WEB_DELETE_ACCOUNT_URL` now points at
`https://www.songnotes.cloud/delete-account`. **One thing still blocks a
real submission:**

- **Play Console's Data Safety → Account deletion field must be filled in
  separately, with that same URL** (`https://www.songnotes.cloud/delete-account`)
  — Play checks that field independently of what the app links to; pointing
  the app at a page doesn't populate this form field automatically. This is
  an account-holder action in Play Console itself, not something fixable in
  either repo. The developer's Play Console account is verified as of
  2026-08-20, so this is ready to do — just needs to actually be entered.

## Does your app collect or share any of the required user data types?

**Yes.**

## Data types

### Personal info → Email address

- **Collected:** Yes
- **Shared with third parties:** Yes — with Supabase, our authentication
  and backend provider, solely to operate the service (not sold, not for
  advertising).
- **Processed ephemerally:** No
- **Optional or required:** Optional — the app is fully usable without an
  account (Phase 13: local-first is now the default posture, not a fallback
  mode); only needed to sync across devices, and even then only collected if
  the user completes sign-up, not merely by opening the app.
- **Purpose(s):** Account management, App functionality.

### Audio → Voice or sound recordings

- **Collected:** No.
- Reasoning: recordings made via the Scratchpad feature and calibration
  tones are processed and stored **only on-device** (local files under the
  app's private storage). Nothing is transmitted off the device. Play's
  definition of "collected" is about leaving the device — since it never
  does, this is answered No, not "collected but not shared."
- If Play Console's specific wording for your form version treats
  on-device-only processing as requiring disclosure anyway, answer:
  **Collected: Yes, Shared: No, Processed ephemerally: Yes (not stored
  after processing off-device — because it's never sent off-device at
  all), Purpose: App functionality.**

### Files and docs / App activity → User-generated content (song lyrics, chords, titles, metadata)

- **Collected:** Yes (only if the user creates an account and syncs).
- **Shared with third parties:** Yes — with Supabase, as **encrypted
  ciphertext only**. Supabase (and SongNotes' own developer) cannot read
  the plaintext content; it's encrypted client-side with a key derived
  from the user's account password before it ever leaves the device.
- **Processed ephemerally:** No (persisted, encrypted, for sync).
- **Optional or required:** Optional — local-only use never sends this
  anywhere, and even a signed-in user's edits stay device-only until they
  explicitly press Sync (Phase 13: no automatic push, ever).
- **Purpose(s):** App functionality (cross-device sync).
- Note: Play's Data Safety form has a specific "Is this data encrypted in
  transit?" toggle (answer **Yes** — all traffic is HTTPS) and a separate
  question about user control over deletion (see the account-deletion gap
  above).

### Device or other IDs

- **Collected:** No. No advertising ID, no device fingerprinting, no
  analytics SDK of any kind is present in the app's dependencies.

### Location, Financial info, Health and fitness, Messages, Photos and videos, Contacts, Calendar, Web browsing

- **Collected:** No, for all of these. Confirmed by reading the app's
  declared Android permissions (`AndroidManifest.xml`) — only
  `RECORD_AUDIO`, `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_MICROPHONE`,
  `INTERNET`, `ACCESS_NETWORK_STATE`, and `VIBRATE` are requested. None of
  these categories are touched.

### App info and performance → Crash logs / Diagnostics

- **Collected:** No. There is no crash-reporting or analytics SDK
  (no Firebase Crashlytics, no Google Analytics, nothing similar) anywhere
  in the app's dependency graph as of this draft. If you add one later,
  update this section before your next release.

## Security practices section

- **Is all user data encrypted in transit?** Yes (HTTPS/TLS to Supabase).
- **Do you provide a way for users to request data deletion?** **Yes** —
  `https://www.songnotes.cloud/delete-account`, live since 2026-08-20 (see
  the gap note above). Still needs entering into Play Console's own Account
  deletion field separately before this can be submitted.
- **Data safety practices reviewed by an independent third party?** No
  (typical for a small/indie app — leave unchecked unless that changes).

## Target audience / Content rating

Not strictly part of Data Safety, but adjacent Play Console sections
you'll also need for release:
- **Target age group:** general audience (not primarily for children) —
  matches the "Children's privacy" section of the privacy policy.
- **Content rating questionnaire:** no violence, no user-generated content
  visible to *other* users (songs are private to each account, no social/
  sharing features), no user-to-user communication.
