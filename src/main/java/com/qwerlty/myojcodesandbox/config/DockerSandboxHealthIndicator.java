package com.qwerlty.myojcodesandbox.config;

import com.github.dockerjava.api.DockerClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "codesandbox.type", havingValue = "container", matchIfMissing = true)
public class DockerSandboxHealthIndicator implements HealthIndicator {

    private final DockerClient dockerClient;

    public DockerSandboxHealthIndicator(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public Health health() {
        try {
            dockerClient.pingCmd().exec();
            return Health.up().withDetail("runtime", "docker").build();
        } catch (RuntimeException exception) {
            return Health.down()
                    .withDetail("runtime", "docker")
                    .withDetail("errorType", exception.getClass().getSimpleName())
                    .build();
        }
    }
}
