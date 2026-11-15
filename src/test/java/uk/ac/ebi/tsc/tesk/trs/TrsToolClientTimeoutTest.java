package uk.ac.ebi.tsc.tesk.trs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

/**
 * The TRS lookups during task creation time out (as with OkHttp's defaults up to 1.2.0), so that a
 * TRS host that doesn't answer can't hold request threads indefinitely
 */
public class TrsToolClientTimeoutTest {

    @Test
    public void lookups_haveConnectAndReadTimeouts() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.readTimeout(any())).thenReturn(builder);
        when(builder.build()).thenReturn(new RestTemplate());

        new TrsToolClient(builder, new TrsProperties());

        verify(builder).connectTimeout(TrsToolClient.TIMEOUT);
        verify(builder).readTimeout(TrsToolClient.TIMEOUT);
        assertThat(TrsToolClient.TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }
}
