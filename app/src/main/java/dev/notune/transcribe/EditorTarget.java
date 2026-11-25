package dev.notune.transcribe;

import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

/**
 * Best-effort identity of the text field a dictation started in.
 *
 * <p>Used to refuse inserting a late result into a different field. Editors do
 * not expose a stable instance id to keyboards, so this compares the package,
 * the view id the app assigned, the input type, the field name and the hint.
 * When any of them changed, the result is dropped instead of being pasted into
 * whatever happens to be focused now.
 */
final class EditorTarget {

    final String packageName;
    final int fieldId;
    final int inputType;
    final String fieldName;
    final String hint;

    private EditorTarget(String packageName, int fieldId, int inputType,
                         String fieldName, String hint) {
        this.packageName = packageName;
        this.fieldId = fieldId;
        this.inputType = inputType;
        this.fieldName = fieldName;
        this.hint = hint;
    }

    static EditorTarget from(EditorInfo info) {
        if (info == null) return null;
        CharSequence hintText = info.hintText;
        return new EditorTarget(
                info.packageName == null ? "" : info.packageName.toString(),
                info.fieldId,
                info.inputType,
                info.fieldName == null ? "" : info.fieldName,
                hintText == null ? "" : hintText.toString());
    }

    /**
     * True when the editor exposes an identifier that is stable per field.
     * Package and input type alone are shared by every field in an app, so two
     * different fields can look identical when an app assigns no view id and
     * no field name. Without such an identifier the caller must also require
     * an unchanged input session.
     */
    boolean hasStrongIdentity() {
        return fieldId != 0 || !fieldName.isEmpty();
    }

    boolean matches(EditorInfo info) {
        if (info == null) return false;
        EditorTarget other = from(info);
        if (!TextUtils.equals(packageName, other.packageName)
                || fieldId != other.fieldId
                || inputType != other.inputType
                || !TextUtils.equals(fieldName, other.fieldName)) {
            return false;
        }
        // Some apps clear the hint as soon as the field has text, which would
        // make the same field look different. Compare hints only when both are
        // present; an absent hint carries no evidence either way.
        if (!hint.isEmpty() && !other.hint.isEmpty()) {
            return TextUtils.equals(hint, other.hint);
        }
        return true;
    }
}
