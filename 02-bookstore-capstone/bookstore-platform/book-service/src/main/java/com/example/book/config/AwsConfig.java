package com.example.book.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;

/**
 * The DynamoDB client, and the one thing about it that must not be configured.
 *
 * <h2>No credentials appear anywhere in this project</h2>
 *
 * <p>{@link DefaultCredentialsProvider} walks a fixed chain: environment variables, the Java system
 * properties, the shared {@code ~/.aws/credentials} file, container credentials, and finally the EC2/EKS
 * instance metadata service. On a laptop it finds the file that {@code aws configure} wrote; in Step 10
 * it will find an IRSA role attached to the pod, with no code change and no secret anywhere.
 *
 * <p>That progression is the reason there is no {@code app.aws.access-key} property. A config server
 * that held one would be a config server worth stealing (D18), and a value in a YAML file is a value
 * that reaches a Git history eventually. <strong>The best place to put a credential is nowhere.</strong>
 *
 * <h2>The endpoint override exists for tests, not for production</h2>
 *
 * <p>{@code app.aws.dynamodb.endpoint} is normally empty, and the SDK then resolves the real regional
 * endpoint. A test sets it to a local DynamoDB, which is what keeps the suite from needing an AWS
 * account. It is deliberately not a `dev`/`prod` profile switch: the property is empty in both, so
 * running locally talks to real DynamoDB and nothing pretends otherwise.
 */
@Configuration
public class AwsConfig {

    @Bean
    public DynamoDbClient dynamoDbClient(
            @Value("${app.aws.region}") String region,
            @Value("${app.aws.dynamodb.endpoint:}") String endpointOverride) {

        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());

        if (!endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride));
        }

        return builder.build();
    }
}
