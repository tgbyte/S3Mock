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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.InstanceOfAssertFactories
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException

/**
 * Test the application using the AmazonS3 SDK V2.
 */
internal class BucketTestsV2IT : S3TestBase() {

  @Test
  fun createAndDeleteBucket(testInfo: TestInfo) {
    val bucketName = bucketName(testInfo)
    s3ClientV2.createBucket(CreateBucketRequest.builder().bucket(bucketName).build())

    val bucketCreated = s3ClientV2.waiter()
      .waitUntilBucketExists(HeadBucketRequest.builder().bucket(bucketName).build())
    val bucketCreatedResponse = bucketCreated.matched().response().get()
    assertThat(bucketCreatedResponse).isNotNull

    //does not throw exception if bucket exists.
    s3ClientV2.headBucket(HeadBucketRequest.builder().bucket(bucketName).build())

    s3ClientV2.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build())
    val bucketDeleted = s3ClientV2.waiter()
      .waitUntilBucketNotExists(HeadBucketRequest.builder().bucket(bucketName).build())
    val bucketDeletedResponse = bucketDeleted.matched().exception().get()
    assertThat(bucketDeletedResponse).isNotNull
    assertThat(bucketDeletedResponse).isInstanceOf(NoSuchBucketException::class.java)
  }

  @Test
  fun duplicateBucketCreation(testInfo: TestInfo) {
    val bucketName = bucketName(testInfo)
    s3ClientV2.createBucket(CreateBucketRequest.builder().bucket(bucketName).build())

    val bucketCreated = s3ClientV2.waiter()
      .waitUntilBucketExists(HeadBucketRequest.builder().bucket(bucketName).build())
    val bucketCreatedResponse = bucketCreated.matched().response().get()
    assertThat(bucketCreatedResponse).isNotNull

    assertThatThrownBy {
      s3ClientV2.createBucket(CreateBucketRequest.builder().bucket(bucketName).build())
    }
      .isInstanceOf(AwsServiceException::class.java)
      .hasMessageContaining("Service: S3, Status Code: 409")
      .asInstanceOf(InstanceOfAssertFactories.type(AwsServiceException::class.java))
      .extracting(AwsServiceException::awsErrorDetails)
      .extracting(AwsErrorDetails::errorCode)
      .isEqualTo("BucketAlreadyExists")

    s3ClientV2.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build())
    val bucketDeleted = s3ClientV2.waiter()
      .waitUntilBucketNotExists(HeadBucketRequest.builder().bucket(bucketName).build())
    val bucketDeletedResponse = bucketDeleted.matched().exception().get()
    assertThat(bucketDeletedResponse).isNotNull
    assertThat(bucketDeletedResponse).isInstanceOf(NoSuchBucketException::class.java)
  }

  @Test
  fun duplicateBucketDeletion(testInfo: TestInfo) {
    val bucketName = bucketName(testInfo)
    s3ClientV2.createBucket(CreateBucketRequest.builder().bucket(bucketName).build())

    val bucketCreated = s3ClientV2.waiter()
      .waitUntilBucketExists(HeadBucketRequest.builder().bucket(bucketName).build())
    val bucketCreatedResponse = bucketCreated.matched().response().get()
    assertThat(bucketCreatedResponse).isNotNull

    s3ClientV2.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build())
    val bucketDeleted = s3ClientV2.waiter()
      .waitUntilBucketNotExists(HeadBucketRequest.builder().bucket(bucketName).build())
    val bucketDeletedResponse = bucketDeleted.matched().exception().get()
    assertThat(bucketDeletedResponse).isNotNull
    assertThat(bucketDeletedResponse).isInstanceOf(NoSuchBucketException::class.java)

    assertThatThrownBy {
      s3ClientV2.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build())
    }
      .isInstanceOf(AwsServiceException::class.java)
      .hasMessageContaining("Service: S3, Status Code: 404")
      .asInstanceOf(InstanceOfAssertFactories.type(AwsServiceException::class.java))
      .extracting(AwsServiceException::awsErrorDetails)
      .extracting(AwsErrorDetails::errorCode)
      .isEqualTo("NoSuchBucket")
  }
}
