package com.badlogic.pacman.client;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
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

public class GameClient implements ApplicationListener {
    private Texture backgroundTexture;
    private Texture pacmanTexture;
    private Texture coinTexture;
    private Texture brickTexture;

    private SpriteBatch spriteBatch;
    private FitViewport viewport;
    private Sprite pacmanSprite;

    private GameState currentGameState;
    private float tileSize = 1f;

    private float startX = 0, startY = 0;
    private float targetX = 0, targetY = 0;
    private float moveTime = 0f;
    private float moveDuration = 0.2f;
    private boolean isMoving = false;

    private Channel channel;
    private EventLoopGroup workerGroup;

    @Override
    public void create() {
        backgroundTexture = new Texture(Gdx.files.internal("assets/background.png"));
        pacmanTexture = new Texture(Gdx.files.internal("assets/pacman.png"));
        coinTexture = new Texture(Gdx.files.internal("assets/coin.png"));
        brickTexture = new Texture(Gdx.files.internal("assets/brick.png"));

        spriteBatch = new SpriteBatch();
        viewport = new FitViewport(23, 22);

        pacmanSprite = new Sprite(pacmanTexture);
        pacmanSprite.setSize(tileSize, tileSize);
        pacmanSprite.setPosition(1 * tileSize, 1 * tileSize);

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
            currentGameState = gameState;

            if (currentGameState != null) {
                float newTargetX = currentGameState.getPacmanX() * tileSize;
                float newTargetY = currentGameState.getPacmanY() * tileSize;

                if (Math.abs(newTargetX - pacmanSprite.getX()) > 0.01f ||
                        Math.abs(newTargetY - pacmanSprite.getY()) > 0.01f) {
                    startX = pacmanSprite.getX();
                    startY = pacmanSprite.getY();
                    targetX = newTargetX;
                    targetY = newTargetY;
                    moveTime = 0;
                    isMoving = true;
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
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
        if (isMoving) {
            float delta = Gdx.graphics.getDeltaTime();
            moveTime += delta;

            if (moveTime >= moveDuration) {
                isMoving = false;
                pacmanSprite.setPosition(targetX, targetY);
            } else {
                float alpha = moveTime / moveDuration;
                float x = Interpolation.linear.apply(startX, targetX, alpha);
                float y = Interpolation.linear.apply(startY, targetY, alpha);
                pacmanSprite.setPosition(x, y);
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

        if (currentGameState != null) {
            int[][] maze = currentGameState.getMaze();
            for (int row = 0; row < maze.length; row++) {
                for (int col = 0; col < maze[row].length; col++) {
                    if (maze[row][col] == 1) {
                        spriteBatch.draw(brickTexture, col * tileSize, row * tileSize, tileSize, tileSize);
                    }
                }
            }
        }

        pacmanSprite.draw(spriteBatch);
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
        pacmanTexture.dispose();
        coinTexture.dispose();
        brickTexture.dispose();
        spriteBatch.dispose();
    }
}