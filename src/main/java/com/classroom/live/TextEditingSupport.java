package com.classroom.live;

import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;

public final class TextEditingSupport {
    private TextEditingSupport() {}

    public static void install(JTextComponent component) {
        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        Action copy = action(() -> component.copy());
        Action cut = action(() -> {
            if (component.isEditable()) {
                component.cut();
            }
        });
        Action paste = action(() -> {
            if (component.isEditable()) {
                component.paste();
            }
        });
        Action selectAll = action(component::selectAll);

        bind(component, KeyEvent.VK_C, shortcutMask, "feric-copy", copy);
        bind(component, KeyEvent.VK_X, shortcutMask, "feric-cut", cut);
        bind(component, KeyEvent.VK_V, shortcutMask, "feric-paste", paste);
        bind(component, KeyEvent.VK_A, shortcutMask, "feric-select-all", selectAll);

        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Cut", cut));
        menu.add(item("Copy", copy));
        menu.add(item("Paste", paste));
        menu.addSeparator();
        menu.add(item("Select All", selectAll));
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
                cut.setEnabled(component.isEditable() && component.getSelectedText() != null);
                copy.setEnabled(component.getSelectedText() != null);
                paste.setEnabled(component.isEditable() && component.isEnabled());
                selectAll.setEnabled(component.getDocument().getLength() > 0);
            }

            @Override public void popupMenuWillBecomeInvisible(
                    javax.swing.event.PopupMenuEvent event) {}
            @Override public void popupMenuCanceled(
                    javax.swing.event.PopupMenuEvent event) {}
        });
        component.setComponentPopupMenu(menu);
    }

    private static JMenuItem item(String text, Action action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(action);
        return item;
    }

    private static Action action(Runnable operation) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                operation.run();
            }
        };
    }

    private static void bind(
            JTextComponent component, int key, int modifiers, String name, Action action) {
        component.getInputMap().put(KeyStroke.getKeyStroke(key, modifiers), name);
        component.getActionMap().put(name, action);
    }
}
