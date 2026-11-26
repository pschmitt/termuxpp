package com.termux.app;

import android.content.Context;

import org.robolectric.RuntimeEnvironment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TermuxActivityReloadTest {
    /** `tpp settings reload` while the terminal is in the background must still apply when it is shown. */
    @Test public void aReloadWithoutAVisibleActivityStaysPending() {
        Context context = RuntimeEnvironment.getApplication();
        TermuxActivity.onPropertiesEditedFromSettings(context);
        assertTrue(TermuxActivity.isPropertiesReloadPending());
    }
}
