package me.batmaninv;

public enum Check {
    FAST_HOTBAR("fast-hotbar", "FastHotbar"),
    INV_MOVE("inv-move", "InvMove"),
    FAST_MOUSE("fast-mouse", "FastMouse");

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

    /** Nombre mostrado: FastHotbar, InvMove, FastMouse */
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
