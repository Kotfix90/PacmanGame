package com.badlogic.pacman.common;

import java.io.Serializable;

public class GameState implements Serializable {
    private static final long serialVersionUID = 1L;

    private int pacmanX;
    private int pacmanY;
    private int[][] maze;
    private boolean isAlive;
    private int score;

    public GameState(int pacmanX, int pacmanY, int[][] maze, boolean isAlive, int score) {
        this.pacmanX = pacmanX;
        this.pacmanY = pacmanY;
        this.maze = maze;
        this.isAlive = isAlive;
        this.score = score;
    }

    public int getPacmanX() { return pacmanX; }
    public int getPacmanY() { return pacmanY; }
    public int[][] getMaze() { return maze; }
    public boolean isAlive() { return isAlive; }
    public int getScore() { return score; }
}