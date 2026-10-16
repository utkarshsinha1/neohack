package com.classroom.live;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

public final class RemoteWorkspaceExplorer extends JPanel {
    private final ClassroomService service;
    private final UUID classroomId;
    private final boolean readOnly;
    private final boolean openOnSingleClick;
    private final Consumer<String> openFile;
    private final Consumer<WorkspaceFile> fileChanged;
    private final Consumer<String> fileDeleted;
    private final Consumer<String> statusChanged;
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Workspace");
    private final DefaultTreeModel model = new DefaultTreeModel(rootNode);
    private final JTree tree = new JTree(model);
    private final Map<String, WorkspaceFile> files = new HashMap<>();
    private final Set<String> folders = new HashSet<>();
    private volatile boolean closed;

    public RemoteWorkspaceExplorer(
            ClassroomService service,
            UUID classroomId,
            boolean readOnly,
            boolean openOnSingleClick,
            Consumer<String> openFile,
            Consumer<WorkspaceFile> fileChanged,
            Consumer<String> fileDeleted,
            Consumer<String> statusChanged) {
        super(new BorderLayout(0, 6));
        this.service = service;
        this.classroomId = classroomId;
        this.readOnly = readOnly;
        this.openOnSingleClick = openOnSingleClick;
        this.openFile = openFile;
        this.fileChanged = fileChanged;
        this.fileDeleted = fileDeleted;
        this.statusChanged = statusChanged;
        setBackground(IdeTheme.SIDEBAR);
        setPreferredSize(new java.awt.Dimension(175, 300));
        setMinimumSize(new java.awt.Dimension(130, 150));
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 1, IdeTheme.BORDER));

        JPanel heading = new JPanel(new BorderLayout());
        heading.setBackground(IdeTheme.SIDEBAR);
        heading.setBorder(BorderFactory.createEmptyBorder(9, 10, 4, 8));
        JLabel title = new JLabel(readOnly ? "SHARED CLASSROOM" : "SHARED EXPLORER");
        title.setForeground(IdeTheme.MUTED);
        title.setFont(IdeTheme.UI_FONT.deriveFont(java.awt.Font.BOLD, 10f));
        heading.add(title, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        actions.setOpaque(false);
        if (!readOnly) {
            actions.add(action("+", "New file", () -> createEntry(false)));
            actions.add(action("▣", "New folder", () -> createEntry(true)));
        }
        actions.add(action("↻", "Refresh from server", this::refresh));
        heading.add(actions, BorderLayout.EAST);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setBackground(IdeTheme.SIDEBAR);
        tree.setForeground(IdeTheme.TEXT);
        tree.setFont(IdeTheme.UI_FONT);
        tree.setRowHeight(23);
        tree.setCellRenderer(new Renderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                TreePath selected = tree.getPathForLocation(event.getX(), event.getY());
                if (SwingUtilities.isLeftMouseButton(event)
                        && (openOnSingleClick
                            ? event.getClickCount() == 1 : event.getClickCount() == 2)
                        && selected != null) {
                    Object node = ((DefaultMutableTreeNode) selected.getLastPathComponent())
                        .getUserObject();
                    if (node instanceof Entry entry && !entry.directory()) {
                        openFile.accept(entry.path());
                    }
                }
                if (SwingUtilities.isRightMouseButton(event) && selected != null) {
                    tree.setSelectionPath(selected);
                    showContextMenu(event.getX(), event.getY());
                }
            }
        });
        JScrollPane scroll = new JScrollPane(tree);
        IdeTheme.styleScrollPane(scroll);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(IdeTheme.SIDEBAR);
        add(heading, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        refresh();
    }

    public WorkspaceFile file(String path) {
        return files.get(path);
    }

    public Map<String, String> textFiles() {
        Map<String, String> snapshot = new HashMap<>();
        for (WorkspaceFile file : files.values()) {
            snapshot.put(file.path(), file.content());
        }
        return Map.copyOf(snapshot);
    }

    public void refresh() {
        if (closed) {
            return;
        }
        refresh(() -> {});
    }

    public void close() {
        closed = true;
    }

    private void refresh(Runnable afterLoad) {
        statusChanged.accept("Loading classroom workspace…");
        new SwingWorker<ClassroomService.Snapshot, Void>() {
            @Override
            protected ClassroomService.Snapshot doInBackground() throws IOException {
                return service.load(classroomId);
            }

            @Override
            protected void done() {
                if (closed) {
                    return;
                }
                try {
                    ClassroomService.Snapshot snapshot = get();
                    Map<String, WorkspaceFile> previousFiles = new HashMap<>(files);
                    Map<String, WorkspaceFile> refreshedFiles = new HashMap<>();
                    for (WorkspaceFile incoming : snapshot.files()) {
                        WorkspaceFile previous = previousFiles.values().stream()
                            .filter(file -> file.id().equals(incoming.id()))
                            .findFirst().orElse(null);
                        if (previous == null || incoming.version() > previous.version()) {
                            refreshedFiles.put(incoming.path(), incoming);
                            fileChanged.accept(incoming);
                        } else {
                            refreshedFiles.put(previous.path(), previous);
                        }
                    }
                    for (WorkspaceFile previous : previousFiles.values()) {
                        boolean stillExists = refreshedFiles.values().stream()
                            .anyMatch(file -> file.id().equals(previous.id()));
                        if (!stillExists) {
                            fileDeleted.accept(previous.path());
                        }
                    }
                    files.clear();
                    folders.clear();
                    for (String folder : snapshot.folders()) {
                        folders.add(folder);
                    }
                    files.putAll(refreshedFiles);
                    rebuild();
                    statusChanged.accept("Workspace synchronized");
                    afterLoad.run();
                } catch (Exception exception) {
                    statusChanged.accept("Workspace load failed: " + message(exception));
                }
            }
        }.execute();
    }

    public void applyRealtimeChange(JsonNode change) {
        if (closed) {
            return;
        }
        String table = change.path("table").asText();
        String type = change.path("type").asText();
        JsonNode record = change.path("record");
        JsonNode old = change.path("old_record");
        if ("classroom_files".equals(table)) {
            String oldPath = old.path("file_path").asText("");
            String newPath = record.path("file_path").asText("");
            if (!oldPath.isEmpty()
                    && ("DELETE".equalsIgnoreCase(type) || !oldPath.equals(newPath))) {
                removeFileIfCurrent(old, "DELETE".equalsIgnoreCase(type));
            }
            if (!"DELETE".equalsIgnoreCase(type) && record.hasNonNull("file_path")) {
                WorkspaceFile incoming = decodeFile(record);
                WorkspaceFile current = files.get(incoming.path());
                if (current == null || incoming.version() > current.version()) {
                    files.put(incoming.path(), incoming);
                    fileChanged.accept(incoming);
                }
            }
            rebuild();
        } else if ("classroom_folders".equals(table)) {
            if (old.hasNonNull("folder_path")) {
                folders.remove(old.path("folder_path").asText());
            }
            if (!"DELETE".equalsIgnoreCase(type) && record.hasNonNull("folder_path")) {
                folders.add(record.path("folder_path").asText());
            } else if (record.hasNonNull("folder_path")) {
                folders.remove(record.path("folder_path").asText());
            }
            rebuild();
        }
    }

    private void removeFileIfCurrent(JsonNode node, boolean notifyDeleted) {
        String path = node.path("file_path").asText();
        long version = node.path("version").asLong();
        WorkspaceFile current = files.get(path);
        if (current != null && (version == 0 || current.version() <= version)) {
            files.remove(path);
            if (notifyDeleted) {
                fileDeleted.accept(path);
            }
        }
    }

    private WorkspaceFile decodeFile(JsonNode node) {
        return new WorkspaceFile(
            UUID.fromString(node.path("id").asText()),
            UUID.fromString(node.path("classroom_id").asText()),
            node.path("file_path").asText(),
            node.path("content").asText(),
            node.path("language").asText(),
            node.path("version").asLong(),
            Instant.parse(node.path("updated_at").asText())
        );
    }

    private void rebuild() {
        rootNode.removeAllChildren();
        Map<String, DefaultMutableTreeNode> nodes = new HashMap<>();
        nodes.put("", rootNode);
        List<String> allFolders = new ArrayList<>(folders);
        for (WorkspaceFile file : files.values()) {
            String[] parts = file.path().split("/");
            for (int index = 1; index < parts.length; index++) {
                allFolders.add(String.join("/", java.util.Arrays.copyOfRange(parts, 0, index)));
            }
        }
        allFolders = allFolders.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        for (String folder : allFolders) {
            ensureNode(folder, true, nodes);
        }
        files.values().stream().sorted(Comparator.comparing(WorkspaceFile::path)).forEach(file -> {
            String parent = parent(file.path());
            DefaultMutableTreeNode parentNode = ensureNode(parent, true, nodes);
            DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(
                new Entry(file.path(), false));
            parentNode.add(fileNode);
            nodes.put(file.path(), fileNode);
        });
        model.reload();
    }

    private DefaultMutableTreeNode ensureNode(
            String path, boolean directory, Map<String, DefaultMutableTreeNode> nodes) {
        if (path == null || path.isEmpty()) {
            return rootNode;
        }
        DefaultMutableTreeNode existing = nodes.get(path);
        if (existing != null) {
            return existing;
        }
        String parent = parent(path);
        DefaultMutableTreeNode parentNode = ensureNode(parent, true, nodes);
        DefaultMutableTreeNode created = new DefaultMutableTreeNode(new Entry(path, directory));
        parentNode.add(created);
        nodes.put(path, created);
        return created;
    }

    private void createEntry(boolean directory) {
        String selectedPath = selectedPath();
        String parent = selectedPath == null
            ? "" : (folders.contains(selectedPath) ? selectedPath : parent(selectedPath));
        String prompt = directory ? "New folder path:" : "New file path:";
        String relative = JOptionPane.showInputDialog(this, prompt);
        if (relative == null || relative.isBlank()) {
            return;
        }
        String path;
        try {
            path = WorkspacePath.normalize(parent.isEmpty()
                ? relative.trim() : parent + "/" + relative.trim());
        } catch (IllegalArgumentException exception) {
            showError("Enter a valid relative path inside the classroom.");
            return;
        }
        runOperation(directory ? "Creating folder…" : "Creating file…", () -> {
            if (directory) {
                service.createFolder(classroomId, path);
            } else {
                service.save(classroomId, path, "", WorkspacePath.languageFor(path));
            }
        }, () -> refresh(() -> {
            if (!directory) {
                openFile.accept(path);
            }
        }));
    }

    private void showContextMenu(int x, int y) {
        if (readOnly) {
            return;
        }
        String path = selectedPath();
        if (path == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem rename = new JMenuItem("Rename or move…");
        rename.addActionListener(event -> renameEntry(path));
        JMenuItem delete = new JMenuItem("Delete…");
        delete.addActionListener(event -> deleteEntry(path));
        menu.add(rename);
        menu.add(delete);
        menu.show(tree, x, y);
    }

    private void renameEntry(String oldPath) {
        String newPath = JOptionPane.showInputDialog(
            this, "New classroom-relative path:", oldPath);
        if (newPath == null || newPath.isBlank()) {
            return;
        }
        String destination;
        try {
            destination = WorkspacePath.normalize(newPath.trim());
        } catch (IllegalArgumentException exception) {
            showError("Enter a valid relative path inside the classroom.");
            return;
        }
        boolean directory = folders.contains(oldPath);
        runOperation("Renaming workspace item…", () -> {
            if (directory) {
                service.renameFolder(classroomId, oldPath, destination);
            } else {
                String parent = parent(destination);
                if (parent != null) {
                    service.createFolder(classroomId, parent);
                }
                service.renameFile(classroomId, oldPath, destination);
            }
        }, this::refresh);
    }

    private void deleteEntry(String path) {
        boolean directory = folders.contains(path);
        int answer = JOptionPane.showConfirmDialog(
            this, directory
                ? "Delete this folder and everything in it?\n" + path
                : "Delete this file?\n" + path,
            "Delete Workspace Item", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        runOperation("Deleting workspace item…", () -> {
            if (directory) {
                service.deleteFolder(classroomId, path, true);
            } else {
                service.deleteFile(classroomId, path);
            }
        }, this::refresh);
    }

    private void runOperation(String progress, CheckedRunnable operation, Runnable success) {
        statusChanged.accept(progress);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                operation.run();
                return null;
            }

            @Override
            protected void done() {
                if (closed) {
                    return;
                }
                try {
                    get();
                    success.run();
                } catch (Exception exception) {
                    statusChanged.accept("Workspace operation failed: " + message(exception));
                    showError(message(exception));
                }
            }
        }.execute();
    }

    private String selectedPath() {
        TreePath selection = tree.getSelectionPath();
        if (selection == null) {
            return null;
        }
        Object value = ((DefaultMutableTreeNode) selection.getLastPathComponent())
            .getUserObject();
        return value instanceof Entry entry ? entry.path() : null;
    }

    private static String parent(String path) {
        if (path == null) {
            return null;
        }
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private JButton action(String label, String tooltip, Runnable action) {
        JButton button = new JButton(label);
        IdeTheme.styleButton(button);
        button.setToolTipText(tooltip);
        button.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        button.addActionListener(event -> action.run());
        return button;
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Classroom Explorer", JOptionPane.ERROR_MESSAGE);
    }

    private static String message(Exception exception) {
        Throwable cause = exception.getCause();
        return cause != null && cause.getMessage() != null
            ? cause.getMessage() : exception.getMessage();
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private record Entry(String path, boolean directory) {
        @Override
        public String toString() {
            int separator = path.lastIndexOf('/');
            return separator < 0 ? path : path.substring(separator + 1);
        }
    }

    private static final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(
                JTree tree, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(
                tree, value, selected, expanded, leaf, row, hasFocus);
            Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
            if (userObject instanceof Entry entry) {
                setText(entry.toString());
                setForeground(selected ? IdeTheme.TEXT
                    : entry.directory() ? IdeTheme.TEXT : IdeTheme.MUTED);
            }
            return this;
        }
    }
}
