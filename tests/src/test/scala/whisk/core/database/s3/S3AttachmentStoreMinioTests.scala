/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.database.s3

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import whisk.core.database.test.AttachmentStoreBehaviors
import whisk.core.entity.WhiskEntity

@RunWith(classOf[JUnitRunner])
class S3AttachmentStoreMinioTests extends FlatSpec with AttachmentStoreBehaviors with S3Minio {
  override lazy val store = makeS3Store[WhiskEntity]

  override def storeType: String = "S3"

  override def garbageCollectAttachments: Boolean = false
}
