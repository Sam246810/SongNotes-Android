package com.songnotes.core.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

/**
 * Single shared Supabase client, same "one client instance" pattern as the web
 * app's `src/lib/supabaseClient.js` -- created once against [BuildConfig]'s
 * `SUPABASE_URL`/`SUPABASE_ANON_KEY` (read from `local.properties`, see
 * `core/data/build.gradle.kts`), pointed at the exact same project/schema the
 * desktop web app already syncs against.
 */
object SupabaseClientProvider {
    val isConfigured: Boolean = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val client: SupabaseClient by lazy {
        createSupabaseClient(supabaseUrl = BuildConfig.SUPABASE_URL, supabaseKey = BuildConfig.SUPABASE_ANON_KEY) {
            install(Auth)
            install(Postgrest)
        }
    }
}
