package com.badlogic.pacman.server.config;

import java.io.Serializable;
import java.util.List;

public class GhostsConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<GhostConfig> ghosts;

    public List<GhostConfig> getGhosts() { return ghosts; }
    public void setGhosts(List<GhostConfig> ghosts) { this.ghosts = ghosts; }

    public static class GhostConfig implements Serializable {
        private static final long serialVersionUID = 1L;
        private int startX;
        private int startY;
        private double speed;
        private String color;

        public int getStartX() { return startX; }
        public void setStartX(int startX) { this.startX = startX; }
        public int getStartY() { return startY; }
        public void setStartY(int startY) { this.startY = startY; }
        public double getSpeed() { return speed; }
        public void setSpeed(double speed) { this.speed = speed; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
    }
}