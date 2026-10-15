package com.classroom.live;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

public final class AppLauncher extends JFrame {
    private AppLauncher() {
        super("Feric");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(580, 420));
        setSize(680, 470);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 24));
        root.setBackground(IdeTheme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));

        JLabel brand = new JLabel("FERIC  /  LIVE CODING");
        brand.setForeground(IdeTheme.MUTED);
        brand.setFont(IdeTheme.UI_FONT.deriveFont(Font.BOLD, 11f));
        root.add(brand, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        JLabel title = new JLabel("Choose your workspace", SwingConstants.CENTER);
        title.setForeground(IdeTheme.TEXT);
        title.setFont(IdeTheme.UI_FONT.deriveFont(Font.BOLD, 25f));
        JLabel subtitle = new JLabel(
            "Join a live classroom or start sharing code with your students.",
            SwingConstants.CENTER
        );
        subtitle.setForeground(IdeTheme.MUTED);
        subtitle.setFont(IdeTheme.UI_FONT.deriveFont(13f));
        JPanel intro = new JPanel(new GridLayout(2, 1, 0, 5));
        intro.setOpaque(false);
        intro.add(title);
        intro.add(subtitle);

        JPanel choices = new JPanel(new GridLayout(1, 2, 14, 0));
        choices.setOpaque(false);
        choices.add(roleCard(
            "Teacher",
            "Open the code editor and host a live classroom.",
            "Start teaching",
            new java.awt.Color(70, 142, 198),
            () -> launchTeacher()
        ));
        choices.add(roleCard(
            "Student",
            "View the teacher's code and work in your own copy.",
            "Join classroom",
            new java.awt.Color(98, 167, 131),
            () -> launchStudent()
        ));
        center.add(intro, BorderLayout.NORTH);
        center.add(choices, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        JLabel footer = new JLabel(
            "Classroom files sync through Supabase; student personal work stays on this device."
        );
        footer.setForeground(IdeTheme.MUTED);
        footer.setFont(IdeTheme.UI_FONT.deriveFont(11f));
        footer.setHorizontalAlignment(SwingConstants.CENTER);
        root.add(footer, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JPanel roleCard(
            String role, String detail, String action, java.awt.Color accent,
            Runnable selected) {
        JPanel card = new JPanel(new BorderLayout(0, 12));
        card.setBackground(IdeTheme.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(IdeTheme.BORDER),
            BorderFactory.createEmptyBorder(18, 16, 16, 16)
        ));
        JLabel icon = new JLabel(role.equals("Teacher") ? "▤" : "⌘");
        icon.setForeground(accent);
        icon.setFont(IdeTheme.UI_FONT.deriveFont(Font.PLAIN, 28f));
        JLabel heading = new JLabel(role);
        heading.setForeground(IdeTheme.TEXT);
        heading.setFont(IdeTheme.UI_FONT.deriveFont(Font.BOLD, 17f));
        JLabel description = new JLabel(
            "<html><div style='width:190px'>" + detail + "</div></html>"
        );
        description.setForeground(IdeTheme.MUTED);
        description.setFont(IdeTheme.UI_FONT.deriveFont(12f));

        JPanel copy = new JPanel(new BorderLayout(0, 5));
        copy.setOpaque(false);
        copy.add(heading, BorderLayout.NORTH);
        copy.add(description, BorderLayout.CENTER);

        JButton button = new JButton(action);
        button.setFont(IdeTheme.UI_FONT.deriveFont(Font.BOLD, 12f));
        button.setForeground(IdeTheme.BACKGROUND);
        button.setBackground(accent);
        button.setOpaque(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
        button.addActionListener(event -> selected.run());

        card.add(icon, BorderLayout.NORTH);
        card.add(copy, BorderLayout.CENTER);
        card.add(button, BorderLayout.SOUTH);
        return card;
    }

    private void launchTeacher() {
        SupabaseClient client = SupabaseAuthDialog.authenticate(this);
        if (client != null) {
            dispose();
            new TeacherApp(client);
        }
    }

    private void launchStudent() {
        SupabaseClient client = SupabaseAuthDialog.authenticate(this);
        if (client != null) {
            dispose();
            new StudentApp(client);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            IdeTheme.install();
            new AppLauncher().setVisible(true);
        });
    }
}
