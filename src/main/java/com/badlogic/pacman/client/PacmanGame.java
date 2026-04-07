package com.badlogic.pacman.client;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;

public class PacmanGame extends Game {
    @Override
    public void create() {
        setScreen(new MainMenuScreen(this));
    }

    public void showMainMenuScreen() {
        setScreen(new MainMenuScreen(this));
    }

    public void showServerListScreen() {
        setScreen(new ServerListScreen(this));
    }

    public void startGame(String serverAddress) {
        setScreen(new GameClient(this, serverAddress));
    }
}