package esw.ocs.core

import akka.actor.Scheduler
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.util.Timeout
import csw.command.client.CommandResponseManager
import csw.command.client.messages.sequencer.{LoadAndStartSequence, SequencerMsg}
import csw.location.api.scaladsl.LocationService
import csw.location.models.ComponentId
import csw.location.models.Connection.AkkaConnection
import csw.params.commands.Sequence
import esw.ocs.api.codecs.OcsCodecs
import esw.ocs.api.models.SequencerState._
import esw.ocs.api.models.messages.SequencerMessages._
import esw.ocs.api.models.messages.{GoOnlineHookFailed, _}
import esw.ocs.api.models.{SequencerState, StepList}
import esw.ocs.dsl.ScriptDsl
import esw.ocs.internal.Timeouts

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

class SequencerBehavior(
    componentId: ComponentId,
    script: ScriptDsl,
    locationService: LocationService,
    crm: CommandResponseManager
)(implicit val actorSystem: ActorSystem[_], timeout: Timeout)
    extends OcsCodecs {

  import actorSystem.executionContext

  def setup: Behavior[SequencerMsg] = Behaviors.setup { ctx =>
    idle(SequencerData.initial(ctx.self, crm))
  }

  //BEHAVIORS
  private def idle(data: SequencerData): Behavior[SequencerMsg] = receive(Idle, data) {
    case LoadSequence(sequence, replyTo)                 => load(sequence, replyTo, data)(nextBehavior = loaded)
    case LoadAndStartSequenceInternal(sequence, replyTo) => loadAndStart(sequence, data, replyTo)
    case GoOffline(replyTo)                              => goOffline(replyTo, data)
    case PullNext(replyTo)                               => idle(data.pullNextStep(replyTo))
  }

  private def loaded(data: SequencerData): Behavior[SequencerMsg] = receive(Loaded, data) {
    case AbortSequence(replyTo)     => abortSequence(data, replyTo)(nextBehavior = idle)
    case editorAction: EditorAction => loaded(handleEditorAction(editorAction, data))
    case GoOffline(replyTo)         => goOffline(replyTo, data)
    case StartSequence(replyTo)     => start(data, replyTo)
  }

  private def inProgress(data: SequencerData): Behavior[SequencerMsg] = receive(InProgress, data) {
    case AbortSequence(replyTo)      => abortSequence(data, replyTo)(nextBehavior = inProgress)
    case msg: EditorAction           => inProgress(handleEditorAction(msg, data))
    case PullNext(replyTo)           => inProgress(data.pullNextStep(replyTo))
    case MaybeNext(replyTo)          => replyTo ! MaybeNextResult(data.stepList.flatMap(_.nextExecutable)); Behaviors.same
    case ReadyToExecuteNext(replyTo) => inProgress(data.readyToExecuteNext(replyTo))
    case Update(submitResponse, _)   => inProgress(data.updateStepStatus(submitResponse))
    case _: GoIdle                   => idle(data)
  }

  private def offline(data: SequencerData): Behavior[SequencerMsg] = receive(Offline, data) {
    case GoOnline(replyTo) => goOnline(replyTo, data)(fallbackBehavior = offline, nextBehavior = idle)
  }

  private def shuttingDown(data: SequencerData) = receive(ShuttingDown, data) {
    case ShutdownComplete(replyTo) =>
      replyTo ! Ok
      actorSystem.terminate()
      Behaviors.stopped
  }

  private def goingOnline(data: SequencerData)(
      fallbackBehavior: SequencerData => Behavior[SequencerMsg],
      nextBehavior: SequencerData => Behavior[SequencerMsg]
  ): Behavior[SequencerMsg] =
    receive(GoingOnline, data) {
      case GoOnlineSuccess(replyTo) => replyTo ! Ok; nextBehavior(data)
      case GoOnlineFailed(replyTo)  => replyTo ! GoOnlineHookFailed; fallbackBehavior(data)
    }

  private def goingOffline(data: SequencerData): Behavior[SequencerMsg] = receive(GoingOffline, data) {
    case GoneOffline(replyTo) => replyTo ! Ok; offline(data.copy(stepList = None))
  }

  private def handleCommonMessage[T <: SequencerMsg](
      message: CommonMessage,
      sequencerState: SequencerState[T],
      actorState: SequencerData
  ): Behavior[SequencerMsg] = message match {
    case Shutdown(replyTo)          => shutdown(actorState, replyTo)
    case GetSequence(replyTo)       => replyTo ! actorState.stepList; Behaviors.same
    case GetSequencerState(replyTo) => replyTo ! sequencerState; Behaviors.same
  }

  private def handleEditorAction(editorAction: EditorAction, data: SequencerData): SequencerData = {
    import data._
    editorAction match {
      case Add(commands, replyTo)             => updateStepList(replyTo, stepList.map(_.append(commands)))
      case Pause(replyTo)                     => updateStepListResult(replyTo, stepList.map(_.pause))
      case Resume(replyTo)                    => updateStepList(replyTo, stepList.map(_.resume))
      case Replace(id, commands, replyTo)     => updateStepListResult(replyTo, stepList.map(_.replace(id, commands)))
      case Prepend(commands, replyTo)         => updateStepList(replyTo, stepList.map(_.prepend(commands)))
      case Delete(id, replyTo)                => updateStepListResult(replyTo, stepList.map(_.delete(id)))
      case Reset(replyTo)                     => updateStepList(replyTo, stepList.map(_.discardPending))
      case InsertAfter(id, commands, replyTo) => updateStepListResult(replyTo, stepList.map(_.insertAfter(id, commands)))
      case AddBreakpoint(id, replyTo)         => updateStepListResult(replyTo, stepList.map(_.addBreakpoint(id)))
      case RemoveBreakpoint(id, replyTo)      => updateStepListResult(replyTo, stepList.map(_.removeBreakpoint(id)))
    }
  }

  private def abortSequence(data: SequencerData, replyTo: ActorRef[OkOrUnhandledResponse])(
      nextBehavior: SequencerData => Behavior[SequencerMsg]
  ): Behavior[SequencerMsg] = {
    script.executeAbort().onComplete(_ => data.self ! AbortSequenceComplete(replyTo))
    abortingSequence(data)(nextBehavior)
  }

  private def load(sequence: Sequence, replyTo: ActorRef[LoadSequenceResponse], data: SequencerData)(
      nextBehavior: SequencerData => Behavior[SequencerMsg]
  ): Behavior[SequencerMsg] =
    createStepList(sequence, data) match {
      case Left(err)       => replyTo ! err; Behaviors.same
      case Right(newState) => replyTo ! Ok; nextBehavior(newState)
    }

  private def start(data: SequencerData, replyTo: ActorRef[SequenceResponse]): Behavior[SequencerMsg] =
    inProgress(data.startSequence(replyTo))

  private def loadAndStart(
      sequence: Sequence,
      data: SequencerData,
      replyTo: ActorRef[SequenceResponse]
  ): Behavior[SequencerMsg] =
    createStepList(sequence, data) match {
      case Left(err)       => replyTo ! err; Behaviors.same
      case Right(newState) => start(newState, replyTo)
    }

  private def createStepList(
      sequence: Sequence,
      data: SequencerData
  ): Either[DuplicateIdsFound.type, SequencerData] =
    StepList(sequence).map(currentStepList => data.copy(stepList = Some(currentStepList)))

  private def shutdown(data: SequencerData, replyTo: ActorRef[Ok.type]): Behavior[SequencerMsg] = {

    // run both the futures in parallel and wait for both to complete
    // once all finished, send ShutdownComplete self message irrespective of any failures
    val f1 = locationService.unregister(AkkaConnection(componentId))
    val f2 = script.executeShutdown()
    f1.onComplete(_ => f2.onComplete(_ => data.self ! ShutdownComplete(replyTo)))

    shuttingDown(data)
  }

  private def abortingSequence(
      data: SequencerData
  )(nextBehavior: SequencerData => Behavior[SequencerMsg]): Behavior[SequencerMsg] =
    receive[AbortSequenceMessage](AbortingSequence, data) {
      case AbortSequenceComplete(replyTo) =>
        import data._
        val maybeStepList = stepList.flatMap { x =>
          val inProgressStepList = x.discardPending
          if (inProgressStepList.steps.isEmpty) None
          else Some(inProgressStepList)
        }
        nextBehavior(updateStepList(replyTo, maybeStepList))
    }

  private def goOnline(replyTo: ActorRef[GoOnlineResponse], data: SequencerData)(
      fallbackBehavior: SequencerData => Behavior[SequencerMsg],
      nextBehavior: SequencerData => Behavior[SequencerMsg]
  ): Behavior[SequencerMsg] = {
    script.executeGoOnline().onComplete {
      case Success(_) => data.self ! GoOnlineSuccess(replyTo)
      case Failure(_) => data.self ! GoOnlineFailed(replyTo)
    }
    goingOnline(data)(fallbackBehavior, nextBehavior)
  }

  private def goOffline(replyTo: ActorRef[OkOrUnhandledResponse], data: SequencerData): Behavior[SequencerMsg] = {
    // go to offline data.even if handler fails, note that this is different than GoOnline
    script.executeGoOffline().onComplete(_ => data.self ! GoneOffline(replyTo))
    goingOffline(data)
  }

  protected def receive[T <: SequencerMsg: ClassTag](state: SequencerState[T], data: SequencerData)(
      f: T => Behavior[SequencerMsg]
  ): Behavior[SequencerMsg] = Behaviors.receive { (ctx, msg) =>
    implicit val timeout: Timeout     = Timeouts.LongTimeout
    implicit val scheduler: Scheduler = ctx.system.scheduler

    msg match {
      case msg: CommonMessage                => handleCommonMessage(msg, state, data)
      case msg: T                            => f(msg)
      case msg: UnhandleableSequencerMessage => msg.replyTo ! Unhandled(state, msg.getClass.getSimpleName); Behaviors.same
      case LoadAndStartSequence(sequence, replyTo) =>
        val sequenceResponseF: Future[SequenceResponse] = ctx.self ? (LoadAndStartSequenceInternal(sequence, _))
        sequenceResponseF.foreach(res => replyTo ! res.toSubmitResponse(sequence.runId))
        Behaviors.same
      case _ => Behaviors.unhandled
    }
  }
}
