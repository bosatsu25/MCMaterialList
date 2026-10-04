package dev.mcmateriallist.fabric.client.work;

/** Pinned upstream screen geometry, in Minecraft GUI coordinates. */
public final class WorkScreenGeometry {
    private WorkScreenGeometry() {}
    public static boolean compact(int width, int height) { return width >= 600 && height >= 328; }
}
