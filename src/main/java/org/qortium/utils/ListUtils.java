package org.qortium.utils;

import org.qortium.arbitrary.misc.Service;
import org.qortium.list.QdnFilter;
import org.qortium.list.ResourceListManager;

import java.util.Collections;
import java.util.List;

/**
 * Convenience accessors for the node's four resource lists. Core only ever consults these four
 * exact list names &mdash; it never scans prefixed list groups.
 * <ul>
 *     <li>{@code followedQdn} / {@code blockedQdn} &mdash; gitignore-style {@code SERVICE/NAME/IDENTIFIER}
 *         wildcard patterns that drive which QDN resources this node downloads, stores and serves
 *         (see {@link QdnFilter} / {@link org.qortium.list.QdnPattern}).</li>
 *     <li>{@code blockedChatNames} / {@code blockedChatAddresses} &mdash; exact names/addresses whose
 *         chat messages are hidden from local read APIs (the messages are still stored and relayed).</li>
 * </ul>
 */
public class ListUtils {

    /* QDN follow / block (wildcard SERVICE/NAME/IDENTIFIER patterns) */

    public static List<String> followedQdn() {
        return nonNull(ResourceListManager.getInstance().getStringsInList("followedQdn"));
    }

    public static List<String> blockedQdn() {
        return nonNull(ResourceListManager.getInstance().getStringsInList("blockedQdn"));
    }

    /**
     * The number of follow patterns. Used to apportion follow-mode storage capacity; with wildcard
     * patterns there is no longer a fixed "number of followed names", so the pattern count is used.
     */
    public static int followedQdnCount() {
        return followedQdn().size();
    }

    /**
     * Build a reusable matcher for the {@code followedQdn} list. Prefer this over
     * {@link #isQdnFollowed} when testing many resources in one pass (address aliases are resolved once).
     */
    public static QdnFilter followedQdnFilter() {
        return QdnFilter.forList("followedQdn");
    }

    /**
     * Build a reusable matcher for the {@code blockedQdn} list. Prefer this over
     * {@link #isQdnBlocked} when testing many resources in one pass (address aliases are resolved once).
     */
    public static QdnFilter blockedQdnFilter() {
        return QdnFilter.forList("blockedQdn");
    }

    public static boolean isQdnFollowed(Service service, String name, String identifier) {
        return followedQdnFilter().matches(service, name, identifier);
    }

    public static boolean isQdnBlocked(Service service, String name, String identifier) {
        return blockedQdnFilter().matches(service, name, identifier);
    }

    /* Chat blocking (exact name / address, applied at local read time only) */

    public static List<String> blockedChatNames() {
        return nonNull(ResourceListManager.getInstance().getStringsInList("blockedChatNames"));
    }

    public static List<String> blockedChatAddresses() {
        return nonNull(ResourceListManager.getInstance().getStringsInList("blockedChatAddresses"));
    }

    public static boolean isChatNameBlocked(String name) {
        return ResourceListManager.getInstance().listContains("blockedChatNames", name, false);
    }

    public static boolean isChatAddressBlocked(String address) {
        return ResourceListManager.getInstance().listContains("blockedChatAddresses", address, true);
    }

    private static List<String> nonNull(List<String> list) {
        return list == null ? Collections.emptyList() : list;
    }

}
