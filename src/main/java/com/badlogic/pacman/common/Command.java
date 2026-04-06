package com.badlogic.pacman.common;

import java.io.Serializable;

public class Command implements Serializable {
    private static final long serialVersionUID = 1L;

    private CommandType type;
    private Direction direction;
    private boolean[] keysPressed;
    private boolean horizontalBounce;
    private boolean verticalBounce;

    public Command(CommandType type) {
        this.type = type;
    }

    public Command(CommandType type, Direction direction) {
        this.type = type;
        this.direction = direction;
    }

    public Command(CommandType type, boolean[] keysPressed) {
        this.type = type;
        this.keysPressed = keysPressed;
    }

    public Command(CommandType type, boolean horizontalBounce, boolean verticalBounce) {
        this.type = type;
        this.horizontalBounce = horizontalBounce;
        this.verticalBounce = verticalBounce;
    }

    public CommandType getType() { return type; }
    public Direction getDirection() { return direction; }
    public boolean[] getKeysPressed() { return keysPressed; }
    public boolean isHorizontalBounce() { return horizontalBounce; }
    public boolean isVerticalBounce() { return verticalBounce; }
}