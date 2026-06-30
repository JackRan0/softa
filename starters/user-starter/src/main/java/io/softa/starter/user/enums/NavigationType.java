package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Navigation node type.
 * - GROUP:  Category container (sidebar section header). No route. Not granted.
 * - MENU:   Sidebar entry. Has route. Pure-container MENU (model=null) acts like GROUP.
 * - BUTTON: Sub-page entry under a MENU. Has route. Granted.
 * - TAB:    Cross-model auth-context tab in a MultiView page. No route. Granted.
 */
@Getter
@AllArgsConstructor
public enum NavigationType {
    GROUP("Group", "Category container, not granted"),
    MENU("Menu", "Sidebar entry"),
    BUTTON("Button", "Sub-page entry"),
    TAB("Tab", "Cross-model auth-context tab")
    ;

    @JsonValue
    private final String code;

    private final String description;
}
