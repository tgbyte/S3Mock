/*
 *  Copyright 2017-2022 Adobe.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.adobe.testing.s3mock.its

import com.amazonaws.services.s3.model.AbortMultipartUploadRequest
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest
import com.amazonaws.services.s3.model.CopyPartRequest
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest
import com.amazonaws.services.s3.model.ListMultipartUploadsRequest
import com.amazonaws.services.s3.model.ListPartsRequest
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PartETag
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.UploadPartRequest
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.lang3.ArrayUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import software.amazon.awssdk.utils.http.SdkHttpUtils
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Date
import java.util.UUID

/**
 * Test the application using the AmazonS3 SDK V1.
 */
internal class MultiPartUploadV1IT : S3TestBase() {
  /**
   * Tests if user metadata can be passed by multipart upload.
   */
  @Test
  fun testMultipartUpload_withUserMetadata(testInfo: TestInfo) {
    val bucketName = givenBucketV1(testInfo)
    val uploadFile = File(UPLOAD_FILE_NAME)
    val objectMetadata = ObjectMetadata()
    objectMetadata.addUserMetadata("key", "value")
    val initiateMultipartUploadResult = s3Client
      .initiateMultipartUpload(
        InitiateMultipartUploadRequest(bucketName, UPLOAD_FILE_NAME, objectMetadata)
      )
    val uploadId = initiateMultipartUploadResult.uploadId
    val uploadPartResult = s3Client.uploadPart(
      UploadPartRequest()
        .withBucketName(initiateMultipartUploadResult.bucketName)
        .withKey(initiateMultipartUploadResult.key)
        .withUploadId(uploadId)
        .withFile(uploadFile)
        .withFileOffset(0)
        .withPartNumber(1)
        .withPartSize(uploadFile.length())
        .withLastPart(true)
    )
    val partETags = listOf(uploadPartResult.partETag)
    s3Client.completeMultipartUpload(
      CompleteMultipartUploadRequest(
        initiateMultipartUploadResult.bucketName,
        initiateMultipartUploadResult.key,
        initiateMultipartUploadResult.uploadId,
        partETags
      )
    )
    val metadataExisting = s3Client.getObjectMetadata(
      initiateMultipartUploadResult.bucketName, initiateMultipartUploadResult.key
    )
    assertThat(metadataExisting.userMetadata)
      .`as`("User metadata should be identical!")
      .isEqualTo(objectMetadata.userMetadata)
  }

  /**
   * Tests if a multipart upload with the last part being smaller than 5MB works.
   */
  @Test
  fun shouldAllowMultipartUploads(testInfo: TestInfo) {
    val bucketName = givenBucketV1(testInfo)
    val uploadFile = File(UPLOAD_FILE_NAME)
    val objectMetadata = ObjectMetadata()
    objectMetadata.addUserMetadata("key", "value")
    val initiateMultipartUploadResult = s3Client
      .initiateMultipartUpload(
        InitiateMultipartUploadRequest(bucketName, UPLOAD_FILE_NAME, objectMetadata)
      )
    val uploadId = initiateMultipartUploadResult.uploadId
    // upload part 1, >5MB
    val randomBytes = randomBytes()
    val partETag = uploadPart(bucketName, UPLOAD_FILE_NAME, uploadId, 1, randomBytes)
    // upload part 2, <5MB
    val uploadPartResult = s3Client.uploadPart(
      UploadPartRequest()
        .withBucketName(initiateMultipartUploadResult.bucketName)
        .withKey(initiateMultipartUploadResult.key)
        .withUploadId(uploadId)
        .withFile(uploadFile)
        .withPartNumber(2)
        .withPartSize(uploadFile.length())
        .withLastPart(true)
    )
    val partETags = listOf(partETag, uploadPartResult.partETag)
    val completeMultipartUpload = s3Client.completeMultipartUpload(
      CompleteMultipartUploadRequest(
        initiateMultipartUploadResult.bucketName,
        initiateMultipartUploadResult.key,
        initiateMultipartUploadResult.uploadId,
        partETags
      )
    )
    // Verify only 1st and 3rd counts
    val `object` = s3Client.getObject(bucketName, UPLOAD_FILE_NAME)
    val uploadFileBytes = readStreamIntoByteArray(uploadFile.inputStream())
    val allMd5s = ArrayUtils.addAll(
      DigestUtils.md5(randomBytes),
      *DigestUtils.md5(uploadFileBytes)
    )

    // verify special etag
    assertThat(completeMultipartUpload.eTag).`as`("Special etag doesn't match.")
      .isEqualTo(DigestUtils.md5Hex(allMd5s) + "-2")

    // verify content size
    assertThat(`object`.objectMetadata.contentLength)
      .`as`("Content length doesn't match")
      .isEqualTo(randomBytes.size.toLong() + uploadFileBytes.size.toLong())

    // verify contents
    assertThat(readStreamIntoByteArray(`object`.objectContent)).`as`(
      "Object contents doesn't match"
    ).isEqualTo(concatByteArrays(randomBytes, uploadFileBytes))
  }

