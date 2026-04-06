package com.badlogic.pacman.client;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
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

import java.util.HashMap;
import java.util.Map;

public class GameClient implements ApplicationListener {
    private Texture backgroundTexture;
    private Texture brickTexture;

    // Массивы текстур для анимации (по 3 кадра для каждого направления)
    private Texture[] pacmanRightFrames = new Texture[3];
    private Texture[] pacmanLeftFrames = new Texture[3];
    private Texture[] pacmanUpFrames = new Texture[3];
    private Texture[] pacmanDownFrames = new Texture[3];

    private Texture[] otherPacmanRightFrames = new Texture[3];
    private Texture[] otherPacmanLeftFrames = new Texture[3];
    private Texture[] otherPacmanUpFrames = new Texture[3];
    private Texture[] otherPacmanDownFrames = new Texture[3];

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

    // Для анимации рта
    private Map<String, Float> animationTime = new HashMap<>();
    private Map<String, Integer> currentFrame = new HashMap<>();

    private String localPlayerId;
    private float tileSize = 1f;
    private float moveDuration = 0.05f;

    // Скорость анимации рта (кадров в секунду)
    private float animationSpeed = 10f; // 10 кадров в секунду

    // Для хранения лабиринта
    private int[][] currentMaze;

    private Channel channel;
    private EventLoopGroup workerGroup;

    @Override
    public void create() {
        backgroundTexture = new Texture(Gdx.files.internal("assets/background.png"));
        brickTexture = new Texture(Gdx.files.internal("assets/brick.png"));

        // Загружаем 3 кадра анимации для своего Pacman'а (вправо)
        pacmanRightFrames[0] = new Texture(Gdx.files.internal("assets/pacman_right_1.png")); // Рот закрыт
        pacmanRightFrames[1] = new Texture(Gdx.files.internal("assets/pacman_right_2.png")); // Рот полуоткрыт
        pacmanRightFrames[2] = new Texture(Gdx.files.internal("assets/pacman_right_3.png")); // Рот полностью открыт

        // Для левого направления (можно создать отраженные текстуры или загрузить отдельные)
        pacmanLeftFrames[0] = new Texture(Gdx.files.internal("assets/pacman_left_1.png"));
        pacmanLeftFrames[1] = new Texture(Gdx.files.internal("assets/pacman_left_2.png"));
        pacmanLeftFrames[2] = new Texture(Gdx.files.internal("assets/pacman_left_3.png"));

        // Для верхнего направления
        pacmanUpFrames[0] = new Texture(Gdx.files.internal("assets/pacman_up_1.png"));
        pacmanUpFrames[1] = new Texture(Gdx.files.internal("assets/pacman_up_2.png"));
        pacmanUpFrames[2] = new Texture(Gdx.files.internal("assets/pacman_up_3.png"));

        // Для нижнего направления
        pacmanDownFrames[0] = new Texture(Gdx.files.internal("assets/pacman_down_1.png"));
        pacmanDownFrames[1] = new Texture(Gdx.files.internal("assets/pacman_down_2.png"));
        pacmanDownFrames[2] = new Texture(Gdx.files.internal("assets/pacman_down_3.png"));

        // Для других игроков (можно использовать те же текстуры или другие)
        otherPacmanRightFrames = pacmanRightFrames.clone();
        otherPacmanLeftFrames = pacmanLeftFrames.clone();
        otherPacmanUpFrames = pacmanUpFrames.clone();
        otherPacmanDownFrames = pacmanDownFrames.clone();

        spriteBatch = new SpriteBatch();
        viewport = new FitViewport(23, 22);

        connectToServer();
    }

