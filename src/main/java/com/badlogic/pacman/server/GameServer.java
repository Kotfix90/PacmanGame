package com.badlogic.pacman.server;

import com.badlogic.pacman.common.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GameServer {
    private final int port;
    private final ConcurrentHashMap<String, ClientInfo> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Channel, String> channelToId = new ConcurrentHashMap<>();
    private ScheduledExecutorService gameLoop;
    private int nextPlayerId = 1;

    public GameServer(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new ObjectEncoder());
                            pipeline.addLast(new ObjectDecoder(ClassResolvers.cacheDisabled(null)));
                            pipeline.addLast(new GameServerHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            System.out.println("Game server started on port " + port);

            // Запускаем глобальный игровой цикл
            startGameLoop();

            ChannelFuture future = bootstrap.bind(port).sync();
            future.channel().closeFuture().sync();
        } finally {
            if (gameLoop != null) {
                gameLoop.shutdown();
            }
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    private void startGameLoop() {
        gameLoop = Executors.newSingleThreadScheduledExecutor();
        gameLoop.scheduleAtFixedRate(() -> {
            float delta = 1/60f; // 60 FPS

            // Обновляем всех клиентов
            for (ClientInfo client : clients.values()) {
                client.update(delta);
            }

            // Отправляем состояние всем клиентам
            broadcastGameState();
        }, 0, 16, TimeUnit.MILLISECONDS);
    }

    private void broadcastGameState() {
        // Собираем состояния всех игроков
        Map<String, GameState.PlayerState> allPlayers = new HashMap<>();
        for (Map.Entry<String, ClientInfo> entry : clients.entrySet()) {
            ClientInfo client = entry.getValue();
            allPlayers.put(entry.getKey(),
                    new GameState.PlayerState(
                            client.getCurrentTileX(),
                            client.getCurrentTileY(),
                            client.getScore(),
                            client.getCurrentDirection()
                    ));
        }

        // Отправляем каждому клиенту
        for (Map.Entry<String, ClientInfo> entry : clients.entrySet()) {
            String playerId = entry.getKey();
            ClientInfo client = entry.getValue();
            if (client.getChannel().isActive()) {
                GameState state = new GameState(allPlayers, client.getMaze(), playerId);
                client.getChannel().writeAndFlush(state);
            }
        }
    }

    private class GameServerHandler extends SimpleChannelInboundHandler<Command> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            System.out.println("Client connected: " + ctx.channel().remoteAddress());
            String playerId = "Player" + (nextPlayerId++);
            ClientInfo clientInfo = new ClientInfo(ctx.channel(), playerId);
            clients.put(playerId, clientInfo);
            channelToId.put(ctx.channel(), playerId);

            System.out.println("Assigned ID: " + playerId + ", Total players: " + clients.size());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Command command) {
            String playerId = channelToId.get(ctx.channel());
            if (playerId == null) return;

            ClientInfo clientInfo = clients.get(playerId);
            if (clientInfo == null) return;

            switch (command.getType()) {
                case MOVE:
                    if (command.getDirection() != null) {
                        clientInfo.handleSingleDirection(command.getDirection());
                    } else if (command.getKeysPressed() != null) {
                        boolean[] keys = command.getKeysPressed();
                        clientInfo.handleComplexMove(keys[0], keys[1], keys[2], keys[3]);
                    }
                    break;
                case BOUNCE_MODE:
                    clientInfo.handleBounceMode(command.isHorizontalBounce(), command.isVerticalBounce());
                    break;
                case RESPAWN:
                    clientInfo.respawn();
                    break;
                case DISCONNECT:
                    ctx.close();
                    break;
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            String playerId = channelToId.remove(ctx.channel());
            if (playerId != null) {
                clients.remove(playerId);
                System.out.println("Client disconnected: " + playerId + ", Total players: " + clients.size());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    private static class ClientInfo {
        private Channel channel;
        private String playerId;
        private int[][] maze;
        private int currentTileX, currentTileY;
        private int targetTileX, targetTileY;
        private float moveTime;
        private float moveDuration = 0.05f;
        private boolean isMoving;
        private Queue<int[]> moveQueue;
        private int[] currentMoveCommand;
        private boolean isBouncingMode;
        private int bounceDirection;
        private boolean rightPressed, leftPressed, upPressed, downPressed;
        private int score;
        private String currentDirection = "RIGHT"; // ← ДОБАВЛЕНО ПОЛЕ

        public ClientInfo(Channel channel, String playerId) {
            this.channel = channel;
            this.playerId = playerId;
            initializeMaze();
            this.currentTileX = 1;
            this.currentTileY = 1;
            this.targetTileX = 1;
            this.targetTileY = 1;
            this.moveTime = 0;
            this.isMoving = false;
            this.moveQueue = new LinkedList<>();
            this.isBouncingMode = false;
            this.bounceDirection = 1;
            this.score = 0;
            this.currentDirection = "RIGHT";
        }

        private void initializeMaze() {
            maze = new int[][] {
                    {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1},
                    {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,1,1,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,1,0,1,1,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,1,0,1,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,1,0,1,0,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,1,1,1,0,1,1,1,1,1,1,1,1,1,0,0,0,0,0,0,0,1},
                    {1,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,1,0,1,1,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,1,0,1,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,1,0,1,0,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,1},
                    {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,0,1,1,1,1,1,1,1,0,0,0,0,0,0,1,1,1,1,1,1,0,1},
                    {1,0,1,0,0,0,0,0,1,0,0,0,0,0,0,1,0,0,0,0,1,0,1},
                    {1,0,1,0,1,1,1,1,1,0,0,0,0,0,0,1,0,1,1,1,1,0,1},
                    {1,0,1,0,1,0,0,0,0,0,0,0,0,0,0,1,0,1,0,0,0,0,1},
                    {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                    {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1}
            };
        }

        public Channel getChannel() { return channel; }
        public int getCurrentTileX() { return currentTileX; }
        public int getCurrentTileY() { return currentTileY; }
        public int[][] getMaze() { return maze; }
        public int getScore() { return score; }
        public String getCurrentDirection() { return currentDirection; }

        public void handleSingleDirection(Direction direction) {
            if (isMoving) return;

            int newX = currentTileX;
            int newY = currentTileY;

            switch (direction) {
                case UP: newY++; break;
                case DOWN: newY--; break;
                case LEFT: newX--; break;
                case RIGHT: newX++; break;
                default: return;
            }

            if (!isWall(newX, newY)) {
                addMoveCommand(newX, newY);
            }
        }

        public void handleComplexMove(boolean up, boolean down, boolean left, boolean right) {
            upPressed = up;
            downPressed = down;
            leftPressed = left;
            rightPressed = right;

            if (isMoving) return;

            if (up && left && !right && !down) {
                if (!isWall(currentTileX, currentTileY + 1)) {
                    addMoveCommand(currentTileX, currentTileY + 1);
                    if (!isWall(currentTileX - 1, currentTileY + 1)) {
                        addMoveCommand(currentTileX - 1, currentTileY + 1);
                    }
                } else if (!isWall(currentTileX - 1, currentTileY)) {
                    addMoveCommand(currentTileX - 1, currentTileY);
                }
            }
            else if (up && right && !left && !down) {
                if (!isWall(currentTileX, currentTileY + 1)) {
                    addMoveCommand(currentTileX, currentTileY + 1);
                    if (!isWall(currentTileX + 1, currentTileY + 1)) {
                        addMoveCommand(currentTileX + 1, currentTileY + 1);
                    }
                } else if (!isWall(currentTileX + 1, currentTileY)) {
                    addMoveCommand(currentTileX + 1, currentTileY);
                }
            }
            else if (down && left && !up && !right) {
                if (!isWall(currentTileX, currentTileY - 1)) {
                    addMoveCommand(currentTileX, currentTileY - 1);
                    if (!isWall(currentTileX - 1, currentTileY - 1)) {
                        addMoveCommand(currentTileX - 1, currentTileY - 1);
                    }
                } else if (!isWall(currentTileX - 1, currentTileY)) {
                    addMoveCommand(currentTileX - 1, currentTileY);
                }
            }
            else if (down && right && !up && !left) {
                if (!isWall(currentTileX, currentTileY - 1)) {
                    addMoveCommand(currentTileX, currentTileY - 1);
                    if (!isWall(currentTileX + 1, currentTileY - 1)) {
                        addMoveCommand(currentTileX + 1, currentTileY - 1);
                    }
                } else if (!isWall(currentTileX + 1, currentTileY)) {
                    addMoveCommand(currentTileX + 1, currentTileY);
                }
            }
            else {
                if (up && !isWall(currentTileX, currentTileY + 1)) {
                    addMoveCommand(currentTileX, currentTileY + 1);
                } else if (down && !isWall(currentTileX, currentTileY - 1)) {
                    addMoveCommand(currentTileX, currentTileY - 1);
                } else if (left && !isWall(currentTileX - 1, currentTileY)) {
                    addMoveCommand(currentTileX - 1, currentTileY);
                } else if (right && !isWall(currentTileX + 1, currentTileY)) {
                    addMoveCommand(currentTileX + 1, currentTileY);
                }
            }
        }

        public void handleBounceMode(boolean isHorizontal, boolean isVertical) {
            if (isMoving) return;

            isBouncingMode = true;

            if (isHorizontal) {
                if (currentTileX == 1) bounceDirection = 1;
                else if (currentTileX == 21) bounceDirection = -1;

                if (!isMoving && moveQueue.isEmpty()) {
                    int nextX = currentTileX + bounceDirection;
                    if (nextX >= 1 && nextX <= 21 && !isWall(nextX, currentTileY)) {
                        addMoveCommand(nextX, currentTileY);
                    } else {
                        bounceDirection *= -1;
                        nextX = currentTileX + bounceDirection;
                        if (!isWall(nextX, currentTileY)) {
                            addMoveCommand(nextX, currentTileY);
                        }
                    }
                }
            } else if (isVertical) {
                if (currentTileY == 1) bounceDirection = 1;
                else if (currentTileY == 20) bounceDirection = -1;

                if (!isMoving && moveQueue.isEmpty()) {
                    int nextY = currentTileY + bounceDirection;
                    if (nextY >= 1 && nextY <= 20 && !isWall(currentTileX, nextY)) {
                        addMoveCommand(currentTileX, nextY);
                    } else {
                        bounceDirection *= -1;
                        nextY = currentTileY + bounceDirection;
                        if (!isWall(currentTileX, nextY)) {
                            addMoveCommand(currentTileX, nextY);
                        }
                    }
                }
            }
        }

        public void respawn() {
            currentTileX = 1;
            currentTileY = 1;
            targetTileX = 1;
            targetTileY = 1;
            moveQueue.clear();
            isMoving = false;
            isBouncingMode = false;
            bounceDirection = 1;
            currentDirection = "RIGHT";
            System.out.println(playerId + " respawned at (1,1)");
        }

        private void addMoveCommand(int x, int y) {
            moveQueue.add(new int[]{x, y});
            if (!isMoving) {
                startNextMove();
            }
        }

        private void startNextMove() {
            if (!moveQueue.isEmpty()) {
                currentMoveCommand = moveQueue.poll();
                targetTileX = currentMoveCommand[0];
                targetTileY = currentMoveCommand[1];
                moveTime = 0;
                isMoving = true;

                // Определяем направление движения
                if (targetTileX > currentTileX) {
                    currentDirection = "RIGHT";
                } else if (targetTileX < currentTileX) {
                    currentDirection = "LEFT";
                } else if (targetTileY > currentTileY) {
                    currentDirection = "UP";
                } else if (targetTileY < currentTileY) {
                    currentDirection = "DOWN";
                }
            }
        }

        private boolean isWall(int x, int y) {
            if (x < 0 || x >= maze[0].length || y < 0 || y >= maze.length) {
                return true;
            }
            return maze[y][x] == 1;
        }

        public void update(float delta) {
            if (isMoving) {
                moveTime += delta;

                if (moveTime >= moveDuration) {
                    currentTileX = targetTileX;
                    currentTileY = targetTileY;
                    isMoving = false;

                    if (isBouncingMode) {
                        handleBouncingModeContinue();
                    } else {
                        startNextMove();
                    }
                }
            }
        }

        private void handleBouncingModeContinue() {
            boolean onlyHorizontal = (rightPressed || leftPressed) && !upPressed && !downPressed;
            boolean onlyVertical = (upPressed || downPressed) && !rightPressed && !leftPressed;

            if (onlyHorizontal && rightPressed && leftPressed) {
                int nextX = currentTileX + bounceDirection;
                if (nextX >= 1 && nextX <= 21 && !isWall(nextX, currentTileY)) {
                    addMoveCommand(nextX, currentTileY);
                } else {
                    bounceDirection *= -1;
                    nextX = currentTileX + bounceDirection;
                    if (!isWall(nextX, currentTileY)) {
                        addMoveCommand(nextX, currentTileY);
                    }
                }
            } else if (onlyVertical && upPressed && downPressed) {
                int nextY = currentTileY + bounceDirection;
                if (nextY >= 1 && nextY <= 20 && !isWall(currentTileX, nextY)) {
                    addMoveCommand(currentTileX, nextY);
                } else {
                    bounceDirection *= -1;
                    nextY = currentTileY + bounceDirection;
                    if (!isWall(currentTileX, nextY)) {
                        addMoveCommand(currentTileX, nextY);
                    }
                }
            }
            startNextMove();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        new GameServer(port).start();
    }
}