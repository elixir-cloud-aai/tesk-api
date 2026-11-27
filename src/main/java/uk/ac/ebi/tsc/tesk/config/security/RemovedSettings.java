package uk.ac.ebi.tsc.tesk.config.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

/**
 * Refuses the settings of the removed group model and of the removed service clients of #153 and
 * #220, in every profile: any setting under {@code tesk.api.authorisation} (groups, admin and
 * manager groups, the claim, legacy user labels, task visibility, service clients, and any other
 * key there, also misspelt ones), without exceptions. It is a configuration class, never lazy, so
 * that the check runs in every profile, at startup. An installation that still sets one of them
 * fails at startup and names it, with what replaces it ({@link #REPLACEMENTS}), instead of running
 * with rules it no longer has. There is no migration: tasks are their creators' only.
 */
@Configuration
@Lazy(false)
public class RemovedSettings {

    /** The prefix of the removed settings */
    static final ConfigurationPropertyName REMOVED =
            ConfigurationPropertyName.of("tesk.api.authorisation");

    /**
     * The service clients' settings ({@code tesk.api.authorisation.service-clients}), as Spring
     * names them, dashes removed: from properties ({@code service-clients}, {@code serviceClients})
     * and from environment variables, whose {@code _} become dots ({@code
     * TESK_API_AUTHORISATION_SERVICE_CLIENTS_*}) or not ({@code ..._SERVICECLIENTS_*})
     */
    static final List<String> SERVICE_CLIENTS =
            List.of(
                    "tesk.api.authorisation.serviceclients",
                    "tesk.api.authorisation.service.clients");

    /** What replaces a service clients' setting that has no entry in {@link #REPLACEMENTS} */
    static final String ANY_SERVICE_CLIENT_SETTING =
            "the service clients of tesk.api.auth.issuers[i].service-clients";

    /**
     * What replaces each setting of the service clients, by its name below {@code service-clients}
     * without dashes and dots (as properties and environment variables give it)
     */
    static final Map<String, String> REPLACEMENTS = replacements();

    private static Map<String, String> replacements() {
        String issuer = "tesk.api.auth.issuers[i].";
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put(
                "enabled",
                "nothing; listing a client under "
                        + issuer
                        + "service-clients[j].client-id accepts its tokens");
        replacements.put("issuer", issuer + "url");
        replacements.put("jwksuri", issuer + "jwks-uri");
        replacements.put("clientclaim", issuer + "client-claim");
        replacements.put(
                "clients",
                issuer
                        + "service-clients[j].client-id and .allow-any-audience (a client's"
                        + " token must have its own client ID as audience)");
        replacements.put("requireaccesstokentype", "tesk.api.auth.require-access-token-type");
        replacements.put("requireclientsubject", "nothing; sub must be the client ID");
        replacements.put("clockskewseconds", "tesk.api.auth.clock-skew-seconds");
        return replacements;
    }

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
     * @throws IllegalStateException if one of them is set, naming it and what replaces it
     */
    static void refuse(Environment environment) {
        Optional<ConfigurationPropertyName> removed = firstRemoved(environment);
        if (removed.isPresent()) {
            throw new IllegalStateException(message(removed.get()));
        }
    }

    /** Why the setting is refused, and what replaces it */
    static String message(ConfigurationPropertyName name) {
        Optional<String> setting = serviceClientSetting(name);
        if (setting.isEmpty()) {
            return name
                    + " was removed with the group model (see the upgrading notes):"
                    + " remove the setting";
        }
        return name
                + " was removed with the service clients of tesk.api.authorisation (see the"
                + " upgrading notes); replaced by: "
                + REPLACEMENTS.getOrDefault(setting.get(), ANY_SERVICE_CLIENT_SETTING);
    }

    /**
     * The name of the service clients' setting below {@code service-clients}, without dashes and
     * dots (e.g. {@code jwksuri}), if the name is one of them; empty for {@code service-clients}
     * itself
     */
    static Optional<String> serviceClientSetting(ConfigurationPropertyName name) {
        return PropertyNames.below(name, SERVICE_CLIENTS).map(rest -> rest.replace(".", ""));
    }

    /** The first removed setting that is set, if any */
    static Optional<ConfigurationPropertyName> firstRemoved(Environment environment) {
        return PropertyNames.firstSet(environment, RemovedSettings::isRemoved);
    }

    /** Whether the name is a removed setting: the prefix or under it */
    static boolean isRemoved(ConfigurationPropertyName name) {
        return REMOVED.equals(name) || REMOVED.isAncestorOf(name);
    }
}
