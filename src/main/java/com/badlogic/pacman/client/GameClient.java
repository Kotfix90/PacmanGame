package com.badlogic.pacman.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
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

import java.util.HashMap;
import java.util.Map;

public class GameClient implements Screen {
    private final PacmanGame game;
    private String serverAddress;
    private Texture backgroundTexture;
    private Texture brickTexture;
    private BitmapFont font;
    private GlyphLayout layout;

    private Skin skin;
    private Stage stage;

    // Текстуры для анимации всех Pacman'ов
    private Texture[][] pacmanFrames = new Texture[4][3];

    private SpriteBatch spriteBatch;
    private FitViewport viewport;

    private Map<String, Sprite> playerSprites = new HashMap<>();
    private Map<String, Float> targetX = new HashMap<>();
    private Map<String, Float> targetY = new HashMap<>();
    private Map<String, Float> startX = new HashMap<>();
    private Map<String, Float> startY = new HashMap<>();
    private Map<String, Float> moveTime = new HashMap<>();
    private Map<String, Boolean> isMoving = new HashMap<>();
    private Map<String, String> playerDirections = new HashMap<>();
    private Map<String, Float> animationTime = new HashMap<>();
    private Map<String, Integer> currentFrame = new HashMap<>();

    private String localPlayerId;
    private float tileSize = 1f;
    private float moveDuration = 0.1f;
    private float animationSpeed = 10f;
    private int[][] currentMaze;

    private Channel channel;
    private EventLoopGroup workerGroup;

    public GameClient(PacmanGame game, String serverAddress) {
        this.game = game;
        this.serverAddress = serverAddress;
    }

    @Override
    public void show() {
        backgroundTexture = new Texture(Gdx.files.internal("assets/background.png"));
        brickTexture = new Texture(Gdx.files.internal("assets/brick.png"));

        // Загрузка скина (без настройки шрифта)
        try {
            skin = new Skin(Gdx.files.internal("skin/neon-ui.json"));
        } catch (Exception e) {
            System.err.println("Failed to load skin: " + e.getMessage());
        }

        spriteBatch = new SpriteBatch();
        viewport = new FitViewport(23, 22);

        loadPacmanTextures();
        connectToServer();
    }

    private void loadPacmanTextures() {
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
    }

    private void connectToServer() {
        String[] parts = serverAddress.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        workerGroup = new NioEventLoopGroup();

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
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        handleInput();
        update(delta);
        draw();

        if (stage != null) {
            stage.act(delta);
        }
    }

    private void update(float delta) {
        updateAnimations(delta);

        for (Map.Entry<String, Sprite> entry : playerSprites.entrySet()) {
            String playerId = entry.getKey();
            Sprite sprite = entry.getValue();

            if (isMoving.getOrDefault(playerId, false)) {
                float time = moveTime.getOrDefault(playerId, 0f) + delta;
                moveTime.put(playerId, time);

                if (time >= moveDuration) {
                    isMoving.put(playerId, false);
                    sprite.setPosition(targetX.get(playerId), targetY.get(playerId));
                } else {
                    float alpha = time / moveDuration;
                    float x = Interpolation.linear.apply(startX.get(playerId), targetX.get(playerId), alpha);
                    float y = Interpolation.linear.apply(startY.get(playerId), targetY.get(playerId), alpha);
                    sprite.setPosition(x, y);
                }
            }
        }
    }

