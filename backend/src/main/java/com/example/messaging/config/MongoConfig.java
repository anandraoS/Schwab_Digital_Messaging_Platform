package com.example.messaging.config;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import java.net.InetSocketAddress;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Local/test only: an in-memory MongoDB-compatible server (mongo-java-server) bound to a random loopback port so the
 * diagnostics store can be exercised without MongoDB. The prod-like profile uses {@code spring.data.mongodb.uri}.
 */
@Configuration
@Profile({"local", "test"})
public class MongoConfig {

    @Bean(destroyMethod = "shutdown")
    public MongoServer inMemoryMongoServer() {
        MongoServer server = new MongoServer(new MemoryBackend());
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        return server;
    }

    @Bean(destroyMethod = "close")
    public MongoClient mongoClient(MongoServer server) {
        InetSocketAddress addr = server.getLocalAddress();
        return MongoClients.create(new ConnectionString("mongodb://127.0.0.1:" + addr.getPort()));
    }
}
