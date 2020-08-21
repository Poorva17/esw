package esw.gateway.server

import akka.actor.typed.ActorSystem
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import csw.alarm.api.scaladsl.AlarmAdminService
import csw.command.api.scaladsl.CommandService
import csw.event.api.scaladsl.{EventPublisher, EventService, EventSubscriber}
import csw.event.client.internal.commons.EventSubscriberUtil
import csw.logging.api.scaladsl.Logger
import csw.prefix.models.Prefix
import esw.gateway.api.{AdminApi, AlarmApi, EventApi, LoggingApi}
import esw.gateway.impl._
import esw.gateway.server.utils.Resolver
import esw.ocs.api.SequencerApi
import esw.wiring.CswWiring
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar._

import scala.concurrent.duration.FiniteDuration

class CswWiringMocks(implicit actorSystem: ActorSystem[_]) {
  import actorSystem.executionContext

  val cswWiring: CswWiring     = mock[CswWiring]
  val logger: Logger           = mock[Logger]
  val loggerCache: LoggerCache = mock[LoggerCache]
  when(loggerCache.get(any[Prefix])).thenReturn(logger)

  //command service mocks
  val resolver: Resolver             = mock[Resolver]
  val commandService: CommandService = mock[CommandService]
  val sequencer: SequencerApi        = mock[SequencerApi]
  val adminApi: AdminApi             = mock[AdminApi]
  //alarm service mocks
  val alarmService: AlarmAdminService = mock[AlarmAdminService]

  //event service mocks
  val eventService: EventService               = mock[EventService]
  val eventSubscriberUtil: EventSubscriberUtil = mock[EventSubscriberUtil]
  val eventPublisher: EventPublisher           = mock[EventPublisher]
  val eventSubscriber: EventSubscriber         = mock[EventSubscriber]

  when(cswWiring.eventSubscriberUtil).thenReturn(eventSubscriberUtil)
  when(cswWiring.eventService).thenReturn(eventService)
  when(cswWiring.alarmService).thenReturn(alarmService)
  when(eventService.defaultPublisher).thenReturn(eventPublisher)
  when(eventService.defaultSubscriber).thenReturn(eventSubscriber)

  val alarmApi: AlarmApi     = new AlarmImpl(alarmService)
  val eventApi: EventApi     = new EventImpl(eventService, eventSubscriberUtil)
  val loggingApi: LoggingApi = new LoggingImpl(loggerCache)
}

class RateLimiterStub[A](delay: FiniteDuration) extends GraphStage[FlowShape[A, A]] {
  final val in    = Inlet.create[A]("DroppingThrottle.in")
  final val out   = Outlet.create[A]("DroppingThrottle.out")
  final val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit = {
            push(out, grab(in))
          }
        }
      )

      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit = pull(in)
        }
      )
    }
}
