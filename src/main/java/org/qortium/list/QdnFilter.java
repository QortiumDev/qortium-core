package org.qortium.list;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.arbitrary.misc.Service;
import org.qortium.data.naming.NameData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;

import java.util.ArrayList;
import java.util.List;

/**
 * A compiled set of {@link QdnPattern}s for one QDN list ({@code followedQdn} or
 * {@code blockedQdn}), ready to match resource {@code (service, name, identifier)} triples.
 * <p>
 * Build a filter once per scan/search pass and reuse it across candidates &mdash; building
 * resolves every address-alias entry to its owned names with a single repository lookup per
 * address, so per-candidate matching is pure in-memory string work. When a list contains no
 * address aliases, building touches the repository zero times.
 */
public class QdnFilter {

    private static final Logger LOGGER = LogManager.getLogger(QdnFilter.class);

    private final List<QdnPattern> patterns;

    private QdnFilter(List<QdnPattern> patterns) {
        this.patterns = patterns;
    }

    /**
     * Build a filter from the named resource list (exact name, no prefix scanning).
     */
    public static QdnFilter forList(String listName) {
        return build(ResourceListManager.getInstance().getStringsInList(listName));
    }

    /**
     * Build a filter directly from a list of raw pattern strings.
     */
    public static QdnFilter ofPatterns(List<String> rawPatterns) {
        return build(rawPatterns);
    }

    static QdnFilter build(List<String> rawPatterns) {
        List<QdnPattern> compiled = new ArrayList<>();
        if (rawPatterns == null || rawPatterns.isEmpty()) {
            return new QdnFilter(compiled);
        }

        List<QdnPattern> addressAliases = new ArrayList<>();
        for (String raw : rawPatterns) {
            QdnPattern pattern = QdnPattern.parse(raw);
            if (pattern == null) {
                continue;
            }
            if (pattern.getAddressAlias() != null) {
                addressAliases.add(pattern);
            } else {
                compiled.add(pattern);
            }
        }

        // Expand "any name owned by this address" aliases into one concrete-name pattern each.
        if (!addressAliases.isEmpty()) {
            try (final Repository repository = RepositoryManager.getRepository()) {
                for (QdnPattern alias : addressAliases) {
                    List<NameData> names = repository.getNameRepository().getNamesByOwner(alias.getAddressAlias());
                    if (names == null) {
                        continue;
                    }
                    for (NameData nameData : names) {
                        if (nameData != null && nameData.getName() != null) {
                            compiled.add(alias.withName(nameData.getName()));
                        }
                    }
                }
            } catch (DataException e) {
                LOGGER.info("Unable to resolve address aliases for QDN list: {}", e.getMessage());
            }
        }

        return new QdnFilter(compiled);
    }

    public boolean isEmpty() {
        return this.patterns.isEmpty();
    }

    public boolean matches(Service service, String name, String identifier) {
        return matches(service == null ? null : service.name(), name, identifier);
    }

    public boolean matches(String serviceName, String name, String identifier) {
        if (this.patterns.isEmpty()) {
            return false;
        }
        for (QdnPattern pattern : this.patterns) {
            if (pattern.matches(serviceName, name, identifier)) {
                return true;
            }
        }
        return false;
    }
}
