package com.danielhackerxd.modpackdownloader;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class DownloadPromptScreen extends Screen {

    private final List<ModEntry> entries;
    private final List<ModCheckbox> checkboxes = new ArrayList<>();
    private Button nextButton;

    private Component alertMessage = null;
    private int alertTicks = 0;

    // Screen stages: choosing mods -> downloading -> finished (restart) / error
    private enum Stage { CHOOSING, DOWNLOADING, RESTART_REQUIRED, ERROR }
    private Stage stage = Stage.CHOOSING;

    private List<ModEntry> failedEntries = List.of();

    public DownloadPromptScreen(List<ModEntry> entries) {
        super(Component.translatable("screen.mdfm.title"));
        this.entries = entries;
    }

    @Override
    protected void init() {
        checkboxes.clear();

        int top = 70;
        int rowHeight = 22;
        int listWidth = Math.min(360, this.width - 80);
        int left = (this.width - listWidth) / 2;

        for (int i = 0; i < entries.size(); i++) {
            ModEntry entry = entries.get(i);
            // The mod's name comes from the pack's mods.json, so it is not
            // translatable via a lang file: it is shown exactly as the
            // pack author wrote it.
            ModCheckbox box = new ModCheckbox(
                    left, top + i * rowHeight, listWidth, 20,
                    Component.literal(entry.name), entry
            );
            checkboxes.add(box);
            this.addRenderableWidget(box);
        }

        int buttonsY = top + entries.size() * rowHeight + 30;

        // A single button whose behavior depends on the current stage
        // (see onButtonPressed). We don't reassign Button.onPress because
        // it's a protected field: instead, the creation lambda itself
        // checks the current state every time it's pressed.
        nextButton = Button.builder(Component.translatable("screen.mdfm.next"), b -> onButtonPressed())
                .bounds((this.width - 150) / 2, buttonsY, 150, 20)
                .build();
        this.addRenderableWidget(nextButton);

        updateNextButtonState();
    }

    private void onButtonPressed() {
        switch (stage) {
            case CHOOSING -> onNextPressed();
            case RESTART_REQUIRED -> {
                if (this.minecraft != null) {
                    this.minecraft.stop();
                }
            }
            case ERROR -> startDownload();
            case DOWNLOADING -> { /* button is disabled, shouldn't be pressable */ }
        }
    }

    private void updateNextButtonState() {
        boolean allSelected = entries.stream().allMatch(e -> e.selected);
        if (stage == Stage.CHOOSING) {
            nextButton.active = allSelected;
        }
    }

    private void onNextPressed() {
        boolean allSelected = entries.stream().allMatch(e -> e.selected);
        if (!allSelected) {
            showAlert(Component.translatable("screen.mdfm.error.select_all"));
            return;
        }
        startDownload();
    }

    private void showAlert(Component message) {
        this.alertMessage = message;
        this.alertTicks = 100; // ~5 seconds at 20 ticks/sec
    }

    private void startDownload() {
        stage = Stage.DOWNLOADING;
        setWidgetsEnabled(false);
        nextButton.setMessage(Component.translatable("screen.mdfm.next"));

        ModDownloader.downloadAllAsync(
                entries,
                entry -> { /* progress update, the screen just repaints every frame */ },
                failed -> {
                    this.failedEntries = failed;
                    if (failed.isEmpty()) {
                        stage = Stage.RESTART_REQUIRED;
                        nextButton.setMessage(Component.translatable("screen.mdfm.exit_game"));
                    } else {
                        stage = Stage.ERROR;
                        nextButton.setMessage(Component.translatable("screen.mdfm.retry"));
                    }
                    nextButton.active = true;
                }
        );
    }

    private void setWidgetsEnabled(boolean enabled) {
        for (ModCheckbox box : checkboxes) {
            box.active = enabled;
        }
        nextButton.active = false;
    }

    @Override
    public void tick() {
        super.tick();
        if (alertTicks > 0) {
            alertTicks--;
            if (alertTicks == 0) {
                alertMessage = null;
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        graphics.drawCenteredString(this.font, this.title, centerX, 20, 0xFFFFFF);

        MultiLineLabel intro = MultiLineLabel.create(this.font,
                Component.translatable("screen.mdfm.intro"),
                Math.min(420, this.width - 60));
        intro.renderCentered(graphics, centerX, 36, 10, 0xCCCCCC);

        if (stage == Stage.DOWNLOADING) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.mdfm.downloading"),
                    centerX, this.height - 60, 0xFFFF55);
        } else if (stage == Stage.RESTART_REQUIRED) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.mdfm.restart_required.success"),
                    centerX, this.height - 74, 0x55FF55);
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.mdfm.restart_required.instructions"),
                    centerX, this.height - 60, 0xFFFFFF);
        } else if (stage == Stage.ERROR) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.mdfm.error.failed_count", failedEntries.size()),
                    centerX, this.height - 60, 0xFF5555);
        }

        if (alertMessage != null) {
            graphics.drawCenteredString(this.font,
                    alertMessage.copy().withStyle(ChatFormatting.RED),
                    centerX, this.height - 20, 0xFF5555);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // This screen cannot be closed with Esc: it's mandatory.
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    /**
     * Custom checkbox: in 1.20.1 the Checkbox class has no static builder()
     * and no onValueChange callback, so it has to be subclassed and
     * onPress() overridden to find out when the state changes.
     */
    private class ModCheckbox extends Checkbox {
        private final ModEntry entry;

        public ModCheckbox(int x, int y, int width, int height, Component message, ModEntry entry) {
            super(x, y, width, height, message, entry.selected);
            this.entry = entry;
        }

        @Override
        public void onPress() {
            super.onPress();
            entry.selected = this.selected();
            updateNextButtonState();
        }
    }
}
