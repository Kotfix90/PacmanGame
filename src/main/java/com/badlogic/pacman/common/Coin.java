package com.badlogic.pacman.common;

import java.io.Serializable;

public class Coin implements Serializable {
    private final int x;
    private final int y;

    public Coin(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() { return x; }
    public int getY() { return y; }
}