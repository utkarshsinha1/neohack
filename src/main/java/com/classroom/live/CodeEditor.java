package com.classroom.live;

import java.awt.Color;
import java.io.InputStream;
import java.util.Locale;
import javax.swing.BorderFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;

public final class CodeEditor extends RSyntaxTextArea {
    private String fileName;

    public CodeEditor(String fileName) {
        super(30, 100);
        this.fileName = fileName;
        setFont(IdeTheme.MONO_FONT);
        setTabSize(4);
        setTabsEmulated(true);
        setAutoIndentEnabled(true);
        setCodeFoldingEnabled(true);
        setHighlightCurrentLine(true);
        setCurrentLineHighlightColor(new Color(255, 255, 255, 12));
        setBracketMatchingEnabled(true);
        setAnimateBracketMatching(false);
        setAntiAliasingEnabled(true);
        setBackground(IdeTheme.EDITOR);
        setForeground(IdeTheme.TEXT);
        setCaretColor(IdeTheme.TEXT);
        setSelectionColor(IdeTheme.SELECTION);
        setSelectedTextColor(Color.WHITE);
        setWhitespaceVisible(false);
        setLanguageForFile(fileName);
        TextEditingSupport.install(this);

        try (InputStream themeStream = getClass().getResourceAsStream(
                "/org/fife/ui/rsyntaxtextarea/themes/dark.xml")) {
            if (themeStream != null) {
                Theme theme = Theme.load(themeStream);
                theme.apply(this);
                setBackground(IdeTheme.EDITOR);
                setForeground(IdeTheme.TEXT);
                setCaretColor(IdeTheme.TEXT);
                setSelectionColor(IdeTheme.SELECTION);
                setSelectedTextColor(Color.WHITE);
                setCurrentLineHighlightColor(new Color(255, 255, 255, 12));
            }
        } catch (Exception ignored) {
            // The editor remains usable with the colors configured above.
        }
    }

    public void setLanguageForFile(String fileName) {
        this.fileName = fileName == null ? "" : fileName;
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        } else if (name.endsWith(".c") || name.endsWith(".h")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_C);
        } else if (name.endsWith(".cc") || name.endsWith(".cpp")
                || name.endsWith(".cxx") || name.endsWith(".hpp")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS);
        } else if (name.endsWith(".py")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_PYTHON);
        } else if (name.endsWith(".js") || name.endsWith(".jsx")
                || name.endsWith(".mjs")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT);
        } else if (name.endsWith(".ts") || name.endsWith(".tsx")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT);
        } else if (name.endsWith(".cs")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_CSHARP);
        } else if (name.endsWith(".html") || name.endsWith(".htm")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_HTML);
        } else if (name.endsWith(".css")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_CSS);
        } else if (name.endsWith(".xml")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_XML);
        } else if (name.endsWith(".json")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        } else if (name.endsWith(".sh")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL);
        } else if (name.endsWith(".sql")) {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        } else {
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        }
    }

    public String getFileName() {
        return fileName;
    }

    public RTextScrollPane createScrollPane() {
        RTextScrollPane scrollPane = new RTextScrollPane(this, true);
        scrollPane.setFoldIndicatorEnabled(true);
        scrollPane.setLineNumbersEnabled(true);
        scrollPane.setBackground(IdeTheme.EDITOR);
        scrollPane.getGutter().setBackground(IdeTheme.EDITOR);
        scrollPane.getGutter().setBorderColor(IdeTheme.BORDER);
        scrollPane.getGutter().setLineNumberColor(IdeTheme.MUTED);
        scrollPane.getGutter().setCurrentLineNumberColor(IdeTheme.ACCENT);
        scrollPane.setBorder(BorderFactory.createLineBorder(IdeTheme.BORDER));
        scrollPane.getVerticalScrollBar().setUI(new IdeTheme.DarkScrollBarUI());
        scrollPane.getHorizontalScrollBar().setUI(new IdeTheme.DarkScrollBarUI());
        scrollPane.getViewport().setBackground(IdeTheme.EDITOR);
        return scrollPane;
    }
}
