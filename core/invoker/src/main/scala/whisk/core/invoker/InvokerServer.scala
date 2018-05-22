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

package whisk.core.invoker

import whisk.http.BasicRasService
import whisk.common.TransactionId

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Route

import akka.actor.ActorSystem
import akka.actor.ActorRef

/**
 * Implements web server to handle certain REST API calls.
 * Currently provides a health ping and shutdown routes.
 */
class InvokerServer(actorSystem: ActorSystem, healthScheduler: ActorRef) extends BasicRasService {
  override def routes(implicit transid: TransactionId): Route = {
    super.routes ~ (path("shutdown") & get) {
      actorSystem.stop(healthScheduler)
      complete(OK)
    }
  }
}
