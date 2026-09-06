package net.stracciatella.miner.gui;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.stracciatella.miner.MinerCommands;
import net.stracciatella.miner.MinerConfig;

/**
 * Edit the chunk miner's blacklist: type an id, or add the block you were
 * last looking at.
 *
 * <p>"Looking at" reads {@code Minecraft.hitResult}, which still holds the
 * block the crosshair was on when the menu was opened. That is the whole
 * block-selection mode — aim at the block, open the menu, add it — and it
 * needs no click handling of its own, no mode the player can get stuck in,
 * and no way to accidentally blacklist something while walking around.
 */
public class BlacklistScreen extends Screen {

    private static final int ROWS_PER_PAGE = 7;
    private static final int ROW_HEIGHT = 22;
    private static final int LIST_TOP = 60;
    private static final int LIST_WIDTH = 220;
    private static final int REMOVE_WIDTH = 60;
    private static final int GAP = 4;
    private static final int TITLE_Y = 16;
    private static final int STATUS_Y = 40;
    private static final int TEXT_COLOR = 0xFFFFFF;

    private final Screen parent;
    private final MinerConfig config;

    private EditBox input;
    private String status = "";
    private int page;

    BlacklistScreen(Screen parent, MinerConfig config) {
        super(Component.literal("Chunk miner blacklist"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        int rowWidth = LIST_WIDTH + GAP + REMOVE_WIDTH;
        int left = this.width / 2 - rowWidth / 2;

        List<String> entries = config.chunkMinerBlacklist;
        int pages = Math.max(1, (entries.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        page = Math.min(page, pages - 1);
        int first = page * ROWS_PER_PAGE;

        for (int i = 0; i < ROWS_PER_PAGE && first + i < entries.size(); i++) {
            String id = entries.get(first + i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            Button label = Button.builder(Component.literal(id), button -> { })
                    .bounds(left, y, LIST_WIDTH, 20)
                    .build();
            label.active = false;
            this.addRenderableWidget(label);
            this.addRenderableWidget(Button.builder(Component.literal("Remove"),
                            button -> remove(id))
                    .bounds(left + LIST_WIDTH + GAP, y, REMOVE_WIDTH, 20)
                    .build());
        }

        int controlsY = LIST_TOP + ROWS_PER_PAGE * ROW_HEIGHT + GAP;
        if (pages > 1) {
            this.addRenderableWidget(Button.builder(Component.literal("< Page"),
                            button -> turnPage(-1))
                    .bounds(left, controlsY, 60, 20)
                    .build());
            this.addRenderableWidget(Button.builder(
                            Component.literal("Page " + (page + 1) + "/" + pages),
                            button -> turnPage(1))
                    .bounds(left + 64, controlsY, 100, 20)
                    .build());
        }

        int inputY = controlsY + ROW_HEIGHT + GAP;
        input = new EditBox(this.font, left, inputY, LIST_WIDTH, 20,
                Component.literal("Block id"));
        input.setMaxLength(128);
        input.setHint(Component.literal("minecraft:chest"));
        this.addRenderableWidget(input);
        this.addRenderableWidget(Button.builder(Component.literal("Add"),
                        button -> add(input.getValue()))
                .bounds(left + LIST_WIDTH + GAP, inputY, REMOVE_WIDTH, 20)
                .build());

        int bottomY = inputY + ROW_HEIGHT + GAP;
        this.addRenderableWidget(Button.builder(Component.literal("Add block you're looking at"),
                        button -> addLookedAt())
                .bounds(left, bottomY, rowWidth, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                        button -> this.onClose())
                .bounds(left, bottomY + ROW_HEIGHT + GAP, rowWidth, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, TEXT_COLOR);
        if (!status.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.literal(status),
                    this.width / 2, STATUS_Y, TEXT_COLOR);
        } else if (config.chunkMinerBlacklist.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("Blacklist is empty — the miner digs everything")
                            .withStyle(ChatFormatting.GRAY),
                    this.width / 2, STATUS_Y, TEXT_COLOR);
        }
    }

    private void turnPage(int delta) {
        int pages = Math.max(1,
                (config.chunkMinerBlacklist.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        page = Math.floorMod(page + delta, pages);
        rebuild();
    }

    private void add(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String id = MinerCommands.normalize(raw.trim());
        if (id == null) {
            status = "'" + raw.trim() + "' is not a block";
            return;
        }
        if (config.chunkMinerBlacklist.contains(id)) {
            status = id + " is already on the list";
            return;
        }
        config.chunkMinerBlacklist.add(id);
        config.save();
        status = "Added " + id;
        rebuild();
    }

    private void addLookedAt() {
        HitResult hit = this.minecraft.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK
                || this.minecraft.level == null) {
            status = "Aim at a block before opening this menu";
            return;
        }
        var state = this.minecraft.level.getBlockState(blockHit.getBlockPos());
        add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    private void remove(String id) {
        if (config.chunkMinerBlacklist.remove(id)) {
            config.save();
            status = "Removed " + id;
        }
        rebuild();
    }

    /** Rebuild the widgets so the list reflects the change. */
    private void rebuild() {
        String kept = input != null ? input.getValue() : "";
        this.rebuildWidgets();
        if (input != null) {
            input.setValue(kept);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
