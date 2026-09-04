package at.aimon.memory.store;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Wiring for the persistence layer. Imported by both runnable applications. */
@Configuration
@ComponentScan(basePackages = "at.aimon.memory.store")
@EnableTransactionManagement
public class StoreConfiguration {
}
