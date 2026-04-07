package com.badlogic.pacman.client;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

public class GameClientLauncher {
    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Pacman Client");
        config.setWindowedMode(1000, 800);
        config.setForegroundFPS(60);

        new Lwjgl3Application(new PacmanGame(), config);
    }
}