package dev.leonetic.features.modules.hud;

/**
 * Snap positions for the Active Modules HUD list.
 * Declared as a top-level enum so EnumConverter.increaseEnum() works correctly
 * (it uses getDeclaringClass().getEnumConstants() — nested enums return the
 * enclosing class which breaks cycling in the ClickGUI).
 */
public enum ActiveModulesSnapTo {
    TOP_LEFT,
    TOP_RIGHT,
    CENTER_LEFT,
    CENTER_RIGHT,
    BOTTOM_LEFT,
    DEFAULT,       // center-right (legacy name, kept for config compat)
    BOTTOMRIGHT    // bottom-right above coords/ping (legacy name)
}