  @Test
  @Throws(IOException::class)
  fun shouldInitiateMultipartAndRetrieveParts(testInfo: TestInfo) {
    val bucketName = givenBucketV1(testInfo)
    val uploadFile = File(UPLOAD_FILE_NAME)
    val objectMetadata = ObjectMetadata()
    val hash = DigestUtils.md5Hex(FileInputStream(uploadFile))
    objectMetadata.addUserMetadata("key", "value")
    val initiateMultipartUploadResult = s3Client
      .initiateMultipartUpload(
        InitiateMultipartUploadRequest(bucketName, UPLOAD_FILE_NAME, objectMetadata)
      )
    val uploadId = initiateMultipartUploadResult.uploadId
    val key = initiateMultipartUploadResult.key
    s3Client.uploadPart(
      UploadPartRequest()
        .withBucketName(initiateMultipartUploadResult.bucketName)
        .withKey(initiateMultipartUploadResult.key)
        .withUploadId(uploadId)
        .withFile(uploadFile)
        .withFileOffset(0)
        .withPartNumber(1)
        .withPartSize(uploadFile.length())
        .withLastPart(true)
    )
    val listPartsRequest = ListPartsRequest(
      bucketName,
      key,
      uploadId
    )
    val partListing = s3Client.listParts(listPartsRequest)
    assertThat(partListing.parts).`as`("Part listing should be 1").hasSize(1)
    val partSummary = partListing.parts[0]
    assertThat(partSummary.eTag).`as`("Etag should match").isEqualTo(hash)
    assertThat(partSummary.partNumber).`as`("Part number should match").isEqualTo(1)
    assertThat(partSummary.lastModified).`as`("LastModified should be valid date")
      .isExactlyInstanceOf(Date::class.java)
  }

  /**
   * Tests if not yet completed / aborted multipart uploads are listed.
   */
  @Test
  fun shouldListMultipartUploads(testInfo: TestInfo) {
    val bucketName = givenBucketV1(testInfo)
    assertThat(
      s3Client.listMultipartUploads(ListMultipartUploadsRequest(bucketName))
        .multipartUploads
    ).isEmpty()
    val initiateMultipartUploadResult = s3Client
      .initiateMultipartUpload(InitiateMultipartUploadRequest(bucketName, UPLOAD_FILE_NAME))
    val uploadId = initiateMultipartUploadResult.uploadId
    val listing = s3Client.listMultipartUploads(ListMultipartUploadsRequest(bucketName))
    assertThat(listing.multipartUploads).isNotEmpty
    assertThat(listing.bucketName).isEqualTo(bucketName)
    assertThat(listing.multipartUploads).hasSize(1)
    val upload = listing.multipartUploads[0]
    assertThat(upload.uploadId).isEqualTo(uploadId)
    assertThat(upload.key).isEqualTo(UPLOAD_FILE_NAME)
  }

