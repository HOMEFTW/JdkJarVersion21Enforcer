package com.andgatech.jdkjarversion21enforcer.ui;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Non-blocking informational popup shown as a <b>fallback</b> when the auto-fix flow could not
 * configure the JVM property for the current launch. Examples of when this is shown:
 *
 * <ul>
 * <li>The user picked &quot;Skip this time&quot; in {@link RelaunchPromptDialog} \u2014 the game will
 * continue running without {@code -Djdk.util.jar.version=21}, so we still want to remind the
 * user how to add it manually next time.</li>
 * <li>The user picked &quot;Don't ask again&quot;.</li>
 * <li>The JVM fork attempt failed (rare).</li>
 * </ul>
 *
 * <p>
 * The popup is launched on the EDT via {@link SwingUtilities#invokeLater} and never blocks the
 * caller. Headless environments simply skip the dialog.
 */
public final class ManualLauncherInstructionsPopup {

    private ManualLauncherInstructionsPopup() {}

    /**
     * Asynchronously shows the manual-configuration reminder. Safe to call from any thread; safe
     * to call in headless environments (no-op).
     *
     * @param modName       title prefix
     * @param desiredOption e.g. {@code -Djdk.util.jar.version=21}
     * @param jarFileName   the redistribution jar's file name (used in the {@code -javaagent:}
     *                      example), or {@code null} to skip the agent example
     */
    public static void show(String modName, String desiredOption, String jarFileName) {
        if (!RestartPopup.canShow()) return;
        final String title = modName + " \u2014 Manual configuration reminder";
        final String message = buildMessage(desiredOption, jarFileName);
        SwingUtilities.invokeLater(() -> {
            try {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Throwable ignored) {}
                JOptionPane.showMessageDialog(null, message, title, JOptionPane.INFORMATION_MESSAGE);
            } catch (Throwable ignored) {
                // never propagate UI failure
            }
        });
    }

    static String buildMessage(String desiredOption, String jarFileName) {
        StringBuilder sb = new StringBuilder();
        sb.append("This launch is running WITHOUT the JVM startup argument:\n\n")
            .append("    ")
            .append(desiredOption)
            .append("\n\n")
            .append("Minecraft 1.7.10 mods may behave incorrectly on Java 22+ in this state.\n\n")
            .append("To fix this permanently outside the in-game dialog, add ONE of the following\n")
            .append("to your launcher's JVM arguments:\n\n")
            .append("    ")
            .append(desiredOption)
            .append("\n");
        if (jarFileName != null && !jarFileName.isEmpty()) {
            sb.append("        \u2014 or \u2014\n")
                .append("    -javaagent:mods/")
                .append(jarFileName)
                .append("\n");
        }
        sb.append("\nCommon launchers:\n")
            .append(
                "  \u2022 PrismLauncher / MultiMC: Edit Instance \u2192 Settings \u2192 Java \u2192 JVM arguments\n")
            .append("  \u2022 HMCL / PCL2 / BakaXL: Game JVM arguments\n")
            .append("  \u2022 Vanilla launcher: Installations \u2192 More Options \u2192 JVM arguments\n");
        return sb.toString();
    }
}
