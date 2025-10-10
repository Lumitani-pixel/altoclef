package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.chains.MLGBucketFallChain;
import adris.altoclef.multiversion.versionedfields.Entities;
import adris.altoclef.multiversion.item.ItemVer;
import adris.altoclef.trackers.storage.ItemStorageTracker;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import baritone.api.utils.input.Input;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HoglinEntity;
import net.minecraft.entity.mob.ZoglinEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Controls and applies killaura
 */
public class KillAura {
    // Smart aura data
    private final List<Entity> targets = new ArrayList<>();
    boolean shielding = false;
    private double forceFieldRange = Double.POSITIVE_INFINITY;
    private Entity forceHit = null;
    public boolean attackedLastTick = false;

    public static void equipWeapon(AltoClef mod) {
        List<ItemStack> invStacks = mod.getItemStorage().getItemStacksPlayerInventory(true);
        if (!invStacks.isEmpty()) {
            float handDamage = Float.NEGATIVE_INFINITY;
            for (ItemStack invStack : invStacks) {
                if (invStack.getItem() instanceof SwordItem item) {
                    float itemDamage = item.getMaterial().getAttackDamage();
                    Item handItem = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
                    if (handItem instanceof SwordItem handToolItem) {
                        handDamage = handToolItem.getMaterial().getAttackDamage();
                    }
                    if (itemDamage > handDamage) {
                        mod.getSlotHandler().forceEquipItem(item);
                    } else {
                        mod.getSlotHandler().forceEquipItem(handItem);
                    }
                }
            }
        }
    }

    public void tickStart() {
        targets.clear();
        forceHit = null;
        attackedLastTick = false;
    }

    public void applyAura(Entity entity) {
        targets.add(entity);
        // Always hit ghast balls.
        if (entity instanceof FireballEntity) forceHit = entity;
    }

    public void setRange(double range) {
        forceFieldRange = range;
    }

    public void tickEnd(AltoClef mod) {
        Optional<Entity> entities = targets.stream().min(StlHelper.compareValues(entity -> entity.squaredDistanceTo(mod.getPlayer())));
        if (entities.isEmpty()) {
            stopShielding(mod);
            return;
        }

        Entity entity = entities.get();
        MLGBucketFallChain mlgChain = mod.getMLGBucketChain();
        ItemStorageTracker itemStorage = mod.getItemStorage();
        Item offhandItem = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem();
        double distSq = entity.squaredDistanceTo(mod.getPlayer());

        // Quick exits for performance and clarity
        boolean inRange = Double.isInfinite(forceFieldRange)
                || distSq < forceFieldRange * forceFieldRange
                || distSq < 40;

        if (mod.getEntityTracker().entityFound(PotionEntity.class)
                || !inRange
                || mlgChain.isFalling(mod)
                || !mlgChain.doneMLG()
                || mlgChain.isChorusFruiting()) {
            stopShielding(mod);
            return;
        }

        // Skip certain hostile entity types
        Set<Class<?>> ignoredEntities = Set.of(
                CreeperEntity.class,
                HoglinEntity.class,
                ZoglinEntity.class,
                Entities.WARDEN,
                WitherEntity.class
        );

        boolean shouldShield = !ignoredEntities.contains(entity.getClass())
                && (itemStorage.hasItem(Items.SHIELD) || itemStorage.hasItemInOffhand(Items.SHIELD))
                && !mod.getPlayer().getItemCooldownManager().isCoolingDown(offhandItem)
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel();

        if (shouldShield) {
            LookHelper.lookAt(mod, entity.getEyePos());
            ItemStack shieldSlot = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);

            if (shieldSlot.getItem() != Items.SHIELD) {
                mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
            } else if (!WorldHelper.isSurroundedByHostiles()) {
                startShielding(mod);
            }
        }

