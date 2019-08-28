package esw.gateway.server2

import akka.actor.typed.{ActorSystem, SpawnProtocol}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import csw.alarm.api.scaladsl.AlarmAdminService
import csw.command.api.scaladsl.CommandService
import csw.event.api.scaladsl.{EventPublisher, EventService, EventSubscriber}
import csw.event.client.internal.commons.EventSubscriberUtil
import csw.logging.api.scaladsl.Logger
import esw.http.core.commons.RouteHandlers
import esw.http.core.utils.{ComponentFactory, CswContext}
import esw.http.core.wiring.ActorRuntime
import org.mockito.MockitoSugar._

import scala.concurrent.duration.FiniteDuration

class CswContextMocks(system: ActorSystem[SpawnProtocol]) {

  val cswCtx: CswContext         = mock[CswContext]
  val actorRuntime: ActorRuntime = new ActorRuntime(system)
  val logger: Logger             = mock[Logger]

  //command service mocks
  val componentFactory: ComponentFactory = mock[ComponentFactory]
  val commandService: CommandService     = mock[CommandService]
  val alarmService: AlarmAdminService    = mock[AlarmAdminService]

  //event service mocks
  val eventService: EventService               = mock[EventService]
  val eventSubscriberUtil: EventSubscriberUtil = mock[EventSubscriberUtil]
  val eventPublisher: EventPublisher           = mock[EventPublisher]
  val eventSubscriber: EventSubscriber         = mock[EventSubscriber]
  val handlers: RouteHandlers                  = new RouteHandlers(logger)

  when(cswCtx.logger).thenReturn(logger)

  when(cswCtx.routeHandlers).thenReturn(handlers)

  when(cswCtx.componentFactory).thenReturn(componentFactory)

  when(cswCtx.eventSubscriberUtil).thenReturn(eventSubscriberUtil)
  when(cswCtx.eventService).thenReturn(eventService)
  when(cswCtx.alarmService).thenReturn(alarmService)
  when(eventService.defaultPublisher).thenReturn(eventPublisher)
  when(eventService.defaultSubscriber).thenReturn(eventSubscriber)
}

class RateLimiterStub[A](delay: FiniteDuration) extends GraphStage[FlowShape[A, A]] {
  final val in    = Inlet.create[A]("DroppingThrottle.in")
  final val out   = Outlet.create[A]("DroppingThrottle.out")
  final val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        push(out, grab(in))
      }
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = pull(in)
    })
  }
}
