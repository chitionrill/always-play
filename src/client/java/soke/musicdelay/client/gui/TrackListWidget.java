package soke.musicdelay.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import soke.musicdelay.client.BrowsableTrack;

import java.util.List;

/**
 * Список строк браузера треков: заголовки групп (с отступом по глубине вложенности)
 * и сами треки с кнопками play/add. Никакой логики фильтрации или плейлистов внутри —
 * все действия идут через TrackRowCallbacks, который реализует владеющий экран.
 *
 * Раньше этот класс был вложенным в MusicBrowserScreen и неявно видел его поле font
 * (унаследованное от Screen). Как отдельный top-level класс своего Screen-родителя
 * не имеет, поэтому Font сохраняется явно через конструктор.
 */
public class TrackListWidget extends ObjectSelectionList<TrackListWidget.TrackEntry> {

    private static final Identifier MUSIC_NOTES_SPRITE = Identifier.parse("icon/music_notes");
    private static final int PLAY_BUTTON_SIZE = 16;
    private static final int PLUS_BUTTON_SIZE = 16;
    private static final int GROUP_INDENT_PX = 10;

    private final Font font;
    private final TrackRowCallbacks callbacks;

    public TrackListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight, TrackRowCallbacks callbacks) {
        super(minecraft, width, height, y, itemHeight);
        this.font = minecraft.font;
        this.callbacks = callbacks;
    }

    public void setEntries(List<BrowsableTrack> tracks) {
        this.clearEntries();
        for (BrowsableTrack track : tracks) {
            this.addEntry(new TrackEntry(track));
        }
    }

    public double getScrollAmountPublic() {
        return this.scrollAmount();
    }

    @Override
    public int getRowWidth() {
        return Math.min(420, this.width - 20);
    }

    public class TrackEntry extends ObjectSelectionList.Entry<TrackEntry> {
        final BrowsableTrack track;

        TrackEntry(BrowsableTrack track) {
            this.track = track;
        }

        @Override
        public Component getNarration() {
            return track.displayName;
        }

        // Вложенные заголовки/треки из дерева папок сдвигаются вправо на depth
        // уровней, чтобы визуально показать вложенность (папка -> подпапка -> трек).
        private int indentPx() {
            return track.depth * GROUP_INDENT_PX;
        }

        private int playButtonX() {
            return getContentRight() - PLAY_BUTTON_SIZE - PLUS_BUTTON_SIZE - 8;
        }

        private int plusButtonX() {
            return getContentRight() - PLUS_BUTTON_SIZE;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            if (track.kind == BrowsableTrack.Kind.HEADER) {
                boolean collapsed = callbacks.isHeaderCollapsed(track);
                int arrowX = getContentX() + 4 + indentPx();
                graphics.text(font, collapsed ? "\u25B6" : "\u25BC", arrowX, getContentY() + 5, 0xFFFFAA00);
                graphics.centeredText(font, track.displayName, getContentXMiddle(), getContentY() + 5, 0xFFFFFF55);
                return;
            }

            boolean isSelected = callbacks.isSelected(track);
            if (hovered || isSelected) {
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                        isSelected ? 0x80336633 : 0x40FFFFFF);
            }

            int iconX = getContentX() + indentPx();
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MUSIC_NOTES_SPRITE, iconX, getContentY() + 3, 16, 16, -1);

            int textX = iconX + 20;
            int maxTextWidth = playButtonX() - textX - 6;
            Component displayText = font.width(track.displayName) > maxTextWidth
                    ? Component.literal(font.plainSubstrByWidth(track.displayName.getString(), maxTextWidth) + "...")
                    : track.displayName;
            graphics.text(font, displayText, textX, getContentY() + 6, 0xFFDDDDDD);

            boolean playHovered = isOverButton(mouseX, mouseY, playButtonX(), PLAY_BUTTON_SIZE);
            graphics.text(font, "\u25B6", playButtonX() + 3, getContentY() + 6, playHovered ? 0xFFFFFFFF : 0xFF55FF55);

            boolean plusHovered = isOverButton(mouseX, mouseY, plusButtonX(), PLUS_BUTTON_SIZE);
            graphics.text(font, "+", plusButtonX() + 5, getContentY() + 6, plusHovered ? 0xFFFFFFFF : 0xFF55AAFF);
        }

        private boolean isOverButton(int mouseX, int mouseY, int buttonX, int size) {
            return mouseX >= buttonX && mouseX < buttonX + size && mouseY >= getY() && mouseY < getY() + getHeight();
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (track.kind == BrowsableTrack.Kind.HEADER) {
                callbacks.onToggleHeader(track);
                return true;
            }

            int mx = (int) event.x();
            int my = (int) event.y();

            if (isOverButton(mx, my, playButtonX(), PLAY_BUTTON_SIZE)) {
                callbacks.onPlay(track);
                return true;
            }
            if (isOverButton(mx, my, plusButtonX(), PLUS_BUTTON_SIZE)) {
                callbacks.onAdd(track);
                return true;
            }

            callbacks.toggleSelected(track);
            return true;
        }
    }
}