# Google Play listing and Play Console answers

Copy-paste material for publishing SongNotes on Google Play. Drafted
2026-10-29 from what the app actually does (code, `docs/PRIVACY_POLICY.md`,
`docs/DATA_SAFETY_FORM.md`). Check anything marked **[YOU]** before you
submit it. Character limits are Play's; each draft is within them.

---

## 1. Main store listing

**App name** (30 max): `SongNotes`

Alternative if you want the purpose in the name (26):
`SongNotes: Lyrics & Chords`

### Short description (80 max)

Pick one:

1. `Write lyrics and chords, then hum the idea into a voice note before it's gone.` (78)
2. `A songwriting notebook: lyrics, chords, and a voice recorder built right in.` (76)
3. `Lyrics, chords and voice notes in one place. Works offline, no account.` (71)

### Full description (4000 max)

```
SongNotes is a songwriting notebook for your phone. Write lyrics with the
chords sitting right above the words, then record the melody before it
slips away, all on the same page.

WRITE
• Lyrics with chords placed above the exact syllable, like a real chord sheet
• Paragraphs that wrap the way a notes app does, so editing never fights you
• Song details at a glance: BPM, key, tuning and capo
• Transpose the whole song up or down in one tap
• Chord diagrams for every chord, and save your own voicings
• A built-in piano to find notes and check chords
• Hands-free autoscroll while you play, with adjustable speed

RECORD
• Record a voice note straight from the lyrics, without leaving the page
• A metronome and count-in that stay on the song's beat, take after take
• Simple mode: a clean voice recorder for one track at a time. Trim with
  pinch-to-zoom and see exactly what will be cut. Pick any of the song's
  tracks, and hear it alone or with the others
• Advanced mode: a small multitrack arrangement with bars, beats, a snap
  grid, and clips you can move, trim, split and duplicate across tracks
• Mute, solo and volume per track, and undo for every edit
• Keeps recording while you look at your lyrics, with a status-bar timer
• Latency calibration so takes line up, even on Bluetooth headphones

SHARE
• Share a song as text or as a clean PDF chord sheet
• Share your demo as a WAV audio file
• Copy the lyrics and chords to paste anywhere

PRIVATE BY DESIGN
• Works fully offline with no account
• Your songs are stored in an encrypted database on your phone
• Recordings never leave your device
• Optional account to sync your songs between your own devices. Songs are
  encrypted on your phone before they're sent, so no one else can read them
• Sync only happens when you tap Sync
• No ads, no tracking, no analytics

SongNotes is for anyone who writes songs: guitarists, pianists, singers,
and anyone with a melody stuck in their head and a chorus half-finished.
```

(About 2,000 characters, well under the limit, so there's room to add a
line about who made it, or a link to songnotes.cloud.)

### Release notes for the first version (500 max)

```
The first release of SongNotes: write lyrics with chords, record voice
notes from the lyrics page, and build demos with Simple and Advanced modes.
Works offline; optional encrypted sync.
```

### Category, tags and contact

- **App or game:** App
- **Category:** Music & Audio
- **Tags** (Play suggests the list; pick the closest ones): Songwriting,
  Music notation / chords, Voice recorder, Notes
- **Contact email:** **[YOU]** a public address Play shows on the listing
  (it doesn't have to be your personal one)
- **Website:** `https://www.songnotes.cloud`
- **Privacy policy URL:** **[YOU]** where you host `docs/PRIVACY_POLICY.md`
  once it's filled in (see section 6). For example
  `https://www.songnotes.cloud/privacy`.

---

## 2. Graphics (ready, in `docs/store/`)

| Asset | File | Notes |
|---|---|---|
| App icon, 512 × 512 | `docs/store/play-icon-512.png` | The same design as the launcher icon in the app (beamed notes on notebook paper). |
| Feature graphic, 1024 × 500 | `docs/store/feature-graphic-1024x500.png` | "SongNotes / Write it. Hum it. Keep it." with a voice-note waveform. |
| Phone screenshots, 1080 × 1920 | `docs/store/screenshots/01…06` | Captioned and framed in this order: chords over lyrics, recording from the lyrics, Simple mode, trimming, Advanced mode, chord diagrams. |

The screenshots use a made-up song ("Paper Boats") and synthesized demo
takes. There are no real lyrics or recordings in them.

`docs/store/launcher-preview.png` shows the icon at launcher sizes, for
reference only; don't upload it.

---

## 3. App content (Play Console → Policy → App content)

### Privacy policy
The URL from section 1.

### Ads
**No**, the app doesn't contain ads.

### App access
Choose **"All or some functionality is restricted"**, because sync needs
an account. Then add instructions like these:

```
SongNotes works fully without an account: writing songs, chords, the piano,
autoscroll, sharing, and all recording features need no sign-in.

An account is only used for optional sync between devices. To test it:
Account → Sign in, with
  Email:    [YOU: a test account email]
  Password: [YOU: its password]
Then open a song and tap Sync.
```

**[YOU]** Create a dedicated test account for this. Don't use your own.

### Content rating (IARC questionnaire)
- **Category:** Reference, News, or Educational? No. Choose **"Utility,
  Productivity, Communication, or Other"** (or the "All other app types"
  option, depending on the form's wording).
- **Violence, sexuality, language, controlled substances, gambling:** No
  to all.
- **Does the app allow users to interact or exchange content with each
  other?** No. Songs are private to each account; there's no sharing
  between users inside the app.
- **Does the app share the user's location?** No.
- **Does the app allow purchases of digital goods?** No.

Expected result: rated for everyone (PEGI 3 / ESRB Everyone).

### Target audience
- **Ages:** choose **13–15, 16–17 and 18+** (or 18+ only). Don't include
  under-13 groups: that brings in the Families policy and its extra rules.
- **Appeals to children?** No.

### News app
No.

### COVID-19, financial features, health, government
Not applicable / No.

### Data safety
Full draft answers are in `docs/DATA_SAFETY_FORM.md`. Quick version:

- **Does the app collect or share user data?** Yes.
- **Email address:** collected, and shared with Supabase (auth provider)
  only to run the service. It's optional (only if the user makes an
  account), and its purpose is account management.
