package com.songnotes.android

/**
 * The web app's forgot-password flow (`/forgot-password`, see the web repo's
 * src/auth/ForgotPasswordPage.jsx) -- Android deliberately links out to it
 * rather than reimplementing Supabase email-link/deep-link handling before the
 * app has even shipped (see docs/PLAN.md's forgot-password phase entry: "shared
 * crypto parity + recovery unlock screen; password reset itself links out to
 * the web").
 *
 * Live as of 2026-08-27, same deployment as [WEB_DELETE_ACCOUNT_URL] below
 * (Vercel, custom domain `songnotes.cloud`). This was an `example.com`
 * placeholder until now -- deliberately IANA-reserved rather than a guessed
 * "real-looking" domain, after `songnotes.app` turned out to be a live,
 * unrelated third-party product. The delete-account link was pointed at the
 * real origin in commit bee73f1 and this one was missed in the same pass,
 * leaving "Forgot your password?" a dead end for anyone who tapped it.
 */
const val WEB_FORGOT_PASSWORD_URL = "https://www.songnotes.cloud/forgot-password"

/**
 * The web app's self-service account-deletion flow (`/delete-account`, see the
 * web repo's src/auth/DeleteAccountPage.jsx). Google Play's Account Deletion
 * policy requires a reachable deletion path -- sign-out alone doesn't satisfy
 * it -- and Android links out to the web page rather than reimplementing the
 * delete flow natively, same rationale as [WEB_FORGOT_PASSWORD_URL] above.
 *
 * Live as of 2026-08-20 (see the web repo's docs/DEPLOYMENT.md: deployed on
 * Vercel, custom domain `songnotes.cloud` connected via Namecheap DNS).
 *
 * TODO before release: this same URL still needs to be entered separately
 * into Play Console under Data safety -> Account deletion -- Play checks
 * that field independently of what the app links to; updating this constant
 * doesn't populate it.
 */
const val WEB_DELETE_ACCOUNT_URL = "https://www.songnotes.cloud/delete-account"
