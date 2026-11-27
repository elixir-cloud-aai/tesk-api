package uk.ac.ebi.tsc.tesk.config.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Settings found by name, in the forms that Spring reads */
public class PropertyNamesTest {

    private static final List<String> PREFIXES = List.of("a.serviceclients", "a.service.clients");

    private static Optional<String> below(String name) {
        return PropertyNames.below(ConfigurationPropertyName.of(name), PREFIXES);
    }

    @Test
    public void prefixItself_hasEmptyRest() {
        assertEquals(Optional.of(""), below("a.service-clients"));
    }

    @Test
    public void namesBelowPrefix_giveTheirRest() {
        assertEquals(Optional.of(".jwksuri"), below("a.service-clients.jwks-uri"));
        assertEquals(Optional.of("[0].clientid"), below("a.service-clients[0].client-id"));
        assertEquals(Optional.of(".enabled"), below("a.service.clients.enabled"));
    }

    @Test
    public void otherNames_areNotBelow() {
        assertEquals(Optional.empty(), below("a.service-clientsx"));
        assertEquals(Optional.empty(), below("a.service"));
        assertEquals(Optional.empty(), below("b.service-clients"));
    }

    @Test
    public void firstSet_findsMatchingSetting() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("a.service-clients.x", "1")));
        assertEquals(
                Optional.of(ConfigurationPropertyName.of("a.service-clients.x")),
                PropertyNames.firstSet(environment, name -> below(name.toString()).isPresent()));
        assertEquals(Optional.empty(), PropertyNames.firstSet(environment, name -> false));
    }
}
