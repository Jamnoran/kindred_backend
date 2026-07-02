package com.kindred.api.media

import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/** Thin wrapper over S3Client so services can be tested without AWS types. */
@Component
class MediaStorage(
    private val s3: S3Client,
    private val props: S3Properties,
) {

    fun get(key: String): ByteArray =
        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(props.bucket).key(key).build()).asByteArray()

    fun put(key: String, bytes: ByteArray, contentType: String) {
        s3.putObject(
            PutObjectRequest.builder().bucket(props.bucket).key(key).contentType(contentType).build(),
            RequestBody.fromBytes(bytes),
        )
    }

    fun delete(key: String) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(props.bucket).key(key).build())
    }
}
