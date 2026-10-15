package com.classroom.live;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

public final class SupabaseAuthDialog {
    private SupabaseAuthDialog() {}

    public static SupabaseClient authenticate(Window owner) {
        SupabaseClient[] authenticated = new SupabaseClient[1];
        JDialog dialog = new JDialog(owner, "Sign in to Feric", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(10, 10));

        JPanel fields = new JPanel(new java.awt.GridLayout(0, 1, 6, 6));
        fields.setBorder(BorderFactory.createEmptyBorder(16, 16, 4, 16));
        JTextField email = new JTextField(28);
        JPasswordField password = new JPasswordField(28);
        JLabel message = new JLabel("Use your Supabase Auth email and password.");
        email.setBackground(IdeTheme.EDITOR);
        email.setForeground(IdeTheme.TEXT);
        password.setBackground(IdeTheme.EDITOR);
        password.setForeground(IdeTheme.TEXT);
        fields.add(new JLabel("Email"));
        fields.add(email);
        fields.add(new JLabel("Password"));
        fields.add(password);
        fields.add(message);

        JButton signIn = new JButton("Sign in");
        JButton register = new JButton("Create account");
        JButton cancel = new JButton("Cancel");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        actions.add(cancel);
        actions.add(register);
        actions.add(signIn);
        dialog.add(fields, BorderLayout.CENTER);
        dialog.add(actions, BorderLayout.SOUTH);

        signIn.addActionListener(event -> authenticate(
            dialog, email, password, message, signIn, register, false, authenticated));
        register.addActionListener(event -> authenticate(
            dialog, email, password, message, signIn, register, true, authenticated));
        cancel.addActionListener(event -> dialog.dispose());
        dialog.getRootPane().setDefaultButton(signIn);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return authenticated[0];
    }

    private static void authenticate(
            JDialog dialog,
            JTextField email,
            JPasswordField password,
            JLabel message,
            JButton signIn,
            JButton register,
            boolean createAccount,
            SupabaseClient[] authenticated) {
        String address = email.getText().trim();
        char[] secret = password.getPassword();
        if (address.isBlank() || secret.length == 0) {
            message.setText("Enter both an email address and password.");
            return;
        }
        signIn.setEnabled(false);
        register.setEnabled(false);
        message.setText(createAccount ? "Creating account…" : "Signing in…");

        new SwingWorker<SupabaseClient, Void>() {
            @Override
            protected SupabaseClient doInBackground() throws Exception {
                SupabaseClient client = new SupabaseClient(SupabaseConfig.load());
                if (createAccount) {
                    JsonNode response = client.signUp(address, secret);
                    if (!response.hasNonNull("access_token")) {
                        throw new IOException(
                            "Account created. Confirm the email address, then sign in here.");
                    }
                    return client;
                }
                client.signIn(address, secret);
                return client;
            }

            @Override
            protected void done() {
                java.util.Arrays.fill(secret, '\0');
                signIn.setEnabled(true);
                register.setEnabled(true);
                try {
                    authenticated[0] = get();
                    dialog.dispose();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    message.setText("Authentication was interrupted.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    message.setText(cause == null || cause.getMessage() == null
                        ? "Authentication failed." : cause.getMessage());
                }
            }
        }.execute();
    }
}
