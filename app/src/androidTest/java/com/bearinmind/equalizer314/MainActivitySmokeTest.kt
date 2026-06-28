package com.bearinmind.equalizer314

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke tests for MainActivity.
 *
 * These tests verify that the main activity launches correctly and
 * core UI elements are rendered. They run on a device or emulator
 * (API 28+).
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun activity_launchesSuccessfully() {
        // Verify that the main activity is created and displayed
        activityRule.scenario.onActivity { activity ->
            assertNotNull("MainActivity should not be null", activity)
        }
    }

    @Test
    fun bottomBar_isDisplayed() {
        // The bottom navigation bar should be visible on launch
        onView(withId(R.id.bottomBar))
            .check(matches(isDisplayed()))
    }

    @Test
    fun eqPage_isDisplayed() {
        // The main EQ page should be visible
        onView(withId(R.id.pageEq))
            .check(matches(isDisplayed()))
    }

    @Test
    fun equalizerGraph_isDisplayed() {
        // The equalizer graph view should be rendered
        onView(withId(R.id.eqGraphView))
            .check(matches(isDisplayed()))
    }

    @Test
    fun presetPicker_isDisplayed() {
        // The preset picker/spinner should be accessible
        onView(withId(R.id.presetPickerContainer))
            .check(matches(isDisplayed()))
    }

    @Test
    fun powerButton_isDisplayed() {
        // The power toggle should be present in the bottom bar
        onView(withId(R.id.powerFab))
            .check(matches(isDisplayed()))
    }

    @Test
    fun navigationButtons_areDisplayed() {
        // All four navigation buttons should be visible
        onView(withId(R.id.navPresetsButton)).check(matches(isDisplayed()))
        onView(withId(R.id.navMbcButton)).check(matches(isDisplayed()))
        onView(withId(R.id.navLimiterButton)).check(matches(isDisplayed()))
        onView(withId(R.id.navSettingsButton)).check(matches(isDisplayed()))
    }
}
