package com.badlogic.pacman.common;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class GameState implements Serializable {
    private final Map<String, PlayerState> players;
    private final int[][] maze;
    private final String localPlayerId;
    private final List<Coin> coins;
    private final List<Ghost> ghosts;

    public GameState(Map<String, PlayerState> players, int[][] maze, String localPlayerId, List<Coin> coins, List<Ghost> ghosts) {
        this.players = players;
        this.maze = maze;
        this.localPlayerId = localPlayerId;
        this.coins = coins;
        this.ghosts = ghosts;
    }

    public Map<String, PlayerState> getPlayers() { return players; }
    public int[][] getMaze() { return maze; }
    public String getLocalPlayerId() { return localPlayerId; }
    public List<Coin> getCoins() { return coins; }
    public List<Ghost> getGhosts() { return ghosts; }

    public static class PlayerState implements Serializable {
        private final int x, y;
        private final int score;
        private final String direction;
        private final float red, green, blue;

        public PlayerState(int x, int y, int score, String direction, float red, float green, float blue) {
            this.x = x;
            this.y = y;
            this.score = score;
            this.direction = direction;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        public int getX() { return x; }
        public int getY() { return y; }
        public int getScore() { return score; }
        public String getDirection() { return direction; }
        public float getRed() { return red; }
        public float getGreen() { return green; }
        public float getBlue() { return blue; }
    }
}