package com.badlogic.pacman.server.config;

import java.io.Serializable;
import java.util.List;

public class MazeConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private int[][] maze;
    private List<CoinPosition> coins;

    public int[][] getMaze() { return maze; }
    public void setMaze(int[][] maze) { this.maze = maze; }
    public List<CoinPosition> getCoins() { return coins; }
    public void setCoins(List<CoinPosition> coins) { this.coins = coins; }

    public static class CoinPosition implements Serializable {
        private static final long serialVersionUID = 1L;
        private int x;
        private int y;

        public int getX() { return x; }
        public void setX(int x) { this.x = x; }
        public int getY() { return y; }
        public void setY(int y) { this.y = y; }
    }
}