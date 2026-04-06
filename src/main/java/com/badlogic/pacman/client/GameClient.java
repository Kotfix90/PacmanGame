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

    // Текстуры для своего Pacman'а
    private Texture pacmanRight;
    private Texture pacmanLeft;
    private Texture pacmanUp;
    private Texture pacmanDown;

    // Текстуры для чужих Pacman'ов
    private Texture otherPacmanRight;
    private Texture otherPacmanLeft;
    private Texture otherPacmanUp;
    private Texture otherPacmanDown;

    private Texture brickTexture;

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

    private String localPlayerId;
    private float tileSize = 1f;
    private float moveDuration = 0.05f;

    // Для хранения лабиринта
    private int[][] currentMaze;

    private Channel channel;
    private EventLoopGroup workerGroup;

    @Override
    public void create() {
        backgroundTexture = new Texture(Gdx.files.internal("assets/background.png"));

        // Загружаем текстуры для своего Pacman'а
        pacmanRight = new Texture(Gdx.files.internal("assets/pacman(right).png"));
        pacmanLeft = new Texture(Gdx.files.internal("assets/pacman(left).png"));
        pacmanUp = new Texture(Gdx.files.internal("assets/pacman(up).png"));
        pacmanDown = new Texture(Gdx.files.internal("assets/pacman(down).png"));

        // Загружаем текстуры для чужих Pacman'ов (можно использовать те же, но с синим оттенком)
        otherPacmanRight = new Texture(Gdx.files.internal("assets/pacman(right).png"));
        otherPacmanLeft = new Texture(Gdx.files.internal("assets/pacman(left).png"));
        otherPacmanUp = new Texture(Gdx.files.internal("assets/pacman(up).png"));
        otherPacmanDown = new Texture(Gdx.files.internal("assets/pacman(down).png"));

        brickTexture = new Texture(Gdx.files.internal("assets/brick.png"));

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
                    Texture startTexture = getTextureForDirection(newDirection, isLocal);
                    Sprite sprite = new Sprite(startTexture);

                    if (!isLocal) {
                        sprite.setColor(Color.BLUE); // Другие игроки синие
                    }

                    sprite.setSize(tileSize, tileSize);
                    sprite.setPosition(newTargetX, newTargetY);

                    playerSprites.put(playerId, sprite);
                    playerDirections.put(playerId, newDirection);
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

                    // Обновляем текстуру если направление изменилось
                    String oldDirection = playerDirections.get(playerId);
                    if (!newDirection.equals(oldDirection)) {
                        Texture newTexture = getTextureForDirection(newDirection, isLocal);
                        sprite.setTexture(newTexture);
                        playerDirections.put(playerId, newDirection);
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

    // Метод для получения текстуры в зависимости от направления
    private Texture getTextureForDirection(String direction, boolean isLocal) {
        if (isLocal) {
            switch (direction) {
                case "RIGHT": return pacmanRight;
                case "LEFT": return pacmanLeft;
                case "UP": return pacmanUp;
                case "DOWN": return pacmanDown;
                default: return pacmanRight;
            }
        } else {
            switch (direction) {
                case "RIGHT": return otherPacmanRight;
                case "LEFT": return otherPacmanLeft;
                case "UP": return otherPacmanUp;
                case "DOWN": return otherPacmanDown;
                default: return otherPacmanRight;
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

        // Свои текстуры
        pacmanRight.dispose();
        pacmanLeft.dispose();
        pacmanUp.dispose();
        pacmanDown.dispose();

        // Чужие текстуры
        otherPacmanRight.dispose();
        otherPacmanLeft.dispose();
        otherPacmanUp.dispose();
        otherPacmanDown.dispose();

        brickTexture.dispose();
        spriteBatch.dispose();
    }
}