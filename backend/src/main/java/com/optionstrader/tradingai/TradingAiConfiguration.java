package com.optionstrader.tradingai;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class TradingAiConfiguration {
    @Bean
    public RestClient tradingAiRestClient(
            RestClient.Builder builder,
            @Value("${trading.ai.analysis-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${trading.ai.timeout:20s}") Duration timeout,
            @Value("${trading.internal-token:}") String token) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        builder.baseUrl(baseUrl).requestFactory(requestFactory);
        if(!token.isBlank())builder.defaultHeader("Authorization","Bearer "+token);
        return builder.build();
    }
}
