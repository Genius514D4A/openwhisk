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

package org.apache.openwhisk.connector.lean

import akka.actor.ActorSystem
import scala.concurrent.Future
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.openwhisk.common.Counter
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.connector.Message
import org.apache.openwhisk.core.connector.MessageProducer

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.collection.concurrent.Map
import java.nio.charset.StandardCharsets
import scala.concurrent.ExecutionContext

class LeanProducer(queues: Map[String, BlockingQueue[Array[Byte]]])(implicit logging: Logging, actorSystem: ActorSystem)
    extends MessageProducer {

  implicit val ec: ExecutionContext = actorSystem.dispatcher

  override def sentCount(): Long = sentCounter.cur

  /** Sends msg to topic. This is an asynchronous operation. */
  override def send(topic: String, msg: Message, retry: Int = 3): Future[RecordMetadata] = {
    implicit val transid = msg.transid

    logging.info(this, s"BBB in send with topic " + topic + " msg " + transid)
    var queue = queues.getOrElseUpdate(topic, new LinkedBlockingQueue[Array[Byte]]())

    val res = Future {
      queue.put(msg.serialize.getBytes(StandardCharsets.UTF_8))
      sentCounter.next()
      null
    }

    logging.info(this, s"BBB Q.size: " + queue.size())

    res
  }

  /** Closes producer. */
  override def close(): Unit = {
    logging.info(this, "closing lean producer")
  }

  private val sentCounter = new Counter()
}