  /**
   * Tests if empty parts list of not yet completed multipart upload is returned.
   */
  @Test
  fun shouldListEmptyPartListForMultipartUpload(testInfo: TestInfo) {
    val bucketName = givenBucketV1(testInfo)
    assertThat(
      s3Client.listMultipartUploads(ListMultipartUploadsRequest(bucketName))
        .multipartUploads
    ).isEmpty()
    val initiateMultipartUploadResult = s3Client
      .initiateMultipartUpload(InitiateMultipartUploadRequest(bucketName, UPLOAD_FILE_NAME))
    val uploadId = initiateMultipartUploadResult.uploadId
    val listing = s3Client.listParts(ListPartsRequest(bucketName, UPLOAD_FILE_NAME, uploadId))
    assertThat(listing.parts).isEmpty()
    assertThat(listing.bucketName).isEqualTo(bucketName)
    assertThat(listing.uploadId).isEqualTo(uploadId)
    assertThat(SdkHttpUtils.urlDecode(listing.key)).isEqualTo(UPLOAD_FILE_NAME)
  }

  /**
   * Tests that an exception is thrown when listing parts if the upload id is unknown.
   */
  @Test
  fun shouldThrowOnListMultipartUploadsWithUnknownId(testInfo: TestInfo) {
    val bucketName = givenBucketV1(testInfo)
    assertThatThrownBy { s3Client.listParts(ListPartsRequest(bucketName, "NON_EXISTENT_KEY",
      "NON_EXISTENT_UPLOAD_ID")) }
      .isInstanceOf(AmazonS3Exception::class.java)
      .hasMessageContaining("Status Code: 404; Error Code: NoSuchUpload")
  }

  /**
   * Tests if not yet completed / aborted multipart uploads are listed with prefix filtering.
   */
  @Test
  fun shouldListMultipartUploadsWithPrefix(testInfo: TestInfo) {
    val bucketName = givenBucketV1(testInfo)
    s3Client.initiateMultipartUpload(
      InitiateMultipartUploadRequest(bucketName, "key1")
    )
    s3Client.initiateMultipartUpload(
      InitiateMultipartUploadRequest(bucketName, "key2")
    )
    val listMultipartUploadsRequest = ListMultipartUploadsRequest(bucketName)
    listMultipartUploadsRequest.prefix = "key2"
    val listing = s3Client.listMultipartUploads(listMultipartUploadsRequest)
    assertThat(listing.multipartUploads).hasSize(1)
    assertThat(listing.multipartUploads[0].key).isEqualTo("key2")
  }

  /**
   * Tests if multipart uploads are stored and can be retrieved by bucket.
   */
  @Test
  fun shouldListMultipartUploadsWithBucket(testInfo: TestInfo) {
    // create multipart upload 1
    val bucketName1 = givenBucketV1(testInfo)
    s3Client.initiateMultipartUpload(
      InitiateMultipartUploadRequest(bucketName1, "key1")
    )
    // create multipart upload 2
    val bucketName2 = givenRandomBucketV1()
    s3Client.initiateMultipartUpload(
      InitiateMultipartUploadRequest(bucketName2, "key2")
    )

    // assert multipart upload 1
    val listMultipartUploadsRequest1 = ListMultipartUploadsRequest(bucketName1)
    val listing1 = s3Client.listMultipartUploads(listMultipartUploadsRequest1)
    assertThat(listing1.multipartUploads).hasSize(1)
    assertThat(listing1.multipartUploads[0].key).isEqualTo("key1")

    // assert multipart upload 2
    val listMultipartUploadsRequest2 = ListMultipartUploadsRequest(bucketName2)
    val listing2 = s3Client.listMultipartUploads(listMultipartUploadsRequest2)
    assertThat(listing2.multipartUploads).hasSize(1)
    assertThat(listing2.multipartUploads[0].key).isEqualTo("key2")
  }

