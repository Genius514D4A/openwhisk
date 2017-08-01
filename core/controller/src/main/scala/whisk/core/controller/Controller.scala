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

package whisk.core.controller

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import akka.actor.Actor
import akka.actor.ActorContext
import akka.actor.ActorSystem
import akka.japi.Creator
import spray.http.StatusCodes._
import spray.http.Uri
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.routing.Directive.pimpApply
import spray.routing.Route
import whisk.common.AkkaLogging
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.entitlement._
import whisk.core.entitlement.EntitlementProvider
import whisk.core.entity._
import whisk.core.entity.ActivationId.ActivationIdGenerator
import whisk.core.entity.ExecManifest.Runtimes
import whisk.core.loadBalancer.LoadBalancerService
import whisk.http.BasicHttpService
import whisk.http.BasicRasService

import scala.util.{ Failure, Success }


/**
 * The Controller is the service that provides the REST API for OpenWhisk.
 *
 * It extends the BasicRasService so it includes a ping endpoint for monitoring.
 *
 * Spray sends messages to akka Actors -- the Controller is an Actor, ready to receive messages.
 *
 * It is possible to deploy a hot-standby controller. Each controller needs its own instance. This instance is a
 * consecutive numbering, starting with 0.
 * The state and cache of each controller is not shared to the other controllers.
 * If the base controller crashes, the hot-standby controller will be used. After the base controller is up again,
 * it will be used again. Because of the empty cache after restart, there are no problems with inconsistency.
 * The only problem that could occur is, that the base controller is not reachable, but does not restart. After switching
 * back to the base controller, there could be an inconsistency in the cache (e.g. if a user has updated an action). This
 * inconsistency will be resolved by its own after removing the cached item, 5 minutes after it has been generated.
 *
 * @Idioglossia uses the spray-routing DSL
 * http://spray.io/documentation/1.1.3/spray-routing/advanced-topics/understanding-dsl-structure/
 *
 * @param config A set of properties needed to run an instance of the controller service
 * @param instance if running in scale-out, a unique identifier for this instance in the group
 * @param verbosity logging verbosity
 * @param executionContext Scala runtime support for concurrent operations
 */
class Controller(
    override val instance: InstanceId,
    runtimes: Runtimes,
    implicit val whiskConfig: WhiskConfig,
    implicit val logging: Logging)
    extends BasicRasService
    with Actor {

    // each akka Actor has an implicit context
    override def actorRefFactory: ActorContext = context

    override val numberOfInstances = whiskConfig.controllerInstances.toInt

    /**
     * A Route in spray is technically a function taking a RequestContext as a parameter.
     *
     * @Idioglossia The ~ spray DSL operator composes two independent Routes, building a routing
     * tree structure.
     * @see http://spray.io/documentation/1.2.3/spray-routing/key-concepts/routes/#composing-routes
     */
    override def routes(implicit transid: TransactionId): Route = {
        // handleRejections wraps the inner Route with a logical error-handler for unmatched paths
        handleRejections(customRejectionHandler) {
            super.routes ~ {
                (pathEndOrSingleSlash & get) {
                    complete(OK, info)
                }
            } ~ {
                apiv1.routes
            } ~ {
                swagger.swaggerRoutes
            } ~ {
                internalInvokerHealth
            }
        }
    }

    TransactionId.controller.mark(this, LoggingMarkers.CONTROLLER_STARTUP(instance.toInt), s"starting controller instance ${instance.toInt}")

    // initialize datastores
    private implicit val actorSystem = context.system
    private implicit val executionContext = actorSystem.dispatcher
    private implicit val authStore = WhiskAuthStore.datastore(whiskConfig)
    private implicit val entityStore = WhiskEntityStore.datastore(whiskConfig)
    private implicit val activationStore = WhiskActivationStore.datastore(whiskConfig)

    // initialize backend services
    private implicit val loadBalancer = new LoadBalancerService(whiskConfig, instance, entityStore)
    private implicit val consulServer = whiskConfig.consulServer
    private implicit val entitlementProvider = new LocalEntitlementProvider(whiskConfig, loadBalancer)
    private implicit val activationIdFactory = new ActivationIdGenerator {}

    // register collections
    Collection.initialize(entityStore)

    /** The REST APIs. */
    implicit val controllerInstance = instance
    private val apiv1 = new RestAPIVersion("api", "v1")
    private val swagger = new SwaggerDocs(Uri.Path.Empty, "infoswagger.json")

    /**
     * Handles GET /invokers URI.
     *
     * @return JSON of invoker health
     */
    private val internalInvokerHealth = {
        (path("invokers") & get) {
            complete {
                loadBalancer.invokerHealth.map(_.mapValues(_.asString).toJson.asJsObject)
            }
        }
    }

    // controller top level info
    private val info = Controller.info(whiskConfig, runtimes, List(apiv1.basepath()))
}

/**
 * Singleton object provides a factory to create and start an instance of the Controller service.
 */
object Controller {

    // requiredProperties is a Map whose keys define properties that must be bound to
    // a value, and whose values are default values.   A null value in the Map means there is
    // no default value specified, so it must appear in the properties file
    def requiredProperties = Map(WhiskConfig.servicePort -> 8080.toString) ++
        Map(WhiskConfig.controllerInstances -> 1.toString) ++
        ExecManifest.requiredProperties ++
        RestApiCommons.requiredProperties ++
        LoadBalancerService.requiredProperties ++
        EntitlementProvider.requiredProperties

    def optionalProperties = EntitlementProvider.optionalProperties

    private def info(config: WhiskConfig, runtimes: Runtimes, apis: List[String]) = JsObject(
        "description" -> "OpenWhisk".toJson,
        "support" -> JsObject(
            "github" -> "https://github.com/apache/incubator-openwhisk/issues".toJson,
            "slack" -> "http://slack.openwhisk.org".toJson),
        "api_paths" -> apis.toJson,
        "limits" -> JsObject(
            "actions_per_minute" -> config.actionInvokePerMinuteLimit.toInt.toJson,
            "triggers_per_minute" -> config.triggerFirePerMinuteLimit.toInt.toJson,
            "concurrent_actions" -> config.actionInvokeConcurrentLimit.toInt.toJson),
        "runtimes" -> runtimes.toJson)

    // akka-style factory to create a Controller object
    private class ServiceBuilder(config: WhiskConfig, instance: InstanceId, logging: Logging) extends Creator[Controller] {
        // this method is not reached unless ExecManifest was initialized successfully
        def create = new Controller(instance, ExecManifest.runtimesManifest, config, logging)
    }

    def main(args: Array[String]): Unit = {
        implicit val actorSystem = ActorSystem("controller-actor-system")
        implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))

        // extract configuration data from the environment
        val config = new WhiskConfig(requiredProperties, optionalProperties)

        // if deploying multiple instances (scale out), must pass the instance number as the
        require(args.length >= 1, "controller instance required")
        val instance = args(0).toInt

        def abort() = {
            logger.error(this, "Bad configuration, cannot start.")
            actorSystem.terminate()
            Await.result(actorSystem.whenTerminated, 30.seconds)
            sys.exit(1)
        }

        if (!config.isValid) {
            abort()
        }

        ExecManifest.initialize(config) match {
            case Success(_) =>
                val port = config.servicePort.toInt
                BasicHttpService.startService(actorSystem, "controller", "0.0.0.0", port, new ServiceBuilder(config, InstanceId(instance), logger))

            case Failure(t) =>
                logger.error(this, s"Invalid runtimes manifest: $t")
                abort()
        }
    }
}
