package com.classroom.live;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.border.EmptyBorder;
import java.util.function.Supplier;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CompilerPanel {
    private static final ExecutorService COMPILERS = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "compiler-worker");
        thread.setDaemon(true);
        return thread;
    });

    private CompilerPanel() {}

    public static void shutdown() {
        COMPILERS.shutdownNow();
    }

    public static JPanel create(JTextArea editor) {
        return create(() -> editor);
    }

    public static JPanel create(Supplier<JTextArea> editorProvider) {
        return create(editorProvider, null);
    }

    public static JPanel create(
            Supplier<JTextArea> editorProvider,
            Supplier<Map<String, String>> javaWorkspaceProvider) {
        JTextField className = new JTextField("Main", 8);
        JComboBox<String> language = new JComboBox<>(
            new String[] {"Java", "C", "C++", "C#", "Python", "JavaScript"}
        );
        JTextArea input = new JTextArea(3, 30);
        JTextArea output = new JTextArea();
        JButton runButton = new JButton("▶  Run");

        input.setFont(IdeTheme.MONO_FONT.deriveFont(13f));
        input.setForeground(IdeTheme.TEXT);
        input.setCaretColor(IdeTheme.TEXT);
        input.setBackground(IdeTheme.EDITOR);
        input.setSelectionColor(IdeTheme.SELECTION);
        input.setBorder(BorderFactory.createEmptyBorder(8, 9, 8, 9));
        input.setTabSize(4);

        output.setFont(IdeTheme.MONO_FONT.deriveFont(13f));
        output.setEditable(false);
        output.setForeground(IdeTheme.TEXT);
        output.setCaretColor(IdeTheme.TEXT);
        output.setBackground(IdeTheme.EDITOR);
        output.setSelectionColor(IdeTheme.SELECTION);
        output.setBorder(BorderFactory.createEmptyBorder(8, 9, 8, 9));
        output.setTabSize(4);

        className.setPreferredSize(new Dimension(110, 29));
        styleField(className);
        styleCombo(language);
        language.addActionListener(event -> {
            boolean isJava = "Java".equals(language.getSelectedItem());
            className.setEnabled(isJava);
            className.setToolTipText(isJava ? "Java main class name" : "Only used for Java");
        });

        runButton.setUI(new BasicButtonUI());
        runButton.setBackground(IdeTheme.ACCENT);
        runButton.setForeground(IdeTheme.BACKGROUND);
        runButton.setOpaque(true);
        runButton.setContentAreaFilled(true);
        runButton.setBorderPainted(false);
        runButton.setFocusPainted(false);
        runButton.setFont(IdeTheme.UI_FONT.deriveFont(Font.BOLD));
        runButton.setBorder(BorderFactory.createEmptyBorder(7, 13, 7, 13));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 7));
        toolbar.setBackground(IdeTheme.PANEL);
        toolbar.setBorder(BorderFactory.createMatteBorder(
            0, 0, 1, 0, IdeTheme.BORDER
        ));
        JLabel languageLabel = new JLabel("Language");
        languageLabel.setForeground(IdeTheme.MUTED);
        JLabel classNameLabel = new JLabel("Main class");
        classNameLabel.setForeground(IdeTheme.MUTED);
        toolbar.add(languageLabel);
        toolbar.add(language);
        toolbar.add(classNameLabel);
        toolbar.add(className);
        toolbar.add(runButton);

        JPanel inputPanel = consolePanel("PROGRAM INPUT", input);
        JPanel outputPanel = consolePanel("OUTPUT", output);
        javax.swing.JSplitPane consoles = new javax.swing.JSplitPane(
            javax.swing.JSplitPane.HORIZONTAL_SPLIT, inputPanel, outputPanel
        );
        IdeTheme.styleSplitPane(consoles);
        consoles.setResizeWeight(0.35);
        consoles.setDividerSize(5);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(IdeTheme.PANEL);
        panel.setBorder(BorderFactory.createMatteBorder(
            1, 0, 0, 0, IdeTheme.BORDER
        ));
        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(consoles, BorderLayout.CENTER);

        runButton.addActionListener(event -> {
            String selectedLanguage = (String) language.getSelectedItem();
            JTextArea editor = editorProvider.get();
            String code = editor.getText();
            String name = className.getText().trim();
            if ("Java".equals(selectedLanguage) && "Main".equals(name)
                    && editor instanceof CodeEditor codeEditor) {
                String fileName = codeEditor.getFileName();
                if (fileName != null && fileName.toLowerCase().endsWith(".java")) {
                    int separator = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
                    String baseName = fileName.substring(separator + 1);
                    name = baseName.substring(0, baseName.length() - ".java".length());
                }
            }
            String programInput = input.getText();
            String classNameToRun = name;
            Map<String, String> javaWorkspace = "Java".equals(selectedLanguage)
                    && javaWorkspaceProvider != null ? javaWorkspaceProvider.get() : null;

            runButton.setEnabled(false);
            runButton.setText("Running…");
            output.setText("Compiling and running " + selectedLanguage + "…");

            COMPILERS.execute(() -> {
                String result = javaWorkspace == null
                    ? JavaRunner.run(code, selectedLanguage, classNameToRun, programInput)
                    : JavaRunner.runJavaProject(
                        javaWorkspace, classNameToRun, programInput);
                SwingUtilities.invokeLater(() -> {
                    output.setText(result);
                    output.setCaretPosition(0);
                    runButton.setText("▶  Run");
                    runButton.setEnabled(true);
                });
            });
        });

        return panel;
    }

    private static JPanel consolePanel(String title, JTextArea textArea) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setBackground(IdeTheme.PANEL);
        panel.setBorder(new EmptyBorder(8, 9, 8, 9));
        JLabel label = new JLabel(title);
        label.setForeground(IdeTheme.MUTED);
        label.setFont(IdeTheme.UI_FONT.deriveFont(Font.BOLD, 10f));
        panel.add(label, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(textArea);
        IdeTheme.styleScrollPane(scrollPane);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private static void styleField(JTextField field) {
        field.setBackground(IdeTheme.EDITOR);
        field.setForeground(IdeTheme.TEXT);
        field.setCaretColor(IdeTheme.TEXT);
        field.setSelectionColor(IdeTheme.SELECTION);
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(IdeTheme.BORDER),
            BorderFactory.createEmptyBorder(4, 7, 4, 7)
        ));
    }

    private static void styleCombo(JComboBox<String> combo) {
        IdeTheme.styleComboBox(combo);
    }
}
