/*
 * Copyright 2015 The original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.netflix.spinnaker.clouddriver.azureContainers

import com.netflix.spinnaker.clouddriver.azureContainers.config.AzureConfigurationProperties
import com.netflix.spinnaker.clouddriver.azureContainers.health.AzureHealthIndicator
import com.netflix.spinnaker.clouddriver.azureContainers.security.AzureCredentialsInitializer

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.PropertySource
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableConfigurationProperties
@EnableScheduling
@ConditionalOnProperty('azure.enabled')
@ComponentScan(["com.netflix.spinnaker.clouddriver.azureContainers"])
@PropertySource(value = "classpath:META-INF/clouddriver-core.properties", ignoreResourceNotFound = true)
@Import([ AzureCredentialsInitializer ])
class AzureConfiguration {
  @Bean
  @ConfigurationProperties("azure")
  AzureConfigurationProperties azureConfigurationProperties() {
    new AzureConfigurationProperties()
  }

  @Bean
  AzureHealthIndicator azureHealthIndicator() {
    new AzureHealthIndicator()
  }

  @Bean
  String azureApplicationName(@Value('${Implementation-Version:Unknown}') String implementationVersion) {
    "Spinnaker/$implementationVersion"
  }
}