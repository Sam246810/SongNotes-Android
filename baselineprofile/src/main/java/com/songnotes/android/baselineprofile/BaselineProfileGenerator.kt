package com.songnotes.android.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Generates `app/src/main/baseline-prof.txt` by driving the two journeys
 * that matter most for perceived speed: cold launch to the Songs home
 * screen, then opening Scratchpad -- the heaviest screen in the app (native
 * audio engine init, Compose timeline/waveform rendering, an existing
 * multitrack project loading off disk). Piano and the editor screens are
 * comparatively lightweight and aren't included here -- this profile covers
 * the path nearly every session takes, not every screen that exists.
 */
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "com.songnotes.android",
    ) {
        pressHome()
        startActivityAndWait()

        // Songs is the home screen (docs/handoff/PHASE-11-prep-navigation.md)
        // -- wait for its content to actually render before interacting,
        // rather than racing Compose's first frame.
        device.wait(Until.hasObject(By.text("Songs")), 5_000)

        val scratchpadButton = device.wait(Until.findObject(By.desc("Scratchpad")), 5_000)
        scratchpadButton?.click()
        device.wait(Until.hasObject(By.text("Scratchpad")), 5_000)
    }
}
