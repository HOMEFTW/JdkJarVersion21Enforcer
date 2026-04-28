package com.andgatech.jdkjarversion21enforcer.tweaker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.andgatech.jdkjarversion21enforcer.relaunch.JvmRelauncher;
import com.andgatech.jdkjarversion21enforcer.relaunch.RelaunchService;

/**
 * Smoke tests for the LaunchWrapper Tweaker entry point. We can't drive a real LaunchWrapper boot
 * from a unit test, but we can:
 *
 * <ul>
 * <li>verify the Tweaker has the canonical no-arg constructor that LaunchWrapper requires;</li>
 * <li>verify {@link JdkJarVersion21Tweaker#getLaunchTarget()} / {@link
 * JdkJarVersion21Tweaker#getLaunchArguments()} return the expected "I am not the main tweaker"
 * sentinels;</li>
 * <li>verify {@code injectIntoClassLoader} is safe to call without a real classloader on Java
 * &le; 21 (test JVM) and that it always sets the {@code HANDLED} system property.</li>
 * </ul>
 */
class JdkJarVersion21TweakerTest {

    private String originalHandled;
    private String originalGuard;

    @BeforeEach
    void clearProps() {
        originalHandled = System.getProperty(RelaunchService.HANDLED_PROPERTY);
        originalGuard = System.getProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY);
        System.clearProperty(RelaunchService.HANDLED_PROPERTY);
        System.clearProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY);
    }

    @AfterEach
    void restoreProps() {
        if (originalHandled == null) System.clearProperty(RelaunchService.HANDLED_PROPERTY);
        else System.setProperty(RelaunchService.HANDLED_PROPERTY, originalHandled);
        if (originalGuard == null) System.clearProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY);
        else System.setProperty(JvmRelauncher.RELAUNCH_GUARD_PROPERTY, originalGuard);
    }

    @Test
    void canBeConstructedWithNoArgs() {
        assertDoesNotThrow(() -> new JdkJarVersion21Tweaker());
    }

    @Test
    void getLaunchTargetIsNullSoLaunchWrapperUsesTheRealOne() {
        assertNull(new JdkJarVersion21Tweaker().getLaunchTarget());
    }

    @Test
    void getLaunchArgumentsIsEmptySoWeDoNotInjectAnyAdditionalLaunchArgs() {
        assertArrayEquals(new String[0], new JdkJarVersion21Tweaker().getLaunchArguments());
    }

    @Test
    void acceptOptionsCapturesGameDirAndDoesNotThrow(@TempDir Path tmp) {
        JdkJarVersion21Tweaker tw = new JdkJarVersion21Tweaker();
        File gameDir = tmp.toFile();
        File assets = tmp.resolve("assets")
            .toFile();
        assertDoesNotThrow(() -> tw.acceptOptions(Collections.<String>emptyList(), gameDir, assets, "default-profile"));
    }

    @Test
    void injectIntoClassLoaderIsSafeAndAlwaysMarksHandled(@TempDir Path tmp) {
        JdkJarVersion21Tweaker tw = new JdkJarVersion21Tweaker();
        tw.acceptOptions(Collections.<String>emptyList(), tmp.toFile(), null, "test");

        // Passing null is safe: the tweaker swallows any exception and still sets the handled
        // flag so preInit will not retry.
        assertDoesNotThrow(() -> tw.injectIntoClassLoader(null));
        assertTrue(RelaunchService.isAlreadyHandled());
    }
}
