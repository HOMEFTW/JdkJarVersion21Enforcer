package com.andgatech.jdkjarversion21enforcer.ui;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Modal three-button dialog presented to the user once we know the property
 * {@code jdk.util.jar.version=21} is not yet effective in this JVM.
 *
 * <p>
 * The wording is intentionally action-oriented: it tells the user what we are about to do (write
 * a config file for the next launch + fork a child JVM for the current launch) and asks for a
 * single "Apply" confirmation. We never ask the user to manually edit their launcher's JVM args
 * here — that fallback message is delivered later by
 * {@code ManualLauncherInstructionsPopup}, only when the user opts out / the auto-fix fails.
 *
 * <p>
 * Headless environments and AWT/EDT failures default to {@link Outcome#SKIP_ONCE} so server /
 * CI loads never hang.
 */
public final class RelaunchPromptDialog {

    public enum Outcome {
        RESTART_NOW,
        SKIP_ONCE,
        SUPPRESS_FOREVER
    }

    /**
     * What the lwjgl3ify config patcher did just before we built this dialog. Used to tailor the
     * dialog message so the user knows whether the next launch will already pick up the option
     * automatically (CREATED / APPLIED) or whether the option was found pre-existing
     * (ALREADY_PRESENT) or whether persistence failed (NO_CONFIG_OR_ERROR).
     */
    public enum PatcherStatus {
        CREATED,
        APPLIED,
        ALREADY_PRESENT,
        NO_CONFIG_OR_ERROR
    }

    private RelaunchPromptDialog() {}

    public static Outcome askUserModally(String javaSpecificationVersion, String modName, String desiredOption,
        String configFileName, String suppressKey, PatcherStatus patcherStatus) {
        if (!RestartPopup.canShow()) return Outcome.SKIP_ONCE;
        AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.SKIP_ONCE);
        try {
            SwingUtilities.invokeAndWait(
                () -> outcome.set(
                    showDialog(
                        javaSpecificationVersion,
                        modName,
                        desiredOption,
                        configFileName,
                        suppressKey,
                        patcherStatus)));
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            return Outcome.SKIP_ONCE;
        } catch (InvocationTargetException e) {
            return Outcome.SKIP_ONCE;
        } catch (Throwable t) {
            return Outcome.SKIP_ONCE;
        }
        return outcome.get();
    }

    private static Outcome showDialog(String javaSpecificationVersion, String modName, String desiredOption,
        String configFileName, String suppressKey, PatcherStatus patcherStatus) {
        try {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Throwable ignored) {}

            String[] options = { "Apply & Restart Now (recommended)", "Skip this time", "Don't ask again" };
            String message = buildMessage(
                javaSpecificationVersion,
                desiredOption,
                configFileName,
                suppressKey,
                patcherStatus);
            int choice = JOptionPane.showOptionDialog(
                null,
                message,
                modName + " \u2014 Configure JVM startup arguments",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);
            switch (choice) {
                case 0:
                    return Outcome.RESTART_NOW;
                case 1:
                    return Outcome.SKIP_ONCE;
                case 2:
                    return Outcome.SUPPRESS_FOREVER;
                default:
                    return Outcome.SKIP_ONCE;
            }
        } catch (Throwable t) {
            return Outcome.SKIP_ONCE;
        }
    }

    static String buildMessage(String javaSpecificationVersion, String desiredOption, String configFileName,
        String suppressKey, PatcherStatus patcherStatus) {
        StringBuilder sb = new StringBuilder();
        sb.append("Java ")
            .append(javaSpecificationVersion)
            .append(" was detected, but the following JVM startup argument is not yet active:\n\n")
            .append("    ")
            .append(desiredOption)
            .append("\n\n")
            .append("Without it, Minecraft 1.7.10 mods may break in subtle ways on Java 22+.\n\n");
        sb.append("Persistent configuration status:\n");
        switch (patcherStatus) {
            case CREATED:
                sb.append(
                    "  \u2022 lwjgl3ify-relauncher.json was missing; a fresh one was just\n    created with the option pre-filled. Future non-RFB launches will\n    apply it automatically.\n\n");
                break;
            case APPLIED:
                sb.append(
                    "  \u2022 The option was just appended to lwjgl3ify-relauncher.json. Future\n    non-RFB launches will apply it automatically.\n\n");
                break;
            case ALREADY_PRESENT:
                sb.append(
                    "  \u2022 lwjgl3ify-relauncher.json already contains the option. The current\n    JVM, however, was not started with it.\n\n");
                break;
            case NO_CONFIG_OR_ERROR:
            default:
                sb.append(
                    "  \u2022 lwjgl3ify config could not be written. Persistent configuration\n    via lwjgl3ify is not available right now.\n\n");
                break;
        }
        sb.append("What would you like to do for the CURRENT launch?\n\n")
            .append(
                "  \u2022 Apply & Restart Now (recommended): re-launch this game in a child JVM\n    with the option set, so this very session has it.\n")
            .append(
                "  \u2022 Skip this time: continue this launch without the option (compatibility\n    issues likely). A reminder window will explain how to add it manually.\n")
            .append("  \u2022 Don't ask again: suppress this dialog permanently by writing\n    ")
            .append(suppressKey)
            .append("=true into config/")
            .append(configFileName)
            .append(". The reminder window will still\n    be shown once.");
        return sb.toString();
    }
}
