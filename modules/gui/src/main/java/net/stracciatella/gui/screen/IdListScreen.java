package net.stracciatella.gui.screen;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Edit a list of registry ids: type one, remove one, or add the one the
 * game already has in front of the player — the block under the crosshair,
 * the item in hand, whatever the page's {@link Spec#contextId()} reads.
 *
 * <p>Every list a page here edits is the same list: block ids the chunk
 * miner leaves standing, item ids the bot does not walk to. Both are a
 * paged list of strings with a remove button each, a text box with
 * validation, and one context button. What differs is what an id names,
 * what the context button reads, and where the list is saved — and that is
 * exactly what the {@link Spec} carries, so the widget code lives once and
 * the feature modules keep only the wording and the lookups.
 */
public final class IdListScreen extends Screen {

    /**
     * What one list needs from its page.
     *
     * @param title          heading and window title
     * @param entries        the live list — edited in place, so the owner's
     *                       config sees every change without a copy back
     * @param normalize      canonical id for what the player typed, or null
     *                       when it names nothing in the registry
     * @param notAnId        what a rejected entry is not, e.g. "is not a block"
     * @param emptyHint      shown while the list is empty
     * @param inputHint      placeholder in the text box
     * @param save           persists the list after every change
     * @param contextLabel   the context button's label
     * @param contextId      the id that button adds, or null when the game
     *                       has nothing in front of the player right now
     * @param contextMissing what to say when it returns null
     */
    public record Spec(String title,
                       List<String> entries,
                       Function<String, String> normalize,
                       String notAnId,
                       String emptyHint,
                       String inputHint,
                       Runnable save,
                       String contextLabel,
                       Supplier<String> contextId,
                       String contextMissing) {
    }

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
    private final Spec spec;

    private EditBox input;
    private String status = "";
    private int page;

    public IdListScreen(Screen parent, Spec spec) {
        super(Component.literal(spec.title()));
        this.parent = parent;
        this.spec = spec;
    }

    @Override
    protected void init() {
        int rowWidth = LIST_WIDTH + GAP + REMOVE_WIDTH;
        int left = this.width / 2 - rowWidth / 2;

        List<String> entries = spec.entries();
        int pages = pages();
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
        input = new EditBox(this.font, left, inputY, LIST_WIDTH, 20, Component.literal("Id"));
        input.setMaxLength(128);
        input.setHint(Component.literal(spec.inputHint()));
        this.addRenderableWidget(input);
        this.addRenderableWidget(Button.builder(Component.literal("Add"),
                        button -> add(input.getValue()))
                .bounds(left + LIST_WIDTH + GAP, inputY, REMOVE_WIDTH, 20)
                .build());

        int bottomY = inputY + ROW_HEIGHT + GAP;
        this.addRenderableWidget(Button.builder(Component.literal(spec.contextLabel()),
                        button -> addFromContext())
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
        } else if (spec.entries().isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.literal(spec.emptyHint()).withStyle(ChatFormatting.GRAY),
                    this.width / 2, STATUS_Y, TEXT_COLOR);
        }
    }

    private int pages() {
        return Math.max(1, (spec.entries().size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
    }

    private void turnPage(int delta) {
        page = Math.floorMod(page + delta, pages());
        rebuild();
    }

    private void add(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String id = spec.normalize().apply(raw.trim());
        if (id == null) {
            status = "'" + raw.trim() + "' " + spec.notAnId();
            return;
        }
        if (spec.entries().contains(id)) {
            status = id + " is already on the list";
            return;
        }
        spec.entries().add(id);
        spec.save().run();
        status = "Added " + id;
        rebuild();
    }

    private void addFromContext() {
        String id = spec.contextId().get();
        if (id == null) {
            status = spec.contextMissing();
            return;
        }
        add(id);
    }

    private void remove(String id) {
        if (spec.entries().remove(id)) {
            spec.save().run();
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

    /**
     * Not a pause screen, like the root menu: the lists here are edited while
     * a bot is working, and in singleplayer a pausing screen would stop it.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
