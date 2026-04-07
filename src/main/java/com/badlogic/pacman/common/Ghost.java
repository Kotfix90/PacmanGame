package com.badlogic.pacman.common;

import java.io.Serializable;
import java.util.Random;

public class Ghost implements Serializable {
    private static final long serialVersionUID = 1L;

    private int x;
    private int y;
    private final double speed;
    private String direction;
    private double moveAccumulator = 0;
    private String color;
    private double moveDelay = 0;

    private boolean isChasing = false;
    private int targetX = -1;
    private int targetY = -1;
    private transient Random random = new Random();

    // Память о последней позиции игрока
    private int lastKnownPlayerX = -1;
    private int lastKnownPlayerY = -1;
    private double memoryTimer = 0;
    private static final double MEMORY_DURATION = 2.0; // 2 секунды памяти
    private boolean hasMemory = false;

    public Ghost(int x, int y, double speed) {
        this(x, y, speed, null);
    }

    public Ghost(int x, int y, double speed, String color) {
        this.x = x;
        this.y = y;
        this.speed = speed;
        this.color = color;
        this.direction = "RIGHT";
    }

    // Геттеры и сеттеры
    public int getX() { return x; }
    public int getY() { return y; }
    public double getSpeed() { return speed; }
    public String getDirection() { return direction; }
    public String getColor() { return color; }
    public boolean isChasing() { return isChasing; }

    public void setX(int x) { this.x = x; }
    public void setY(int y) { this.y = y; }
    public void setDirection(String direction) { this.direction = direction; }
    public void setColor(String color) { this.color = color; }
    public void setChasing(boolean chasing) { this.isChasing = chasing; }

    public void move(int[][] maze) {
        moveAccumulator += speed;

        if (moveAccumulator < 1.0) {
            return;
        }

        moveAccumulator = 0;

        String newDirection;

        if (isChasing && targetX != -1 && targetY != -1) {
            // Режим преследования - выбираем направление к цели
            newDirection = getDirectionTowardsTarget(targetX, targetY);
        } else if (hasMemory && lastKnownPlayerX != -1 && lastKnownPlayerY != -1) {
            // Режим памяти - двигаемся к последнему известному месту
            newDirection = getDirectionTowardsTarget(lastKnownPlayerX, lastKnownPlayerY);
        } else {
            // Случайное движение
            String[] directions = {"UP", "DOWN", "LEFT", "RIGHT"};
            newDirection = directions[random.nextInt(directions.length)];
        }

        int newX = x;
        int newY = y;

        switch (newDirection) {
            case "UP": newY--; break;
            case "DOWN": newY++; break;
            case "LEFT": newX--; break;
            case "RIGHT": newX++; break;
        }

        if (newX >= 0 && newX < maze[0].length && newY >= 0 && newY < maze.length && maze[newY][newX] != 1) {
            x = newX;
            y = newY;
            direction = newDirection;
        }
    }

    // Новый метод для проверки видимости игрока с учетом направления взгляда
    public boolean canSeePlayer(int playerX, int playerY, int[][] maze) {
        // Проверяем, находится ли игрок в поле зрения призрака
        if (!isInFieldOfView(playerX, playerY)) {
            return false;
        }

        // Проверяем линию видимости
        return hasLineOfSight(playerX, playerY, maze);
    }

    // Проверка, находится ли игрок в поле зрения (90 градусов вперед + диагонали)
    private boolean isInFieldOfView(int playerX, int playerY) {
        int dx = playerX - x;
        int dy = playerY - y;

        switch (direction) {
            case "UP":
                // Смотрим вверх: видим игроков сверху, слева-сверху, справа-сверху
                // НЕ видим: снизу, справа, слева, справа-снизу, слева-снизу
                return dy < 0 && Math.abs(dx) <= Math.abs(dy);

            case "DOWN":
                // Смотрим вниз: видим игроков снизу, слева-снизу, справа-снизу
                // НЕ видим: сверху, справа, слева, справа-сверху, слева-сверху
                return dy > 0 && Math.abs(dx) <= Math.abs(dy);

            case "LEFT":
                // Смотрим влево: видим игроков слева, слева-сверху, слева-снизу
                // НЕ видим: справа, сверху, снизу, справа-сверху, справа-снизу
                return dx < 0 && Math.abs(dy) <= Math.abs(dx);

            case "RIGHT":
                // Смотрим вправо: видим игроков справа, справа-сверху, справа-снизу
                // НЕ видим: слева, сверху, снизу, слева-сверху, слева-снизу
                return dx > 0 && Math.abs(dy) <= Math.abs(dx);

            default:
                return false;
        }
    }

