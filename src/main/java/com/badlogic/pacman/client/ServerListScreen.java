package com.badlogic.pacman.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;

import java.util.ArrayList;
import java.util.List;

public class ServerListScreen implements Screen {
    private final PacmanGame game;
    private Stage stage;
    private Skin skin;
    private List<String> servers;
    private List<TextButton> serverButtons;

    public ServerListScreen(PacmanGame game) {
        this.game = game;
        servers = new ArrayList<>();
        servers.add("localhost:8080");
        //servers.add("192.168.1.100:8080");
    }

    @Override
    public void show() {
        stage = new Stage();
        Gdx.input.setInputProcessor(stage);

        skin = new Skin(Gdx.files.internal("skin/neon-ui.json"));

        Table table = new Table();
        table.setFillParent(true);
        stage.addActor(table);

        Label titleLabel = new Label("Select Server", skin);
        table.add(titleLabel).colspan(2).padBottom(20).row();

        ScrollPane scrollPane = new ScrollPane(null, skin);
        Table serverTable = new Table();
        serverButtons = new ArrayList<>();

        for (String server : servers) {
            TextButton serverButton = new TextButton(server, skin);
            final String serverAddress = server;
            serverButton.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    game.startGame(serverAddress);
                }
            });
            serverTable.add(serverButton).width(300).padBottom(10).row();
            serverButtons.add(serverButton);
        }

        scrollPane.setActor(serverTable);
        table.add(scrollPane).colspan(2).height(300).row();

        TextButton backButton = new TextButton("Back", skin);
        backButton.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                game.showMainMenuScreen();
            }
        });
        table.add(backButton).width(150).padTop(20).colspan(2);
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        stage.act(delta);
        stage.draw();
    }

    @Override
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    @Override
    public void pause() {}

    @Override
    public void resume() {}

    @Override
    public void hide() {}

    @Override
    public void dispose() {
        stage.dispose();
        skin.dispose();
    }
}