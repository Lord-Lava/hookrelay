package com.hookrelay.config;

import com.hookrelay.delivery.application.FanoutProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FanoutProperties.class)
public class FanoutConfiguration {}
