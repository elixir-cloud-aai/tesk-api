package uk.ac.ebi.tsc.tesk.config.security;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.env.Environment;

/**
 * Finds settings by name in every form that Spring reads (properties, YAML, environment variables,
 * lists), for the startup checks that refuse settings ({@link RemovedSettings}, {@link
 * ResourceServerConfiguration})
 */
final class PropertyNames {

    private PropertyNames() {}

    /** The first setting whose name matches, in any property source of the environment */
    static Optional<ConfigurationPropertyName> firstSet(
            Environment environment, Predicate<ConfigurationPropertyName> matches) {
        return StreamSupport.stream(
                        ConfigurationPropertySources.get(environment).spliterator(), false)
                .filter(IterableConfigurationPropertySource.class::isInstance)
                .map(IterableConfigurationPropertySource.class::cast)
                .flatMap(IterableConfigurationPropertySource::stream)
                .filter(matches)
                .findFirst();
    }

    /**
     * The rest of the name below the first of the prefixes that it is or starts with (e.g. {@code
     * .jwksuri} or {@code [0].clientid}), in lower case and without dashes; empty if it is below
     * none. Prefixes are given in that form; environment variables give dots for dashes ({@code
     * SERVICE_CLIENTS}) or nothing ({@code SERVICECLIENTS}), so callers list both forms.
     *
     * @return the rest, {@code ""} for a prefix itself
     */
    static Optional<String> below(ConfigurationPropertyName name, List<String> prefixes) {
        String normalised = name.toString().toLowerCase(Locale.ROOT).replace("-", "");
        for (String prefix : prefixes) {
            if (normalised.equals(prefix)
                    || normalised.startsWith(prefix + ".")
                    || normalised.startsWith(prefix + "[")) {
                return Optional.of(normalised.substring(prefix.length()));
            }
        }
        return Optional.empty();
    }
}
