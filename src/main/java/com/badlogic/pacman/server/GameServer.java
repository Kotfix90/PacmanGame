package com.badlogic.pacman.server;

import com.badlogic.pacman.common.*;
import com.badlogic.pacman.server.config.ConfigLoader;
import com.badlogic.pacman.server.config.MazeConfig;
import com.badlogic.pacman.server.config.GhostsConfig;
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
    private final ConcurrentHashMap<String, float[]> playerColors = new ConcurrentHashMap<>();
    private ScheduledExecutorService gameLoop;
    private int nextPlayerId = 1;
    private static final int MAX_PLAYERS = 4;
    private List<Coin> coins = new ArrayList<>();
    private List<Ghost> ghosts = new ArrayList<>();
    private int[][] maze;

    private static final float[][] COLOR_PALETTE = {
            {1.0f, 0.0f, 0.0f},  // Красный
            {0.0f, 0.0f, 1.0f},  // Синий
            {0.0f, 1.0f, 0.0f},  // Зеленый
            {1.0f, 1.0f, 0.0f},  // Желтый
    };

    public GameServer(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        loadConfiguration();
        startGameLoop();

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

            ChannelFuture future = bootstrap.bind(port).sync();
            System.out.println("Server bound to port, waiting for connections...");

            future.channel().closeFuture().sync();
        } finally {
            if (gameLoop != null) {
                gameLoop.shutdown();
            }
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    private void loadConfiguration() throws Exception {
        MazeConfig mazeConfig = ConfigLoader.loadMazeConfig();
        this.maze = mazeConfig.getMaze();
        this.coins = ConfigLoader.loadCoinsFromConfig(mazeConfig);
        GhostsConfig ghostsConfig = ConfigLoader.loadGhostsConfig();
        this.ghosts = ConfigLoader.loadGhostsFromConfig(ghostsConfig);

        System.out.println("=== Configuration Summary ===");
        System.out.println("Maze size: " + maze.length + "x" + maze[0].length);
        System.out.println("Total coins: " + coins.size());
        System.out.println("Total ghosts: " + ghosts.size());
        System.out.println("==============================");
    }

    private void startGameLoop() {
        System.out.println("Starting game loop...");
        gameLoop = Executors.newSingleThreadScheduledExecutor();
        gameLoop.scheduleAtFixedRate(() -> {
            try {
                if (clients.isEmpty()) {
                    return;
                }

                float delta = 1.0f / 60.0f;

                // Обновляем состояние призраков с учетом всех игроков
                updateGhostsWithChasing(delta);

                // Двигаем призраков
                for (Ghost ghost : ghosts) {
                    ghost.move(maze);
                }

                // Обновляем всех игроков
                for (ClientInfo client : clients.values()) {
                    client.update(delta);
                    client.checkGhostCollision();
                }

                broadcastGameState();
            } catch (Exception e) {
                System.err.println("Error in game loop: " + e.getMessage());
                e.printStackTrace();
            }
        }, 0, 16, TimeUnit.MILLISECONDS);
    }

    private void updateGhostsWithChasing(float deltaTime) {
        for (Ghost ghost : ghosts) {
            boolean foundPlayer = false;
            ClientInfo nearestPlayer = null;
            double minDistance = Double.MAX_VALUE;

            for (ClientInfo client : clients.values()) {
                int playerX = client.getCurrentTileX();
                int playerY = client.getCurrentTileY();

                if (ghost.canSeePlayer(playerX, playerY, maze)) {
                    foundPlayer = true;
                    double distance = Math.sqrt(Math.pow(playerX - ghost.getX(), 2) +
                            Math.pow(playerY - ghost.getY(), 2));
                    if (distance < minDistance) {
                        minDistance = distance;
                        nearestPlayer = client;
                    }
                }
            }

            if (foundPlayer && nearestPlayer != null) {
                ghost.updateChasingState(nearestPlayer.getCurrentTileX(),
                        nearestPlayer.getCurrentTileY(),
                        maze, deltaTime);
            } else {
                ghost.updateChasingState(-1, -1, maze, deltaTime);
            }
        }
    }

    private void broadcastGameState() {
        if (clients.isEmpty()) return;

        Map<String, GameState.PlayerState> allPlayers = new HashMap<>();

        for (Map.Entry<String, ClientInfo> entry : clients.entrySet()) {
            String playerId = entry.getKey();
            ClientInfo client = entry.getValue();
            float[] color = playerColors.get(playerId);

            allPlayers.put(playerId,
                    new GameState.PlayerState(
                            client.getCurrentTileX(),
                            client.getCurrentTileY(),
                            client.getScore(),
                            client.getCurrentDirection(),
                            color[0], color[1], color[2]
                    ));
        }

        for (Map.Entry<String, ClientInfo> entry : clients.entrySet()) {
            String playerId = entry.getKey();
            ClientInfo client = entry.getValue();
            if (client.getChannel() != null && client.getChannel().isActive()) {
                GameState state = new GameState(
                        new HashMap<>(allPlayers),
                        client.getMaze(),
                        playerId,
                        new ArrayList<>(coins),
                        new ArrayList<>(ghosts)
                );
                client.getChannel().writeAndFlush(state);
            }
        }
    }

    private class GameServerHandler extends SimpleChannelInboundHandler<Command> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            if (clients.size() >= MAX_PLAYERS) {
                System.out.println("Server is full. Rejecting connection.");
                ctx.close();
                return;
            }

            System.out.println("Client connected: " + ctx.channel().remoteAddress());
            String playerId = "Player" + (nextPlayerId++);

            float[] assignedColor = assignPlayerColor(playerId);

            ClientInfo clientInfo = new ClientInfo(ctx.channel(), playerId);
            clients.put(playerId, clientInfo);
            channelToId.put(ctx.channel(), playerId);
            playerColors.put(playerId, assignedColor);

            System.out.println("Assigned ID: " + playerId +
                    ", Color: RGB(" + assignedColor[0] + "," + assignedColor[1] + "," + assignedColor[2] + ")" +
                    ", Total players: " + clients.size());

            sendInitialGameState(ctx.channel(), playerId);
        }

        private void sendInitialGameState(Channel channel, String playerId) {
            Map<String, GameState.PlayerState> allPlayers = new HashMap<>();

            for (Map.Entry<String, ClientInfo> entry : clients.entrySet()) {
                String pid = entry.getKey();
                ClientInfo client = entry.getValue();
                float[] color = playerColors.get(pid);

                allPlayers.put(pid,
                        new GameState.PlayerState(
                                client.getCurrentTileX(),
                                client.getCurrentTileY(),
                                client.getScore(),
                                client.getCurrentDirection(),
                                color[0], color[1], color[2]
                        ));
            }

            ClientInfo client = clients.get(playerId);
            if (client != null) {
                GameState state = new GameState(
                        allPlayers,
                        client.getMaze(),
                        playerId,
                        new ArrayList<>(coins),
                        new ArrayList<>(ghosts)
                );
                channel.writeAndFlush(state);
                System.out.println("Sent initial game state to " + playerId);
            }
        }

        private float[] assignPlayerColor(String playerId) {
            int colorIndex = (nextPlayerId - 2) % COLOR_PALETTE.length;
            return COLOR_PALETTE[colorIndex].clone();
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
                    System.out.println("Received DISCONNECT command from " + playerId);
                    ctx.close();
                    break;
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            String playerId = channelToId.remove(ctx.channel());
            if (playerId != null) {
                clients.remove(playerId);
                playerColors.remove(playerId);
                System.out.println("Client disconnected: " + playerId + ", Total players: " + clients.size());

                if (!clients.isEmpty()) {
                    broadcastGameState();
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("Exception in server handler:");
            cause.printStackTrace();
            String playerId = channelToId.get(ctx.channel());
            if (playerId != null) {
                System.err.println("Error for player: " + playerId);
            }
            ctx.close();
        }
    }

    private class ClientInfo {
        private Channel channel;
        private String playerId;
        private int[][] maze;
        private int currentTileX, currentTileY;
        private int targetTileX, targetTileY;
        private float moveTime;
        private float moveDuration = 0.1f;
        private boolean isMoving;
        private Queue<int[]> moveQueue;
        private int[] currentMoveCommand;
        private boolean isBouncingMode;
        private int bounceDirection;
        private boolean rightPressed, leftPressed, upPressed, downPressed;
        private int score;
        private String currentDirection = "RIGHT";
        private boolean isAlive = true;

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

        public Channel getChannel() { return channel; }
        public int getCurrentTileX() { return currentTileX; }
        public int getCurrentTileY() { return currentTileY; }
        public int[][] getMaze() { return maze; }
        public int getScore() { return score; }
        public String getCurrentDirection() { return currentDirection; }

        private void initializeMaze() {
            this.maze = new int[GameServer.this.maze.length][];
            for (int i = 0; i < GameServer.this.maze.length; i++) {
                this.maze[i] = GameServer.this.maze[i].clone();
            }
        }

        private boolean isPlayerAt(int x, int y) {
            for (ClientInfo client : clients.values()) {
                if (client != this && client.currentTileX == x && client.currentTileY == y && client.isAlive) {
                    return true;
                }
            }
            return false;
        }

        private void checkCoinCollision(int x, int y) {
            Iterator<Coin> iterator = coins.iterator();
            while (iterator.hasNext()) {
                Coin coin = iterator.next();
                if (coin.getX() == x && coin.getY() == y) {
                    iterator.remove();
                    score++;
                    System.out.println(playerId + " collected a coin! Score: " + score);
                }
            }
        }

        public void checkGhostCollision() {
            for (Ghost ghost : ghosts) {
                if (ghost.getX() == currentTileX && ghost.getY() == currentTileY && isAlive) {
                    respawn();
                    System.out.println(playerId + " was caught by a ghost!");
                    break;
                }
            }
        }

        public void handleSingleDirection(Direction direction) {
            if (isMoving || !isAlive) return;

            int newX = currentTileX;
            int newY = currentTileY;

            switch (direction) {
                case UP: newY++; break;
                case DOWN: newY--; break;
                case LEFT: newX--; break;
                case RIGHT: newX++; break;
                default: return;
            }

            if (!isWall(newX, newY) && !isPlayerAt(newX, newY)) {
                addMoveCommand(newX, newY);
            }
        }

        public void handleComplexMove(boolean up, boolean down, boolean left, boolean right) {
            if (!isAlive) return;

            upPressed = up;
            downPressed = down;
            leftPressed = left;
            rightPressed = right;

            if (isMoving) return;

            if (up && left && !right && !down) {
                if (!isWall(currentTileX, currentTileY + 1) && !isPlayerAt(currentTileX, currentTileY + 1)) {
                    addMoveCommand(currentTileX, currentTileY + 1);
                    if (!isWall(currentTileX - 1, currentTileY + 1) && !isPlayerAt(currentTileX - 1, currentTileY + 1)) {
                        addMoveCommand(currentTileX - 1, currentTileY + 1);
                    }
                } else if (!isWall(currentTileX - 1, currentTileY) && !isPlayerAt(currentTileX - 1, currentTileY)) {
                    addMoveCommand(currentTileX - 1, currentTileY);
                }
            }
            else if (up && right && !left && !down) {
                if (!isWall(currentTileX, currentTileY + 1) && !isPlayerAt(currentTileX, currentTileY + 1)) {
                    addMoveCommand(currentTileX, currentTileY + 1);
                    if (!isWall(currentTileX + 1, currentTileY + 1) && !isPlayerAt(currentTileX + 1, currentTileY + 1)) {
                        addMoveCommand(currentTileX + 1, currentTileY + 1);
                    }
                } else if (!isWall(currentTileX + 1, currentTileY) && !isPlayerAt(currentTileX + 1, currentTileY)) {
                    addMoveCommand(currentTileX + 1, currentTileY);
                }
            }
            else if (down && left && !up && !right) {
                if (!isWall(currentTileX, currentTileY - 1) && !isPlayerAt(currentTileX, currentTileY - 1)) {
                    addMoveCommand(currentTileX, currentTileY - 1);
                    if (!isWall(currentTileX - 1, currentTileY - 1) && !isPlayerAt(currentTileX - 1, currentTileY - 1)) {
                        addMoveCommand(currentTileX - 1, currentTileY - 1);
                    }
                } else if (!isWall(currentTileX - 1, currentTileY) && !isPlayerAt(currentTileX - 1, currentTileY)) {
                    addMoveCommand(currentTileX - 1, currentTileY);
                }
            }
            else if (down && right && !up && !left) {
                if (!isWall(currentTileX, currentTileY - 1) && !isPlayerAt(currentTileX, currentTileY - 1)) {
                    addMoveCommand(currentTileX, currentTileY - 1);
                    if (!isWall(currentTileX + 1, currentTileY - 1) && !isPlayerAt(currentTileX + 1, currentTileY - 1)) {
                        addMoveCommand(currentTileX + 1, currentTileY - 1);
                    }
                } else if (!isWall(currentTileX + 1, currentTileY) && !isPlayerAt(currentTileX + 1, currentTileY)) {
                    addMoveCommand(currentTileX + 1, currentTileY);
                }
            }
            else {
                if (up && !isWall(currentTileX, currentTileY + 1) && !isPlayerAt(currentTileX, currentTileY + 1)) {
                    addMoveCommand(currentTileX, currentTileY + 1);
                } else if (down && !isWall(currentTileX, currentTileY - 1) && !isPlayerAt(currentTileX, currentTileY - 1)) {
                    addMoveCommand(currentTileX, currentTileY - 1);
                } else if (left && !isWall(currentTileX - 1, currentTileY) && !isPlayerAt(currentTileX - 1, currentTileY)) {
                    addMoveCommand(currentTileX - 1, currentTileY);
                } else if (right && !isWall(currentTileX + 1, currentTileY) && !isPlayerAt(currentTileX + 1, currentTileY)) {
                    addMoveCommand(currentTileX + 1, currentTileY);
                }
            }
        }

        public void handleBounceMode(boolean isHorizontal, boolean isVertical) {
            if (isMoving || !isAlive) return;

            isBouncingMode = true;

            if (isHorizontal) {
                if (currentTileX == 1) bounceDirection = 1;
                else if (currentTileX == 21) bounceDirection = -1;

                if (!isMoving && moveQueue.isEmpty()) {
                    int nextX = currentTileX + bounceDirection;
                    if (nextX >= 1 && nextX <= 21 && !isWall(nextX, currentTileY) && !isPlayerAt(nextX, currentTileY)) {
                        addMoveCommand(nextX, currentTileY);
                    } else {
                        bounceDirection *= -1;
                        nextX = currentTileX + bounceDirection;
                        if (!isWall(nextX, currentTileY) && !isPlayerAt(nextX, currentTileY)) {
                            addMoveCommand(nextX, currentTileY);
                        }
                    }
                }
            } else if (isVertical) {
                if (currentTileY == 1) bounceDirection = 1;
                else if (currentTileY == 20) bounceDirection = -1;

                if (!isMoving && moveQueue.isEmpty()) {
                    int nextY = currentTileY + bounceDirection;
                    if (nextY >= 1 && nextY <= 20 && !isWall(currentTileX, nextY) && !isPlayerAt(currentTileX, nextY)) {
                        addMoveCommand(currentTileX, nextY);
                    } else {
                        bounceDirection *= -1;
                        nextY = currentTileY + bounceDirection;
                        if (!isWall(currentTileX, nextY) && !isPlayerAt(currentTileX, nextY)) {
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
            isAlive = true;
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
            if (!isAlive) return;

            if (isMoving) {
                moveTime += delta;

                if (moveTime >= moveDuration) {
                    if (!isPlayerAt(targetTileX, targetTileY)) {
                        currentTileX = targetTileX;
                        currentTileY = targetTileY;
                        checkCoinCollision(currentTileX, currentTileY);
                    } else {
                        moveQueue.clear();
                    }

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
                if (nextX >= 1 && nextX <= 21 && !isWall(nextX, currentTileY) && !isPlayerAt(nextX, currentTileY)) {
                    addMoveCommand(nextX, currentTileY);
                } else {
                    bounceDirection *= -1;
                    nextX = currentTileX + bounceDirection;
                    if (!isWall(nextX, currentTileY) && !isPlayerAt(nextX, currentTileY)) {
                        addMoveCommand(nextX, currentTileY);
                    }
                }
            } else if (onlyVertical && upPressed && downPressed) {
                int nextY = currentTileY + bounceDirection;
                if (nextY >= 1 && nextY <= 20 && !isWall(currentTileX, nextY) && !isPlayerAt(currentTileX, nextY)) {
                    addMoveCommand(currentTileX, nextY);
                } else {
                    bounceDirection *= -1;
                    nextY = currentTileY + bounceDirection;
                    if (!isWall(currentTileX, nextY) && !isPlayerAt(currentTileX, nextY)) {
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