- **User-generated content (songs):** collected only when the user syncs.
  It's sent to Supabase as encrypted data the backend can't read. It's
  optional, and its purpose is app functionality.
- **Audio recordings:** not collected. They never leave the device.
- **Everything else** (location, contacts, photos, device IDs, crash logs,
  analytics): not collected.
- **Encrypted in transit:** Yes.
- **Users can request deletion:** Yes, at
  `https://www.songnotes.cloud/delete-account`
- **Independent security review:** No.

The app's own "Delete account" link opens the same address (fixed
2026-10-31; it used to open the Vercel deployment URL).

### Foreground service declaration
Play asks for this because the app declares
`FOREGROUND_SERVICE_MICROPHONE`.

- **Foreground service type:** Microphone
- **Task type:** User-initiated audio recording
- **Description:**

```
SongNotes lets users record song ideas (voice notes and multitrack takes).
When the user taps Record, a microphone foreground service keeps the take
recording while they leave the recording screen to read their lyrics, which
is the core songwriting workflow. The service shows an ongoing notification
with a timer and stops as soon as the user stops recording. It never starts
without the user tapping Record, and the audio stays on the device.
```

- **Video** (Play wants a link, for example an unlisted YouTube video,
  about 30 seconds). Film this:
  1. Open a song and tap Record on the voice-note bar.
  2. Show the recording notification in the status bar.
  3. Scroll the lyrics, then open the Scratchpad, while it keeps recording.
  4. Tap Stop. The notification disappears and the take appears.

### Permissions (for your reference; Play doesn't ask about these separately)
- **Microphone:** recording voice notes and takes. Asked the first time
  you tap Record.
- **Notifications:** the recording timer in the status bar. Asked once, on
  the first Record.
- **Internet / network state:** optional sync only.
- **Vibrate:** small haptic feedback.

---

## 4. Testing before production

If your developer account is a **personal** account created after
13 November 2023, Google requires a closed test before you can apply for
production. The test needs **at least 12 testers opted in for 14 days in a
row**. Organisation accounts skip this. **[YOU]** check which you have
under Play Console → Settings → Developer account.

**Order to do it in:**
1. **Internal testing:** upload the `.aab`, add yourself, and install it
   from Play to check the release build works. This has no review wait.
2. **Closed testing:** create an email list of 12 or more people, send
   them the opt-in link, and keep them opted in for 14 days. Ask them to
   actually open the app a few times; Play asks about tester engagement
   when you apply.
3. **Apply for production access**, answering Play's questions about the
   test.
4. **Production release.** The first review usually takes a few days.

**Message to send testers:**

```
Hi! I'm testing my songwriting app, SongNotes, before it goes on the Play
Store, and Google needs 12 people to stay in the test for 14 days.

1. Join with this link (use the Google account on your Android phone):
   [YOU: paste the closed-test opt-in link]
2. Install SongNotes from the Play Store page it takes you to.
3. Keep it installed for at least 14 days. Opening it now and then and
   trying things out (write a song, record a voice note) helps a lot.

Anything that breaks or confuses you, just tell me. Thank you!
```

**Questions Play asks when you apply for production** (have answers ready):
- How did you recruit testers? (Friends, family, a songwriting group…)
- How easy was it to recruit them?
- What did testers think, and what did you change because of it?
- Who is the app for, and what does it do that makes it worth installing?
- How many installs do you expect in the first year?

---

## 5. Building what you upload

The upload key is set up (`C:/Users/Sam/Documents/songnotes-release.jks`,
wired in through `local.properties`). Build the bundle from the project
folder in PowerShell:
```
.\gradlew bundleRelease
```
It's written to `app/build/outputs/bundle/release/app-release.aab`, as
version 1.0.0 (versionCode 1).

- Raise `versionCode` by one for every later upload (2, 3, …). Play
  rejects a repeat.
- When asked, enroll in **Play App Signing**. It's the default for new
  apps.

---

## 6. Privacy policy

`docs/PRIVACY_POLICY.md` is filled in, apart from the **contact email**
near the end. Once that's in, publish it at
`https://www.songnotes.cloud/privacy` and use that URL in Play Console.
