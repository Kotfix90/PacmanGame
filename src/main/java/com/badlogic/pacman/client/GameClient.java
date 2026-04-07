package com.badlogic.pacman.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.pacman.common.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GameClient implements Screen {
    private final PacmanGame game;
    private String serverAddress;

    private Texture backgroundTexture;
    private Texture brickTexture;
    private Texture coinTexture;
    private Texture ghostTexture;
    private Texture[][] pacmanFrames = new Texture[4][3];

    private SpriteBatch spriteBatch;
    private FitViewport viewport;

    private final Map<String, PlayerRenderData> players = new ConcurrentHashMap<>();
    private final Map<String, GhostRenderData> ghosts = new ConcurrentHashMap<>();
    private final List<Sprite> coinSprites = new ArrayList<>();

    private static class PlayerRenderData {
        Sprite sprite;
        float targetX, targetY;
        float startX, startY;
        float moveProgress;
        boolean isMoving;
        String direction;
        float animationTime;
        int currentFrame;
        float red, green, blue;

        PlayerRenderData(Sprite sprite, float x, float y, String direction, float r, float g, float b) {
            this.sprite = sprite;
            this.targetX = x;
            this.targetY = y;
            this.startX = x;
            this.startY = y;
            this.moveProgress = 1;
            this.isMoving = false;
            this.direction = direction;
            this.animationTime = 0;
            this.currentFrame = 0;
            this.red = r;
            this.green = g;
            this.blue = b;
        }
    }

    private static class GhostRenderData {
        Sprite sprite;
        float targetX, targetY;
        float startX, startY;
        float moveProgress;
        boolean isMoving;

        GhostRenderData(Sprite sprite, float x, float y) {
            this.sprite = sprite;
            this.targetX = x;
            this.targetY = y;
            this.startX = x;
            this.startY = y;
            this.moveProgress = 1;
            this.isMoving = false;
        }
    }

    private String localPlayerId;
    private float tileSize = 1f;
    private float moveDuration = 0.1f;
    private float animationSpeed = 10f;
    private int[][] currentMaze;

    private volatile boolean gameInitialized = false;
    private final Object gameStateLock = new Object();
    private volatile Map<String, GameState.PlayerState> lastPlayers = new HashMap<>();
    private volatile List<Coin> lastCoins = new ArrayList<>();
    private volatile List<Ghost> lastGhosts = new ArrayList<>();
    private volatile int[][] lastMaze;

    private Channel channel;
    private EventLoopGroup workerGroup;
    private volatile boolean isDisconnecting = false;

    public GameClient(PacmanGame game, String serverAddress) {
        this.game = game;
        this.serverAddress = serverAddress;
    }

    @Override
    public void show() {
        System.out.println("GameClient.show() started");
        loadTextures();
        initializeViewportAndBatch();
        connectToServer();
    }

    private void loadTextures() {
        try {
            backgroundTexture = new Texture(Gdx.files.internal("assets/background.png"));
            brickTexture = new Texture(Gdx.files.internal("assets/brick.png"));
            coinTexture = new Texture(Gdx.files.internal("assets/coin.png"));
            ghostTexture = new Texture(Gdx.files.internal("assets/ghost.png"));

            pacmanFrames[0][0] = new Texture(Gdx.files.internal("assets/pacman_right_1.png"));
            pacmanFrames[0][1] = new Texture(Gdx.files.internal("assets/pacman_right_2.png"));
            pacmanFrames[0][2] = new Texture(Gdx.files.internal("assets/pacman_right_3.png"));

            pacmanFrames[1][0] = new Texture(Gdx.files.internal("assets/pacman_left_1.png"));
            pacmanFrames[1][1] = new Texture(Gdx.files.internal("assets/pacman_left_2.png"));
            pacmanFrames[1][2] = new Texture(Gdx.files.internal("assets/pacman_left_3.png"));

            pacmanFrames[2][0] = new Texture(Gdx.files.internal("assets/pacman_up_1.png"));
            pacmanFrames[2][1] = new Texture(Gdx.files.internal("assets/pacman_up_2.png"));
            pacmanFrames[2][2] = new Texture(Gdx.files.internal("assets/pacman_up_3.png"));

            pacmanFrames[3][0] = new Texture(Gdx.files.internal("assets/pacman_down_1.png"));
            pacmanFrames[3][1] = new Texture(Gdx.files.internal("assets/pacman_down_2.png"));
            pacmanFrames[3][2] = new Texture(Gdx.files.internal("assets/pacman_down_3.png"));

            System.out.println("All textures loaded successfully");
        } catch (Exception e) {
            System.err.println("Error loading textures: " + e.getMessage());
        }
    }

    private void initializeViewportAndBatch() {
        spriteBatch = new SpriteBatch();
        viewport = new FitViewport(23, 22);
    }

    private void connectToServer() {
        String[] parts = serverAddress.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        workerGroup = new NioEventLoopGroup();

        new Thread(() -> {
            try {
                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(workerGroup)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ChannelPipeline pipeline = ch.pipeline();
                                pipeline.addLast(new ObjectEncoder());
                                pipeline.addLast(new ObjectDecoder(ClassResolvers.cacheDisabled(null)));
                                pipeline.addLast(new ClientHandler());
                            }
                        });

                ChannelFuture future = bootstrap.connect(host, port).sync();
                channel = future.channel();
                System.out.println("Connected to server: " + serverAddress);
            } catch (Exception e) {
                System.err.println("Connection failed: " + e.getMessage());
            }
        }).start();
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        updateGameData();

        if (gameInitialized) {
            handleInput();
            update(delta);
            draw();
        } else {
            drawLoadingScreen();
        }
    }

    private void updateGameData() {
        synchronized (gameStateLock) {
            if (lastMaze != null && currentMaze == null) {
                currentMaze = lastMaze;
                gameInitialized = true;
                System.out.println("Maze size: " + currentMaze[0].length + "x" + currentMaze.length);
            }

            if (currentMaze != null) {
                // Обновляем монетки
                coinSprites.clear();
                for (Coin coin : lastCoins) {
                    Sprite coinSprite = new Sprite(coinTexture);
                    coinSprite.setSize(tileSize, tileSize);
                    coinSprite.setPosition(coin.getX() * tileSize, coin.getY() * tileSize);
                    coinSprites.add(coinSprite);
                }

                // Обновляем приведений
                updateGhostsFromState(lastGhosts);

                // Обновляем игроков - это автоматически удалит отключившихся
                updatePlayersFromState(lastPlayers);
            }
        }
    }

    private void updateGhostsFromState(List<Ghost> ghostList) {
        Set<String> currentGhostIds = new HashSet<>();
        int ghostIndex = 0;

        for (Ghost ghost : ghostList) {
            String id = "ghost_" + ghostIndex;
            currentGhostIds.add(id);
            ghostIndex++;

            float targetGridX = ghost.getX();
            float targetGridY = ghost.getY();
            float targetWorldX = targetGridX * tileSize;
            float targetWorldY = targetGridY * tileSize;

            GhostRenderData info = ghosts.get(id);

            if (info == null) {
                Sprite sprite = new Sprite(ghostTexture);
                sprite.setSize(tileSize, tileSize);
                sprite.setPosition(targetWorldX, targetWorldY);
                info = new GhostRenderData(sprite, targetWorldX, targetWorldY);
                ghosts.put(id, info);
            } else {
                float currentWorldX = info.sprite.getX();
                float currentWorldY = info.sprite.getY();

                int currentGridX = Math.round(currentWorldX / tileSize);
                int currentGridY = Math.round(currentWorldY / tileSize);

                if (currentGridX != targetGridX || currentGridY != targetGridY) {
                    info.startX = currentWorldX;
                    info.startY = currentWorldY;
                    info.targetX = targetWorldX;
                    info.targetY = targetWorldY;
                    info.moveProgress = 0;
                    info.isMoving = true;
                }
            }
        }

        // Удаляем приведений, которых больше нет
        ghosts.keySet().retainAll(currentGhostIds);
    }

    private void updatePlayersFromState(Map<String, GameState.PlayerState> playersState) {
        // Удаляем игроков, которые отключились
        Set<String> currentPlayerIds = new HashSet<>(playersState.keySet());
        Set<String> playersToRemove = new HashSet<>(players.keySet());
        playersToRemove.removeAll(currentPlayerIds);

        // Удаляем спрайты отключившихся игроков
        for (String playerId : playersToRemove) {
            PlayerRenderData removed = players.remove(playerId);
            if (removed != null && removed.sprite != null) {
                System.out.println("Removing disconnected player: " + playerId);
                // Спрайт будет удален сборщиком мусора
            }
        }

        // Обновляем или создаем текущих игроков
        for (Map.Entry<String, GameState.PlayerState> entry : playersState.entrySet()) {
            String playerId = entry.getKey();
            GameState.PlayerState state = entry.getValue();

            float newTargetX = state.getX() * tileSize;
            float newTargetY = state.getY() * tileSize;
            String newDirection = state.getDirection();
            float red = state.getRed();
            float green = state.getGreen();
            float blue = state.getBlue();

            PlayerRenderData info = players.get(playerId);

            if (info == null) {
                Sprite sprite = new Sprite(getTextureForDirection(newDirection, 0));
                sprite.setColor(red, green, blue, 1.0f);
                sprite.setSize(tileSize, tileSize);
                sprite.setPosition(newTargetX, newTargetY);

                info = new PlayerRenderData(sprite, newTargetX, newTargetY, newDirection, red, green, blue);
                players.put(playerId, info);
                System.out.println("Added player: " + playerId);
            } else {
                info.sprite.setColor(red, green, blue, 1.0f);

                if (!newDirection.equals(info.direction)) {
                    Texture newTexture = getTextureForDirection(newDirection, 0);
                    info.sprite.setTexture(newTexture);
                    info.direction = newDirection;
                    info.currentFrame = 0;
                    info.animationTime = 0;
                }

                float currentX = info.sprite.getX();
                float currentY = info.sprite.getY();

                if (Math.abs(newTargetX - currentX) > 0.01f || Math.abs(newTargetY - currentY) > 0.01f) {
                    info.startX = currentX;
                    info.startY = currentY;
                    info.targetX = newTargetX;
                    info.targetY = newTargetY;
                    info.moveProgress = 0;
                    info.isMoving = true;
                }
            }
        }
    }

    private void update(float delta) {
        updateAnimations(delta);
        updatePlayerPositions(delta);
        updateGhostPositions(delta);
    }

    private void updatePlayerPositions(float delta) {
        for (PlayerRenderData info : players.values()) {
            if (info.isMoving) {
                info.moveProgress += delta / moveDuration;

                if (info.moveProgress >= 1) {
                    info.isMoving = false;
                    info.sprite.setPosition(info.targetX, info.targetY);
                } else {
                    float alpha = info.moveProgress;
                    float x = Interpolation.linear.apply(info.startX, info.targetX, alpha);
                    float y = Interpolation.linear.apply(info.startY, info.targetY, alpha);
                    info.sprite.setPosition(x, y);
                }
            }
        }
    }

    private void updateGhostPositions(float delta) {
        for (GhostRenderData info : ghosts.values()) {
            if (info.isMoving) {
                info.moveProgress += delta / moveDuration;

                if (info.moveProgress >= 1) {
                    info.isMoving = false;
                    info.sprite.setPosition(info.targetX, info.targetY);
                } else {
                    float alpha = Interpolation.sine.apply(info.moveProgress);
                    float x = info.startX + (info.targetX - info.startX) * alpha;
                    float y = info.startY + (info.targetY - info.startY) * alpha;
                    info.sprite.setPosition(x, y);
                }
            }
        }
    }

    private void updateAnimations(float delta) {
        for (PlayerRenderData info : players.values()) {
            if (info.direction != null && info.isMoving) {
                info.animationTime += delta * animationSpeed;
                int frame = (int) info.animationTime % 3;

                if (frame != info.currentFrame) {
                    Texture newTexture = getTextureForDirection(info.direction, frame);
                    info.sprite.setTexture(newTexture);
                    info.currentFrame = frame;
                }
            }
        }
    }

    private Texture getTextureForDirection(String direction, int frameIndex) {
        int directionIndex = 0;
        switch (direction) {
            case "RIGHT": directionIndex = 0; break;
            case "LEFT": directionIndex = 1; break;
            case "UP": directionIndex = 2; break;
            case "DOWN": directionIndex = 3; break;
        }
        return pacmanFrames[directionIndex][frameIndex % 3];
    }

    private void drawLoadingScreen() {
        viewport.apply();
        spriteBatch.setProjectionMatrix(viewport.getCamera().combined);
        spriteBatch.begin();
        spriteBatch.draw(backgroundTexture, 0, 0, viewport.getWorldWidth(), viewport.getWorldHeight());
        spriteBatch.end();
    }

    private class ClientHandler extends SimpleChannelInboundHandler<GameState> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, GameState gameState) {
            if (gameState == null) return;

            synchronized (gameStateLock) {
                localPlayerId = gameState.getLocalPlayerId();
                if (gameState.getMaze() != null) {
                    lastMaze = gameState.getMaze();
                }
                if (gameState.getPlayers() != null) {
                    lastPlayers = new HashMap<>(gameState.getPlayers());
                }
                if (gameState.getCoins() != null) {
                    lastCoins = new ArrayList<>(gameState.getCoins());
                }
                if (gameState.getGhosts() != null) {
                    lastGhosts = new ArrayList<>(gameState.getGhosts());
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            System.out.println("Disconnected from server");
            Gdx.app.postRunnable(() -> {
                if (!isDisconnecting) {
                    game.setScreen(new MainMenuScreen(game));
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("Exception in client handler: " + cause.getMessage());
            cause.printStackTrace();
            ctx.close();
        }
    }

    private void sendCommand(Command command) {
        if (channel != null && channel.isActive() && !isDisconnecting) {
            channel.writeAndFlush(command);
        }
    }

    private void handleInput() {
        boolean rightPressed = Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.RIGHT);
        boolean leftPressed = Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.LEFT);
        boolean upPressed = Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.UP);
        boolean downPressed = Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.DOWN);

        if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.R)) {
            sendCommand(new Command(CommandType.RESPAWN));
        }

        boolean onlyHorizontal = (rightPressed || leftPressed) && !upPressed && !downPressed;
        boolean onlyVertical = (upPressed || downPressed) && !rightPressed && !leftPressed;

        if (onlyHorizontal && rightPressed && leftPressed) {
            sendCommand(new Command(CommandType.BOUNCE_MODE, true, false));
        } else if (onlyVertical && upPressed && downPressed) {
            sendCommand(new Command(CommandType.BOUNCE_MODE, false, true));
        } else {
            if ((upPressed && leftPressed) || (upPressed && rightPressed) ||
                    (downPressed && leftPressed) || (downPressed && rightPressed)) {
                boolean[] keys = {upPressed, downPressed, leftPressed, rightPressed};
                sendCommand(new Command(CommandType.MOVE, keys));
            } else {
                Direction direction = Direction.NONE;
                if (upPressed) direction = Direction.UP;
                else if (downPressed) direction = Direction.DOWN;
                else if (leftPressed) direction = Direction.LEFT;
                else if (rightPressed) direction = Direction.RIGHT;

                if (direction != Direction.NONE) {
                    sendCommand(new Command(CommandType.MOVE, direction));
                }
            }
        }
    }

    private void draw() {
        viewport.apply();
        spriteBatch.setProjectionMatrix(viewport.getCamera().combined);
        spriteBatch.begin();

        // Фон
        spriteBatch.draw(backgroundTexture, 0, 0, viewport.getWorldWidth(), viewport.getWorldHeight());

        // Лабиринт
        if (currentMaze != null) {
            for (int row = 0; row < currentMaze.length; row++) {
                for (int col = 0; col < currentMaze[row].length; col++) {
                    if (currentMaze[row][col] == 1) {
                        spriteBatch.draw(brickTexture,
                                col * tileSize,
                                row * tileSize,
                                tileSize,
                                tileSize);
                    }
                }
            }
        }

        // Монетки
        for (Sprite coinSprite : coinSprites) {
            coinSprite.draw(spriteBatch);
        }

        // Приведения
        for (GhostRenderData info : ghosts.values()) {
            info.sprite.draw(spriteBatch);
        }

        // Игроки
        for (PlayerRenderData info : players.values()) {
            info.sprite.draw(spriteBatch);
        }

        spriteBatch.end();
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override
    public void pause() {}

    @Override
    public void resume() {}

    @Override
    public void hide() {
        disconnect();
    }

    @Override
    public void dispose() {
        disconnect();

        if (backgroundTexture != null) backgroundTexture.dispose();
        if (brickTexture != null) brickTexture.dispose();
        if (coinTexture != null) coinTexture.dispose();
        if (ghostTexture != null) ghostTexture.dispose();

        for (Texture[] frames : pacmanFrames) {
            for (Texture tex : frames) {
                if (tex != null) tex.dispose();
            }
        }

        if (spriteBatch != null) spriteBatch.dispose();
    }

    private void disconnect() {
        if (isDisconnecting) return;
        isDisconnecting = true;

        if (channel != null && channel.isActive()) {
            sendCommand(new Command(CommandType.DISCONNECT));
            try {
                Thread.sleep(100); // Даем время отправить команду
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            channel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}