  /**
   * Tests if a multipart upload can be aborted.
   */
  @Test
  fun shouldAbortMultipartUpload(testInfo: TestInfo) {
    val bucketName = givenBucketV1(testInfo)
    assertThat(
      s3Client.listMultipartUploads(ListMultipartUploadsRequest(bucketName))
        .multipartUploads
    ).isEmpty()
    val initiateMultipartUploadResult = s3Client
      .initiateMultipartUpload(InitiateMultipartUploadRequest(bucketName, UPLOAD_FILE_NAME))
    val uploadId = initiateMultipartUploadResult.uploadId
    val randomBytes = randomBytes()
    val partETag = uploadPart(bucketName, UPLOAD_FILE_NAME, uploadId, 1, randomBytes)
    assertThat(
      s3Client.listMultipartUploads(ListMultipartUploadsRequest(bucketName))
        .multipartUploads
    ).isNotEmpty
    val partsBeforeComplete =
      s3Client.listParts(ListPartsRequest(bucketName, UPLOAD_FILE_NAME, uploadId))
        .parts
    assertThat(partsBeforeComplete).hasSize(1)
    assertThat(partsBeforeComplete[0].eTag).isEqualTo(partETag.eTag)
    s3Client.abortMultipartUpload(
      AbortMultipartUploadRequest(bucketName, UPLOAD_FILE_NAME, uploadId)
    )
    assertThat(
      s3Client.listMultipartUploads(ListMultipartUploadsRequest(bucketName))
        .multipartUploads
    ).isEmpty()

    // List parts, make sure we find no parts
    assertThatThrownBy { s3Client.listParts(ListPartsRequest(bucketName, UPLOAD_FILE_NAME,
      uploadId)) }
      .isInstanceOf(AmazonS3Exception::class.java)
      .hasMessageContaining("Status Code: 404; Error Code: NoSuchUpload")
  }

  /**
   * Tests if the parts specified in CompleteUploadRequest are adhered
   * irrespective of the number of parts uploaded before.
   */
  @Test
  @Throws(IOException::class)
  fun shouldAdherePartsInCompleteMultipartUploadRequest(testInfo: TestInfo) {
    val bucketName = givenBucketV1(testInfo)
    val key = UUID.randomUUID().toString()
    assertThat(
      s3Client.listMultipartUploads(ListMultipartUploadsRequest(bucketName))
        .multipartUploads
    ).isEmpty()

    // Initiate upload
    val initiateMultipartUploadResult = s3Client
      .initiateMultipartUpload(InitiateMultipartUploadRequest(bucketName, key))
    val uploadId = initiateMultipartUploadResult.uploadId

    // Upload 3 parts
    val randomBytes1 = randomBytes()
    val partETag1 = uploadPart(bucketName, key, uploadId, 1, randomBytes1)
    val randomBytes2 = randomBytes()
    uploadPart(bucketName, key, uploadId, 2, randomBytes2) //ignore result in this test
    val randomBytes3 = randomBytes()
    val partETag3 = uploadPart(bucketName, key, uploadId, 3, randomBytes3)

    // Adding to parts list only 1st and 3rd part
    val parts: MutableList<PartETag> = ArrayList()
    parts.add(partETag1)
    parts.add(partETag3)

    // Try to complete with these parts
    val result = s3Client.completeMultipartUpload(
      CompleteMultipartUploadRequest(bucketName, key, uploadId, parts)
    )

    // Verify only 1st and 3rd counts
    val `object` = s3Client.getObject(bucketName, key)
    val allMd5s = ArrayUtils.addAll(
      DigestUtils.md5(randomBytes1),
      *DigestUtils.md5(randomBytes3)
    )

    // verify special etag
    assertThat(result.eTag).`as`("Special etag doesn't match.")
      .isEqualTo(DigestUtils.md5Hex(allMd5s) + "-2")

    // verify content size
    assertThat(`object`.objectMetadata.contentLength)
      .`as`("Content length doesn't match")
      .isEqualTo(randomBytes1.size.toLong() + randomBytes3.size)

    // verify contents
    assertThat(readStreamIntoByteArray(`object`.objectContent)).`as`(
      "Object contents doesn't match"
    )
      .isEqualTo(concatByteArrays(randomBytes1, randomBytes3))
  }

