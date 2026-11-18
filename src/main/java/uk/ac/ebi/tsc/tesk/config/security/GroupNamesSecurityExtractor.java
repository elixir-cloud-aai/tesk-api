package uk.ac.ebi.tsc.tesk.config.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Custom authorities extractor, that looks for groups element and transforms it into authorities.
 * Inspired by {@code FixedAuthoritiesExtractor} of Spring Security OAuth. Used by {@link
 * UserInfoTokenIntrospector}.
 *
 * @author Ania Niewielska &lt;aniewielska@ebi.ac.uk&gt;
 */
public class GroupNamesSecurityExtractor {

    public GroupNamesSecurityExtractor(String authoritiesClaim, String groupPrefix) {
        this.authoritiesClaim = authoritiesClaim;
        this.groupPrefix = groupPrefix;
    }

    private final String authoritiesClaim;
    private final String groupPrefix;

    /**
     * The group names in the groups claim of the user info, as authorities; without that claim, the
     * authority {@code <group prefix>:USER}; a null claim gives no authorities
     */
    public List<GrantedAuthority> extractAuthorities(Map<String, Object> map) {
        String authorities = this.groupPrefix + ":USER";
        if (map.containsKey(this.authoritiesClaim)) {
            authorities = asAuthorities(map.get(this.authoritiesClaim));
        }
        return AuthorityUtils.commaSeparatedStringToAuthorityList(authorities);
    }

    private String asAuthorities(Object object) {
        if (object == null) {
            // a null claim means no groups, not a group called "null"
            return "";
        }
        if (object instanceof Collection) {
            return StringUtils.collectionToCommaDelimitedString((Collection<?>) object);
        }
        if (ObjectUtils.isArray(object)) {
            return StringUtils.arrayToCommaDelimitedString((Object[]) object);
        }
        // a single group, e.g. a string of comma-separated names
        return String.valueOf(object);
    }
}
