package com.hanumoka.orchestrator.pure.config;

import com.hanumoka.common.logging.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    // MDC의 traceId를 X-Request-ID 헤더로 전파
                    String traceId = MDC.get(RequestIdFilter.MDC_TRACE_ID);
                    if (traceId != null) {
                        request.getHeaders().add(RequestIdFilter.REQUEST_ID_HEADER, traceId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
