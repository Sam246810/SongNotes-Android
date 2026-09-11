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
 * The web app is now deployed at https://song-notes-jet.vercel.app. Play
 * Console's Data safety -> Account deletion field must also be set to this
 * same URL -- Play checks that field independently of what the app links to,
 * so updating this constant alone does not satisfy the Play Store
 * requirement.
 */
const val WEB_DELETE_ACCOUNT_URL = "https://song-notes-jet.vercel.app/delete-account"