  /**
   * Tests that uploaded parts can be listed regardless if the MultipartUpload was completed or
   * aborted.
   */
  @Test
  fun shouldListPartsOnCompleteOrAbort(testInfo: TestInfo) {
    val bucketName = givenBucketV1(testInfo)
    val key = randomName
    assertThat(
      s3Client.listMultipartUploads(ListMultipartUploadsRequest(bucketName))
        .multipartUploads
    ).isEmpty()

    // Initiate upload
    val initiateMultipartUploadResult = s3Client
      .initiateMultipartUpload(InitiateMultipartUploadRequest(bucketName, key))
    val uploadId = initiateMultipartUploadResult.uploadId

    // Upload part
    val randomBytes = randomBytes()
    val partETag = uploadPart(bucketName, key, uploadId, 1, randomBytes)

    // List parts, make sure we find part 1
    val partsBeforeComplete = s3Client.listParts(ListPartsRequest(bucketName, key, uploadId))
      .parts
    assertThat(partsBeforeComplete).hasSize(1)
    assertThat(partsBeforeComplete[0].eTag).isEqualTo(partETag.eTag)

    // Complete, ignore result in this test
    s3Client.completeMultipartUpload(
      CompleteMultipartUploadRequest(bucketName, key, uploadId, listOf(partETag))
    )

    // List parts, make sure we find no parts
    assertThatThrownBy { s3Client.listParts(ListPartsRequest(bucketName, key, uploadId)) }
      .isInstanceOf(AmazonS3Exception::class.java)
      .hasMessageContaining("Status Code: 404; Error Code: NoSuchUpload")
  }

  /**
   * Upload two objects, copy as parts without length, complete multipart.
   */
  @Test
  @Throws(IOException::class)
  fun shouldCopyPartsAndComplete(testInfo: TestInfo) {
    //Initiate upload in random bucket
    val bucketName2 = givenRandomBucketV1()
    val multipartUploadKey = randomName
    val initiateMultipartUploadResult = s3Client
      .initiateMultipartUpload(
        InitiateMultipartUploadRequest(bucketName2, multipartUploadKey)
      )
    val uploadId = initiateMultipartUploadResult.uploadId
    val parts: MutableList<PartETag> = ArrayList()

    //bucket for test data
    val bucketName1 = givenBucketV1(testInfo)

    //create two objects, initiate copy part with full object length
    val sourceKeys = arrayOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
    val allRandomBytes: MutableList<ByteArray> = ArrayList()
    for (i in sourceKeys.indices) {
      val key = sourceKeys[i]
      val partNumber = i + 1
      val randomBytes = randomBytes()
      val metadata1 = ObjectMetadata()
      metadata1.contentLength = randomBytes.size.toLong()
      s3Client.putObject(
        PutObjectRequest(
          bucketName1, key, ByteArrayInputStream(randomBytes),
          metadata1
        )
      )
      val request = CopyPartRequest()
        .withPartNumber(partNumber)
        .withUploadId(uploadId)
        .withDestinationBucketName(bucketName2)
        .withDestinationKey(multipartUploadKey)
        .withSourceKey(key)
        .withSourceBucketName(bucketName1)
      val result = s3Client.copyPart(request)
      val etag = result.eTag
      val partETag = PartETag(partNumber, etag)
      parts.add(partETag)
      allRandomBytes.add(randomBytes)
    }
    assertThat(allRandomBytes).hasSize(2)

    // Complete with parts
    val result = s3Client.completeMultipartUpload(
      CompleteMultipartUploadRequest(bucketName2, multipartUploadKey, uploadId, parts)
    )

    // Verify parts
    val `object` = s3Client.getObject(bucketName2, multipartUploadKey)
    val allMd5s = ArrayUtils.addAll(
      DigestUtils.md5(allRandomBytes[0]),
      *DigestUtils.md5(allRandomBytes[1])
    )

    // verify etag
    assertThat(result.eTag)
      .`as`("etag doesn't match.")
      .isEqualTo(DigestUtils.md5Hex(allMd5s) + "-2")

    // verify content size
    assertThat(`object`.objectMetadata.contentLength)
      .`as`("Content length doesn't match")
      .isEqualTo(allRandomBytes[0].size.toLong() + allRandomBytes[1].size)

    // verify contents
    assertThat(readStreamIntoByteArray(`object`.objectContent))
      .`as`("Object contents doesn't match")
      .isEqualTo(concatByteArrays(allRandomBytes[0], allRandomBytes[1]))
  }

