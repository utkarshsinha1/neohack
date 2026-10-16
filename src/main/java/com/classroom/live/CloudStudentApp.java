package com.classroom.live;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

public class CloudStudentApp extends javax.swing.JFrame {
    private static final String EMPTY_TAB = "No file open";
    private final ClassroomService service;
    private final JTextField codeField = new JTextField(10);
    private final JLabel status = new JLabel("Join a classroom using its room code");
    private final JLabel roomLabel = new JLabel("Not connected");
    private final JTabbedPane sharedTabs = new JTabbedPane();
    private final JTabbedPane personalTabs = new JTabbedPane();
    private final Map<String, CodeEditor> sharedEditors = new HashMap<>();
    private final Map<String, CodeEditor> personalEditors = new HashMap<>();
    private final Map<String, String> personalSourceCache = new HashMap<>();
    private final Set<Path> personalCopiesInProgress = new HashSet<>();
    private final Timer personalSaveTimer;
    private final Timer heartbeatTimer;
    private RemoteWorkspaceExplorer sharedExplorer;
    private WorkspaceExplorer personalExplorer;
    private RealtimeSyncService realtime;
    private ClassroomService.Classroom activeRoom;
    private Path personalRoot;
    private CodeEditor activePersonalEditor;
    private JSplitPane sharedSplit;
    private JSplitPane personalSplit;
    private boolean suppressPersonalSave;
    private SupabaseClient client;

