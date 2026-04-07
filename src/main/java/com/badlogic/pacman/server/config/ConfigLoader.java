package com.badlogic.pacman.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.badlogic.pacman.common.Coin;
import com.badlogic.pacman.common.Ghost;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ConfigLoader {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static MazeConfig loadMazeConfig() throws Exception {
        String configPath = "/config/maze.json";

        System.out.println("Loading maze config from: " + configPath);

        try (InputStream is = ConfigLoader.class.getResourceAsStream(configPath)) {
            if (is == null) {
                throw new RuntimeException("Maze config file not found at: " + configPath +
                        "\nPlease ensure file exists at: src/main/resources/config/maze.json");
            }
            MazeConfig config = objectMapper.readValue(is, MazeConfig.class);
            System.out.println("Maze config loaded successfully!");
            System.out.println("Maze size: " + config.getMaze().length + "x" + config.getMaze()[0].length);
            return config;
        }
    }

    public static GhostsConfig loadGhostsConfig() throws Exception {
        String configPath = "/config/ghosts.json";

        System.out.println("Loading ghosts config from: " + configPath);

        try (InputStream is = ConfigLoader.class.getResourceAsStream(configPath)) {
            if (is == null) {
                throw new RuntimeException("Ghosts config file not found at: " + configPath +
                        "\nPlease ensure file exists at: src/main/resources/config/ghosts.json");
            }
            GhostsConfig config = objectMapper.readValue(is, GhostsConfig.class);
            System.out.println("Ghosts config loaded successfully!");
            System.out.println("Ghosts count: " + config.getGhosts().size());
            return config;
        }
    }

    /**
     * Загружает монетки из конфигурации.
     * Если в конфиге нет списка монеток или он пустой,
     * то автоматически расставляет монетки на всех свободных клетках лабиринта (где значение 0)
     */
    public static List<Coin> loadCoinsFromConfig(MazeConfig config) {
        List<Coin> coins = new ArrayList<>();

        // Проверяем, есть ли явно указанные монетки в конфиге
        boolean hasExplicitCoins = config.getCoins() != null && !config.getCoins().isEmpty();

        if (hasExplicitCoins) {
            // Если есть явно указанные монетки, используем их
            System.out.println("Loading " + config.getCoins().size() + " coins from config file");
            for (MazeConfig.CoinPosition pos : config.getCoins()) {
                coins.add(new Coin(pos.getX(), pos.getY()));
            }
        } else {
            // Если нет монеток в конфиге, расставляем их на всех свободных клетках
            System.out.println("No coins found in config, auto-placing coins on all free cells...");
            int[][] maze = config.getMaze();
            int coinCount = 0;

            for (int y = 0; y < maze.length; y++) {
                for (int x = 0; x < maze[y].length; x++) {
                    // Если клетка свободна (0) - ставим монетку
                    if (maze[y][x] == 0) {
                        coins.add(new Coin(x, y));
                        coinCount++;
                    }
                }
            }

            System.out.println("Auto-placed " + coinCount + " coins on all free cells");
        }

        return coins;
    }

    /**
     * Альтернативный метод с возможностью принудительного авто-размещения
     * @param config конфигурация лабиринта
     * @param autoPlaceIfMissing если true - авто-размещение, если false - только из конфига
     */
    public static List<Coin> loadCoinsFromConfig(MazeConfig config, boolean autoPlaceIfMissing) {
        if (!autoPlaceIfMissing) {
            // Только из конфига, без авто-размещения
            List<Coin> coins = new ArrayList<>();
            if (config.getCoins() != null) {
                for (MazeConfig.CoinPosition pos : config.getCoins()) {
                    coins.add(new Coin(pos.getX(), pos.getY()));
                }
            }
            return coins;
        }

        // С авто-размещением
        return loadCoinsFromConfig(config);
    }

    public static List<Ghost> loadGhostsFromConfig(GhostsConfig config) {
        List<Ghost> ghosts = new ArrayList<>();
        if (config.getGhosts() != null) {
            for (GhostsConfig.GhostConfig ghostConfig : config.getGhosts()) {
                Ghost ghost = new Ghost(
                        ghostConfig.getStartX(),
                        ghostConfig.getStartY(),
                        ghostConfig.getSpeed()
                );
                ghosts.add(ghost);
            }
        }
        return ghosts;
    }
}