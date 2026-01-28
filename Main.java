// TetrisGame.java
// Single-file Tetris (Swing) with SQLite saves/leaderboard.
// Save as TetrisGame.java

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/* ===================== TetrisGame (main) ===================== */
public class Main extends JFrame {
    public static final String DB_FILE = "tetris.db";

    private CardLayout cardLayout;
    private JPanel cards;
    private MainMenu menu;
    private NewGamePanel newGamePanel;
    private PlayPanel playPanel;
    private SaveManager saveManager;

    public Main() {
        super("TetrisGame");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);

        try {
            SaveManager.ensureDriver();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null,
                    "SQLite JDBC driver não encontrado. Coloque sqlite-jdbc jar no classpath.\n" + ex.getMessage(),
                    "Driver ausente", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        saveManager = new SaveManager(DB_FILE);

        cardLayout = new CardLayout();
        cards = new JPanel(cardLayout);

        menu = new MainMenu();
        newGamePanel = new NewGamePanel();
        playPanel = new PlayPanel(saveManager, this::showMenu);

        cards.add(menu, "menu");
        cards.add(newGamePanel, "newgame");
        cards.add(playPanel, "play");

        add(cards);
        pack();
        setLocationRelativeTo(null);

        // wiring
        menu.onNew = () -> cardLayout.show(cards, "newgame");
        menu.onLoad = this::handleLoad;
        menu.onExit = () -> System.exit(0);

        newGamePanel.onStart = (name, diff) -> {
            playPanel.startNewGame(name, diff);
            cardLayout.show(cards, "play");
        };
        newGamePanel.onBack = () -> cardLayout.show(cards, "menu");

        cardLayout.show(cards, "menu");
        setVisible(true);
    }

