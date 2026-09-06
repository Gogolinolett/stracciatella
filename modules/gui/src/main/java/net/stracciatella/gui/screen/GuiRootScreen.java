package net.stracciatella.gui.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.stracciatella.gui.GuiPage;
import net.stracciatella.gui.GuiRegistry;

/**
 * The menu the GUI key opens: one button per registered {@link GuiPage}.
 *
 * <p>Built from plain vanilla widgets rather than a config-screen library —
 * the mod already ships no third-party UI dependency, and every page here is
 * a handful of buttons and lists that {@code Button}/{@code Screen} cover
 * outright.
 */
public class GuiRootScreen extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_SPACING = 24;
    private static final int TITLE_Y = 20;
    private static final int TOP_MARGIN = 44;
    private static final int BOTTOM_MARGIN = 28;
    private static final int TEXT_COLOR = 0xFFFFFF;

    private final Screen parent;

    public GuiRootScreen(Screen parent) {
        super(Component.literal("Stracciatella"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - BUTTON_WIDTH / 2;
        int rows = GuiRegistry.pages().size();
        // Centre the list, but never let it climb into the title.
        int top = Math.max(TOP_MARGIN, this.height / 2 - rows * ROW_SPACING / 2);

        int row = 0;
        for (GuiPage page : GuiRegistry.pages()) {
            this.addRenderableWidget(Button.builder(page.title(),
                            button -> this.minecraft.setScreen(page.createScreen(this)))
                    .bounds(left, top + row * ROW_SPACING, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build());
            row++;
        }

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                        button -> this.onClose())
                .bounds(left, this.height - BOTTOM_MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, TEXT_COLOR);
        if (GuiRegistry.pages().isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("No pages registered"),
                    this.width / 2, this.height / 2, TEXT_COLOR);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    /**
     * Never pause. In singleplayer a pausing screen would freeze the world,
     * and this menu exists to watch and steer a bot that is supposed to keep
     * working while it is open.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
