package dev.notune.transcribe;

/**
 * Plain snapshot of the focused input field, built from an
 * AccessibilityNodeInfo by the service. Holds only field attributes, never
 * field content, so {@link BubbleController} stays free of Android types.
 */
public final class FieldInfo {
    // android.text.InputType values, inlined to keep this class Android-free.
    static final int TYPE_MASK_CLASS = 0x0000000f;
    static final int TYPE_MASK_VARIATION = 0x00000ff0;
    static final int TYPE_CLASS_TEXT = 0x00000001;
    static final int TYPE_CLASS_NUMBER = 0x00000002;
    static final int TYPE_CLASS_PHONE = 0x00000003;
    static final int TYPE_CLASS_DATETIME = 0x00000004;
    static final int TYPE_TEXT_VARIATION_PASSWORD = 0x00000080;
    static final int TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090;
    static final int TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0;
    static final int TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010;

    public final boolean editable;
    public final boolean visibleToUser;
    public final boolean password;
    public final int inputType;
    public final String className;
    public final String viewId;

    public FieldInfo(boolean editable, boolean visibleToUser, boolean password,
                     int inputType, String className, String viewId) {
        this.editable = editable;
        this.visibleToUser = visibleToUser;
        this.password = password;
        this.inputType = inputType;
        this.className = className;
        this.viewId = viewId;
    }

    boolean isPasswordField() {
        if (password) return true;
        if (className != null && className.toLowerCase(java.util.Locale.ROOT).contains("password")) {
            return true;
        }
        int cls = inputType & TYPE_MASK_CLASS;
        int variation = inputType & TYPE_MASK_VARIATION;
        if (cls == TYPE_CLASS_TEXT) {
            return variation == TYPE_TEXT_VARIATION_PASSWORD
                    || variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        if (cls == TYPE_CLASS_NUMBER) {
            return variation == TYPE_NUMBER_VARIATION_PASSWORD;
        }
        return false;
    }

    /** Number, PIN, phone and date fields: dictation makes no sense there. */
    boolean isNonTextField() {
        int cls = inputType & TYPE_MASK_CLASS;
        return cls == TYPE_CLASS_NUMBER || cls == TYPE_CLASS_PHONE || cls == TYPE_CLASS_DATETIME;
    }

    /** Heuristic: the class name or view ID mentions "search". */
    boolean isSearchField() {
        return containsSearch(className) || containsSearch(viewId);
    }

    private static boolean containsSearch(String s) {
        return s != null && s.toLowerCase(java.util.Locale.ROOT).contains("search");
    }
}
