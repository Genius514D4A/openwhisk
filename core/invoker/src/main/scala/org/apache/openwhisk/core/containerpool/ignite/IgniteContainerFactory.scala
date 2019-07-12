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

package org.apache.openwhisk.core.containerpool.ignite

import akka.actor.ActorSystem
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.containerpool.docker.DockerClientWithFileAccess
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.containerpool.{Container, ContainerFactory, ContainerFactoryProvider}
import org.apache.openwhisk.core.entity.{ByteSize, ExecManifest, InvokerInstanceId}
import pureconfig.loadConfigOrThrow

import scala.concurrent.{ExecutionContext, Future}

object IgniteContainerFactoryProvider extends ContainerFactoryProvider {
  override def instance(actorSystem: ActorSystem,
                        logging: Logging,
                        config: WhiskConfig,
                        instanceId: InvokerInstanceId,
                        parameters: Map[String, Set[String]]): ContainerFactory = {
    //Ignore parameters as they are  specific for Docker based creation. They do not map to Ignite
    val dockerApi = new DockerClientWithFileAccess()(actorSystem.dispatcher)(logging, actorSystem)
    new IgniteContainerFactory(instanceId)(
      actorSystem,
      actorSystem.dispatcher,
      logging,
      new IgniteClient(dockerApi)(actorSystem.dispatcher, actorSystem, logging))
  }
}

case class IgniteConfig(extraArgs: Map[String, Set[String]])

class IgniteContainerFactory(instance: InvokerInstanceId)(implicit actorSystem: ActorSystem,
                                                          ec: ExecutionContext,
                                                          logging: Logging,
                                                          ignite: IgniteApi)
    extends ContainerFactory {
  private implicit val config: IgniteConfig = loadConfigOrThrow[IgniteConfig](ConfigKeys.ignite)

  override def createContainer(tid: TransactionId,
                               name: String,
                               actionImage: ExecManifest.ImageName,
                               userProvidedImage: Boolean,
                               memory: ByteSize,
                               cpuShares: Int)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    IgniteContainer.create(tid, actionImage, memory, cpuShares, Some(name))
  }

  override def init(): Unit = ???

  override def cleanup(): Unit = ???
}