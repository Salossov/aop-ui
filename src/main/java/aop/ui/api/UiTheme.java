package aop.ui.api;

/** Resource folder and ARGB text colors; carries no client-only classes. */
public record UiTheme(String namespace, String textureFolder, int text, int muted, int accent) {
    public static final UiTheme THAUMCRAFT = new UiTheme("aop_ui", "thaumcraft", 0xffe9dbb9, 0xffa99c85, 0xffc5a052);
    public static final UiTheme TINKERER = new UiTheme("aop_ui", "tinkerer", 0xffffe4b2, 0xffb4a28d, 0xffe1b74f);
    public static final UiTheme COMPACT = new UiTheme("aop_ui", "compact", 0xffdbdfd8, 0xff989f9c, 0xff9aaf9f);
    public static final UiTheme UI = new UiTheme("aop_ui", "ui", 0xffdbdfd8, 0xff989f9c, 0xff9aaf9f);

    /** Shared grayscale masks take these tints at render time. Existing custom themes still work. */
    public boolean sharedMasks() { return namespace.equals("aop_ui"); }
    public int surface() { return switch(textureFolder) { case "tinkerer" -> 0xff201a20; case "compact" -> 0xff191d1e; default -> 0xff201c17; }; }
    public int edge() { return switch(textureFolder) { case "tinkerer" -> 0xff665138; case "compact" -> 0xff49534f; default -> 0xff615139; }; }
    public int jewel() { return switch(textureFolder) { case "tinkerer" -> 0xffd171bb; case "compact" -> 0xff93bbb2; default -> 0xffac78d1; }; }
}
