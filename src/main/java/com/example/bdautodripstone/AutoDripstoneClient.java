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
    private int phase = 0; // 0 = nic, 1 = czekam na klape, 2 = czekam na kucanie, 3 = sprzatam
    private int timer = 0;
    private int waited = 0;
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

    private void say(ClientPlayerEntity player, String text) {
        player.sendMessage(Text.literal(text), false);
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
            if (!(state.getBlock() instanceof TrapdoorBlock)) {
                phase = 0;
                return;
            }
            if (state.get(TrapdoorBlock.OPEN)) {
                // klapa otwarta, dripstone spada sam
                phase = 0;
                return;
            }
            if (state.get(TrapdoorBlock.HALF) != BlockHalf.BOTTOM) {
                say(player, "§cBD: klapa musi byc w dolnej polowie bloku");
                phase = 0;
                return;
            }
            dripSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isOf(Items.POINTED_DRIPSTONE)) {
                    dripSlot = i;
                    break;
                }
            }
            if (dripSlot == -1) {
                say(player, "§cBD: brak pointed dripstone w hotbarze");
                phase = 0;
                return;
            }
            previousSlot = inv.selectedSlot;
            wasSneaking = client.options.sneakKey.isPressed();
            inv.selectedSlot = dripSlot;
            client.options.sneakKey.setPressed(true); // kucniecie, zeby nie otworzyc klapy
            waited = 0;
            phase = 2;
        } else if (phase == 2) {
            waited++;
            if (waited < 4) {
                return; // serwer musi dostac informacje o kucaniu
            }
            if (!player.isSneaking() && waited < 15) {
                return; // czekamy az postac naprawde kuca
            }
            BlockPos pos = pending;
            BlockHitResult hit = new BlockHitResult(
                    new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
                    Direction.DOWN, pos, false);
            ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
            if (result.isAccepted()) {
                say(player, "§aBD: dripstone wysłany do postawienia");
            } else {
                say(player, "§cBD: nie udało się postawić (wynik: " + result + ")");
            }
            timer = 2;
            phase = 3;
        } else {
            if (--timer > 0) {
                return;
            }
            client.options.sneakKey.setPressed(wasSneaking);
            inv.selectedSlot = previousSlot;
            pending = null;
            phase = 0;
        }
    }
}
