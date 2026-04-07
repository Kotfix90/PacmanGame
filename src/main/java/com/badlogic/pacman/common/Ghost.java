package com.badlogic.pacman.common;

import java.io.Serializable;
import java.util.Random;

public class Ghost implements Serializable {
    private static final long serialVersionUID = 1L;

    private int x;
    private int y;
    private final double speed;
    private String direction;
    private double moveAccumulator = 0;

    public Ghost(int x, int y, double speed) {
        this.x = x;
        this.y = y;
        this.speed = speed;
        this.direction = "RIGHT";
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public double getSpeed() { return speed; }
    public String getDirection() { return direction; }

    public void setX(int x) { this.x = x; }
    public void setY(int y) { this.y = y; }
    public void setDirection(String direction) { this.direction = direction; }

    public void move(int[][] maze) {
        moveAccumulator += speed;

        // Двигаемся только когда накопилось достаточно времени
        if (moveAccumulator < 1.0) {
            return;
        }

        moveAccumulator = 0;

        Random random = new Random();
        String[] directions = {"UP", "DOWN", "LEFT", "RIGHT"};
        String newDirection = directions[random.nextInt(directions.length)];

        int newX = x;
        int newY = y;

        switch (newDirection) {
            case "UP": newY--; break;
            case "DOWN": newY++; break;
            case "LEFT": newX--; break;
            case "RIGHT": newX++; break;
        }

        if (newX >= 0 && newX < maze[0].length && newY >= 0 && newY < maze.length && maze[newY][newX] != 1) {
            x = newX;
            y = newY;
            direction = newDirection;
        }
    }
}