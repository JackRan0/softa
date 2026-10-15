package io.softa.starter.user.service;

import io.softa.starter.user.dto.ListViewPreferenceDTO;
import io.softa.starter.user.entity.ListViewPreference;

import io.softa.framework.orm.service.EntityService;

/**
 * The caller's own list arrangements. Every method acts on the signed-in account and no other.
 */
public interface ListViewPreferenceService extends EntityService<ListViewPreference, Long> {

    /** The caller's arrangement of a list, filtered to what they may read now; null when none. */
    ListViewPreferenceDTO getMine(String viewKey);

    /** Save the non-null parts of {@code preference} over the caller's arrangement of its list. */
    ListViewPreferenceDTO saveMine(ListViewPreferenceDTO preference);

    /**
     * Forget part of the caller's arrangement.
     *
     * @param part {@code columns}, {@code export}, {@code settings}, or null for all three
     */
    void resetMine(String viewKey, String part);
}