    private void handleLoad() {
        try {
            List<SaveInfo> saves = saveManager.listSaves();
            if (saves.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Nenhum save encontrado.", "Carregar Save", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String[] opts = new String[saves.size()];
            for (int i = 0; i < saves.size(); i++) {
                SaveInfo s = saves.get(i);
                opts[i] = String.format("%d | %s | %s | %s", s.id, s.player, s.difficulty, Instant.ofEpochMilli(s.when));
            }
            String sel = (String) JOptionPane.showInputDialog(this, "Escolha um save:", "Carregar Save",
                    JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
            if (sel != null) {
                int id = Integer.parseInt(sel.split(" ")[0]);
                GameState gs = saveManager.loadGame(id);
                if (gs != null) {
                    playPanel.startFromState(gs);
                    cardLayout.show(cards, "play");
                } else {
                    JOptionPane.showMessageDialog(this, "Erro ao carregar save.", "Erro", JOptionPane.ERROR_MESSAGE);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Erro ao listar saves: " + ex.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showMenu() { cardLayout.show(cards, "menu"); }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main());
    }
}

/* ===================== UI: Main Menu ===================== */
class MainMenu extends JPanel {
    Runnable onNew;
    Runnable onLoad;
    Runnable onExit;

    public MainMenu() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(1000, 640));

        JLabel title = new JLabel("TetrisGame", SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 48));
        add(title, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridLayout(4, 1, 10, 10));
        center.setBorder(BorderFactory.createEmptyBorder(80, 300, 80, 300));
        JButton bNew = new JButton("Novo Jogo");
        JButton bLoad = new JButton("Carregar Save");
        JButton bLeaderboard = new JButton("Leaderboard");
        JButton bExit = new JButton("Sair");

        bNew.setFont(new Font("SansSerif", Font.PLAIN, 24));
        bLoad.setFont(new Font("SansSerif", Font.PLAIN, 24));
        bLeaderboard.setFont(new Font("SansSerif", Font.PLAIN, 20));
        bExit.setFont(new Font("SansSerif", Font.PLAIN, 24));

        center.add(bNew);
        center.add(bLoad);
        center.add(bLeaderboard);
        center.add(bExit);

        add(center, BorderLayout.CENTER);

        bNew.addActionListener(e -> { if (onNew != null) onNew.run(); });
        bLoad.addActionListener(e -> { if (onLoad != null) onLoad.run(); });
        bExit.addActionListener(e -> { if (onExit != null) onExit.run(); });
        bLeaderboard.addActionListener(e -> {
            try {
                SaveManager sm = new SaveManager(Main.DB_FILE);
                List<ScoreInfo> top = sm.topScores(10);
                StringBuilder sb = new StringBuilder();
                if (top.isEmpty()) sb.append("Nenhum score salvo.\n");
                else {
                    int r = 1;
                    for (ScoreInfo si : top) {
                        sb.append(String.format("%d) %s — %d pts — %s\n", r++, si.player, si.score, Instant.ofEpochMilli(si.when)));
                    }
                }
                JTextArea ta = new JTextArea(sb.toString());
                ta.setEditable(false);
                JOptionPane.showMessageDialog(this, new JScrollPane(ta), "Leaderboard", JOptionPane.PLAIN_MESSAGE);
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "Erro leaderboard: " + ex.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}

/* ===================== UI: New Game Panel ===================== */
enum Difficulty { EASY, MEDIUM, HARD }

class NewGamePanel extends JPanel {
    interface StartCallback { void start(String player, Difficulty diff); }
    StartCallback onStart;
    Runnable onBack;

    public NewGamePanel() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(1000, 640));

        JLabel title = new JLabel("Novo Jogo - Escolha Dificuldade", SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 28));
        add(title, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridLayout(5, 1, 6, 6));
        center.setBorder(BorderFactory.createEmptyBorder(20, 300, 20, 300));
        JLabel lblName = new JLabel("Nome do jogador:");
        JTextField nameField = new JTextField("Jogador");
        nameField.setFont(new Font("SansSerif", Font.PLAIN, 18));

        JButton bEasy = new JButton("EASY");
        JButton bMed = new JButton("MEDIUM");
        JButton bHard = new JButton("HARD");
        bEasy.setFont(new Font("SansSerif", Font.PLAIN, 20));
        bMed.setFont(new Font("SansSerif", Font.PLAIN, 20));
        bHard.setFont(new Font("SansSerif", Font.PLAIN, 20));

        center.add(lblName);
        center.add(nameField);
        center.add(bEasy);
        center.add(bMed);
        center.add(bHard);

        add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        JButton bBack = new JButton("Voltar");
        bottom.add(bBack);
        add(bottom, BorderLayout.SOUTH);

        bBack.addActionListener(e -> { if (onBack != null) onBack.run(); });

        bEasy.addActionListener(e -> {
            if (onStart != null) onStart.start(nameField.getText().trim(), Difficulty.EASY);
        });
        bMed.addActionListener(e -> {
            if (onStart != null) onStart.start(nameField.getText().trim(), Difficulty.MEDIUM);
        });
        bHard.addActionListener(e -> {
            if (onStart != null) onStart.start(nameField.getText().trim(), Difficulty.HARD);
        });
    }
}

/* ===================== UI: Play Panel ===================== */
class PlayPanel extends JPanel {
    private GameBoard board;
    private JPanel side;
    private JLabel lblPlayer, lblScore, lblLevel, lblLines, lblTimer, lblErrors;
    private JButton btnSave, btnPause, btnMenu, btnSaveScore;
    private SaveManager saveManager;
    private Runnable onBack;

    public PlayPanel(SaveManager saveManager, Runnable onBack) {
        this.saveManager = saveManager;
        this.onBack = onBack;
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(1000, 640));
        board = new GameBoard(this);
        add(board, BorderLayout.CENTER);

        side = new JPanel(null);
        side.setPreferredSize(new Dimension(260, 640));
        add(side, BorderLayout.EAST);

        lblPlayer = new JLabel("Jogador: -"); lblPlayer.setBounds(10,10,240,25); side.add(lblPlayer);
        lblScore = new JLabel("Score: 0"); lblScore.setBounds(10,45,240,25); side.add(lblScore);
        lblLevel = new JLabel("Level: 1"); lblLevel.setBounds(10,80,240,25); side.add(lblLevel);
        lblLines = new JLabel("Lines: 0"); lblLines.setBounds(10,115,240,25); side.add(lblLines);
        lblTimer = new JLabel("Timer: -"); lblTimer.setBounds(10,150,240,25); side.add(lblTimer);
        lblErrors = new JLabel("Erros: 0/3"); lblErrors.setBounds(10,185,240,25); side.add(lblErrors);

        JLabel lblNext = new JLabel("Next preview (no HARD):"); lblNext.setBounds(10,230,240,20); side.add(lblNext);

        btnSave = new JButton("Salvar Jogo"); btnSave.setBounds(10,520,120,30); side.add(btnSave);
        btnPause = new JButton("Pause"); btnPause.setBounds(130,520,120,30); side.add(btnPause);
        btnMenu = new JButton("Menu"); btnMenu.setBounds(10,560,120,30); side.add(btnMenu);
        btnSaveScore = new JButton("Salvar Score"); btnSaveScore.setBounds(130,560,120,30); side.add(btnSaveScore);

        btnSave.addActionListener(e -> saveGame());
        btnPause.addActionListener(e -> togglePause());
        btnMenu.addActionListener(e -> {
            board.stopAllTimers();
            if (onBack != null) onBack.run();
        });
        btnSaveScore.addActionListener(e -> {
            try {
                saveManager.insertScore(board.getPlayerName(), board.getScore(), board.getDifficulty());
                JOptionPane.showMessageDialog(this, "Score salvo no leaderboard.");
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "Erro ao salvar score: " + ex.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public void startNewGame(String player, Difficulty diff) {
        removeAll();
        setLayout(new BorderLayout());
        board = new GameBoard(this);
        add(board, BorderLayout.CENTER);
        add(side, BorderLayout.EAST);
        revalidate(); repaint();
        board.startNew(player, diff);
        board.requestFocusInWindow(); // ensure key bindings respond
        updateHUD();
    }

    public void startFromState(GameState gs) {
        removeAll();
        setLayout(new BorderLayout());
        board = new GameBoard(this);
        add(board, BorderLayout.CENTER);
        add(side, BorderLayout.EAST);
        revalidate(); repaint();
        board.loadState(gs);
        board.requestFocusInWindow();
        updateHUD();
    }

    void saveGame() {
        try {
            GameState gs = board.toGameState();
            int id = saveManager.saveGame(gs);
            JOptionPane.showMessageDialog(this, "Jogo salvo (id=" + id + ")");
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Erro ao salvar: " + ex.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    void togglePause() {
        if (board.isHardMode()) {
            JOptionPane.showMessageDialog(this, "Pause não disponível em HARD.");
            return;
        }
        board.togglePause();
        updateHUD();
    }

    public void updateHUD() {
        SwingUtilities.invokeLater(() -> {
            lblPlayer.setText("Jogador: " + board.getPlayerName());
            lblScore.setText("Score: " + board.getScore());
            lblLevel.setText("Level: " + board.getLevel());
            lblLines.setText("Lines: " + board.getLinesCleared());
            lblTimer.setText("Timer: " + board.getTimerDisplay());
            lblErrors.setText("Erros: " + board.getErrors() + "/3");
            btnPause.setVisible(!board.isHardMode());
        });
    }
}

/* ===================== GameBoard (logic + rendering) ===================== */
class GameBoard extends JPanel {
    private static final int CELL = 28;
    private static final int COLS = 10;
    private static final int ROWS = 20;
    private final int WIDTH = COLS * CELL;
    private final int HEIGHT = ROWS * CELL;

    private Color[][] grid;
    private Tetromino current;
    private Tetromino next;
    private int curRow, curCol;
    private boolean gameOver = false;
    private boolean paused = false;

    private String player = "Jogador";
    private int score = 0;
    private int level = 1;
    private int lines = 0;

    private Difficulty difficulty = Difficulty.EASY;
    private javax.swing.Timer fallTimer;
    private int baseDelay = 500;
    private Random rand = new Random();

    // HARD specifics
    private Timer perPieceTimer;
    private int perPieceLeft = 0;
    private int hardErrors = 0;
    private final int HARD_MAX_ERRORS = 3;

    private PlayPanel parent;

    public GameBoard(PlayPanel parent) {
        this.parent = parent;
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(Color.BLACK);
        setFocusable(true);

        setupKeyBindings();
    }

    private void setupKeyBindings() {
        InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "moveLeft");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "moveRight");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "softDrop");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "rotate");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "hardDrop");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), "pause");

        am.put("moveLeft", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (canAcceptInput()) { tryMoveSafe(current, curRow, curCol - 1); refresh(); }
            }
        });
        am.put("moveRight", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (canAcceptInput()) { tryMoveSafe(current, curRow, curCol + 1); refresh(); }
            }
        });
        am.put("softDrop", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (canAcceptInput()) {
                    if (tryMoveSafe(current, curRow + 1, curCol)) { curRow++; score += 1; refresh(); }
                }
            }
        });
        am.put("rotate", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (canAcceptInput()) { rotateCurrent(); refresh(); }
            }
        });
        am.put("hardDrop", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (canAcceptInput()) {
                    while (tryMoveSafe(current, curRow + 1, curCol)) { curRow++; score += 2; }
                    lockPiece();
                    clearLines();
                    spawnPiece();
                    refresh();
                }
            }
        });
        am.put("pause", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (difficulty == Difficulty.HARD) return;
                togglePause();
                refresh();
            }
        });
    }

    private boolean canAcceptInput() {
        if (gameOver) return false;
        if (paused) return false;
        if (current == null) return false;
        return true;
    }

    private void refresh() {
        parent.updateHUD();
        repaint();
    }

    // Safe wrapper for tryMove that checks current not null
    private boolean tryMoveSafe(Tetromino t, int nr, int nc) {
        if (t == null) return false;
        return tryMove(t.shape, nr, nc);
    }

    // start fresh
    public void startNew(String playerName, Difficulty diff) {
        this.player = (playerName == null || playerName.isEmpty()) ? "Jogador" : playerName;
        this.difficulty = diff;
        initState();
        requestFocusInWindow();
    }

    // load saved state
    public void loadState(GameState gs) {
        try {
            this.player = gs.player;
            this.difficulty = gs.difficulty;
            this.score = gs.score;
            this.level = gs.level;
            this.lines = gs.lines;
            this.hardErrors = gs.hardErrors;
            this.grid = deepCopyGrid(gs.grid);
            // clone tetromino shapes to avoid shared arrays
            this.current = (gs.current != null) ? gs.current.copy() : null;
            this.next = (gs.next != null) ? gs.next.copy() : null;
            this.curRow = gs.curRow; this.curCol = gs.curCol;
            this.gameOver = gs.gameOver;
            setupTimers();
            repaint();
            requestFocusInWindow();
        } catch (Exception ex) {
            ex.printStackTrace();
            initState();
        }
    }

    // export state
    public GameState toGameState() {
        GameState gs = new GameState();
        gs.player = player;
        gs.difficulty = difficulty;
        gs.score = score;
        gs.level = level;
        gs.lines = lines;
        gs.hardErrors = hardErrors;
        gs.grid = deepCopyGrid(grid);
        gs.current = (current != null) ? current.copy() : null;
        gs.next = (next != null) ? next.copy() : null;
        gs.curRow = curRow; gs.curCol = curCol;
        gs.gameOver = gameOver;
        return gs;
    }

    private void initState() {
        grid = new Color[ROWS][COLS];
        next = Tetromino.random(rand);
        score = 0; level = 1; lines = 0; hardErrors = 0; gameOver = false; paused = false;
        spawnPiece();
        setupTimers();
        repaint();
    }

    private void setupTimers() {
        if (fallTimer != null) fallTimer.stop();
        switch (difficulty) {
            case EASY: baseDelay = 600; break;
            case MEDIUM: baseDelay = 350; break;
            case HARD: baseDelay = 180; break;
        }
        fallTimer = new javax.swing.Timer(baseDelay, e -> {
            if (!paused && !gameOver) stepDown();
        });
        fallTimer.start();

        stopPerPieceTimer();
        if (difficulty == Difficulty.HARD) startPerPieceTimer(30);
    }

    public void stopAllTimers() {
        if (fallTimer != null) fallTimer.stop();
        stopPerPieceTimer();
    }

    private void startPerPieceTimer(int seconds) {
        stopPerPieceTimer();
        perPieceLeft = seconds;
        perPieceTimer = new Timer(true);
        perPieceTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (gameOver || paused) return;
                perPieceLeft--;
                parent.updateHUD();
                if (perPieceLeft <= 0) {
                    SwingUtilities.invokeLater(() -> {
                        lockPiece();
                        clearLines();
                        spawnPiece();
                        hardErrors++;
                        if (hardErrors >= HARD_MAX_ERRORS) {
                            gameOver = true;
                            stopAllTimers();
                            JOptionPane.showMessageDialog(GameBoard.this, "Você errou 3 vezes. Game Over.", "Game Over", JOptionPane.INFORMATION_MESSAGE);
                        }
                        parent.updateHUD();
                    });
                }
            }
        }, 1000, 1000);
    }

    private void stopPerPieceTimer() {
        if (perPieceTimer != null) {
            perPieceTimer.cancel();
            perPieceTimer = null;
        }
    }

    // count empty top rows of a shape
    private int topEmptyRows(int[][] shape) {
        int empty = 0;
        for (int i = 0; i < shape.length; i++) {
            boolean allZero = true;
            for (int j = 0; j < shape[i].length; j++) {
                if (shape[i][j] != 0) { allZero = false; break; }
            }
            if (allZero) empty++; else break;
        }
        return empty;
    }

    private void spawnPiece() {
        // clone next to current to avoid sharing internal arrays
        current = (next != null) ? next.copy() : Tetromino.random(rand);
        next = Tetromino.random(rand);

        // compute spawn col so piece is centered based on shape width
        int shapeWidth = current.shape[0].length;
        int emptyTop = topEmptyRows(current.shape);
        curRow = -emptyTop;
        curCol = (COLS - shapeWidth) / 2;

        // If the current shape exceeds board horizontally, adjust curCol
        if (curCol < -2) curCol = -2;

        // If initial spawn collides because grid filled near top, try small shifts (left/right),
        // otherwise game over (board is full)
        if (collides(current.shape, curRow, curCol)) {
            boolean placed = false;
            // try shifting left/right within small range
            for (int shift = 1; shift <= 2 && !placed; shift++) {
                if (!collides(current.shape, curRow, curCol - shift)) { curCol -= shift; placed = true; break; }
                if (!collides(current.shape, curRow, curCol + shift)) { curCol += shift; placed = true; break; }
            }
            if (!placed) {
                gameOver = true;
                stopAllTimers();
                JOptionPane.showMessageDialog(this, "Game Over!", "Game Over", JOptionPane.INFORMATION_MESSAGE);
                parent.updateHUD();
                repaint();
                return;
            }
        } else {
            if (difficulty == Difficulty.HARD) startPerPieceTimer(30);
        }
        parent.updateHUD();
        repaint();
    }

    private boolean collides(int[][] shape, int r, int c) {
        for (int i = 0; i < shape.length; i++) {
            for (int j = 0; j < shape[i].length; j++) {
                if (shape[i][j] != 0) {
                    int gr = r + i, gc = c + j;
                    // horizontal out-of-bounds or below board -> collision
                    if (gc < 0 || gc >= COLS || gr >= ROWS) return true;
                    // if inside visible board, check occupancy
                    if (gr >= 0) {
                        if (grid[gr][gc] != null) return true;
                    }
                    // if gr < 0 (above top) ignore occupancy (there is no stored grid above top)
                }
            }
        }
        return false;
    }

    private void stepDown() {
        if (current == null) return;
        if (!tryMove(current.shape, curRow + 1, curCol)) {
            lockPiece();
            clearLines();
            spawnPiece();
        } else {
            curRow++;
        }
        parent.updateHUD();
        repaint();
    }

    private boolean tryMove(int[][] shape, int nr, int nc) {
        if (!collides(shape, nr, nc)) {
            curRow = nr; curCol = nc; return true;
        }
        return false;
    }

    /**
     * Lock the current piece into the grid.
     * If there is an overlap with existing blocks we will attempt to shift the piece
     * upward until it fits. Only if we cannot resolve the overlap we set game over.
     */
    private void lockPiece() {
        if (current == null) return;
        Color col = current.color;
        int[][] s = current.shape;

        // check conflict
        boolean conflict = hasConflictAt(curRow, curCol, s);

        if (conflict) {
            // try to move piece up until no conflict (reasonable attempt to avoid overlap)
            int attempts = 0;
            int maxUp = current.shape.length + 2 + Math.abs(curRow); // allow moving into negative rows
            while (conflict && attempts <= maxUp) {
                curRow--;
                attempts++;
                conflict = hasConflictAt(curRow, curCol, s);
            }
            if (conflict) {
                // couldn't resolve -> board truly full at those columns/rows -> game over
                gameOver = true;
                stopAllTimers();
                JOptionPane.showMessageDialog(this, "Conflito ao fixar peça. Game Over.", "Game Over", JOptionPane.INFORMATION_MESSAGE);
                parent.updateHUD();
                repaint();
                return;
            }
        }

        // finally place blocks (only inside board)
        for (int i = 0; i < s.length; i++) {
            for (int j = 0; j < s[i].length; j++) {
                if (s[i][j] != 0) {
                    int gr = curRow + i, gc = curCol + j;
                    if (gr >= 0 && gr < ROWS && gc >= 0 && gc < COLS) {
                        grid[gr][gc] = col;
                    }
                }
            }
        }
    }

    private boolean hasConflictAt(int baseRow, int baseCol, int[][] s) {
        for (int i = 0; i < s.length; i++) {
            for (int j = 0; j < s[i].length; j++) {
                if (s[i][j] != 0) {
                    int gr = baseRow + i, gc = baseCol + j;
                    if (gr >= 0 && gr < ROWS && gc >= 0 && gc < COLS) {
                        if (grid[gr][gc] != null) return true;
                    } else if (gc < 0 || gc >= COLS || gr >= ROWS) {
                        // Out of horizontal bounds or below board is treated as conflict/invalid position
                        return true;
                    }
                    // if gr < 0 (above top) ignore occupancy
                }
            }
        }
        return false;
    }

    private void clearLines() {
        int cleared = 0;
        for (int r = ROWS - 1; r >= 0; r--) {
            boolean full = true;
            for (int c = 0; c < COLS; c++) if (grid[r][c] == null) { full = false; break; }
            if (full) {
                cleared++;
                for (int rr = r; rr > 0; rr--) System.arraycopy(grid[rr-1], 0, grid[rr], 0, COLS);
                for (int cc = 0; cc < COLS; cc++) grid[0][cc] = null;
                r++; // re-check same index after shift
            }
        }
        if (cleared > 0) {
            lines += cleared;
            score += scoreForLines(cleared);
            level = 1 + lines / 10;
            int newDelay = Math.max(60, baseDelay - (level - 1) * 30);
            if (fallTimer != null) fallTimer.setDelay(newDelay);
        }
    }

    private int scoreForLines(int n) {
        switch (n) {
            case 1: return 100 * level;
            case 2: return 300 * level;
            case 3: return 500 * level;
            case 4: return 800 * level;
            default: return n * 200 * level;
        }
    }

    private void rotateCurrent() {
        if (current == null) return;
        int[][] rot = rotateMatrix(current.shape);
        // improved simple kicks: try small lateral shifts as well
        int[][] kicks = {{0,0},{0,-1},{0,1},{0,-2},{0,2},{-1,0},{1,0}};
        for (int[] k : kicks) {
            int nr = curRow + k[0], nc = curCol + k[1];
            if (!collides(rot, nr, nc)) {
                current.shape = rot; curRow = nr; curCol = nc; return;
            }
        }
    }

    private int[][] rotateMatrix(int[][] mat) {
        int n = mat.length, m = mat[0].length;
        int[][] res = new int[m][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++)
                res[j][n-1-i] = mat[i][j];
        return res;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        // background
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // draw fixed blocks
        if (grid != null) {
            for (int r = 0; r < ROWS; r++)
                for (int c = 0; c < COLS; c++)
                    if (grid[r][c] != null) drawCell(g, c*CELL, r*CELL, grid[r][c], CELL);
        }

        // draw current piece (only where inside board)
        if (!gameOver && current != null) {
            int[][] s = current.shape;
            for (int i = 0; i < s.length; i++)
                for (int j = 0; j < s[i].length; j++)
                    if (s[i][j] != 0) {
                        int drawR = curRow + i;
                        int drawC = curCol + j;
                        if (drawR >= 0 && drawR < ROWS && drawC >= 0 && drawC < COLS)
                            // draw only on visible board. Rendering may draw current on top of fixed if they overlap,
                            // but lockPiece now tries to prevent overlap; any remaining overlap means board full.
                            drawCell(g, (drawC)*CELL, (drawR)*CELL, current.color, CELL);
                    }
        }

        // grid lines
        g.setColor(Color.GRAY);
        for (int x = 0; x <= WIDTH; x += CELL) g.drawLine(x, 0, x, HEIGHT);
        for (int y = 0; y <= HEIGHT; y += CELL) g.drawLine(0, y, WIDTH, y);

        // next preview (small) if not HARD
        if (difficulty != Difficulty.HARD) {
            g.setColor(Color.WHITE);
            g.drawString("Next:", WIDTH - 80, 20);
            if (next != null) {
                int sx = WIDTH - 80, sy = 30;
                int[][] s = next.shape;
                int half = CELL/2;
                for (int i = 0; i < s.length; i++)
                    for (int j = 0; j < s[i].length; j++)
                        if (s[i][j] != 0) drawCell(g, sx + j*half, sy + i*half, next.color, half);
            }
        }

        // footer text
        g.setColor(Color.WHITE);
        g.drawString("Player: " + player, 8, HEIGHT - 6);
    }

    private void drawCell(Graphics g, int x, int y, Color color, int size) {
        g.setColor(color);
        g.fillRect(x+1, y+1, size-2, size-2);
        g.setColor(color.brighter());
        g.drawRect(x+1, y+1, size-2, size-2);
    }

    // Public control methods
    public void togglePause() {
        if (difficulty == Difficulty.HARD) return;
        paused = !paused;
    }

    public boolean isHardMode() { return difficulty == Difficulty.HARD; }

    // getters for HUD
    public String getPlayerName() { return player; }
    public int getScore() { return score; }
    public int getLevel() { return level; }
    public int getLinesCleared() { return lines; }
    public String getTimerDisplay() { return (difficulty == Difficulty.HARD) ? (perPieceLeft + "s") : "-"; }
    public int getErrors() { return hardErrors; }
    public Difficulty getDifficulty() { return difficulty; }

    // helpers
    private Color[][] deepCopyGrid(Color[][] g) {
        if (g == null) return null;
        Color[][] out = new Color[g.length][g[0].length];
        for (int i = 0; i < g.length; i++) System.arraycopy(g[i], 0, out[i], 0, g[i].length);
        return out;
    }
}

