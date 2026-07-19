package com.kindred.api.media

import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CORSConfiguration
import software.amazon.awssdk.services.s3.model.CORSRule
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import org.slf4j.LoggerFactory

@ConfigurationProperties("kindred.s3")
data class S3Properties(
    val endpoint: String,
    /** Browser-reachable URL for presigned upload URLs. Defaults to `endpoint` when blank. */
    val publicEndpoint: String = "",
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
)

@Configuration
@EnableConfigurationProperties(S3Properties::class)
class S3Config {

    /**
     * Presigner uses the public endpoint so generated URLs are reachable by the browser.
     * When running in Docker the internal endpoint (e.g. http://minio:9000) differs from
     * what the host browser can reach (e.g. http://localhost:9000).
     */
    @Bean
    fun s3Presigner(props: S3Properties): S3Presigner {
        val endpoint = props.publicEndpoint.ifBlank { props.endpoint }
        return S3Presigner.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)),
            )
            // MinIO (and R2 custom domains) want path-style bucket addressing
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
    }

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

    /**
     * Ensures the media bucket exists and sets a CORS policy so browsers can PUT presigned
     * uploads directly. PutBucketCors is attempted but logged-and-skipped on 501 (MinIO drops
     * bucket-level CORS; use MINIO_API_CORS_ALLOW_ORIGIN on the server instead).
     */
    @Bean
    fun s3BucketInit(s3: S3Client, props: S3Properties) = ApplicationRunner {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket).build())
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) {
                s3.createBucket(CreateBucketRequest.builder().bucket(props.bucket).build())
            } else {
                throw e
            }
        }
        try {
            s3.putBucketCors(
                PutBucketCorsRequest.builder()
                    .bucket(props.bucket)
                    .corsConfiguration(
                        CORSConfiguration.builder()
                            .corsRules(
                                CORSRule.builder()
                                    .allowedOrigins("*")
                                    .allowedMethods("GET", "PUT", "HEAD")
                                    .allowedHeaders("*")
                                    .maxAgeSeconds(3600)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
        } catch (e: S3Exception) {
            // MinIO dropped bucket-level CORS (501); handled via MINIO_API_CORS_ALLOW_ORIGIN instead.
            if (e.statusCode() == 501) {
                LoggerFactory.getLogger(S3Config::class.java)
                    .warn("PutBucketCors not supported by this storage backend (501) — set CORS at the server level (e.g. MINIO_API_CORS_ALLOW_ORIGIN)")
            } else {
                throw e
            }
        }
    }
}
