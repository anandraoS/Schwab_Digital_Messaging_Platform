package com.example.messaging.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Restricts Mongo repository scanning to the diagnostics package (stage 2). Without this, Spring Data would probe
 * every JPA repository interface in strict mode and log noise for each one.
 */
@Configuration
@EnableMongoRepositories(basePackages = "com.example.messaging.diagnostics")
public class MongoRepositoriesConfig {}
