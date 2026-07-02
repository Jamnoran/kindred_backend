package com.kindred.api.media

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

@ConfigurationProperties("kindred.s3")
data class S3Properties(
    val endpoint: String,
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
)

@Configuration
@EnableConfigurationProperties(S3Properties::class)
class S3Config {

    /** Presigning is offline — no connection is made to the endpoint at startup. */
    @Bean
    fun s3Presigner(props: S3Properties): S3Presigner = S3Presigner.builder()
        .endpointOverride(URI.create(props.endpoint))
        .region(Region.of(props.region))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)),
        )
        // MinIO (and R2 custom domains) want path-style bucket addressing
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build()

    /** Sync client used by the image-processing worker (get/put/delete objects). */
    @Bean
    fun s3Client(props: S3Properties): S3Client = S3Client.builder()
        .endpointOverride(URI.create(props.endpoint))
        .region(Region.of(props.region))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)),
        )
        .forcePathStyle(true)
        .build()
}
