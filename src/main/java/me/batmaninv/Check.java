package me.batmaninv;

public enum Check {
    FAST_HOTBAR("fast-hotbar", "FastHotbar"),
    INV_MOVE("inv-move", "InvMove"),
    FAST_MOUSE("fast-mouse", "FastMouse"),
    AUTO_ARMOR("auto-armor", "AutoArmor"),
    CONTAINER_WALL("container-wall", "ContainerWall");

    private final String id;
    private final String display;

    Check(String id, String display) {
        this.id = id;
        this.display = display;
    }

    /** Clave usada en config.yml / messages.yml / data.yml */
    public String id() {
        return id;
    }

    /** Nombre mostrado: FastHotbar, InvMove, FastMouse, AutoArmor, ContainerWall */
    public String display() {
        return display;
    }

    public static Check byId(String id) {
        for (Check c : values()) {
            if (c.id.equalsIgnoreCase(id)) {
                return c;
            }
        }
        return null;
    }
}
