```java
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
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
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
    public static boolean enabled = false;
    public static int mode = 0; // 0 = wolny, 1 = szybki, 2 = blyskawica
    private static boolean openGui = false;

    // Minimalny odstep miedzy probami postawienia (ms)
    private static final long[] COOLDOWN_MS = {1000, 300, 0};
    private long lastPlaceMs = 0;

    private BlockPos pending = null;
    private int phase = 0; // 0 = nic, 1 = czekam na klape, 2 = przywracam slot
    private int timer = 0;
    private int previousSlot = -1;

    private static final Direction[] SIDES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            for (String name : new String[]{"BD", "bd"}) {
                dispatcher.register(ClientCommandManager.literal(name).executes(context -> {
                    enabled = !enabled;
                    context.getSource().sendFeedback(Text.literal(
                            enabled ? "§aAutoDripstone: WŁĄCZONY" : "§cAutoDripstone: WYŁĄCZONY"));
                    return 1;
                }));
            }

           
