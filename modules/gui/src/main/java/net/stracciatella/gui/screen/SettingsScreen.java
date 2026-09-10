package net.stracciatella.gui.screen;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A page of settings that each cycle through their values when clicked — the
 * companion to {@link IdListScreen} for the things that are not lists.
 *
 * <p>There is exactly <b>one</b> row type, and that is the design. A setting here
 * is a label, a description, something that can say what the value currently
 * reads as, and something that moves it on. A boolean is that with two values, an
 * enum is that with however many the enum has, and a numeric knob with a few
 * sensible steps is that too — so the screen needs no cases for any of them, and
 * no generics. The obvious alternative, a row type per kind of setting
 * ({@code Toggle}, {@code Choice<E>}, {@code Slider}), was rejected: it puts the
 * gui module in the business of knowing what a value <em>is</em>, which is the
 * owning module's knowledge, and every new kind of setting would mean a new case
 * here.
 *
 * <p>The owner therefore supplies the cycling. That is one line at each call site
 * and it keeps the question "what comes after STAIRCASE" where the answer is
 * known.
 */
public final class SettingsScreen extends Screen {

    /**
     * One row.
     *
     * @param label       shown on the button, before the value
     * @param description shown as the button's tooltip; say what the setting
     *                    does, not what its values are
     * @param value       how the current value reads, e.g. "on" or "STAIRCASE"
     * @param advance     move to the next value; the screen saves afterwards
     */
    public record Setting(String label, String description,
                          Supplier<String> value, Runnable advance) {
    }

    /**
     * @param title    heading and window title
     * @param settings the rows, in the order they should read
     * @param save     persists the owner's config after every change
     */
    public record Spec(String title, List<Setting> settings, Runnable save) {
    }

    private static final int ROWS_PER_PAGE = 7;
    private static final int ROW_HEIGHT = 24;
    private static final int LIST_TOP = 56;
    private static final int ROW_WIDTH = 280;
    private static final int GAP = 4;
    private static final int TITLE_Y = 16;
    private static final int STATUS_Y = 38;
    private static final int TEXT_COLOR = 0xFFFFFF;

    private final Screen parent;
    private final Spec spec;

    private String status = "";
    private int page;

    public SettingsScreen(Screen parent, Spec spec) {
        super(Component.literal(spec.title()));
        this.parent = parent;
        this.spec = spec;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - ROW_WIDTH / 2;
        List<Setting> settings = spec.settings();
        int pages = pages();
        page = Math.min(page, pages - 1);
        int first = page * ROWS_PER_PAGE;

        for (int i = 0; i < ROWS_PER_PAGE && first + i < settings.size(); i++) {
            Setting setting = settings.get(first + i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            Button button = Button.builder(
                            Component.literal(setting.label() + ": " + setting.value().get()),
                            ignored -> advance(setting))
                    .bounds(left, y, ROW_WIDTH, 20)
                    .tooltip(Tooltip.create(Component.literal(setting.description())))
                    .build();
            this.addRenderableWidget(button);
        }

        int controlsY = LIST_TOP + ROWS_PER_PAGE * ROW_HEIGHT + GAP;
        if (pages > 1) {
            this.addRenderableWidget(Button.builder(Component.literal("< Page"),
                            ignored -> turnPage(-1))
                    .bounds(left, controlsY, 60, 20)
                    .build());
            this.addRenderableWidget(Button.builder(
                            Component.literal("Page " + (page + 1) + "/" + pages),
                            ignored -> turnPage(1))
                    .bounds(left + 64, controlsY, 100, 20)
                    .build());
        }

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                        ignored -> this.onClose())
                .bounds(left, controlsY + ROW_HEIGHT + GAP, ROW_WIDTH, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, TEXT_COLOR);
        if (!status.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.literal(status).withStyle(ChatFormatting.GRAY),
                    this.width / 2, STATUS_Y, TEXT_COLOR);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private void advance(Setting setting) {
        setting.advance().run();
        spec.save().run();
        status = setting.label() + " is now " + setting.value().get();
        // The button's own label has to be rebuilt: it was created with the old
        // value baked in, and a setting that visibly does not change when clicked
        // reads as a broken menu.
        rebuild();
    }

    private int pages() {
        return Math.max(1, (spec.settings().size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
    }

    private void turnPage(int delta) {
        page = Math.floorMod(page + delta, pages());
        rebuild();
    }

    private void rebuild() {
        this.rebuildWidgets();
    }

    /**
     * Not a pause screen, like the rest of this menu: these settings are changed
     * while a bot is working, and in singleplayer a pausing screen would stop it.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