        performDelayedAttack(mod);
        // Run force field on map
        switch (mod.getModSettings().getForceFieldStrategy()) {
            case FASTEST:
                performFastestAttack(mod);
                break;
            case SMART:
                // Attack force mobs ALWAYS. (currently used only for fireballs)
                if (forceHit != null) {
                    attack(mod, forceHit, true);
                    break;
                }

                if (!mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFalling(mod) &&
                        mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting()) {
                    performDelayedAttack(mod);
                }
                break;
            case DELAY:
                performDelayedAttack(mod);
                break;
            case OFF:
                break;
        }
    }

    private void performDelayedAttack(AltoClef mod) {
        if (!mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFalling(mod) &&
                mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting()) {
            if (forceHit != null) {
                attack(mod, forceHit, true);
            }
            // wait for the attack delay
            if (targets.isEmpty()) {
                return;
            }

            Optional<Entity> toHit = targets.stream().min(StlHelper.compareValues(entity -> entity.squaredDistanceTo(mod.getPlayer())));

            if (mod.getPlayer() == null || mod.getPlayer().getAttackCooldownProgress(0) < 1) {
                return;
            }

            toHit.ifPresent(entity -> attack(mod, entity, true));
        }
    }

    private void performFastestAttack(AltoClef mod) {
        if (!mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFalling(mod) &&
                mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting()) {
            // Just attack whenever you can
            for (Entity entity : targets) {
                attack(mod, entity);
            }
        }
    }

    private void attack(AltoClef mod, Entity entity) {
        attack(mod, entity, false);
    }

    private void attack(AltoClef mod, Entity entity, boolean equipSword) {
        if (entity == null) return;
        if (!(entity instanceof FireballEntity)) {
            double xAim = entity.getX();
            double yAim = entity.getY() + (entity.getHeight() / 1.4);
            double zAim = entity.getZ();
            LookHelper.lookAt(mod, new Vec3d(xAim, yAim, zAim));
        }
        if (Double.isInfinite(forceFieldRange) || entity.squaredDistanceTo(mod.getPlayer()) < forceFieldRange * forceFieldRange ||
                entity.squaredDistanceTo(mod.getPlayer()) < 40) {
            if (entity instanceof FireballEntity) {
                mod.getControllerExtras().attack(entity);
            }
            boolean canAttack;
            if (equipSword) {
                equipWeapon(mod);
                canAttack = true;
            } else {
                // Equip non-tool
                canAttack = mod.getSlotHandler().forceDeequipHitTool();
            }
            if (canAttack) {
                if (mod.getPlayer().isOnGround() || mod.getPlayer().getVelocity().getY() < 0 || mod.getPlayer().isTouchingWater()) {
                    attackedLastTick = true;
                    mod.getControllerExtras().attack(entity);
                }
            }
        }
    }

    public void startShielding(AltoClef mod) {
        shielding = true;
        InputControls input = mod.getInputControls();
        SlotHandler slotHandler = mod.getSlotHandler();

        // Pause all automated behaviors
        mod.getClientBaritone().getPathingBehavior().requestPause();
        mod.getExtraBaritoneSettings().setInteractionPaused(true);

        // Handle player holding food instead of shield
        if (!mod.getPlayer().isBlocking()) {
            ItemStack equipped = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());

            if (ItemVer.isFood(equipped)) {
                // Try to find an empty slot first
                Optional<ItemStack> emptySlot = mod.getItemStorage().getItemStacksPlayerInventory(false)
                        .stream()
                        .filter(ItemStack::isEmpty)
                        .findFirst();

                if (emptySlot.isPresent()) {
                    slotHandler.clickSlot(PlayerSlot.getEquipSlot(), 0, SlotActionType.QUICK_MOVE);
                } else {
                    // Otherwise move food to garbage slot if available
                    StorageHelper.getGarbageSlot(mod)
                            .ifPresent(slot -> slotHandler.forceEquipItem(StorageHelper.getItemStackInSlot(slot).getItem()));
                }
            }
        }

        // Begin shielding inputs
        input.hold(Input.SNEAK);
        input.hold(Input.CLICK_RIGHT);
    }

    public void stopShielding(AltoClef mod) {
        if (!shielding) return;

        InputControls input = mod.getInputControls();
        ItemStack cursorItem = StorageHelper.getItemStackInCursorSlot();

        // If cursor holds food, stash it or discard
        if (ItemVer.isFood(cursorItem)) {
            Optional<Slot> targetSlot = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorItem, false)
                    .or(() -> StorageHelper.getGarbageSlot(mod));

            targetSlot.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP));
        }

        // Release all held inputs and resume automation
        input.release(Input.SNEAK);
        input.release(Input.CLICK_RIGHT);
        input.release(Input.JUMP);

        mod.getExtraBaritoneSettings().setInteractionPaused(false);
        shielding = false;
    }

    public boolean isShielding() {
        return shielding;
    }

    public enum Strategy {
        OFF,
        FASTEST,
        DELAY,
        SMART
    }
}