  /**
   * Puts an Object; Copies part of that object to a new bucket;
   * Requests parts for the uploadId; compares etag of upload response and parts list.
   */
  @Test
  fun shouldCopyObjectPart(testInfo: TestInfo) {
    val sourceKey = UPLOAD_FILE_NAME
    val (bucketName, putObjectResult) = givenBucketAndObjectV1(testInfo, UPLOAD_FILE_NAME)
    val destinationBucketName = givenRandomBucketV1()
    val destinationKey = "copyOf/$sourceKey"
    val objectMetadata = ObjectMetadata()
    objectMetadata.addUserMetadata("key", "value")
    val initiateMultipartUploadResult = s3Client
      .initiateMultipartUpload(
        InitiateMultipartUploadRequest(
          destinationBucketName, destinationKey,
          objectMetadata
        )
      )
    val uploadId = initiateMultipartUploadResult.uploadId
    val copyPartRequest = CopyPartRequest()
    copyPartRequest.destinationBucketName = destinationBucketName
    copyPartRequest.uploadId = uploadId
    copyPartRequest.destinationKey = destinationKey
    copyPartRequest.sourceBucketName = bucketName
    copyPartRequest.sourceKey = sourceKey
    copyPartRequest.firstByte = 0L
    copyPartRequest.lastByte = putObjectResult.metadata.contentLength
    val copyPartResult = s3Client.copyPart(copyPartRequest)
    val partListing = s3Client.listParts(
      ListPartsRequest(
        initiateMultipartUploadResult.bucketName,
        initiateMultipartUploadResult.key,
        initiateMultipartUploadResult.uploadId
      )
    )
    assertThat(partListing.parts).hasSize(1)
    assertThat(partListing.parts[0].eTag).isEqualTo(copyPartResult.eTag)
  }

  /**
   * Tries to copy part of a non-existing object to a new bucket.
   */
  @Test
  fun shouldThrowNoSuchKeyOnCopyObjectPartForNonExistingKey(testInfo: TestInfo) {
    val sourceKey = "NON_EXISTENT_KEY"
    val destinationBucketName = givenRandomBucketV1()
    val destinationKey = "copyOf/$sourceKey"
    val bucketName = givenBucketV1(testInfo)
    val objectMetadata = ObjectMetadata()
    objectMetadata.addUserMetadata("key", "value")
    val initiateMultipartUploadResult = s3Client
      .initiateMultipartUpload(
        InitiateMultipartUploadRequest(
          destinationBucketName, destinationKey,
          objectMetadata
        )
      )
    val uploadId = initiateMultipartUploadResult.uploadId
    val copyPartRequest = CopyPartRequest()
    copyPartRequest.destinationBucketName = destinationBucketName
    copyPartRequest.uploadId = uploadId
    copyPartRequest.destinationKey = destinationKey
    copyPartRequest.sourceBucketName = bucketName
    copyPartRequest.sourceKey = sourceKey
    copyPartRequest.firstByte = 0L
    copyPartRequest.lastByte = 5L
    assertThatThrownBy { s3Client.copyPart(copyPartRequest) }
      .isInstanceOf(AmazonS3Exception::class.java)
      .hasMessageContaining("Status Code: 404; Error Code: NoSuchKey")
  }

  private fun uploadPart(
    bucketName: String,
    key: String,
    uploadId: String,
    partNumber: Int,
    randomBytes: ByteArray
  ): PartETag {
    return s3Client
      .uploadPart(
        createUploadPartRequest(bucketName, key, uploadId)
          .withPartNumber(partNumber)
          .withPartSize(randomBytes.size.toLong())
          .withInputStream(ByteArrayInputStream(randomBytes))
      )
      .partETag
  }

  private fun createUploadPartRequest(bucketName: String, key: String, uploadId: String): UploadPartRequest {
    return UploadPartRequest()
      .withBucketName(bucketName)
      .withKey(key)
      .withUploadId(uploadId)
  }
}
