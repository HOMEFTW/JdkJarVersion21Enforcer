package com.andgatech.jdkjarversion21enforcer.ui;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Shows a small "please restart" Swing dialog after a successful auto-patch. Designed to be
 * completely safe to call from anywhere:
 *
 * <ul>
 * <li>Returns immediately on headless environments (dedicated servers, CI).</li>
 * <li>All Swing work runs on the EDT via {@link SwingUtilities#invokeLater(Runnable)} so it
 * does <b>not</b> block Forge's {@code preInit}.</li>
 * <li>Any unexpected Swing/AWT failure (broken X server, macOS first-thread restrictions, ...)
 * is swallowed; the auto-patch path still completes successfully and the WARN log line
 * remains the primary signal.</li>
 * </ul>
 */
public final class RestartPopup {

    private RestartPopup() {}

    /**
     * Best-effort check of whether we can show a Swing dialog at all. Wrapped in a try/catch
     * because some headless detections themselves throw on broken display setups.
     */
    public static boolean canShow() {
        try {
            if (GraphicsEnvironment.isHeadless()) return false;
            Toolkit.getDefaultToolkit();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Asynchronously show an INFO message dialog. No-op on headless environments or when AWT
     * cannot be initialised.
     *
     * @param title   dialog title
     * @param message body text shown to the user
     */
    public static void showRestartReminder(String title, String message) {
        if (!canShow()) return;
        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    try {
                        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    } catch (Throwable ignored) {
                        // Look-and-feel is best-effort; default L&F is fine.
                    }
                    JOptionPane.showMessageDialog(null, message, title, JOptionPane.INFORMATION_MESSAGE);
                } catch (Throwable t) {
                    // Best-effort; never let a UI failure break Forge initialization.
                }
            });
        } catch (Throwable t) {
            // SwingUtilities.invokeLater itself can throw if the EDT cannot be started.
        }
    }
}
