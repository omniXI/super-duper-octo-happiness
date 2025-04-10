package pwn.noobs.trouserstreak.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.block.entity.*;
import net.minecraft.client.option.KeyBinding; // <-- Important import for pressing keys
import net.minecraft.entity.EntityPose;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import pwn.noobs.trouserstreak.Trouser;
import pwn.noobs.trouserstreak.modules.NewerNewChunks;

import java.util.HashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;

public class AdvancedExplorer extends Module {
    private enum ExplorerState {
        NAVIGATING,
        DETECTED,
        AUTO_XP_STARTING,
        AUTO_XP_PLACING_BLOCK,
        AUTO_XP_LOOKING_DOWN,
        AUTO_XP_USING_BOTTLES,
        AUTO_TAKE_OFF
    }

    private enum ChunkStatus {
        NEW,
        OLD,
        OLD_GENERATION,
        BEING_UPDATED,
        TICK_EXPLOIT,
        UNKNOWN
    }

    // --- Setting Groups ---
    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgNavigation = settings.createGroup("Navigation");
    private final SettingGroup sgDetection  = settings.createGroup("Detection");
    private final SettingGroup sgAutoXP     = settings.createGroup("AutoXP");

    // --- General Settings ---
    private final Setting<Boolean> stopOnFound = sgGeneral.add(new BoolSetting.Builder()
        .name("Stop-On-Found")
        .description("Stop when indicator found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyOnFound = sgGeneral.add(new BoolSetting.Builder()
        .name("Notify-On-Found")
        .description("Notify when indicator found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate player view towards targets.")
        .defaultValue(true)
        .build()
    );

    // --- Navigation Settings ---
    private final Setting<Integer> searchRadius = sgNavigation.add(new IntSetting.Builder()
        .name("Search-Radius")
        .description("Radius in chunks.")
        .defaultValue(64)
        .min(8)
        .sliderRange(8, 128)
        .build()
    );

    private final Setting<Integer> spiralStepSize = sgNavigation.add(new IntSetting.Builder()
        .name("Spiral-Step-Size")
        .description("How many chunks to jump per logical spiral step.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Integer> spiralStartLayer = sgNavigation.add(new IntSetting.Builder()
        .name("Spiral-Start-Layer")
        .description("Which spiral layer (distance from center) to start at.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> visitedChunkCacheSize = sgNavigation.add(new IntSetting.Builder()
        .name("Visited-Chunk-Cache")
        .description("How many recent chunks to remember to avoid revisiting.")
        .defaultValue(50)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    // --- Detection Settings ---
    private final Setting<Boolean> checkContainers = sgDetection.add(new BoolSetting.Builder()
        .name("Check-Containers")
        .description("Detect containers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> containerScanRadius = sgDetection.add(new IntSetting.Builder()
        .name("Container-Scan-Radius")
        .description("Radius for container scan.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 16)
        .visible(checkContainers::get)
        .build()
    );

    // --- AutoXP Settings ---
    private final Setting<Boolean> enableAutoXP = sgAutoXP.add(new BoolSetting.Builder()
        .name("Enable-AutoXP")
        .description("Enable AutoXP feature.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> elytraDurabilityThreshold = sgAutoXP.add(new IntSetting.Builder()
        .name("Elytra-Durability-Threshold")
        .description("Min durability % for AutoXP.")
        .defaultValue(50)
        .min(1)
        .max(99)
        .sliderRange(1, 99)
        .visible(enableAutoXP::get)
        .build()
    );

    private final Setting<Integer> autoXpBottleCount = sgAutoXP.add(new IntSetting.Builder()
        .name("XP-Bottle-Count")
        .description("Bottles per AutoXP cycle.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 64)
        .visible(enableAutoXP::get)
        .build()
    );

    private final Setting<Block> blockToPlaceBelow = sgAutoXP.add(new BlockSetting.Builder()
        .name("Block-To-Place")
        .description("Block for XP stand.")
        .defaultValue(Blocks.OBSIDIAN)
        .visible(enableAutoXP::get)
        .build()
    );

    private final Setting<Integer> xpWaitTicks = sgAutoXP.add(new IntSetting.Builder()
        .name("XP-Wait-Ticks")
        .description("Delay between bottle throws.")
        .defaultValue(4)
        .min(0)
        .sliderRange(0, 20)
        .visible(enableAutoXP::get)
        .build()
    );

    private final Setting<Boolean> enableAutoTakeOff = sgAutoXP.add(new BoolSetting.Builder()
        .name("Enable-Auto-TakeOff")
        .description("Attempt takeoff after XP.")
        .defaultValue(true)
        .visible(enableAutoXP::get)
        .build()
    );

    // --- Module State Variables ---
    private ExplorerState currentState = ExplorerState.NAVIGATING;
    private ChunkPos currentTargetChunk = null;
    private ChunkPos nextSpiralChunk = null;
    private BlockPos foundIndicatorPos = null;
    private boolean searching = false;

    private int stuckTicks = 0;
    private BlockPos lastPos = null;
    private int autoXpStateTicks = 0;
    private int bottlesUsed = 0;
    private int originalSlot = -1;

    // Spiral navigation state
    private ChunkPos spiralCenter = null;
    private int spiralLeg = 0;
    private int spiralSteps = 1;
    private int spiralStepsTaken = 0;
    private int spiralLayer = 1;

    // Visited chunk tracking
    private final Set<ChunkPos> visitedChunksSet = Collections.synchronizedSet(new HashSet<>());
    private final Deque<ChunkPos> visitedChunksQueue = new ArrayDeque<>();

    public AdvancedExplorer() {
        super(
            Trouser.Main,
            "Advanced-Explorer",
            "Finds bases in old chunks, detects containers, and uses AutoXP."
        );
    }

    @Override
    public void onActivate() {
        resetState();
        if (mc.player != null) {
            spiralCenter = mc.player.getChunkPos();
            lastPos = mc.player.getBlockPos();
            visitedChunksSet.add(spiralCenter);
            visitedChunksQueue.add(spiralCenter);
            resetSpiralState();
            nextSpiralChunk = calculateAndAdvanceSpiralTarget();
        } else {
            spiralCenter = null;
        }

        if (notifyOnFound.get()) {
            ChatUtils.info("Advanced Explorer activated.");
        }
    }

    @Override
    public void onDeactivate() {
        // Stop forward movement
        if (mc.player != null) {
            mc.options.forwardKey.setPressed(false);
        }

        if (mc.player != null && (currentState == ExplorerState.NAVIGATING || currentState == ExplorerState.DETECTED)) {
            mc.player.setVelocity(Vec3d.ZERO);
        }

        if (mc.player != null && originalSlot != -1 && originalSlot != mc.player.getInventory().selectedSlot) {
            InvUtils.swap(originalSlot, false);
        }

        resetState();

        if (notifyOnFound.get()) {
            ChatUtils.info("Advanced Explorer deactivated.");
        }
    }

    private void resetState() {
        currentState = ExplorerState.NAVIGATING;
        foundIndicatorPos = null;
        searching = true;
        currentTargetChunk = null;
        nextSpiralChunk = null;
        stuckTicks = 0;
        lastPos = null;
        autoXpStateTicks = 0;
        bottlesUsed = 0;
        originalSlot = -1;

        resetSpiralState();
        visitedChunksSet.clear();
        visitedChunksQueue.clear();
    }

    private void resetSpiralState() {
        spiralLeg = 0;
        spiralLayer = Math.max(1, spiralStartLayer.get());
        spiralSteps = spiralLayer;
        spiralStepsTaken = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        updateVisitedChunks();
        detectStuck();

        switch (currentState) {
            case NAVIGATING:
                handleNavigationState();
                break;
            case DETECTED:
                handleDetectedState();
                break;
            case AUTO_XP_STARTING:
                handleAutoXPStartingState();
                break;
            case AUTO_XP_PLACING_BLOCK:
                handleAutoXPPlacingBlockState();
                break;
            case AUTO_XP_LOOKING_DOWN:
                handleAutoXPLookingDownState();
                break;
            case AUTO_XP_USING_BOTTLES:
                handleAutoXPUsingBottlesState();
                break;
            case AUTO_TAKE_OFF:
                handleAutoTakeOffState();
                break;
            default:
                warning("Entered unknown explorer state! Resetting to NAVIGATING.");
                currentState = ExplorerState.NAVIGATING;
                break;
        }
    }

    // ---------------------------------------------------
    // Movement + Navigation
    // ---------------------------------------------------
    private void handleNavigationState() {
        if (!searching) {
            // Stop forward key if not searching
            mc.options.forwardKey.setPressed(false);
            return;
        }

        // Check if we need AutoXP
        if (enableAutoXP.get() && checkAutoXPTrigger()) return;

        // Container scanning
        if (checkContainers.get() && scanForIndicators()) {
            currentState = ExplorerState.DETECTED;
            handleDetectedState();
            return;
        }

        // If we need a new target
        if (currentTargetChunk == null || isNearTargetChunk(8 * spiralStepSize.get())) {
            // Slow down while deciding next chunk
            mc.player.setVelocity(mc.player.getVelocity().multiply(0.5, 1, 0.5));
            mc.options.forwardKey.setPressed(false);

            int spiralSearchAttempts = 0;
            while (true) {
                if (nextSpiralChunk == null) {
                    nextSpiralChunk = calculateAndAdvanceSpiralTarget();
                }
                if (nextSpiralChunk == null) {
                    info("Spiral path complete or exceeded radius.");
                    searching = false;
                    return;
                }
                spiralSearchAttempts++;
                if (visitedChunksSet.contains(nextSpiralChunk) && spiralSearchAttempts < 10) {
                    // skip over visited chunks
                    nextSpiralChunk = null;
                } else {
                    break;
                }
            }

            if (nextSpiralChunk == null || visitedChunksSet.contains(nextSpiralChunk)) {
                warning("Stuck skipping visited chunks or spiral ended. Pausing.");
                currentTargetChunk = null;
                return;
            }

            ChunkStatus status = getChunkStatusFromNewerNewChunks(nextSpiralChunk);
            if (isChunkNavigable(status)) {
                currentTargetChunk = nextSpiralChunk;
                nextSpiralChunk = null;
            } else {
                ChunkPos detourTarget = findCircumnavigationTarget(nextSpiralChunk);
                if (detourTarget != null) {
                    currentTargetChunk = detourTarget;
                } else {
                    warning("Cannot find path around new/unknown chunk: %d, %d. Pausing.",
                            nextSpiralChunk.x, nextSpiralChunk.z);
                    currentTargetChunk = null;
                }
            }
        }

        // If we have a valid target chunk, rotate and hold forward
        if (currentTargetChunk != null) {
            if (rotate.get()) {
                Vec3d targetPos = new Vec3d(
                    currentTargetChunk.getCenterX(),
                    mc.player.getY(),
                    currentTargetChunk.getCenterZ()
                );
                float targetYaw = (float) Rotations.getYaw(targetPos);
                Rotations.rotate(targetYaw, mc.player.getPitch(), 50);
            }
            // Press forward => rely on "Elytra Fly" to control flight
            mc.options.forwardKey.setPressed(true);
        } else {
            // No target => stop moving
            mc.options.forwardKey.setPressed(false);
        }
    }

    private boolean isNearTargetChunk(double baseDistanceInBlocks) {
        if (currentTargetChunk == null) return true;
        if (mc.player == null) return false;

        double distSq = mc.player.getBlockPos().getSquaredDistance(
            currentTargetChunk.getCenterX(),
            mc.player.getBlockPos().getY(),
            currentTargetChunk.getCenterZ()
        );
        return distSq <= (baseDistanceInBlocks * baseDistanceInBlocks);
    }

    private void updateVisitedChunks() {
        ChunkPos currentPlayerChunk = mc.player.getChunkPos();
        if (!visitedChunksSet.contains(currentPlayerChunk)) {
            visitedChunksSet.add(currentPlayerChunk);
            visitedChunksQueue.add(currentPlayerChunk);

            while (visitedChunksQueue.size() > visitedChunkCacheSize.get()) {
                ChunkPos removed = visitedChunksQueue.poll();
                if (removed != null) {
                    visitedChunksSet.remove(removed);
                }
            }
        }
    }

    private void detectStuck() {
        if (currentState == ExplorerState.NAVIGATING) {
            if (lastPos != null && mc.player.getBlockPos().equals(lastPos)) {
                stuckTicks++;
                // If stuck for ~3 seconds (60 ticks)
                if (stuckTicks > 60) {
                    warning("Stuck! Calculating next spiral target to break free.");
                    nextSpiralChunk = calculateAndAdvanceSpiralTarget();
                    currentTargetChunk = null;
                    stuckTicks = 0;
                    mc.player.setVelocity(Vec3d.ZERO);
                    mc.options.forwardKey.setPressed(false);
                }
            } else {
                stuckTicks = 0;
            }
            lastPos = mc.player.getBlockPos();
        } else {
            stuckTicks = 0;
            lastPos = mc.player.getBlockPos();
        }
    }

    // ---------------------------------------------------
    // Detection + Interaction
    // ---------------------------------------------------
    private void handleDetectedState() {
        if (notifyOnFound.get()) {
            ChatUtils.info(String.format(
                "Player activity indicator found near: %d, %d, %d",
                foundIndicatorPos.getX(),
                foundIndicatorPos.getY(),
                foundIndicatorPos.getZ()
            ));
            mc.world.playSound(
                mc.player.getX(),
                mc.player.getY(),
                mc.player.getZ(),
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.PLAYERS,
                1.0f,
                1.0f,
                false
            );
        }
        if (stopOnFound.get()) {
            searching = false;
            mc.player.setVelocity(Vec3d.ZERO);
            mc.options.forwardKey.setPressed(false);
        } else {
            currentState = ExplorerState.NAVIGATING;
        }
    }

    private boolean scanForIndicators() {
        if (mc.player == null || mc.world == null || !checkContainers.get()) return false;
        BlockPos playerPos = mc.player.getBlockPos();
        int radius = containerScanRadius.get();
        int minY = playerPos.getY() - radius;
        int maxY = playerPos.getY() + radius + 1;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = minY; dy <= maxY; dy++) {
                    if (dy < mc.world.getBottomY() || dy >= mc.world.getHeight()) continue;
                    BlockPos checkPos = playerPos.add(dx, 0, dz).withY(dy);

                    BlockState state = mc.world.getBlockState(checkPos);
                    Block block = state.getBlock();

                    if (block instanceof ChestBlock ||
                        block instanceof BarrelBlock ||
                        block instanceof ShulkerBoxBlock ||
                        block instanceof EnderChestBlock ||
                        block instanceof AbstractFurnaceBlock ||
                        block instanceof DispenserBlock ||
                        block instanceof DropperBlock ||
                        block instanceof HopperBlock
                    ) {
                        foundIndicatorPos = checkPos;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------
    // AutoXP / Durability
    // ---------------------------------------------------
    private boolean checkAutoXPTrigger() {
        if (enableAutoXP.get()) {
            double durabilityPercent = getElytraDurabilityPercent();
            if (durabilityPercent != -1 && durabilityPercent < elytraDurabilityThreshold.get()) {
                info("Elytra durability low (%.1f%%), initiating AutoXP.", durabilityPercent);
                currentState = ExplorerState.AUTO_XP_STARTING;
                mc.player.setVelocity(Vec3d.ZERO);
                mc.options.forwardKey.setPressed(false);
                autoXpStateTicks = 0;
                bottlesUsed = 0;
                originalSlot = mc.player.getInventory().selectedSlot;
                return true;
            }
        }
        return false;
    }

    private double getElytraDurabilityPercent() {
        ItemStack chestStack = mc.player.getInventory().getArmorStack(2);
        if (chestStack.isOf(Items.ELYTRA)) {
            if (chestStack.isDamaged()) {
                int maxDamage = chestStack.getMaxDamage();
                int currentDamage = chestStack.getDamage();
                if (maxDamage == 0) return -1;
                return 100.0 * (maxDamage - currentDamage) / maxDamage;
            }
            else {
                return 100.0;
            }
        }
        return -1;
    }

    private void handleAutoXPStartingState() {
        mc.player.setVelocity(Vec3d.ZERO);
        mc.options.forwardKey.setPressed(false);

        FindItemResult blockResult = InvUtils.findInHotbar(itemStack ->
            itemStack.getItem() instanceof BlockItem &&
            ((BlockItem) itemStack.getItem()).getBlock() == blockToPlaceBelow.get()
        );
        FindItemResult xpBottleResult = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);

        if (!blockResult.found() || !xpBottleResult.found()) {
            error("Required items not found for AutoXP (Need %s and XP Bottles). Stopping.",
                  blockToPlaceBelow.get().getName().getString());
            currentState = ExplorerState.NAVIGATING;
            return;
        }
        currentState = ExplorerState.AUTO_XP_PLACING_BLOCK;
        autoXpStateTicks = 0;
    }

    private void handleAutoXPPlacingBlockState() {
        autoXpStateTicks++;
        BlockPos placePos = mc.player.getBlockPos().down();

        if (mc.world.getBlockState(placePos).isReplaceable()) {
            FindItemResult blockToPlace = InvUtils.findInHotbar(itemStack ->
                itemStack.getItem() instanceof BlockItem &&
                ((BlockItem) itemStack.getItem()).getBlock() == blockToPlaceBelow.get()
            );
            if (blockToPlace.found()) {
                boolean placed = BlockUtils.place(placePos, blockToPlace, rotate.get(), 10, true);
                if (placed) {
                    info("Placed block for XP.");
                    currentState = ExplorerState.AUTO_XP_LOOKING_DOWN;
                    autoXpStateTicks = 0;
                } else {
                    warning("Failed to place block below for XP. Stopping AutoXP.");
                    currentState = ExplorerState.NAVIGATING;
                }
            } else {
                error("Block to place not found in hotbar!");
                currentState = ExplorerState.NAVIGATING;
            }
        } else {
            // Already solid or no block needed
            currentState = ExplorerState.AUTO_XP_LOOKING_DOWN;
            autoXpStateTicks = 0;
        }
    }

    private void handleAutoXPLookingDownState() {
        autoXpStateTicks++;
        Rotations.rotate(mc.player.getYaw(), 90, 10);

        if (Math.abs(mc.player.getPitch() - 90) < 1.0) {
            currentState = ExplorerState.AUTO_XP_USING_BOTTLES;
            autoXpStateTicks = 0;
            bottlesUsed = 0;
        }
    }

    private void handleAutoXPUsingBottlesState() {
        autoXpStateTicks++;
        double currentDurabilityPercent = getElytraDurabilityPercent();

        if (currentDurabilityPercent == -1
            || currentDurabilityPercent >= 99.5
            || bottlesUsed >= autoXpBottleCount.get()
        ) {
            info("Finished using XP bottles. Durability: %.1f%%",
                 currentDurabilityPercent == -1 ? 0 : currentDurabilityPercent);

            if (originalSlot != -1 && mc.player.getInventory().selectedSlot != originalSlot) {
                InvUtils.swap(originalSlot, false);
            }
            originalSlot = -1;

            if (enableAutoTakeOff.get()) {
                currentState = ExplorerState.AUTO_TAKE_OFF;
            } else {
                currentState = ExplorerState.NAVIGATING;
            }
            autoXpStateTicks = 0;
            return;
        }

        if (autoXpStateTicks >= xpWaitTicks.get()) {
            FindItemResult xpBottle = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
            if (!xpBottle.found()) {
                error("Ran out of XP Bottles.");
                currentState = ExplorerState.NAVIGATING;
                if (originalSlot != -1) InvUtils.swap(originalSlot, false);
                originalSlot = -1;
                return;
            }

            if (mc.player.getInventory().selectedSlot != xpBottle.slot()) {
                if (originalSlot == -1) {
                    originalSlot = mc.player.getInventory().selectedSlot;
                }
                InvUtils.swap(xpBottle.slot(), false);
            }
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            bottlesUsed++;
            autoXpStateTicks = 0;
        }
    }

    // ---------------------------------------------------
    // Re-Takeoff (Double Jump)
    // ---------------------------------------------------
    private void handleAutoTakeOffState() {
        info("Attempting Auto Take Off...");
        // Simple approach: do a "double jump" in the same tick
        // If Meteor’s Elytra Fly is on, you’ll typically start gliding
        // after the second jump if there’s enough vertical space.
        mc.player.jump();
        mc.player.jump();

        // Optionally, use fireworks if not in creative
        FindItemResult fireworks = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!mc.player.getAbilities().creativeMode && fireworks.found()) {
            if (mc.player.getInventory().selectedSlot != fireworks.slot()) {
                if (originalSlot == -1) {
                    originalSlot = mc.player.getInventory().selectedSlot;
                }
                InvUtils.swap(fireworks.slot(), false);
            }
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

            if (originalSlot != -1) InvUtils.swap(originalSlot, false);
            originalSlot = -1;
        }

        // Return to normal navigation
        currentState = ExplorerState.NAVIGATING;
        autoXpStateTicks = 0;
    }

    // ---------------------------------------------------
    // Spiral Pathfinding Helpers
    // ---------------------------------------------------
    private ChunkPos calculateAndAdvanceSpiralTarget() {
        if (spiralCenter == null) return null;
        int maxSearchDistSq = searchRadius.get() * searchRadius.get();
        int step = spiralStepSize.get();

        int dx = 0, dz = 0;
        int effectiveStepsTaken = spiralStepsTaken * step;
        int effectiveSteps = spiralSteps * step;

        switch (spiralLeg) {
            case 0: // East
                dx = effectiveStepsTaken;
                dz = 0;
                break;
            case 1: // South
                dx = effectiveSteps;
                dz = effectiveStepsTaken;
                break;
            case 2: // West
                dx = effectiveSteps - effectiveStepsTaken;
                dz = effectiveSteps;
                break;
            case 3: // North
                dx = -effectiveStepsTaken;
                dz = effectiveSteps - effectiveStepsTaken;
                break;
        }

        ChunkPos potentialTarget = new ChunkPos(
            spiralCenter.x + dx,
            spiralCenter.z + dz
        );
        advanceSpiralState();

        if (potentialTarget.getSquaredDistance(spiralCenter) > maxSearchDistSq) {
            return null; // Exceeded radius
        }
        return potentialTarget;
    }

    private void advanceSpiralState() {
        spiralStepsTaken++;
        if (spiralStepsTaken >= spiralSteps) {
            spiralLeg = (spiralLeg + 1) % 4;
            spiralStepsTaken = 0;

            // Increase spiral layer
            if (spiralLeg == 0) {
                spiralLayer++;
                spiralSteps = spiralLayer;
            }
            else if (spiralLeg == 2) {
                spiralSteps = spiralLayer;
            }
        }
    }

    private ChunkPos findCircumnavigationTarget(ChunkPos blockedChunk) {
        if (mc.player == null) return null;
        ChunkPos currentChunk = mc.player.getChunkPos();

        Vec3d directionToTarget = new Vec3d(
            blockedChunk.getCenterX() - mc.player.getX(),
            0,
            blockedChunk.getCenterZ() - mc.player.getZ()
        ).normalize();

        Vec3d[] baseDetourDirs = {
            new Vec3d(-directionToTarget.z, 0, directionToTarget.x),  // Left
            new Vec3d(directionToTarget.z, 0, -directionToTarget.x),  // Right
            new Vec3d(
                -directionToTarget.z - directionToTarget.x,
                0,
                directionToTarget.x - directionToTarget.z
            ).normalize(), // Back-Left
            new Vec3d(
                directionToTarget.z - directionToTarget.x,
                0,
                -(directionToTarget.x + directionToTarget.z)
            ).normalize() // Back-Right
        };

        for (int distance = 1; distance <= 2; distance++) {
            for (Vec3d detourDir : baseDetourDirs) {
                ChunkPos potentialTarget = new ChunkPos(
                    currentChunk.x + (int) Math.round(detourDir.x * distance),
                    currentChunk.z + (int) Math.round(detourDir.z * distance)
                );
                if (isValidDetourTarget(potentialTarget, blockedChunk, currentChunk)) {
                    return potentialTarget;
                }
            }
        }
        return null;
    }

    private boolean isValidDetourTarget(ChunkPos potentialTarget, ChunkPos blockedChunk, ChunkPos currentChunk) {
        if (potentialTarget.equals(blockedChunk)
            || potentialTarget.equals(currentChunk)
            || visitedChunksSet.contains(potentialTarget)
        ) {
            return false;
        }
        ChunkStatus status = getChunkStatusFromNewerNewChunks(potentialTarget);
        return isChunkNavigable(status);
    }

    private ChunkStatus getChunkStatusFromNewerNewChunks(ChunkPos chunkPos) {
        NewerNewChunks newerNewChunksModule = Modules.get().get(NewerNewChunks.class);
        if (newerNewChunksModule == null || !newerNewChunksModule.isActive()) {
            if (mc.world == null) return ChunkStatus.UNKNOWN;
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z, false);
            return (chunk != null) ? ChunkStatus.OLD : ChunkStatus.UNKNOWN;
        }

        NewerNewChunks.NncChunkStatus status = newerNewChunksModule.getChunkStatus(chunkPos);
        switch (status) {
            case NEW:
                return ChunkStatus.NEW;
            case OLD:
                return ChunkStatus.OLD;
            case OLD_GENERATION:
                return ChunkStatus.OLD_GENERATION;
            case BEING_UPDATED:
                return ChunkStatus.BEING_UPDATED;
            case TICK_EXPLOIT:
                return ChunkStatus.TICK_EXPLOIT;
            default:
                return ChunkStatus.UNKNOWN;
        }
    }

    private boolean isChunkNavigable(ChunkStatus status) {
        return status == ChunkStatus.OLD
            || status == ChunkStatus.OLD_GENERATION
            || status == ChunkStatus.BEING_UPDATED;
    }
}