    // Проверка прямой видимости без стен
    private boolean hasLineOfSight(int playerX, int playerY, int[][] maze) {
        int dx = playerX - x;
        int dy = playerY - y;

        // Используем алгоритм Бресенхема для проверки линии
        int steps = Math.max(Math.abs(dx), Math.abs(dy));

        if (steps == 0) return true;

        float stepX = (float) dx / steps;
        float stepY = (float) dy / steps;

        float currentX = x;
        float currentY = y;

        for (int i = 1; i <= steps; i++) {
            currentX += stepX;
            currentY += stepY;

            int checkX = Math.round(currentX);
            int checkY = Math.round(currentY);

            // Проверяем границы
            if (checkX < 0 || checkX >= maze[0].length || checkY < 0 || checkY >= maze.length) {
                return false;
            }

            // Если на пути стена - нет видимости
            if (maze[checkY][checkX] == 1) {
                return false;
            }

            // Если достигли игрока
            if (checkX == playerX && checkY == playerY) {
                return true;
            }
        }

        return true;
    }

    private String getDirectionTowardsTarget(int targetX, int targetY) {
        int dx = targetX - x;
        int dy = targetY - y;

        // Приоритет: сначала движение по оси с большим расстоянием
        if (Math.abs(dx) > Math.abs(dy)) {
            if (dx > 0) return "RIGHT";
            else if (dx < 0) return "LEFT";
        } else if (Math.abs(dy) > Math.abs(dx)) {
            if (dy > 0) return "DOWN";
            else if (dy < 0) return "UP";
        } else {
            // Если расстояния равны, выбираем случайно
            if (random.nextBoolean()) {
                return dx > 0 ? "RIGHT" : "LEFT";
            } else {
                return dy > 0 ? "DOWN" : "UP";
            }
        }

        // Запасной вариант
        String[] directions = {"UP", "DOWN", "LEFT", "RIGHT"};
        return directions[random.nextInt(directions.length)];
    }

    public void setTarget(int playerX, int playerY) {
        this.targetX = playerX;
        this.targetY = playerY;
    }

    public void updateChasingState(int playerX, int playerY, int[][] maze, float deltaTime) {
        boolean seesPlayer = canSeePlayer(playerX, playerY, maze);

        if (seesPlayer) {
            // Видим игрока - обновляем цель и память
            isChasing = true;
            setTarget(playerX, playerY);

            // Сохраняем позицию в память и сбрасываем таймер
            lastKnownPlayerX = playerX;
            lastKnownPlayerY = playerY;
            memoryTimer = MEMORY_DURATION;
            hasMemory = true;

        } else if (isChasing && !seesPlayer) {
            // Только что потеряли игрока из виду
            isChasing = false;
            // Позиция уже сохранена в памяти, таймер уже установлен

        } else if (!isChasing && hasMemory) {
            // В режиме памяти - уменьшаем таймер
            memoryTimer -= deltaTime;

            if (memoryTimer <= 0) {
                // Память истекла
                hasMemory = false;
                lastKnownPlayerX = -1;
                lastKnownPlayerY = -1;
            }

            // Проверяем, достигли ли последнего известного места
            if (hasMemory && x == lastKnownPlayerX && y == lastKnownPlayerY) {
                // Достигли места, где был игрок - сбрасываем память
                hasMemory = false;
                lastKnownPlayerX = -1;
                lastKnownPlayerY = -1;
            }
        }
    }

    // Метод для отладки - возвращает текущее состояние памяти
    public boolean hasMemory() {
        return hasMemory;
    }

    public double getMemoryTimer() {
        return memoryTimer;
    }

    public int getLastKnownPlayerX() {
        return lastKnownPlayerX;
    }

    public int getLastKnownPlayerY() {
        return lastKnownPlayerY;
    }
}