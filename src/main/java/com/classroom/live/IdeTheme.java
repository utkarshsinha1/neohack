package com.classroom.live;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.border.Border;

public final class IdeTheme {
    public static final Color BACKGROUND = new Color(28, 28, 29);
    public static final Color SIDEBAR = new Color(34, 34, 35);
    public static final Color EDITOR = new Color(31, 31, 32);
    public static final Color PANEL = new Color(38, 38, 39);
    public static final Color BORDER = new Color(52, 52, 54);
    public static final Color TEXT = new Color(215, 215, 216);
    public static final Color MUTED = new Color(160, 160, 162);
    public static final Color ACCENT = new Color(126, 163, 193);
    public static final Color SELECTION = new Color(66, 82, 96);
    public static final Color SUCCESS = new Color(143, 177, 147);

    public static final Font UI_FONT = new Font("SansSerif", Font.PLAIN, 12);
    public static final Font MONO_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    public static final Border PANEL_BORDER =
        BorderFactory.createMatteBorder(0, 1, 0, 0, BORDER);

    private IdeTheme() {}

    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception exception) {
            throw new IllegalStateException("Could not install the classroom theme", exception);
        }
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Button.background", PANEL);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.font", UI_FONT);
        UIManager.put("TextField.background", EDITOR);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("ComboBox.background", PANEL);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", SELECTION);
        UIManager.put("ComboBox.selectionForeground", TEXT);
        UIManager.put("TabbedPane.background", SIDEBAR);
        UIManager.put("TabbedPane.foreground", TEXT);
        UIManager.put("TabbedPane.selected", EDITOR);
        UIManager.put("ScrollBarUI", DarkScrollBarUI.class.getName());
        UIManager.put("SplitPaneUI", DarkSplitPaneUI.class.getName());
        UIManager.put("ScrollBar.thumb", new Color(75, 75, 75));
        UIManager.put("ScrollBar.track", PANEL);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("SplitPane.background", BACKGROUND);
        UIManager.put("SplitPane.dividerSize", 6);
        UIManager.put("OptionPane.background", PANEL);
        UIManager.put("OptionPane.messageForeground", TEXT);
    }

    public static void styleButton(JButton button) {
        button.setFont(UI_FONT);
        button.setForeground(TEXT);
        button.setBackground(PANEL);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        button.setOpaque(true);
    }

    public static JScrollPane styleScrollPane(JScrollPane scrollPane) {
        scrollPane.setBackground(EDITOR);
        scrollPane.getViewport().setBackground(EDITOR);
        scrollPane.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        scrollPane.getHorizontalScrollBar().setUI(new DarkScrollBarUI());
        scrollPane.getVerticalScrollBar().setBackground(PANEL);
        scrollPane.getHorizontalScrollBar().setBackground(PANEL);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER));
        return scrollPane;
    }

    public static JSplitPane styleSplitPane(JSplitPane splitPane) {
        splitPane.setUI(new DarkSplitPaneUI());
        splitPane.setBackground(BACKGROUND);
        splitPane.setBorder(null);
        splitPane.setContinuousLayout(true);
        return splitPane;
    }

    public static void styleComboBox(JComboBox<?> combo) {
        combo.setBackground(PANEL);
        combo.setForeground(TEXT);
        combo.setFont(UI_FONT);
        combo.setFocusable(false);
        combo.setUI(new BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                JButton arrow = new JButton("▾");
                arrow.setFont(UI_FONT.deriveFont(10f));
                arrow.setForeground(MUTED);
                arrow.setBackground(PANEL);
                arrow.setBorder(BorderFactory.createEmptyBorder(0, 7, 0, 7));
                arrow.setFocusPainted(false);
                return arrow;
            }
        });
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected,
                    boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setBackground(selected ? SELECTION : PANEL);
                setForeground(TEXT);
                setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
                return this;
            }
        });
    }

    public static final class DarkScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
                thumbColor = new Color(78, 78, 80);
            trackColor = PANEL;
            thumbDarkShadowColor = trackColor;
            thumbHighlightColor = thumbColor;
            thumbLightShadowColor = thumbColor;
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected void paintTrack(Graphics graphics, javax.swing.JComponent component,
                                  Rectangle bounds) {
            graphics.setColor(PANEL);
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected void paintThumb(Graphics graphics, javax.swing.JComponent component,
                                  Rectangle bounds) {
            if (bounds.isEmpty() || !scrollbar.isEnabled()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON
            );
            g2.setColor(isDragging ? new Color(99, 129, 154) : thumbColor);
            int inset = 3;
            g2.fillRoundRect(
                bounds.x + inset, bounds.y + inset,
                Math.max(0, bounds.width - inset * 2),
                Math.max(0, bounds.height - inset * 2),
                8, 8
            );
            g2.dispose();
        }

        private static JButton zeroButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            button.setVisible(false);
            return button;
        }
    }

    public static final class DarkSplitPaneUI extends BasicSplitPaneUI {
        @Override
        public BasicSplitPaneDivider createDefaultDivider() {
            return new BasicSplitPaneDivider(this) {
                @Override
                public void paint(Graphics graphics) {
                    graphics.setColor(BACKGROUND);
                    graphics.fillRect(0, 0, getWidth(), getHeight());
                    graphics.setColor(BORDER);
                    if (getOrientation() == javax.swing.JSplitPane.HORIZONTAL_SPLIT) {
                        int x = getWidth() / 2;
                        graphics.drawLine(x, 0, x, getHeight());
                    } else {
                        int y = getHeight() / 2;
                        graphics.drawLine(0, y, getWidth(), y);
                    }
                }
            };
        }
    }
}
