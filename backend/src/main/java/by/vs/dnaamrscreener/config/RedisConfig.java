package by.vs.dnaamrscreener.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public LettuceConnectionFactory geneRedisConnectionFactory(
            @Value("${spring.data.redis.gene.lettuce.pool.max-idle}") int maxIdle,
            @Value("${spring.data.redis.gene.lettuce.pool.min-idle}") int minIdle,
            @Value("${spring.data.redis.gene.lettuce.pool.max-total}") int maxTotal,
            @Value("${spring.data.redis.gene.host}") String host,
            @Value("${spring.data.redis.gene.port}") int port,
            @Value("${spring.data.redis.gene.password:}") String password,
            @Value("${spring.data.redis.gene.database:0}") int database,
            @Value("${spring.data.redis.gene.timeout:10s}") Duration timeout) {

        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration(host, port);
        conf.setDatabase(database);
        if (password != null && !password.isBlank()) {
            conf.setPassword(password);
        }

        GenericObjectPoolConfig<?> pool = new GenericObjectPoolConfig<>();
        pool.setMinIdle(minIdle);
        pool.setMaxIdle(maxIdle);
        pool.setMaxTotal(maxTotal);

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(pool)
                .commandTimeout(timeout)
                .clientOptions(ClientOptions.builder().autoReconnect(true).build())
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(conf, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    @Qualifier("geneRedisTemplate")
    public StringRedisTemplate geneRedisTemplate(
            @Qualifier("geneRedisConnectionFactory") LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(factory);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @Primary
    public LettuceConnectionFactory sessionRedisConnectionFactory(
            @Value("${spring.data.redis.session.lettuce.pool.max-idle}") int maxIdle,
            @Value("${spring.data.redis.session.lettuce.pool.min-idle}") int minIdle,
            @Value("${spring.data.redis.session.lettuce.pool.max-total}") int maxTotal,
            @Value("${spring.data.redis.session.cluster.nodes}") String nodes,
            @Value("${spring.data.redis.session.password:}") String password,
            @Value("${spring.data.redis.session.timeout:10s}") Duration timeout) {

        List<String> nodeList = Arrays.stream(nodes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (nodeList.isEmpty()) {
            throw new IllegalStateException(
                    "spring.data.redis.session.cluster.nodes must not be empty");
        }

        RedisClusterConfiguration conf = new RedisClusterConfiguration(nodeList);
        if (password != null && !password.isBlank()) {
            conf.setPassword(password);
        }

        GenericObjectPoolConfig<?> pool = new GenericObjectPoolConfig<>();
        pool.setMinIdle(minIdle);
        pool.setMaxIdle(maxIdle);
        pool.setMaxTotal(maxTotal);
        pool.setTestOnBorrow(true);
        pool.setTestWhileIdle(true);

        ClusterTopologyRefreshOptions topologyRefresh = ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(Duration.ofMinutes(1))
                .enableAllAdaptiveRefreshTriggers()
                .build();

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(pool)
                .commandTimeout(timeout)
                .clientOptions(ClusterClientOptions.builder()
                        .topologyRefreshOptions(topologyRefresh)
                        .autoReconnect(true)
                        .build())
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(conf, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    @Primary
    @Qualifier("sessionRedisTemplate")
    public StringRedisTemplate sessionRedisTemplate(
            @Qualifier("sessionRedisConnectionFactory") LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(factory);
        template.afterPropertiesSet();
        return template;
    }
}