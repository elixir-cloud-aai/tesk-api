package uk.ac.ebi.tsc.tesk.authz;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import uk.ac.ebi.tsc.tesk.config.security.AuthorisationProperties;
import uk.ac.ebi.tsc.tesk.tes.service.ServiceInfoService;

/** The bean {@code tesAuthz} ({@link TesAuthz}), in both profiles, so that its checks always run */
@Configuration
public class AuthzConfiguration {

    @Bean
    public TesAuthz tesAuthz(
            Environment environment,
            ServiceInfoService serviceInfoService,
            AuthorisationProperties authorisationProperties) {
        AuthorisationProperties.ServiceClients serviceClients =
                authorisationProperties.getServiceClients();
        return new TesAuthz(
                AuthzSettings.from(environment),
                environment.acceptsProfiles(Profiles.of("auth")),
                () -> serviceInfoService.getServiceInfo().getId(),
                serviceClients.isEnabled() ? serviceClients.getIssuer() : null);
    }
}
