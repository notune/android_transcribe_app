package dev.notune.transcribe;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

/**
 * Reads {@link EditorInfo} and says how much sentence logic the field gets. This is the
 * only place the Android field constants meet {@link TextFitter}, which stays free of
 * Android types.
 */
final class FieldKinds {

    private FieldKinds() { }

    static TextFitter.FieldKind of(EditorInfo info) {
        // Nothing to read: behave as an ordinary text field, which is what the app
        // does today.
        if (info == null) return TextFitter.FieldKind.PROSE;

        int type = info.inputType;
        if (type == InputType.TYPE_NULL) return TextFitter.FieldKind.PLAIN;

        int cls = type & InputType.TYPE_MASK_CLASS;
        int variation = type & InputType.TYPE_MASK_VARIATION;

        if (cls == InputType.TYPE_CLASS_TEXT) {
            switch (variation) {
                case InputType.TYPE_TEXT_VARIATION_PASSWORD:
                case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
                case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
                    return TextFitter.FieldKind.PASSWORD;
                case InputType.TYPE_TEXT_VARIATION_URI:
                case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
                case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
                case InputType.TYPE_TEXT_VARIATION_FILTER:
                    return TextFitter.FieldKind.PLAIN;
                default:
                    break;
            }
        } else if (cls == InputType.TYPE_CLASS_NUMBER) {
            // A numeric PIN is masked like any other password: change nothing at all.
            if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                return TextFitter.FieldKind.PASSWORD;
            }
            return TextFitter.FieldKind.PLAIN;
        } else if (cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) {
            return TextFitter.FieldKind.PLAIN;
        }

        // A search box gets "Weather in Moscow", not "Weather in Moscow. ".
        if ((info.imeOptions & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH) {
            return TextFitter.FieldKind.SEARCH;
        }

        // Everything else, including "no suggestions" and multi-line fields.
        return TextFitter.FieldKind.PROSE;
    }
}
