package com.classroom.live;

import javax.swing.SwingUtilities;

public final class TeacherApp extends CloudTeacherApp {
    public TeacherApp(SupabaseClient client) {
        super(client);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            IdeTheme.install();
            SupabaseClient client = SupabaseAuthDialog.authenticate(null);
            if (client != null) {
                new TeacherApp(client);
            }
        });
    }
}
