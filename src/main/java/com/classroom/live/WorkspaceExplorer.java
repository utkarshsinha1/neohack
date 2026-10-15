package com.classroom.live;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;

public final class WorkspaceExplorer extends JPanel {
    private static final Set<String> SKIPPED_DIRECTORIES =
        Set.of(".git", ".idea", ".gradle", "target", "build", "node_modules");

    private final JLabel rootLabel = new JLabel();
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final JTree tree = new JTree(treeModel);
    private final Consumer<Path> openFile;
    private final ConcurrentHashMap<Path, WatchKey> watchedDirectories =
        new ConcurrentHashMap<>();
    private Path workspace;
    private volatile WatchService watchService;

    public WorkspaceExplorer(Path initialWorkspace, Consumer<Path> openFile) {
        super(new BorderLayout(0, 8));
        this.workspace = initialWorkspace.toAbsolutePath().normalize();
        this.openFile = openFile;
        setBackground(IdeTheme.SIDEBAR);
        setPreferredSize(new Dimension(175, 300));
        setMinimumSize(new Dimension(130, 150));
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 1, IdeTheme.BORDER));

        JPanel heading = new JPanel(new BorderLayout(4, 0));
        heading.setBackground(IdeTheme.SIDEBAR);
        heading.setBorder(BorderFactory.createEmptyBorder(11, 12, 2, 8));
        JLabel title = new JLabel("EXPLORER");
        title.setFont(IdeTheme.UI_FONT.deriveFont(java.awt.Font.BOLD, 11f));
        title.setForeground(IdeTheme.MUTED);
        heading.add(title, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 1, 0));
        actions.setOpaque(false);
        JButton newFile = actionButton("+", "New file");
        JButton newFolder = actionButton("▣", "New folder");
        JButton refresh = actionButton("↻", "Refresh");
        JButton openFolder = actionButton("▱", "Open folder");
        newFile.addActionListener(event -> createEntry(false));
        newFolder.addActionListener(event -> createEntry(true));
        refresh.addActionListener(event -> refresh());
        openFolder.addActionListener(event -> chooseWorkspace());
        actions.add(newFile);
        actions.add(newFolder);
        actions.add(refresh);
        actions.add(openFolder);
        heading.add(actions, BorderLayout.EAST);

        rootLabel.setForeground(IdeTheme.TEXT);
        rootLabel.setFont(IdeTheme.UI_FONT.deriveFont(java.awt.Font.BOLD));
        rootLabel.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 8));

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setBackground(IdeTheme.SIDEBAR);
        tree.setForeground(IdeTheme.TEXT);
        tree.setFont(IdeTheme.UI_FONT);
        tree.setRowHeight(24);
        tree.setCellRenderer(new ExplorerRenderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                    if (path == null) {
                        return;
                    }
                    Object value = ((DefaultMutableTreeNode) path.getLastPathComponent())
                        .getUserObject();
                    if (value instanceof FileEntry entry && entry.file().isFile()) {
                        WorkspaceExplorer.this.openFile.accept(entry.file().toPath());
                    }
                }
            }
        });
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(javax.swing.event.TreeExpansionEvent event)
                    throws ExpandVetoException {
                Object node = event.getPath().getLastPathComponent();
                if (node instanceof DefaultMutableTreeNode treeNode
                        && treeNode.getUserObject() instanceof FileEntry entry
                        && entry.directory() && !entry.loaded()) {
                    loadChildren(treeNode, entry);
                }
            }

            @Override
            public void treeWillCollapse(javax.swing.event.TreeExpansionEvent event) {}
        });

        JPanel treeContents = new JPanel(new BorderLayout());
        treeContents.setBackground(IdeTheme.SIDEBAR);
        treeContents.add(rootLabel, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(tree);
        IdeTheme.styleScrollPane(scrollPane);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(IdeTheme.SIDEBAR);
        treeContents.add(scrollPane, BorderLayout.CENTER);

        add(heading, BorderLayout.NORTH);
        add(treeContents, BorderLayout.CENTER);
        setWorkspace(this.workspace);
    }

    public void setWorkspace(Path path) {
        stopWatching();
        workspace = path.toAbsolutePath().normalize();
        rootLabel.setText(workspace.getFileName() == null
            ? workspace.toString() : workspace.getFileName().toString());
        try {
            watchService = workspace.getFileSystem().newWatchService();
            startWatcher(watchService);
        } catch (IOException exception) {
            showError("Could not watch workspace changes: " + exception.getMessage());
        }
        refresh();
    }

    public Path getWorkspace() {
        return workspace;
    }

    public void close() {
        stopWatching();
    }

    public void refresh() {
        Path root = workspace;
        watchedDirectories.forEach((directory, key) -> {
            if (!directory.equals(root) && watchedDirectories.remove(directory, key)) {
                key.cancel();
            }
        });
        rootNode.removeAllChildren();
        rootNode.setUserObject(new FileEntry(root.toFile(), true, false));
        rootNode.add(new DefaultMutableTreeNode("Loading…"));
        treeModel.reload();
        new SwingWorker<List<FileEntry>, Void>() {
            @Override
            protected List<FileEntry> doInBackground() throws IOException {
                registerDirectory(root);
                return listEntries(root);
            }

            @Override
            protected void done() {
                if (!root.equals(workspace)) {
                    return;
                }
                try {
                    rootNode.removeAllChildren();
                    for (FileEntry entry : get()) {
                        addEntryNode(rootNode, entry);
                    }
                    treeModel.reload();
                } catch (Exception exception) {
                    showError("Could not read workspace: " + exception.getMessage());
                }
            }
        }.execute();
    }

    private void loadChildren(DefaultMutableTreeNode node, FileEntry entry) {
        entry.setLoaded(true);
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode("Loading…"));
        treeModel.reload(node);
        new SwingWorker<List<FileEntry>, Void>() {
            @Override
            protected List<FileEntry> doInBackground() throws IOException {
                registerDirectory(entry.file().toPath());
                return listEntries(entry.file().toPath());
            }

            @Override
            protected void done() {
                try {
                    node.removeAllChildren();
                    for (FileEntry child : get()) {
                        addEntryNode(node, child);
                    }
                    treeModel.reload(node);
                } catch (Exception exception) {
                    entry.setLoaded(false);
                    node.removeAllChildren();
                    node.add(new DefaultMutableTreeNode("…"));
                    treeModel.reload(node);
                    showError("Could not read folder: " + exception.getMessage());
                }
            }
        }.execute();
    }

    private static List<FileEntry> listEntries(Path directory) throws IOException {
        List<FileEntry> entries = new ArrayList<>();
        try (var children = Files.list(directory)) {
            children.filter(path -> !Files.isDirectory(path)
                    || !SKIPPED_DIRECTORIES.contains(path.getFileName().toString()))
                .forEach(path -> entries.add(new FileEntry(
                    path.toFile(), Files.isDirectory(path), false
                )));
        }
        entries.sort(Comparator
            .comparing(FileEntry::directory).reversed()
            .thenComparing(entry -> entry.file().getName().toLowerCase(Locale.ROOT)));
        return entries;
    }

    private void startWatcher(WatchService service) {
        Thread watcher = new Thread(() -> {
            while (watchService == service) {
                WatchKey key;
                try {
                    key = service.take();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.nio.file.ClosedWatchServiceException exception) {
                    return;
                }
                Path directory = (Path) key.watchable();
                boolean changed = key.pollEvents().stream().anyMatch(event ->
                    event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                        || event.kind() == StandardWatchEventKinds.ENTRY_DELETE
                );
                if (!key.reset()) {
                    watchedDirectories.remove(directory, key);
                }
                if (changed) {
                    SwingUtilities.invokeLater(() -> refreshNode(directory));
                }
            }
        }, "workspace-file-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void registerDirectory(Path directory) throws IOException {
        WatchService service = watchService;
        if (service == null || watchedDirectories.containsKey(directory)) {
            return;
        }
        WatchKey key = directory.register(
            service,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE
        );
        WatchKey previous = watchedDirectories.putIfAbsent(directory, key);
        if (previous != null) {
            key.cancel();
        }
    }

    private void refreshNode(Path directory) {
        if (!directory.startsWith(workspace)) {
            return;
        }
        DefaultMutableTreeNode node = findNode(rootNode, directory);
        if (node == null) {
            return;
        }
        Object value = node.getUserObject();
        if (value instanceof FileEntry entry && entry.directory()) {
            loadChildren(node, entry);
        }
    }

    private static DefaultMutableTreeNode findNode(
            DefaultMutableTreeNode node, Path target) {
        Object value = node.getUserObject();
        if (value instanceof FileEntry entry
                && entry.file().toPath().toAbsolutePath().normalize().equals(target)) {
            return node;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            Object child = node.getChildAt(index);
            if (child instanceof DefaultMutableTreeNode treeNode) {
                DefaultMutableTreeNode match = findNode(treeNode, target);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private void stopWatching() {
        WatchService service = watchService;
        watchService = null;
        watchedDirectories.clear();
        if (service != null) {
            try {
                service.close();
            } catch (IOException ignored) {
                // The service can be closed while the watcher is waking up.
            }
        }
    }

    private static void addEntryNode(DefaultMutableTreeNode parent, FileEntry entry) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(entry);
        if (entry.directory()) {
            node.add(new DefaultMutableTreeNode("…"));
        }
        parent.add(node);
    }

    private void createEntry(boolean directory) {
        String kind = directory ? "folder" : "file";
        String name = JOptionPane.showInputDialog(
            this, "Name of the new " + kind + ":", "New " + capitalize(kind),
            JOptionPane.PLAIN_MESSAGE
        );
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        Path parent = workspace;
        TreePath selection = tree.getSelectionPath();
        if (selection != null) {
            Object selected = ((DefaultMutableTreeNode) selection.getLastPathComponent())
                .getUserObject();
            if (selected instanceof FileEntry entry) {
                parent = entry.directory() ? entry.file().toPath() : entry.file().toPath().getParent();
            }
        }
        Path destination;
        try {
            Path childName = Path.of(trimmed);
            if (trimmed.isEmpty() || childName.isAbsolute()
                    || childName.getNameCount() != 1
                    || trimmed.equals(".") || trimmed.equals("..")
                    || trimmed.contains("/") || trimmed.contains("\\")) {
                throw new IllegalArgumentException("invalid child name");
            }
            destination = parent.resolve(childName).normalize();
        } catch (RuntimeException exception) {
            showError("Enter a valid name for the new " + kind + ".");
            return;
        }
        Path parentDirectory = parent;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws IOException {
                if (directory) {
                    Files.createDirectory(destination);
                } else {
                    Files.createFile(destination);
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    refreshNode(parentDirectory);
                    if (!directory) {
                        openFile.accept(destination);
                    }
                } catch (Exception exception) {
                    showError("Could not create " + kind + ": " + exception.getMessage());
                }
            }
        }.execute();
    }

    private void chooseWorkspace() {
        JFileChooser chooser = new JFileChooser(workspace.toFile());
        chooser.setDialogTitle("Open Workspace Folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            setWorkspace(chooser.getSelectedFile().toPath());
        }
    }

    private static JButton actionButton(String label, String description) {
        JButton button = new JButton(label);
        IdeTheme.styleButton(button);
        button.setToolTipText(description);
        button.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        return button;
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Explorer", JOptionPane.ERROR_MESSAGE);
    }

    private static final class FileEntry {
        private final File file;
        private final boolean directory;
        private boolean loaded;

        private FileEntry(File file, boolean directory, boolean loaded) {
            this.file = file;
            this.directory = directory;
            this.loaded = loaded;
        }

        private File file() {
            return file;
        }

        private boolean directory() {
            return directory;
        }

        private boolean loaded() {
            return loaded;
        }

        private void setLoaded(boolean loaded) {
            this.loaded = loaded;
        }
    }

    private static final class ExplorerRenderer extends DefaultTreeCellRenderer {
        private ExplorerRenderer() {
            setBackgroundNonSelectionColor(IdeTheme.SIDEBAR);
            setBackgroundSelectionColor(new java.awt.Color(48, 48, 48));
            setBorderSelectionColor(IdeTheme.ACCENT);
            setTextNonSelectionColor(IdeTheme.TEXT);
        }

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(
                tree, value, selected, expanded, leaf, row, hasFocus
            );
            Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
            if (userObject instanceof FileEntry entry) {
                setText(entry.file().getName());
                setIcon(entry.directory()
                    ? UIManager.getIcon(expanded
                        ? "FileView.directoryIcon" : "FileView.directoryIcon")
                    : fileIcon(entry.file().getName()));
                setForeground(selected ? IdeTheme.TEXT
                    : entry.directory() ? IdeTheme.TEXT : IdeTheme.MUTED);
            }
            return this;
        }

        private static javax.swing.Icon fileIcon(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".java")) {
                return new LetterIcon("J", new java.awt.Color(230, 126, 55));
            }
            if (lower.endsWith(".c") || lower.endsWith(".h")
                    || lower.endsWith(".cpp") || lower.endsWith(".cc")
                    || lower.endsWith(".hpp")) {
                return new LetterIcon("C", new java.awt.Color(86, 156, 214));
            }
            if (lower.endsWith(".py")) {
                return new LetterIcon("P", new java.awt.Color(230, 190, 70));
            }
            if (lower.endsWith(".js") || lower.endsWith(".jsx")
                    || lower.endsWith(".mjs")) {
                return new LetterIcon("JS", new java.awt.Color(230, 190, 70));
            }
            if (lower.endsWith(".png") || lower.endsWith(".jpg")
                    || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                    || lower.endsWith(".svg")) {
                return new LetterIcon("◇", new java.awt.Color(180, 140, 220));
            }
            if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                return new LetterIcon("T", IdeTheme.MUTED);
            }
            return UIManager.getIcon("FileView.fileIcon");
        }
    }

    private static final class LetterIcon implements javax.swing.Icon {
        private final String label;
        private final java.awt.Color color;

        private LetterIcon(String label, java.awt.Color color) {
            this.label = label;
            this.color = color;
        }

        @Override
        public void paintIcon(Component component, java.awt.Graphics graphics, int x, int y) {
            graphics.setColor(color);
            graphics.setFont(IdeTheme.UI_FONT.deriveFont(java.awt.Font.BOLD, 9f));
            graphics.drawString(label, x + (label.length() > 1 ? 0 : 3), y + 12);
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }
}