    private void connectToServer() {
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

            ChannelFuture future = bootstrap.connect("localhost", 8080).sync();
            channel = future.channel();
            System.out.println("Connected to server");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private class ClientHandler extends SimpleChannelInboundHandler<GameState> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, GameState gameState) {
            localPlayerId = gameState.getLocalPlayerId();
            currentMaze = gameState.getMaze();

            for (Map.Entry<String, GameState.PlayerState> entry : gameState.getPlayers().entrySet()) {
                String playerId = entry.getKey();
                GameState.PlayerState state = entry.getValue();

                float newTargetX = state.getX() * tileSize;
                float newTargetY = state.getY() * tileSize;
                String newDirection = state.getDirection();

                if (!playerSprites.containsKey(playerId)) {
                    // Новый игрок
                    boolean isLocal = playerId.equals(localPlayerId);
                    Texture startTexture = getTextureForDirection(newDirection, isLocal, 0);
                    Sprite sprite = new Sprite(startTexture);

                    if (!isLocal) {
                        sprite.setColor(Color.BLUE);
                    }

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
                    float currentX = sprite.getX();
                    float currentY = sprite.getY();

                    boolean isLocal = playerId.equals(localPlayerId);

                    // Обновляем направление если изменилось
                    String oldDirection = playerDirections.get(playerId);
                    if (!newDirection.equals(oldDirection)) {
                        Texture newTexture = getTextureForDirection(newDirection, isLocal, 0);
                        sprite.setTexture(newTexture);
                        playerDirections.put(playerId, newDirection);
                        currentFrame.put(playerId, 0);
                        animationTime.put(playerId, 0f);
                    }

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
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    // Метод для получения текстуры кадра анимации
    private Texture getTextureForDirection(String direction, boolean isLocal, int frameIndex) {
        Texture[] frames;

        if (isLocal) {
            switch (direction) {
                case "RIGHT": frames = pacmanRightFrames; break;
                case "LEFT": frames = pacmanLeftFrames; break;
                case "UP": frames = pacmanUpFrames; break;
                case "DOWN": frames = pacmanDownFrames; break;
                default: frames = pacmanRightFrames;
            }
        } else {
            switch (direction) {
                case "RIGHT": frames = otherPacmanRightFrames; break;
                case "LEFT": frames = otherPacmanLeftFrames; break;
                case "UP": frames = otherPacmanUpFrames; break;
                case "DOWN": frames = otherPacmanDownFrames; break;
                default: frames = otherPacmanRightFrames;
            }
        }

        return frames[frameIndex % frames.length];
    }

    // Обновление анимации для всех игроков
    private void updateAnimations(float delta) {
        for (String playerId : playerSprites.keySet()) {
            boolean isLocal = playerId.equals(localPlayerId);
            String direction = playerDirections.get(playerId);
            Sprite sprite = playerSprites.get(playerId);

            if (direction != null) {
                // Обновляем время анимации
                float time = animationTime.getOrDefault(playerId, 0f) + delta;
                animationTime.put(playerId, time);

                // Меняем кадр в зависимости от скорости анимации
                int frame = (int)(time * animationSpeed) % 3;
                int current = currentFrame.getOrDefault(playerId, 0);

                if (frame != current) {
                    Texture newTexture = getTextureForDirection(direction, isLocal, frame);
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
        boolean rightPressed = Gdx.input.isKeyPressed(Input.Keys.RIGHT);
        boolean leftPressed = Gdx.input.isKeyPressed(Input.Keys.LEFT);
        boolean upPressed = Gdx.input.isKeyPressed(Input.Keys.UP);
        boolean downPressed = Gdx.input.isKeyPressed(Input.Keys.DOWN);

        if (Gdx.input.isKeyJustPressed(Input.Keys.R)) {
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

    private void update() {
        float delta = Gdx.graphics.getDeltaTime();

        // Обновляем анимацию рта
        updateAnimations(delta);

        // Обновляем движение
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

    private void draw() {
        viewport.apply();
        spriteBatch.setProjectionMatrix(viewport.getCamera().combined);
        spriteBatch.begin();

        float worldWidth = viewport.getWorldWidth();
        float worldHeight = viewport.getWorldHeight();
        spriteBatch.draw(backgroundTexture, 0, 0, worldWidth, worldHeight);

        // Отрисовка лабиринта
        if (currentMaze != null) {
            for (int row = 0; row < currentMaze.length; row++) {
                for (int col = 0; col < currentMaze[row].length; col++) {
                    if (currentMaze[row][col] == 1) {
                        spriteBatch.draw(brickTexture, col * tileSize, row * tileSize, tileSize, tileSize);
                    }
                }
            }
        }

        // Отрисовка всех игроков
        for (Sprite sprite : playerSprites.values()) {
            sprite.draw(spriteBatch);
        }

        spriteBatch.end();
    }

    @Override
    public void render() {
        handleInput();
        update();
        draw();
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

        // Свои текстуры
        for (Texture tex : pacmanRightFrames) tex.dispose();
        for (Texture tex : pacmanLeftFrames) tex.dispose();
        for (Texture tex : pacmanUpFrames) tex.dispose();
        for (Texture tex : pacmanDownFrames) tex.dispose();

        spriteBatch.dispose();
    }
}