/* ===================== Tetromino ===================== */
class Tetromino implements Serializable {
    public int[][] shape;
    public Color color;

    private Tetromino(int[][] shape, Color color) {
        this.shape = new int[shape.length][shape[0].length];
        for (int i = 0; i < shape.length; i++) System.arraycopy(shape[i], 0, this.shape[i], 0, shape[i].length);
        this.color = color;
    }

    public Tetromino copy() {
        int[][] s = new int[this.shape.length][this.shape[0].length];
        for (int i = 0; i < this.shape.length; i++) System.arraycopy(this.shape[i], 0, s[i], 0, this.shape[i].length);
        return new Tetromino(s, this.color);
    }

    public static Tetromino random(Random r) {
        switch (r.nextInt(7)) {
            case 0: return createI();
            case 1: return createJ();
            case 2: return createL();
            case 3: return createO();
            case 4: return createS();
            case 5: return createT();
            default: return createZ();
        }
    }

    private static Tetromino createI() {
        int[][] s = {{0,0,0,0},{1,1,1,1},{0,0,0,0},{0,0,0,0}};
        return new Tetromino(s, Color.CYAN);
    }
    private static Tetromino createJ() {
        int[][] s = {{1,0,0},{1,1,1},{0,0,0}};
        return new Tetromino(s, Color.BLUE);
    }
    private static Tetromino createL() {
        int[][] s = {{0,0,1},{1,1,1},{0,0,0}};
        return new Tetromino(s, Color.ORANGE);
    }
    private static Tetromino createO() {
        int[][] s = {{1,1},{1,1}};
        return new Tetromino(s, Color.YELLOW);
    }
    private static Tetromino createS() {
        int[][] s = {{0,1,1},{1,1,0},{0,0,0}};
        return new Tetromino(s, Color.GREEN);
    }
    private static Tetromino createT() {
        int[][] s = {{0,1,0},{1,1,1},{0,0,0}};
        return new Tetromino(s, Color.MAGENTA);
    }
    private static Tetromino createZ() {
        int[][] s = {{1,1,0},{0,1,1},{0,0,0}};
        return new Tetromino(s, Color.RED);
    }
}

