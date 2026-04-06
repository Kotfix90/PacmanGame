package com.badlogic.pacman.common;

import java.io.Serializable;
import java.util.Map;

public class GameState implements Serializable {
    private static final long serialVersionUID = 1L;

    private Map<String, PlayerState> players;
    private int[][] maze;
    private String localPlayerId;

    public GameState(Map<String, PlayerState> players, int[][] maze, String localPlayerId) {
        this.players = players;
        this.maze = maze;
        this.localPlayerId = localPlayerId;
    }

    public Map<String, PlayerState> getPlayers() { return players; }
    public int[][] getMaze() { return maze; }
    public String getLocalPlayerId() { return localPlayerId; }

    public static class PlayerState implements Serializable {
        private static final long serialVersionUID = 1L;
        private int x, y;
        private int score;
        private String direction; // ← ДОБАВЛЯЕМ НАПРАВЛЕНИЕ

        public PlayerState(int x, int y, int score, String direction) {
            this.x = x;
            this.y = y;
            this.score = score;
            this.direction = direction;
        }

        public int getX() { return x; }
        public int getY() { return y; }
        public int getScore() { return score; }
        public String getDirection() { return direction; } // ← ГЕТТЕР ДЛЯ НАПРАВЛЕНИЯ
    }
}