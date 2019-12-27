package agent

import agent.AgentCliCommand.StartCommand
import agent.AgentCommand.SpawnSequenceComponent
import agent.utils.ProcessOutput
import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.SpawnProtocol.Spawn
import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.util.Timeout
import caseapp.core.RemainingArgs
import caseapp.core.app.CommandApp
import csw.location.api.extensions.ActorExtension.RichActor
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.location.impl.commons.ClusterAwareSettings
import csw.location.impl.internal.{ServerWiring, Settings}
import csw.location.models.Connection.AkkaConnection
import csw.location.models.{AkkaRegistration, ComponentId, ComponentType}
import csw.network.utils.Networks
import csw.prefix.models.{Prefix, Subsystem}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.Try

// todo: Add support for default actions e.g. redis
// todo: merge location-agent
// todo: options: clusterPort, auth, interface, devMode
// todo: devmode kills all processes before dying
// todo: try moving this module to csw by merging with location-server

object Main extends CommandApp[AgentCliCommand] {
  override def appName: String    = getClass.getSimpleName.dropRight(1) // remove $ from class name
  override def appVersion: String = BuildInfo.version
  override def progName: String   = BuildInfo.name

  override def run(command: AgentCliCommand, remainingArgs: RemainingArgs): Unit = command match {
    case StartCommand(clusterPortMaybe, interface, unsecured, devMode) =>
      onStart(clusterPortMaybe)
  }

  private def onStart(clusterPortMaybe: Option[Int]): Unit =
    if (ClusterAwareSettings.seedNodes.isEmpty)
      Console.err.println(
        "[ERROR] CLUSTER_SEEDS setting is not specified either as env variable or system property. Please check online documentation for this set-up."
      )
    else {
      val wiring = new ServerWiring(Settings("agent").withClusterPort(clusterPortMaybe))

      implicit val actorSystem: ActorSystem[SpawnProtocol.Command] = wiring.actorSystem
      wiring.actorRuntime.startLogging(progName, Networks().hostname, appVersion)
      import actorSystem.executionContext

      implicit val timeout: Timeout     = Timeout(10.seconds)
      implicit val scheduler: Scheduler = wiring.actorSystem.scheduler
      val coordinatedShutdown           = CoordinatedShutdown(actorSystem.toClassic)
      val agentConnection               = AkkaConnection(ComponentId(Prefix(Subsystem.ESW, "Agent"), ComponentType.Machine))
      val locationService               = HttpLocationServiceFactory.makeLocalClient

      val locationBinding = Await.result(wiring.locationHttpService.start(), timeout.duration)
      coordinatedShutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "unbind-services") { () =>
        locationService
          .unregister(agentConnection)
          .transform(_ => Try(locationBinding.terminate(timeout.duration).map(_ => Done)))
          .flatten
      }

      val processOutput = new ProcessOutput
      val actor         = new AgentActor(locationService, processOutput)
      val agentRef: ActorRef[AgentCommand] =
        Await.result(actorSystem ? (Spawn(actor.behavior, "agent-actor", Props.empty, _)), timeout.duration)

      Await.result(locationService.register(AkkaRegistration(agentConnection, agentRef.toURI)), timeout.duration)

      // Test messages
      val response: Future[Response]  = agentRef ? SpawnSequenceComponent(Prefix(Subsystem.ESW, "primary"))
      val response2: Future[Response] = agentRef ? SpawnSequenceComponent(Prefix(Subsystem.ESW, "secondary"))
      println("primary Response=" + Await.result(response, 10.seconds))
      println("secondary Response=" + Await.result(response2, 10.seconds))
//      val killedResponse: Future[Response] = agentRef ? KillAllProcesses
//      println("Killed All processes:" + Await.result(killedResponse, 10.seconds))
    }
}