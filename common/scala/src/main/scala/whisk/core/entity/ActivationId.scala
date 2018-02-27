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

package whisk.core.entity

import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json._
import whisk.http.Messages

import whisk.core.entity.size._

import scala.util.{Failure, Success, Try}

/**
 * An activation id, is a unique id assigned to activations (invoke action or fire trigger).
 * It must be globally unique.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param asString the activation id
 */
protected[whisk] class ActivationId private (val asString: String) extends AnyVal {
  override def toString: String = asString
  def toJsObject: JsObject = JsObject("activationId" -> asString.toJson)
}

protected[core] object ActivationId extends ArgNormalizer[ActivationId] {

  protected[core] trait ActivationIdGenerator {
    def make(): ActivationId = ActivationId()
  }

  private def isHexadecimal(c: Char) = c.isDigit || c == 'a' || c == 'b' || c == 'd' || c == 'e' || c == 'f'

  /**
   * Parses an activation id from a string.
   *
   * @param id the activation id as string
   * @return ActivationId instance
   */
  def parse(id: String): Try[ActivationId] = {
    val length = id.length
    if (length != 32) {
      Failure(
        new IllegalArgumentException(Messages.activationIdLengthError(SizeError("Activation id", length.B, 32.B))))
    } else if (!id.forall(isHexadecimal)) {
      Failure(new IllegalArgumentException(Messages.activationIdIllegal))
    } else {
      Success(new ActivationId(id))
    }
  }

  /**
   * Generates a random activation id using java.util.UUID factory.
   *
   * Uses fast path to generate the ActivationId without additional requirement checks.
   *
   * @return new ActivationId
   */
  protected[core] def apply(): ActivationId = new ActivationId(UUIDs.randomUUID().toString.filterNot(_ == '-'))

  override protected[core] implicit val serdes = new RootJsonFormat[ActivationId] {
    def write(d: ActivationId) = JsString(d.toString)

    def read(value: JsValue): ActivationId = {
      val parsed = value match {
        case JsString(s) => ActivationId.parse(s)
        case JsNumber(n) => ActivationId.parse(n.toString)
        case _           => Failure(DeserializationException(Messages.activationIdIllegal))
      }

      parsed match {
        case Success(aid)                         => aid
        case Failure(t: IllegalArgumentException) => deserializationError(t.getMessage)
        case Failure(_)                           => deserializationError(Messages.activationIdIllegal)
      }
    }
  }
}
