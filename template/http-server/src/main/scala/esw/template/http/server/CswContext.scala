package esw.template.http.server

import akka.actor.typed.{ActorSystem, SpawnProtocol}
import csw.command.client.CommandServiceFactory
import csw.event.api.scaladsl.EventService
import csw.event.client.EventServiceFactory
import csw.location.api.scaladsl.LocationService
import csw.location.client.scaladsl.HttpLocationServiceFactory
import esw.template.http.server.commons.ActorRuntime
import esw.template.http.server.http.Settings

class CswContext(_port: Option[Int]) {
  lazy val settings                                = new Settings(_port)
  lazy val actorSystem: ActorSystem[SpawnProtocol] = ActorSystem(SpawnProtocol.behavior, "http-server")
  lazy val actorRuntime: ActorRuntime              = new ActorRuntime(actorSystem)

  import actorRuntime._

  lazy val locationService: LocationService = HttpLocationServiceFactory.makeLocalClient(actorSystem, actorRuntime.mat)
  lazy val eventService: EventService       = new EventServiceFactory().make(locationService)
  lazy val componentFactory                 = new ComponentFactory(locationService, CommandServiceFactory)
}