    private class ClientHandler extends SimpleChannelInboundHandler<GameState> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, GameState gameState) {
            localPlayerId = gameState.getLocalPlayerId();
            currentMaze = gameState.getMaze();

            // Удаляем игроков, которые вышли
            playerSprites.keySet().retainAll(gameState.getPlayers().keySet());
            playerDirections.keySet().retainAll(gameState.getPlayers().keySet());
            animationTime.keySet().retainAll(gameState.getPlayers().keySet());
            currentFrame.keySet().retainAll(gameState.getPlayers().keySet());
            startX.keySet().retainAll(gameState.getPlayers().keySet());
            startY.keySet().retainAll(gameState.getPlayers().keySet());
            targetX.keySet().retainAll(gameState.getPlayers().keySet());
            targetY.keySet().retainAll(gameState.getPlayers().keySet());
            moveTime.keySet().retainAll(gameState.getPlayers().keySet());
            isMoving.keySet().retainAll(gameState.getPlayers().keySet());

            for (Map.Entry<String, GameState.PlayerState> entry : gameState.getPlayers().entrySet()) {
                String playerId = entry.getKey();
                GameState.PlayerState state = entry.getValue();

                float newTargetX = state.getX() * tileSize;
                float newTargetY = state.getY() * tileSize;
                String newDirection = state.getDirection();
                float red = state.getRed();
                float green = state.getGreen();
                float blue = state.getBlue();

                if (!playerSprites.containsKey(playerId)) {
                    Sprite sprite = new Sprite(getTextureForDirection(newDirection, 0));
                    sprite.setColor(red, green, blue, 1.0f);
                    sprite.setSize(tileSize, tileSize);
                    sprite.setPosition(newTargetX, newTargetY);

                    playerSprites.put(playerId, sprite);
                    playerDirections.put(playerId, newDirection);
                    animationTime.put(playerId, 0f);
                    currentFrame.put(playerId, 0);
                    startX.put(playerId, newTargetX);
                    startY.put(playerId, newTargetY);
                    targetX.put(playerId, newTargetX);
                    targetY.put(playerId, newTargetY);
                    moveTime.put(playerId, 0f);
                    isMoving.put(playerId, false);
                } else {
                    Sprite sprite = playerSprites.get(playerId);
                    sprite.setColor(red, green, blue, 1.0f);

                    String oldDirection = playerDirections.get(playerId);
                    if (!newDirection.equals(oldDirection)) {
                        Texture newTexture = getTextureForDirection(newDirection, 0);
                        sprite.setTexture(newTexture);
                        playerDirections.put(playerId, newDirection);
                        currentFrame.put(playerId, 0);
                        animationTime.put(playerId, 0f);
                    }

                    float currentX = sprite.getX();
                    float currentY = sprite.getY();

                    if (Math.abs(newTargetX - currentX) > 0.01f || Math.abs(newTargetY - currentY) > 0.01f) {
                        startX.put(playerId, currentX);
                        startY.put(playerId, currentY);
                        targetX.put(playerId, newTargetX);
                        targetY.put(playerId, newTargetY);
                        moveTime.put(playerId, 0f);
                        isMoving.put(playerId, true);
                    }
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
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

    private void updateAnimations(float delta) {
        for (String playerId : playerSprites.keySet()) {
            String direction = playerDirections.get(playerId);
            Sprite sprite = playerSprites.get(playerId);

            if (direction != null) {
                float time = animationTime.getOrDefault(playerId, 0f) + delta;
                animationTime.put(playerId, time);

                int frame = (int)(time * animationSpeed) % 3;
                int current = currentFrame.getOrDefault(playerId, 0);

                if (frame != current) {
                    Texture newTexture = getTextureForDirection(direction, frame);
                    sprite.setTexture(newTexture);
                    currentFrame.put(playerId, frame);
                }
            }
        }
    }

    private void sendCommand(Command command) {
        if (channel != null && channel.isActive()) {
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

        float worldWidth = viewport.getWorldWidth();
        float worldHeight = viewport.getWorldHeight();
        spriteBatch.draw(backgroundTexture, 0, 0, worldWidth, worldHeight);

        if (currentMaze != null) {
            for (int row = 0; row < currentMaze.length; row++) {
                for (int col = 0; col < currentMaze[row].length; col++) {
                    if (currentMaze[row][col] == 1) {
                        spriteBatch.draw(brickTexture, col * tileSize, row * tileSize, tileSize, tileSize);
                    }
                }
            }
        }

        for (Sprite sprite : playerSprites.values()) {
            sprite.draw(spriteBatch);
        }

        spriteBatch.end();

        if (stage != null) {
            stage.draw();
        }
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
        if (stage != null) {
            stage.getViewport().update(width, height, true);
        }
    }

    @Override
    public void pause() {}

    @Override
    public void resume() {}

    @Override
    public void hide() {}

    @Override
    public void dispose() {
        if (channel != null) {
            sendCommand(new Command(CommandType.DISCONNECT));
            channel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }

        backgroundTexture.dispose();
        brickTexture.dispose();

        for (Texture[] frames : pacmanFrames) {
            for (Texture tex : frames) {
                if (tex != null) {
                    tex.dispose();
                }
            }
        }

        spriteBatch.dispose();
        if (font != null) {
            font.dispose();
        }

        if (skin != null) {
            skin.dispose();
        }

        if (stage != null) {
            stage.dispose();
        }
    }
}