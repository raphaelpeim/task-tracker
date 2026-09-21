package com.personal.task_tracker.commons.config;

import org.openapitools.jackson.nullable.JsonNullableJackson3Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {
	@Bean
	public JsonNullableJackson3Module jsonNullableModule() {
		return new JsonNullableJackson3Module();
	}
}
