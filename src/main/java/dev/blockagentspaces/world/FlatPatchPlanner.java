package dev.blockagentspaces.world;

/**
 * Validates the deliberately modest piece of terrain used by the in-world installation.
 *
 * <p>This class has no Minecraft dependencies so its rules can be tested without starting a
 * client. The game-facing adapter supplies the height, ground safety, and clearance checks.</p>
 */
public final class FlatPatchPlanner {
    public static final int WIDTH = 31;
    public static final int DEPTH = 11;
    public static final int CLEARANCE_HEIGHT = 6;
    public static final int HALF_WIDTH = WIDTH / 2;
    public static final int HALF_DEPTH = DEPTH / 2;

    private FlatPatchPlanner() { }

    /** Finds the installation footprint centered on the player and explains the first unsafe cell. */
    public static Result plan(Surface surface, int centerX, int centerZ) {
        int centerGroundY = surface.groundY(centerX, centerZ);
        for (int x = centerX - HALF_WIDTH; x <= centerX + HALF_WIDTH; x++) {
            for (int z = centerZ - HALF_DEPTH; z <= centerZ + HALF_DEPTH; z++) {
                int relativeX = x - centerX;
                int relativeZ = z - centerZ;
                int groundY = surface.groundY(x, z);
                if (!surface.hasSafeGround(x, groundY, z)) {
                    return Result.unsuitable("The ground at " + relative(relativeX, relativeZ)
                        + " is not solid and safe. Stand in the middle of a solid, dry patch.");
                }
                if (groundY != centerGroundY) {
                    int difference = groundY - centerGroundY;
                    return Result.unsuitable("The patch is not level: terrain at " + relative(relativeX, relativeZ)
                        + " is " + Math.abs(difference) + " block" + (Math.abs(difference) == 1 ? "" : "s")
                        + (difference > 0 ? " higher" : " lower")
                        + ". Stand in the middle of a level " + WIDTH + "×" + DEPTH + " patch.");
                }
                for (int y = groundY + 1; y <= groundY + CLEARANCE_HEIGHT; y++) {
                    if (!surface.isClear(x, y, z)) {
                        return Result.unsuitable("The build volume is blocked at " + relative(relativeX, relativeZ)
                            + ", " + (y - groundY) + " block" + (y - groundY == 1 ? "" : "s")
                            + " above the ground. Clear the space above the patch and try again.");
                    }
                }
            }
        }
        return Result.suitable(new Site(centerX - HALF_WIDTH, centerGroundY + 1, centerZ - HALF_DEPTH));
    }

    private static String relative(int x, int z) {
        return "relative " + signed(x) + ", " + signed(z);
    }

    private static String signed(int number) {
        return number >= 0 ? "+" + number : Integer.toString(number);
    }

    public interface Surface {
        int groundY(int x, int z);
        boolean hasSafeGround(int x, int y, int z);
        boolean isClear(int x, int y, int z);
    }

    public record Site(int minX, int buildY, int minZ) { }

    public record Result(Site site, String problem) {
        public static Result suitable(Site site) { return new Result(site, ""); }
        public static Result unsuitable(String problem) { return new Result(null, problem); }
        public boolean isSuitable() { return site != null; }
    }
}
