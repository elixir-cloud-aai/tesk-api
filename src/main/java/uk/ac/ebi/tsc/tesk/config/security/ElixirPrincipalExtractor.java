package uk.ac.ebi.tsc.tesk.config.security;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.StringUtils;

/**
 * Custom principal extractor (puts all of the info from userinfo to custom {@link User} object;
 * used by {@link UserInfoTokenIntrospector})
 *
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 */
public class ElixirPrincipalExtractor {

    private final GroupNamesSecurityExtractor groupsExtractor;
    private final AuthorisationProperties authorisationProperties;
    private final Pattern MEMBERED_GROUP;
    private final Pattern MANAGED_GROUP;
    /** Name of the claim that authorises users without groups; null if that setting is off */
    private final String claimName;
    /** Values of that claim, one of which authorises a user */
    private final Set<String> claimValues;

    ElixirPrincipalExtractor(
            GroupNamesSecurityExtractor groupsExtractor,
            AuthorisationProperties authorisationProperties) {
        this.groupsExtractor = groupsExtractor;
        this.authorisationProperties = authorisationProperties;
        // the configured parts are text, matched literally; only the team name is a pattern
        MEMBERED_GROUP =
                Pattern.compile(
                        "^"
                                + literal(authorisationProperties.getBaseGroupPrefix())
                                + "([^:]+)"
                                + literal(authorisationProperties.getGroupSuffix()));
        MANAGED_GROUP =
                Pattern.compile(
                        "^"
                                + literal(authorisationProperties.getBaseGroupPrefix())
                                + "([^:]+):"
                                + literal(authorisationProperties.getAdminSubgroup())
                                + literal(authorisationProperties.getGroupSuffix()));
        AuthorisationProperties.Claim claim = authorisationProperties.getClaim();
        if (claim.isEnabled()) {
            Set<String> values =
                    Optional.ofNullable(claim.getValues()).orElse(Collections.emptyList()).stream()
                            .filter(StringUtils::hasText)
                            .map(String::trim)
                            .collect(Collectors.toSet());
            if (!StringUtils.hasText(claim.getName()) || values.isEmpty()) {
                // fail at startup rather than authorising nobody, or (with a blank value) anybody
                throw new IllegalStateException(
                        "tesk.api.authorisation.claim.enabled needs"
                                + " tesk.api.authorisation.claim.name and at least one non-blank"
                                + " value in tesk.api.authorisation.claim.values");
            }
            this.claimName = claim.getName().trim();
            this.claimValues = Collections.unmodifiableSet(values);
        } else {
            this.claimName = null;
            this.claimValues = Collections.emptySet();
        }
    }

    /**
     * A configured part of the group names as a regular expression that matches exactly that text
     * (an unset part as {@code null}, as before quoting)
     */
    private static String literal(String part) {
        return Pattern.quote(String.valueOf(part));
    }

    /**
     * Whether the user info has one of the required values in the claim (a string, or a list of
     * them, as JSON gives them), compared exactly; always false if the setting is off
     */
    private boolean isClaimAuthorised(Map<String, Object> map) {
        if (this.claimName == null) {
            return false;
        }
        Object claim = map.get(this.claimName);
        Collection<?> values =
                claim instanceof Collection
                        ? (Collection<?>) claim
                        : Collections.singletonList(claim);
        return values.stream()
                .anyMatch(value -> value instanceof String && this.claimValues.contains(value));
    }

    /** The user of the user info, with its groups and rights */
    public User extractPrincipal(Map<String, Object> map) {
        User.UserBuilder builder =
                User.builder(map.get("sub").toString())
                        .preferredUsername(
                                Optional.ofNullable(map.get("preferred_username"))
                                        .map(Object::toString)
                                        .orElse(null))
                        .name(
                                Optional.ofNullable(map.get("name"))
                                        .map(Object::toString)
                                        .orElse(null))
                        .email(
                                Optional.ofNullable(map.get("email"))
                                        .map(Object::toString)
                                        .orElse(null))
                        .givenName(
                                Optional.ofNullable(map.get("given_name"))
                                        .map(Object::toString)
                                        .orElse(null))
                        .familyName(
                                Optional.ofNullable(map.get("family_name"))
                                        .map(Object::toString)
                                        .orElse(null));

        Set<String> allGroups =
                this.groupsExtractor.extractAuthorities(map).stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());
        Set<String> memberedGroups =
                allGroups.stream()
                        .filter(
                                name ->
                                        MEMBERED_GROUP.matcher(name).matches()
                                                && !authorisationProperties
                                                        .getAdminGroupFull()
                                                        .equals(name))
                        .map(
                                name -> {
                                    Matcher matcher = MEMBERED_GROUP.matcher(name);
                                    matcher.matches();
                                    return matcher.group(1);
                                })
                        .collect(Collectors.toSet());
        Set<String> managedGroups =
                allGroups.stream()
                        .filter(
                                name ->
                                        MANAGED_GROUP.matcher(name).matches()
                                                && !authorisationProperties
                                                        .getAdminGroupFull()
                                                        .equals(name))
                        .map(
                                name -> {
                                    Matcher matcher = MANAGED_GROUP.matcher(name);
                                    matcher.matches();
                                    return matcher.group(1);
                                })
                        .collect(Collectors.toSet());
        boolean isAdmin =
                allGroups.stream()
                        .anyMatch(name -> authorisationProperties.getAdminGroupFull().equals(name));
        return builder.allGroups(allGroups)
                .teskMemberedGroups(memberedGroups)
                .teskManagedGroups(managedGroups)
                .teskAdmin(isAdmin)
                .claimAuthorised(isClaimAuthorised(map))
                .legacyUserLabels(authorisationProperties.isLegacyUserLabels())
                .build();
    }
}
