package io.github.restioson.siege.game.active;

public enum SiegePersonalResource {
    WOOD(64),
    TNT(2);

    public final int max;

    SiegePersonalResource(int max) {
        this.max = max;
    }
}
