package dev.dyad.store;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Wiring for the persistence layer. Imported by both runnable applications. */
@Configuration
@ComponentScan(basePackages = "dev.dyad.store")
@EnableTransactionManagement
public class StoreConfiguration {}