    public CloudStudentApp(SupabaseClient client) {
        super("Feric — Student");
        this.client = client;
        service = new ClassroomService(client);
        IdeTheme.install();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 820);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);
        personalSaveTimer = new Timer(350, event -> persistPersonalEditors());
        personalSaveTimer.setRepeats(false);
        heartbeatTimer = new Timer(30_000, event -> sendHeartbeat());
        buildInterface();
        installIcon();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                personalSaveTimer.stop();
                heartbeatTimer.stop();
                closeRealtime();
                if (personalExplorer != null) {
                    personalExplorer.close();
                }
                if (sharedExplorer != null) {
                    sharedExplorer.close();
                }
                CompilerPanel.shutdown();
            }
        });
        setVisible(true);
    }

    private void buildInterface() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(IdeTheme.BACKGROUND);
        JPanel top = new JPanel(new BorderLayout());
        JLabel title = new JLabel("  FERIC  /  STUDENT");
        title.setForeground(IdeTheme.MUTED);
        title.setFont(IdeTheme.UI_FONT.deriveFont(Font.BOLD, 11f));
        JPanel brand = new JPanel(new BorderLayout());
        brand.setBackground(IdeTheme.PANEL);
        brand.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        brand.add(title, BorderLayout.WEST);
        brand.add(roomLabel, BorderLayout.EAST);
        roomLabel.setForeground(IdeTheme.TEXT);
        top.add(brand, BorderLayout.NORTH);

        JPanel joinBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 7));
        joinBar.setBackground(IdeTheme.SIDEBAR);
        joinBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, IdeTheme.BORDER));
        joinBar.add(label("ROOM CODE"));
        styleField(codeField);
        TextEditingSupport.install(codeField);
        codeField.setToolTipText(
            "Enter the teacher's five-character code; older rooms may still have ten characters");
        ((AbstractDocument) codeField.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(
                    FilterBypass bypass, int offset, String text, AttributeSet attributes)
                    throws BadLocationException {
                replace(bypass, offset, 0, text, attributes);
            }

            @Override
            public void replace(
                    FilterBypass bypass, int offset, int length, String text,
                    AttributeSet attributes) throws BadLocationException {
                String normalized = text == null ? "" : text.replaceAll("\\s+", "")
                    .toUpperCase(java.util.Locale.ROOT);
                int resultingLength = bypass.getDocument().getLength() - length
                    + normalized.length();
                if (resultingLength <= 10) {
                    bypass.replace(offset, length, normalized, attributes);
                }
            }
        });
        joinBar.add(codeField);
        JButton join = new JButton("Join classroom");
        IdeTheme.styleButton(join);
        join.addActionListener(event -> joinRoom());
        joinBar.add(join);
        top.add(joinBar, BorderLayout.SOUTH);

        sharedTabs.setBackground(IdeTheme.SIDEBAR);
        sharedTabs.setForeground(IdeTheme.TEXT);
        setEmptyState(sharedTabs, "Teacher's live code appears here",
            "Join a classroom, then click a file in the Explorer.");
        personalTabs.setBackground(IdeTheme.SIDEBAR);
        personalTabs.setForeground(IdeTheme.TEXT);
        setEmptyState(personalTabs, "Your editable code appears here",
            "Click a teacher file to open your own local copy beside it.");
        personalTabs.addChangeListener(event -> {
            Component selected = personalTabs.getSelectedComponent();
            activePersonalEditor = selected instanceof EditorTab selectedTab
                ? selectedTab.editor() : null;
        });
        JPanel sharedPanel = new JPanel(new BorderLayout());
        sharedPanel.setBackground(IdeTheme.EDITOR);
        JPanel sharedSidebar = new JPanel(new BorderLayout());
        sharedSidebar.setPreferredSize(new Dimension(185, 400));
        sharedSidebar.setMinimumSize(new Dimension(145, 150));
        sharedSidebar.setBackground(IdeTheme.SIDEBAR);
        sharedSidebar.add(sidebarMessage("Join a room to load its shared project"),
            BorderLayout.NORTH);
        sharedSplit = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, sharedSidebar, sharedTabs);
        IdeTheme.styleSplitPane(sharedSplit);
        sharedSplit.setResizeWeight(0.24);
        sharedSplit.setDividerLocation(180);
        sharedPanel.add(workspaceHeading("TEACHER'S LIVE CODE · READ ONLY"),
            BorderLayout.NORTH);
        sharedPanel.add(sharedSplit, BorderLayout.CENTER);

        JPanel personalPanel = new JPanel(new BorderLayout());
        personalPanel.setBackground(IdeTheme.EDITOR);
        JPanel personalSidebar = new JPanel(new BorderLayout());
        personalSidebar.setPreferredSize(new Dimension(185, 400));
        personalSidebar.setMinimumSize(new Dimension(145, 150));
        personalSidebar.setBackground(IdeTheme.SIDEBAR);
        personalSidebar.add(sidebarMessage("Select a teacher file to open your editable copy"),
            BorderLayout.NORTH);
        personalSplit = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, personalSidebar, personalTabs);
        IdeTheme.styleSplitPane(personalSplit);
        personalSplit.setResizeWeight(0.24);
        personalSplit.setDividerLocation(180);
        personalPanel.add(workspaceHeading("MY CODE · EDITABLE"), BorderLayout.NORTH);
        personalPanel.add(personalSplit, BorderLayout.CENTER);
        JSplitPane workspaces = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, sharedPanel, personalPanel);
        IdeTheme.styleSplitPane(workspaces);
        workspaces.setResizeWeight(0.5);
        workspaces.setContinuousLayout(true);
        workspaces.setOneTouchExpandable(true);

        JPanel compiler = CompilerPanel.create(
            () -> activePersonalEditor, this::personalProjectSources);
        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, workspaces, compiler);
        IdeTheme.styleSplitPane(vertical);
        vertical.setResizeWeight(0.72);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(IdeTheme.PANEL);
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, IdeTheme.BORDER));
        footer.setPreferredSize(new Dimension(800, 27));
        status.setForeground(IdeTheme.MUTED);
        footer.add(status, BorderLayout.WEST);
        JLabel local = new JLabel("Run and edit files from your personal workspace only");
        local.setForeground(IdeTheme.MUTED);
        local.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 12));
        footer.add(local, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);
        root.add(vertical, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        SwingUtilities.invokeLater(() -> {
            if (isDisplayable()) {
                sharedSplit.setDividerLocation(0.18);
                personalSplit.setDividerLocation(0.18);
                workspaces.setDividerLocation(0.5);
            }
        });
    }

    private JPanel workspaceHeading(String text) {
        JPanel heading = new JPanel(new BorderLayout());
        heading.setBackground(IdeTheme.PANEL);
        heading.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 8));
        JLabel label = new JLabel(text);
        label.setForeground(IdeTheme.MUTED);
        label.setFont(IdeTheme.UI_FONT.deriveFont(Font.BOLD, 10f));
        heading.add(label, BorderLayout.WEST);
        return heading;
    }

    private JLabel sidebarMessage(String text) {
        JLabel message = new JLabel("<html><div style='width:150px;padding:6px'>" + text
            + "</div></html>");
        message.setForeground(IdeTheme.MUTED);
        message.setVerticalAlignment(JLabel.TOP);
        return message;
    }

    private void setEmptyState(JTabbedPane tabs, String title, String detail) {
        JPanel empty = new JPanel(new BorderLayout());
        empty.setBackground(IdeTheme.EDITOR);
        JLabel message = new JLabel(
            "<html><div style='text-align:center;width:250px'>"
                + "<b>" + title + "</b><br><br>" + detail
                + "</div></html>",
            JLabel.CENTER
        );
        message.setForeground(IdeTheme.MUTED);
        empty.add(message, BorderLayout.CENTER);
        tabs.removeAll();
        tabs.addTab(EMPTY_TAB, empty);
    }

    private void clearEmptyState(JTabbedPane tabs) {
        if (tabs.getTabCount() == 1 && EMPTY_TAB.equals(tabs.getTitleAt(0))) {
            tabs.removeTabAt(0);
        }
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(IdeTheme.MUTED);
        label.setFont(IdeTheme.UI_FONT.deriveFont(Font.BOLD, 9f));
        return label;
    }

    private void joinRoom() {
        String code;
        try {
            code = RoomCode.normalize(codeField.getText());
        } catch (IllegalArgumentException exception) {
            status.setText(exception.getMessage());
            return;
        }
        closeRealtime();
        status.setText("Joining classroom…");
        new SwingWorker<ClassroomService.Classroom, Void>() {
            @Override
            protected ClassroomService.Classroom doInBackground() throws Exception {
                UUID id = service.join(code);
                return service.get(id);
            }

            @Override
            protected void done() {
                try {
                    activeRoom = get();
                    openRoom();
                } catch (Exception exception) {
                    status.setText("Could not join classroom: " + rootMessage(exception));
                    JOptionPane.showMessageDialog(
                        CloudStudentApp.this, rootMessage(exception),
                        "Join Classroom", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void openRoom() {
        UUID roomId = activeRoom.id();
        if (personalExplorer != null) {
            personalExplorer.close();
        }
        if (sharedExplorer != null) {
            sharedExplorer.close();
        }
        closeRealtime();
        sharedEditors.clear();
        personalEditors.clear();
        personalSourceCache.clear();
        setEmptyState(sharedTabs, "Teacher's live code appears here",
            "Click a file in the Explorer to view the latest classroom code.");
        setEmptyState(personalTabs, "Your editable code appears here",
            "A local copy opens here when you select a teacher file.");
        personalRoot = Path.of(System.getProperty("user.home"), ".feric", "workspaces",
            roomId.toString()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(personalRoot);
            if (Files.isSymbolicLink(personalRoot)) {
                throw new IOException("Personal workspace cannot be a symbolic link");
            }
        } catch (IOException exception) {
            status.setText("Could not open personal workspace: " + exception.getMessage());
            return;
        }
        loadPersonalWorkspaceCache();
        personalExplorer = new WorkspaceExplorer(personalRoot, this::openPersonalFile);
        roomLabel.setText(activeRoom.name() + "  ·  SHARED VIEW");
        sharedExplorer = new RemoteWorkspaceExplorer(
            service, roomId, true, true, this::openSharedFile, this::applySharedFile,
            this::removeSharedFile, status::setText);
        sharedSplit.setLeftComponent(sharedExplorer);
        personalSplit.setLeftComponent(personalExplorer);
        sharedSplit.revalidate();
        personalSplit.revalidate();
        realtime = new RealtimeSyncService(
            clientForRealtime(), roomId,
            change -> SwingUtilities.invokeLater(() -> {
                if (activeRoom != null && activeRoom.id().equals(roomId)
                        && sharedExplorer != null) {
                    sharedExplorer.applyRealtimeChange(change);
                }
            }),
            () -> SwingUtilities.invokeLater(() -> {
                if (activeRoom != null && activeRoom.id().equals(roomId)
                        && sharedExplorer != null) {
                    sharedExplorer.refresh();
                    status.setText("Realtime connected; shared workspace reconciled");
                }
            }),
            message -> SwingUtilities.invokeLater(() -> status.setText(message))
        );
        realtime.start();
        sendHeartbeat();
        heartbeatTimer.start();
    }

    private SupabaseClient clientForRealtime() {
        return client;
    }

    private void sendHeartbeat() {
        if (activeRoom == null) {
            return;
        }
        UUID id = activeRoom.id();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws IOException {
                service.heartbeat(id);
                return null;
            }
            @Override
            protected void done() {
                if (activeRoom == null || !activeRoom.id().equals(id)) {
                    return;
                }
                try {
                    get();
                } catch (Exception exception) {
                    status.setText("Classroom heartbeat failed: " + rootMessage(exception));
                }
            }
        }.execute();
    }

    private void openSharedFile(String path) {
        CodeEditor existing = sharedEditors.get(path);
        if (existing != null) {
            select(sharedTabs, path);
            WorkspaceFile current = sharedExplorer == null ? null : sharedExplorer.file(path);
            if (current != null) {
                openPersonalCopy(current);
            }
            return;
        }
        WorkspaceFile file = sharedExplorer == null ? null : sharedExplorer.file(path);
        if (file != null) {
            showFilePair(file);
            return;
        }
        UUID id = activeRoom.id();
        new SwingWorker<ClassroomService.Snapshot, Void>() {
            @Override
            protected ClassroomService.Snapshot doInBackground() throws IOException {
                return service.load(id);
            }
            @Override
            protected void done() {
                if (activeRoom == null || !activeRoom.id().equals(id)) {
                    return;
                }
                try {
                    WorkspaceFile latest = get().files().stream()
                        .filter(candidate -> candidate.path().equals(path))
                        .findFirst().orElseThrow(() -> new IOException("File no longer exists"));
                    showFilePair(latest);
                } catch (Exception exception) {
                    status.setText("Could not open shared file: " + rootMessage(exception));
                }
            }
        }.execute();
    }

    private void showFilePair(WorkspaceFile file) {
        addSharedEditor(file);
        openPersonalCopy(file);
    }

    private void openPersonalCopy(WorkspaceFile file) {
        String path = file.path();
        if (personalEditors.containsKey(path)) {
            select(personalTabs, path);
            return;
        }
        Path root = personalRoot;
        Path target = root.resolve(path);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            openPersonalFile(target);
            return;
        }
        if (!personalCopiesInProgress.add(target)) {
            return;
        }
        status.setText("Opening your editable copy of " + path + "…");
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws IOException {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
                writePersonalFile(root, path, file.content());
                return true;
            }

            @Override
            protected void done() {
                personalCopiesInProgress.remove(target);
                if (!root.equals(personalRoot)) {
                    return;
                }
                try {
                    if (get()) {
                        personalSourceCache.put(path, file.content());
                        if (personalExplorer != null) {
                            personalExplorer.refresh();
                        }
                    }
                    openPersonalFile(target);
                } catch (Exception exception) {
                    status.setText("Could not create your editable copy: "
                        + rootMessage(exception));
                }
            }
        }.execute();
    }

    private void addSharedEditor(WorkspaceFile file) {
        clearEmptyState(sharedTabs);
        CodeEditor editor = new CodeEditor(file.path());
        editor.setText(file.content());
        editor.setEditable(false);
        sharedEditors.put(file.path(), editor);
        sharedTabs.addTab(file.path(), editor.createScrollPane());
        sharedTabs.setSelectedIndex(sharedTabs.getTabCount() - 1);
    }

    private void applySharedFile(WorkspaceFile file) {
        CodeEditor editor = sharedEditors.get(file.path());
        if (editor != null) {
            editor.setText(file.content());
            editor.setCaretPosition(0);
            status.setText("Shared file updated: " + file.path());
        }
    }

    private void removeSharedFile(String path) {
        sharedEditors.remove(path);
        for (int index = 0; index < sharedTabs.getTabCount(); index++) {
            if (sharedTabs.getTitleAt(index).equals(path)) {
                sharedTabs.removeTabAt(index);
                break;
            }
        }
        if (sharedTabs.getTabCount() == 0) {
            setEmptyState(sharedTabs, "No shared file is open",
                "Choose a file from the classroom Explorer.");
        }
    }

    private void openPersonalFile(Path path) {
        Path root = personalRoot;
        Path normalized = path.toAbsolutePath().normalize();
        if (root == null || !normalized.startsWith(root)
                || hasSymbolicLinkComponent(root, normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        String relative = root.relativize(normalized).toString().replace('\\', '/');
        CodeEditor editor = personalEditors.get(relative);
        if (editor != null) {
            select(personalTabs, relative);
            return;
        }
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws IOException {
                return Files.readString(normalized, StandardCharsets.UTF_8);
            }
            @Override
            protected void done() {
                if (!root.equals(personalRoot)) {
                    return;
                }
                try {
                    String contents = get();
                    personalSourceCache.put(relative, contents);
                    addPersonalEditor(relative, contents);
                } catch (Exception exception) {
                    status.setText("Could not open personal file: " + rootMessage(exception));
                }
            }
        }.execute();
    }

    private void addPersonalEditor(String path, String content) {
        clearEmptyState(personalTabs);
        CodeEditor editor = new CodeEditor(path);
        suppressPersonalSave = true;
        editor.setText(content);
        suppressPersonalSave = false;
        personalEditors.put(path, editor);
        EditorTab tab = new EditorTab(editor);
        personalTabs.addTab(path, tab);
        personalTabs.setSelectedComponent(tab);
        activePersonalEditor = editor;
        editor.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                if (!suppressPersonalSave) {
                    personalSourceCache.put(path, editor.getText());
                    status.setText("Saving personal file…");
                    personalSaveTimer.restart();
                }
            }
            @Override public void insertUpdate(DocumentEvent event) { changed(); }
            @Override public void removeUpdate(DocumentEvent event) { changed(); }
            @Override public void changedUpdate(DocumentEvent event) { changed(); }
        });
    }

    private void writePersonalFile(Path root, String relativePath, String contents)
            throws IOException {
        String safePath = WorkspacePath.normalize(relativePath);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path destination = normalizedRoot.resolve(safePath).normalize();
        if (!destination.startsWith(normalizedRoot)
                || Files.isSymbolicLink(normalizedRoot)
                || !Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("File path escapes the personal workspace");
        }
        Path parent = normalizedRoot;
        String[] segments = safePath.split("/");
        for (int index = 0; index < segments.length - 1; index++) {
            parent = parent.resolve(segments[index]);
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(parent)
                        || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Personal workspace path contains a non-directory");
                }
            } else {
                Files.createDirectory(parent);
            }
        }
        if (Files.isSymbolicLink(destination)) {
            throw new IOException("Refusing to overwrite a symbolic link");
        }
        Files.writeString(destination, contents, StandardCharsets.UTF_8);
    }

    private static boolean hasSymbolicLinkComponent(Path root, Path candidate) {
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            return true;
        }
        for (Path segment : root.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private void loadPersonalWorkspaceCache() {
        Path root = personalRoot;
        new SwingWorker<Map<String, String>, Void>() {
            @Override
            protected Map<String, String> doInBackground() throws IOException {
                Map<String, String> files = new HashMap<>();
                try (var paths = Files.walk(root)) {
                    for (Path path : paths
                            .filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                            .toList()) {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        if (!relative.toLowerCase(java.util.Locale.ROOT).endsWith(".java")) {
                            continue;
                        }
                        try {
                            relative = WorkspacePath.normalize(relative);
                        } catch (IllegalArgumentException ignored) {
                            continue;
                        }
                        files.put(relative, Files.readString(path, StandardCharsets.UTF_8));
                    }
                }
                return files;
            }
            @Override
            protected void done() {
                if (!root.equals(personalRoot)) {
                    return;
                }
                try {
                    Map<String, String> loaded = get();
                    for (Map.Entry<String, CodeEditor> entry : personalEditors.entrySet()) {
                        loaded.put(entry.getKey(), entry.getValue().getText());
                    }
                    personalSourceCache.putAll(loaded);
                    status.setText("Personal workspace ready");
                } catch (Exception exception) {
                    status.setText("Could not load personal project files: " + rootMessage(exception));
                }
            }
        }.execute();
    }

    private void persistPersonalEditors() {
        Path root = personalRoot;
        if (root == null) {
            return;
        }
        for (Map.Entry<String, CodeEditor> entry : personalEditors.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue().getText();
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws IOException {
                    writePersonalFile(root, path, content);
                    return null;
                }
                @Override
                protected void done() {
                    try {
                        get();
                        if (root.equals(personalRoot)) {
                            status.setText("Personal changes saved locally");
                        }
                    } catch (Exception exception) {
                        status.setText("Personal save failed: " + rootMessage(exception));
                    }
                }
            }.execute();
        }
    }

    private Map<String, String> personalProjectSources() {
        Map<String, String> sources = new HashMap<>(personalSourceCache);
        for (Map.Entry<String, CodeEditor> entry : personalEditors.entrySet()) {
            sources.put(entry.getKey(), entry.getValue().getText());
        }
        return sources;
    }

    private static void select(JTabbedPane pane, String path) {
        for (int index = 0; index < pane.getTabCount(); index++) {
            if (pane.getTitleAt(index).equals(path)) {
                pane.setSelectedIndex(index);
                return;
            }
        }
    }

    private void closeRealtime() {
        if (realtime != null) {
            realtime.close();
            realtime = null;
        }
        heartbeatTimer.stop();
    }

    private static void styleField(JTextField field) {
        field.setBackground(IdeTheme.EDITOR);
        field.setForeground(IdeTheme.TEXT);
        field.setCaretColor(IdeTheme.TEXT);
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(IdeTheme.BORDER),
            BorderFactory.createEmptyBorder(4, 7, 4, 7)));
    }

    private void installIcon() {
        try (var stream = getClass().getResourceAsStream("/logos/feric-app-icon.png")) {
            if (stream != null) {
                setIconImage(ImageIO.read(stream));
            }
        } catch (IOException ignored) {
            // An app icon is optional.
        }
    }

    private static String rootMessage(Exception exception) {
        Throwable cause = exception.getCause();
        return cause != null && cause.getMessage() != null
            ? cause.getMessage() : exception.getMessage();
    }

    private static final class EditorTab extends JPanel {
        private final CodeEditor editor;

        private EditorTab(CodeEditor editor) {
            super(new BorderLayout());
            this.editor = editor;
            add(editor.createScrollPane(), BorderLayout.CENTER);
        }

        private CodeEditor editor() {
            return editor;
        }
    }
}
