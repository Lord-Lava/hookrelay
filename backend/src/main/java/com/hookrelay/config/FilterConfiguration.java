package com.hookrelay.config;

import com.hookrelay.auth.ApiKeyAuthenticationFilter;
import com.hookrelay.common.RequestBodySizeLimitFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class FilterConfiguration {

  @Bean
  FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyAuthFilterRegistration(
      ApiKeyAuthenticationFilter filter) {
    FilterRegistrationBean<ApiKeyAuthenticationFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    registration.setDispatcherTypes(DispatcherType.REQUEST);
    return registration;
  }

  @Bean
  FilterRegistrationBean<RequestBodySizeLimitFilter> requestBodySizeLimitFilterRegistration(
      RequestBodySizeLimitFilter filter) {
    FilterRegistrationBean<RequestBodySizeLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
    registration.setDispatcherTypes(DispatcherType.REQUEST);
    return registration;
  }
}
