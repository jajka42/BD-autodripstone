package com.example.bdautodripstone;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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

    // minimalny odstep miedzy postawionymi dripstone (ms) dla kazdego trybu
    private static final long[] COOLDOWN_MS = {1000, 400, 0};
    private static final int MAX_WAIT_TICKS = 6;
    private long lastPlaceMs = 0;

    private BlockPos pending = null;
    private int phase = 0; // 0 = nic, 1 = czekam na postawienie
    private int timer = 0;
    private int waited = 0;

    private static final Direction[] SIDES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // /BD (i /bd) wlacza i wylacza moda
            for (String name : new String[]{"BD", "bd"}) {
                dispatcher.register(ClientCommandManager.literal(name).executes(context -> {
                    enabled = !enabled;
                    context.getSource().sendFeedback(Text.literal(
                            enabled ? "§aAutoDripstone: WŁĄCZONY" : "§cAutoDripstone: WYŁĄCZONY"));
                    return 1;
                }));
            }
            // /bdgui otwiera okno z ustawieniami
            for (String name : new String[]{"bdgui", "BDGUI"}) {
                dispatcher.register(ClientCommandManager.literal(name).executes(context -> {
                    openGui = true; // okno otworzy sie w nastepnym ticku, po zamknieciu czatu
                    return 1;
                }));
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (enabled && world.isClient() && hand == Hand.MAIN_HAND && phase == 0
                    && System.currentTimeMillis() - lastPlaceMs >= COOLDOWN_MS[mode]) {
                BlockPos pos = hit.getBlockPos();
                if (world.getBlockState(pos).getBlock() instanceof TrapdoorBlock) {
                    pending = pos;
                    phase = 1;
                    waited = 0;
                    timer = (mode == 2) ? 1 : 3;
                }
            }
            return ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        if (openGui) {
            openGui = false;
            client.setScreen(new BdScreen());
        }
        if (phase == 0) {
            return;
        }
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) {
            phase = 0;
            return;
        }
        PlayerInventory inv = player.getInventory();

        if (--timer > 0) {
            return;
        }

        BlockState state = client.world.getBlockState(pending);
        if (!(state.getBlock() instanceof TrapdoorBlock)
                || state.get(TrapdoorBlock.OPEN)
                || state.get(TrapdoorBlock.HALF) != BlockHalf.BOTTOM) {
            phase = 0; // klapa otwarta (dripstone spada sam) albo zla klapa
            return;
        }

        BlockPos target = pending.down();
        BlockState targetState = client.world.getBlockState(target);
        if (targetState.isOf(Blocks.POINTED_DRIPSTONE)) {
            phase = 0; // juz tam wisi
            return;
        }
        if (!targetState.isReplaceable()) {
            // poprzedni dripstone jeszcze spada, czekamy chwile az miejsce sie zwolni
            if (++waited > MAX_WAIT_TICKS) {
                phase = 0;
            }
            return;
        }

        int dripSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.POINTED_DRIPSTONE)) {
                dripSlot = i;
                break;
            }
        }
        if (dripSlot == -1) {
            phase = 0;
            return;
        }

        // szukamy pelnego bloku obok miejsca, w ktory mozna kliknac (bez klikania klapy)
        BlockPos support = null;
        Direction face = null;
        for (Direction d : SIDES) {
            BlockPos n = target.offset(d);
            BlockState ns = client.world.getBlockState(n);
            if (ns.getBlock() instanceof TrapdoorBlock || ns.hasBlockEntity()) {
                continue;
            }
            if (ns.isSideSolidFullSquare(client.world, n, d.getOpposite())) {
                support = n;
                face = d.getOpposite();
                break;
            }
        }
        if (support == null) {
            phase = 0;
            return;
        }

        Vec3d hitPos = Vec3d.ofCenter(support).add(
                face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, face, support, false);

        int previousSlot = inv.selectedSlot;
        inv.selectedSlot = dripSlot;
        lastPlaceMs = System.currentTimeMillis();
        // phase zostaje 1 podczas klikniecia, zeby wlasne klikniecie nie uruchomilo moda ponownie
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
        inv.selectedSlot = previousSlot;
        pending = null;
        phase = 0;
    }

    // okno /bdgui
    public static class BdScreen extends Screen {
        private static final String[] NAMES = {"Wolny (1/s)", "Szybki (2-3/s)", "Błyskawica (max)"};
        private ButtonWidget toggleButton;
        private final ButtonWidget[] modeButtons = new ButtonWidget[3];

        public BdScreen() {
            super(Text.literal("BD-autodripstone"));
        }

        @Override
        protected void init() {
            int cx = this.width / 2;
            int y = this.height / 2 - 55;

            toggleButton = ButtonWidget.builder(Text.empty(), b -> {
                enabled = !enabled;
                refresh();
            }).dimensions(cx - 100, y, 200, 20).build();
            addDrawableChild(toggleButton);

            for (int i = 0; i < 3; i++) {
                final int index = i;
                modeButtons[i] = ButtonWidget.builder(Text.empty(), b -> {
                    mode = index;
                    refresh();
                }).dimensions(cx - 100, y + 30 + i * 25, 200, 20).build();
                addDrawableChild(modeButtons[i]);
            }

            addDrawableChild(ButtonWidget.builder(Text.literal("Zamknij"), b -> close())
                    .dimensions(cx - 100, y + 115, 200, 20).build());

            refresh();
        }

        private void refresh() {
            toggleButton.setMessage(Text.literal(enabled ? "Mod: §aWŁĄCZONY" : "Mod: §cWYŁĄCZONY"));
            for (int i = 0; i < 3; i++) {
                modeButtons[i].setMessage(Text.literal((mode == i ? "§a> " : "") + NAMES[i]));
            }
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                    this.width / 2, this.height / 2 - 75, 0xFFFFFF);
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }
}
