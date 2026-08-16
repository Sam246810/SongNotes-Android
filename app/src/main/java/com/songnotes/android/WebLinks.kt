package com.songnotes.android

/**
 * The web app's forgot-password flow (`/forgot-password`, see the web repo's
 * src/auth/ForgotPasswordPage.jsx) -- Android deliberately links out to it
 * rather than reimplementing Supabase email-link/deep-link handling before the
 * app has even shipped (see docs/PLAN.md's forgot-password phase entry: "shared
 * crypto parity + recovery unlock screen; password reset itself links out to
 * the web").
 *
 * TODO before release: point this at the real deployed origin. Deliberately
 * NOT a guessed "real-looking" domain -- `songnotes.app` was tried first and
 * turned out to be a live, unrelated third-party product (Clerk-based auth,
 * nothing to do with this app), discovered live on-device during Phase 12
 * verification. `example.com` is IANA-reserved for documentation/placeholder
 * use, guaranteed not to collide with anyone's real site -- same "doesn't have
 * to go anywhere yet" spirit as the web app's own Play Store placeholder link
 * (src/components/MobileAppPromo/MobileAppPromo.jsx), just without the risk of
 * accidentally pointing at someone else's live product in the meantime.
 */
const val WEB_FORGOT_PASSWORD_URL = "https://example.com/songnotes-forgot-password-placeholder"

/**
 * The web app's self-service account-deletion flow (`/delete-account`, see the
 * web repo's src/auth/DeleteAccountPage.jsx). Google Play's Account Deletion
 * policy requires a reachable deletion path -- sign-out alone doesn't satisfy
 * it -- and Android links out to the web page rather than reimplementing the
 * delete flow natively, same rationale as [WEB_FORGOT_PASSWORD_URL] above.
 *
 * TODO before release: point this at the real deployed origin (web app is
 * going live on Vercel -- see that repo's README) and add the same final URL
 * to Play Console under Data safety -> Account deletion, which Play checks
 * independently of what the app links to. Placeholder domain reasoning is the
 * same as [WEB_FORGOT_PASSWORD_URL]: `example.com` is IANA-reserved, so this
 * can't accidentally collide with someone else's live site in the meantime.
 */
const val WEB_DELETE_ACCOUNT_URL = "https://example.com/delete-account"
