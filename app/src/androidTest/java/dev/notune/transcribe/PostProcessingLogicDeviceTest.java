package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * Runs the model-list helpers on the device VM.
 *
 * <p>The same behaviour is covered by JVM tests. This checks the code under
 * ART, where character classes, string handling and the bundled org.json come
 * from the platform rather than from the desktop JDK.
 */
@RunWith(AndroidJUnit4.class)
public class PostProcessingLogicDeviceTest {

    @Test
    public void parsesEveryDocumentedShape() {
        assertEquals(Arrays.asList("a", "b"),
                PostProcessingLogic.parseModelIds("{\"data\":[{\"id\":\"b\"},{\"name\":\"a\"}]}"));
        assertEquals(Arrays.asList("c", "d"),
                PostProcessingLogic.parseModelIds("{\"models\":[{\"model\":\"c\"},{\"name\":\"d\"}]}"));
    }

    @Test
    public void dropsControlAndDirectionCharacters() {
        assertEquals("vendor/model",
                PostProcessingLogic.sanitizeModelId("\u0007vendor\u202E/model\u200F"));
        assertEquals("", PostProcessingLogic.sanitizeModelId(null));
        assertEquals("", PostProcessingLogic.sanitizeModelId("   "));
    }

    @Test
    public void matchesAnyPartOfTheName() {
        assertTrue(PostProcessingLogic.matchesModelQuery("vendor/alpha-chat", "alpha"));
        assertTrue(PostProcessingLogic.matchesModelQuery("vendor/alpha-chat", "PHA-CH"));
        assertTrue(PostProcessingLogic.matchesModelQuery("Модель-2", "модель"));
        assertTrue(PostProcessingLogic.matchesModelQuery("anything", ""));
        assertFalse(PostProcessingLogic.matchesModelQuery("vendor/alpha-chat", "beta"));
    }

    @Test
    public void buildsModelsUrlOnTheSameHost() {
        assertEquals("https://host/v1/models",
                PostProcessingLogic.buildModelsUrl("https://host"));
        assertEquals("https://host/v1/models",
                PostProcessingLogic.buildModelsUrl("https://host/v1/chat/completions"));
    }
}
