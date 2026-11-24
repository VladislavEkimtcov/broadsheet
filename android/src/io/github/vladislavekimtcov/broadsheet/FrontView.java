package io.github.vladislavekimtcov.broadsheet;

import java.util.List;

/**
 * The front page as {@link Main} drives it. Two of them: {@code Front} (the TV's rail and grid,
 * in tv/src) and {@code Feed} (the phone's strip and column, in phone/src). Both are views.
 */
interface FrontView {
    interface Listener {
        void onOpen(Relay.Section section, int index);

        /** Shown faded: read on this device, with "Stories you've read" set to dim. */
        boolean isDimmed(String storyId);

        /** A card got focus, or came to rest on screen (to fetch its story ahead if it stays). */
        void onFocusStory(Story story, int index);

        /** A section was moved to ({@code direction}: +1 down the rail, -1 up, 0 first time). */
        void onRailFocus(int index, int direction);

        /** A section stayed chosen for a moment: the reader is looking at it. */
        void onRailRest(int index);

        /** The last rows of a section that has more to load came near. */
        void onNearEnd(Relay.Section section);

        /** Touch: ☰, or a long press on a card (then {@link #focusedStory} is that card's). */
        void onMenu();

        /** Touch: pulled down to refresh, or tapped the message to try again. */
        void onRefresh();
    }

    boolean hasContent();

    void setStatus(String text);

    /** Shown instead of the page when there is nothing to show; otherwise as a note. */
    void showMessage(String text);

    /** New sections. Keeps the current section (by id) and, if asked, the focused story. */
    void setSections(List<Relay.Section> list, boolean keepFocus);

    /** Sections with a given section and story chosen: after the views were built again. */
    void restore(List<Relay.Section> list, String currentId, String focusedStory, boolean railFocused, boolean keepFocus);

    /** The story the options act on from the front page, or null. */
    Story focusedStory();

    boolean railFocused();

    String statusText();

    /** Opens a section by id (and focuses its first story on the TV). */
    void open(String sectionId);

    /** Fades or restores cards after a story was read or the setting changed. */
    void refreshDim();

    /** More stories arrived for a section: their cards go under the others. */
    void append(Relay.Section s);

    /** Back from the reader: the story that was open (it may have moved with Left/Right). */
    void focusStory(int index);

    Relay.Section currentSection();

    /** A tile's width in pixels, for the picture size to fetch ahead. */
    int tileWidth();

    /** The hero card's size in pixels, so its picture can be decoded before the section shows. */
    int[] heroSize();
}
