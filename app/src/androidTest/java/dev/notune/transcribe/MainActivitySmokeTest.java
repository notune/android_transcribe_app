package dev.notune.transcribe;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Device smoke tests for the main screen.
 *
 * <p>They only read the screen: no network, no settings changes, no dictation.
 * The point is to catch layout and lifecycle breakage on a real device, not to
 * replace manual speech checks.
 *
 * <p>{@code ActivityScenario} is not used on purpose: the activity is declared
 * {@code singleTask}, and the bootstrap activity that ActivityScenario starts
 * would stay above it, leaving no resumed activity for Espresso.
 */
@RunWith(AndroidJUnit4.class)
public class MainActivitySmokeTest {

    private Activity activity;

    @Before
    public void launchActivity() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(),
                MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        assertNotNull("MainActivity did not start", activity);
    }

    @After
    public void finishActivity() {
        if (activity != null) {
            activity.finish();
            activity = null;
        }
    }

    @Test
    public void showsAppTitle() {
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()));
        onView(withText(R.string.app_name)).check(matches(isDisplayed()));
    }

    @Test
    public void postProcessingCardIsReachable() {
        onView(withId(R.id.switch_post_processing)).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withId(R.id.input_pp_model)).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void modelListButtonIsVisibleAndEnabled() {
        onView(withId(R.id.btn_pp_models))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
                .check(matches(withText(R.string.pp_models_fetch)))
                .check(matches(isEnabled()));
    }
}
