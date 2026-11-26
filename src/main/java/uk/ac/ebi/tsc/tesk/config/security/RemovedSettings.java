package uk.ac.ebi.tsc.tesk.config.security;

import java.util.Optional;
import java.util.stream.StreamSupport;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

/**
 * Refuses the settings of the removed group model, in every profile: any setting under {@code
 * tesk.api.authorisation} (groups, admin and manager groups, the claim, legacy user labels, task
 * visibility, and any other key there, also misspelt ones) except the service clients ({@code
 * tesk.api.authorisation.service-clients}, until the token validator replaces them, #222, with a
 * check of their own in {@link ServiceClientTokenServices#refuseRemovedSettings}). It is a
 * configuration class, never lazy, so that the check runs in every profile, at startup. An
 * installation that still sets one of them fails at startup and names it, instead of running with
 * rules it no longer has. There is no migration: tasks are their creators' only.
 */
@Configuration
@Lazy(false)
public class RemovedSettings {

    /** The prefix of the removed settings */
    static final ConfigurationPropertyName REMOVED =
            ConfigurationPropertyName.of("tesk.api.authorisation");

    /**
     * The settings under the prefix that are kept, as Spring names them (dashes removed): from
     * properties ({@code service-clients}, {@code serviceClients}) and from environment variables,
     * whose {@code _} become dots ({@code TESK_API_AUTHORISATION_SERVICE_CLIENTS_*}) or not ({@code
     * ..._SERVICECLIENTS_*})
     */
    static final java.util.List<String> KEPT =
            java.util.List.of(
                    "tesk.api.authorisation.serviceclients",
                    "tesk.api.authorisation.service.clients");

    /**
     * @throws IllegalStateException if one of the removed settings is set
     */
    public RemovedSettings(Environment environment) {
        refuse(environment);
    }

    /**
     * Refuses the removed settings, in any form Spring reads (properties, YAML, environment
     * variables such as {@code TESK_API_AUTHORISATION_BASEGROUP}, lists), also if set to an empty
     * value
     *
     * @throws IllegalStateException if one of them is set
     */
    static void refuse(Environment environment) {
        Optional<ConfigurationPropertyName> removed = firstRemoved(environment);
        if (removed.isPresent()) {
            throw new IllegalStateException(
                    removed.get()
                            + " was removed with the group model (see the upgrading notes):"
                            + " remove the setting");
        }
    }

    /** The first removed setting that is set, if any */
    static Optional<ConfigurationPropertyName> firstRemoved(Environment environment) {
        return StreamSupport.stream(
                        ConfigurationPropertySources.get(environment).spliterator(), false)
                .filter(IterableConfigurationPropertySource.class::isInstance)
                .map(IterableConfigurationPropertySource.class::cast)
                .flatMap(IterableConfigurationPropertySource::stream)
                .filter(RemovedSettings::isRemoved)
                .findFirst();
    }

    /** Whether the name is a kept setting (see {@link #KEPT}) */
    static boolean isKept(ConfigurationPropertyName name) {
        String normalised = name.toString().toLowerCase(java.util.Locale.ROOT).replace("-", "");
        return KEPT.stream()
                .anyMatch(kept ->
                        normalised.equals(kept)
                                || normalised.startsWith(kept + ".")
                                || normalised.startsWith(kept + "["));
    }

    /** Whether the name is a removed setting: under the prefix, and not a kept one */
    static boolean isRemoved(ConfigurationPropertyName name) {
        return (REMOVED.equals(name) || REMOVED.isAncestorOf(name)) && !isKept(name);
    }
}
