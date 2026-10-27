package com.example.bdautodripstone;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoDripstoneClient implements ClientModInitializer {
    private boolean enabled = false;

    private BlockPos pending = null;
    private int phase = 0; // 0 = nic, 1 = czekam na klape, 2 = kucam, 3 = sprzatam
    private int timer = 0;
    private int dripSlot = -1;
    private int previousSlot = -1;
    private boolean wasSneaking = false;

    @Override
    public void onInitializeClient() {
        // komenda /BD (i /bd) wlacza i wylacza moda
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            for (String name : new String[]{"BD", "bd"}) {
                dispatcher.register(ClientCommandManager.literal(name).executes(context -> {
                    enabled = !enabled;
                    context.getSource().sendFeedback(Text.literal(
                            enabled ? "§aAutoDripstone: WŁĄCZONY" : "§cAutoDripstone: WYŁĄCZONY"));
                    return 1;
                }));
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (enabled && world.isClient() && hand == Hand.MAIN_HAND && phase == 0) {
                BlockPos pos = hit.getBlockPos();
                if (world.getBlockState(pos).getBlock() instanceof TrapdoorBlock) {
                    pending = pos;
                    phase = 1;
                    timer = 4; // czekamy az serwer zmieni stan klapy
                }
            }
            return ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        if (phase == 0) {
            return;
        }
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) {
            phase = 0;
            return;
        }
        PlayerInventory inv = player.getInventory();

        if (phase == 1) {
            if (--timer > 0) {
                return;
            }
            BlockState state = client.world.getBlockState(pending);
            boolean ok = state.getBlock() instanceof TrapdoorBlock
                    && !state.get(TrapdoorBlock.OPEN)
                    && state.get(TrapdoorBlock.HALF) == BlockHalf.BOTTOM;
            dripSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isOf(Items.POINTED_DRIPSTONE)) {
                    dripSlot = i;
                    break;
                }
            }
            if (!ok || dripSlot == -1) {
                phase = 0;
                return;
            }
            previousSlot = inv.selectedSlot;
            wasSneaking = client.options.sneakKey.isPressed();
            inv.selectedSlot = dripSlot;
            client.options.sneakKey.setPressed(true); // kucniecie, zeby nie otworzyc klapy
            phase = 2;
            timer = 2;
        } else if (phase == 2) {
            if (--timer > 0) {
                return;
            }
            BlockPos pos = pending;
            BlockHitResult hit = new BlockHitResult(
                    new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
                    Direction.DOWN, pos, false);
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
            phase = 3;
        } else {
            client.options.sneakKey.setPressed(wasSneaking);
            inv.selectedSlot = previousSlot;
            pending = null;
            phase = 0;
        }
    }
}
