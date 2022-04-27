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

package org.apache.openwhisk.core.entity

import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import spray.json._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.http.Messages
import pureconfig._
import pureconfig.generic.auto._

case class MemoryLimitConfig(min: ByteSize, max: ByteSize, std: ByteSize)
case class NamespaceMemoryLimitConfig(min: ByteSize, max: ByteSize)

/**
 * MemoryLimit encapsulates allowed memory for an action. The limit must be within a
 * permissible range (by default [128MB, 512MB]).
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param megabytes the memory limit in megabytes for the action
 */
protected[entity] class MemoryLimit private (val megabytes: Int) extends AnyVal {

  /** It checks the namespace memory limit setting value  */
  @throws[IllegalArgumentException]
  protected[core] def checkNamespaceLimit(user: Identity): Unit = {
    user.limits.memoryMax foreach { limit =>
      require(
        megabytes <= limit.megabytes,
        Messages.sizeExceedsAllowedThreshold(MemoryLimit.memoryLimitFieldName, megabytes, limit.megabytes))
    }
    user.limits.memoryMin foreach { limit =>
      require(
        megabytes >= limit.megabytes,
        Messages.sizeBelowAllowedThreshold(MemoryLimit.memoryLimitFieldName, megabytes, limit.megabytes))
    }
  }
}

protected[core] object MemoryLimit extends ArgNormalizer[MemoryLimit] {
  val config = loadConfigOrThrow[MemoryLimitConfig](ConfigKeys.memory)
  val namespaceDefaultConfig = loadConfigOrThrow[NamespaceMemoryLimitConfig](ConfigKeys.namespaceMemoryLimit)
  val memoryLimitFieldName = "memory"

  /**
   * These values for system are set once at the beginning.
   * Dynamic configuration updates are not supported at the moment.
   */
  protected[core] val STD_MEMORY: ByteSize = config.std
  protected[core] val MIN_MEMORY: ByteSize = config.min
  protected[core] val MAX_MEMORY: ByteSize = config.max

  /** Default namespace limit used if there is no namespace-specific limit */
  protected[core] val MIN_MEMORY_DEFAULT: ByteSize = namespaceDefaultConfig.min
  protected[core] val MAX_MEMORY_DEFAULT: ByteSize = namespaceDefaultConfig.max

  /** A singleton MemoryLimit with default value */
  protected[core] val standardMemoryLimit = MemoryLimit(STD_MEMORY)

  /** Gets MemoryLimit with default value */
  protected[core] def apply(): MemoryLimit = standardMemoryLimit

  /**
   * Creates MemoryLimit for limit, iff limit is within permissible range.
   *
   * @param megabytes the limit in megabytes, must be within permissible range
   * @return MemoryLimit with limit set
   * @throws IllegalArgumentException if limit does not conform to requirements
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(megabytes: ByteSize): MemoryLimit = {
    require(
      megabytes >= MIN_MEMORY,
      Messages.sizeBelowAllowedThreshold(memoryLimitFieldName, megabytes.toMB.toInt, MIN_MEMORY.toMB.toInt))
    require(
      megabytes <= MAX_MEMORY,
      Messages.sizeExceedsAllowedThreshold(memoryLimitFieldName, megabytes.toMB.toInt, MAX_MEMORY.toMB.toInt))
    new MemoryLimit(megabytes.toMB.toInt)
  }

  override protected[core] implicit val serdes = new RootJsonFormat[MemoryLimit] {
    def write(m: MemoryLimit) = JsNumber(m.megabytes)

    def read(value: JsValue) =
      Try {
        val JsNumber(mb) = value
        require(mb.isWhole, "memory limit must be whole number")
        MemoryLimit(mb.intValue MB)
      } match {
        case Success(limit)                       => limit
        case Failure(e: IllegalArgumentException) => deserializationError(e.getMessage, e)
        case Failure(e: Throwable)                => deserializationError("memory limit malformed", e)
      }
  }
}
