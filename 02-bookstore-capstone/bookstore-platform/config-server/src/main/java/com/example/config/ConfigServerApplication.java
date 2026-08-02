package com.example.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * The platform's configuration server.
 *
 * <p>The entire service is this annotation. {@code @EnableConfigServer} registers the controllers that
 * answer {@code /{application}/{profile}} by reading the backing repository and returning a merged,
 * ordered list of property sources. There is no controller, no service and no repository in this module
 * to write - which is the point: configuration distribution is a solved problem, and the interesting
 * work is deciding <em>what</em> to centralise, not how to serve it.
 *
 * <p>What this server deliberately does not do: it does not push. Clients pull on startup, and pull
 * again when told to (Step 6c). A server that pushed would need to know every client's address, which
 * is exactly the coupling a config server exists to remove.
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