/* ===================== GameState ===================== */
class GameState implements Serializable {
    public String player;
    public Difficulty difficulty;
    public int score;
    public int level;
    public int lines;
    public int hardErrors;
    public Color[][] grid;
    public Tetromino current;
    public Tetromino next;
    public int curRow, curCol;
    public boolean gameOver;
}

/* ===================== SaveManager (SQLite) ===================== */
class SaveInfo { int id; String player; Difficulty difficulty; long when; }
class ScoreInfo { int id; String player; int score; long when; }

class SaveManager {
    private final String url;

    public SaveManager(String dbFile) {
        url = "jdbc:sqlite:" + dbFile;
        try { ensureDriver(); ensureTables(); } catch (Exception ex) { ex.printStackTrace(); }
    }

    public static void ensureDriver() throws Exception {
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException ex) { throw new Exception("SQLite JDBC driver não encontrado no classpath."); }
    }

    private Connection conn() throws SQLException { return DriverManager.getConnection(url); }

    private void ensureTables() throws SQLException {
        try (Connection c = conn()) {
            String tSaves = "CREATE TABLE IF NOT EXISTS saves (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT, difficulty TEXT, created INTEGER, state BLOB)";
            String tScores = "CREATE TABLE IF NOT EXISTS scores (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT, score INTEGER, difficulty TEXT, created INTEGER)";
            try (Statement s = c.createStatement()) { s.execute(tSaves); s.execute(tScores); }
        }
    }

    public int saveGame(GameState gs) throws Exception {
        byte[] blob = serialize(gs);
        try (Connection c = conn()) {
            String sql = "INSERT INTO saves (player, difficulty, created, state) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, gs.player);
                ps.setString(2, gs.difficulty.name());
                ps.setLong(3, System.currentTimeMillis());
                ps.setBytes(4, blob);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { if (rs.next()) return rs.getInt(1); }
            }
        }
        return -1;
    }

    public List<SaveInfo> listSaves() throws Exception {
        List<SaveInfo> out = new ArrayList<>();
        try (Connection c = conn()) {
            String sql = "SELECT id, player, difficulty, created FROM saves ORDER BY created DESC";
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SaveInfo si = new SaveInfo();
                    si.id = rs.getInt("id");
                    si.player = rs.getString("player");
                    si.difficulty = Difficulty.valueOf(rs.getString("difficulty"));
                    si.when = rs.getLong("created");
                    out.add(si);
                }
            }
        }
        return out;
    }

    public GameState loadGame(int id) throws Exception {
        try (Connection c = conn()) {
            String sql = "SELECT state FROM saves WHERE id = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        byte[] blob = rs.getBytes("state");
                        return (GameState) deserialize(blob);
                    }
                }
            }
        }
        return null;
    }

    public void insertScore(String player, int score, Difficulty diff) throws Exception {
        try (Connection c = conn()) {
            String sql = "INSERT INTO scores (player, score, difficulty, created) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, player);
                ps.setInt(2, score);
                ps.setString(3, diff.name());
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }
    }

    public List<ScoreInfo> topScores(int limit) throws Exception {
        List<ScoreInfo> out = new ArrayList<>();
        try (Connection c = conn()) {
            String sql = "SELECT id, player, score, created FROM scores ORDER BY score DESC LIMIT ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ScoreInfo si = new ScoreInfo();
                        si.id = rs.getInt("id");
                        si.player = rs.getString("player");
                        si.score = rs.getInt("score");
                        si.when = rs.getLong("created");
                        out.add(si);
                    }
                }
            }
        }
        return out;
    }

    private byte[] serialize(Object o) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(o); oos.flush(); return baos.toByteArray();
        }
    }
    private Object deserialize(byte[] b) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(b); ObjectInputStream ois = new ObjectInputStream(bais)) {
            return ois.readObject();
        }
    }
}
