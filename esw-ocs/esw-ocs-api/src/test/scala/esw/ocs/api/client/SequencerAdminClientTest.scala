package esw.ocs.api.client

import csw.params.commands.{CommandName, Sequence, Setup}
import csw.params.core.models.{Id, Prefix}
import esw.ocs.api.BaseTestSuite
import esw.ocs.api.codecs.SequencerAdminHttpCodecs
import esw.ocs.api.models.StepList
import esw.ocs.api.request.SequencerAdminPostRequest
import esw.ocs.api.request.SequencerAdminPostRequest._
import esw.ocs.api.responses._
import io.bullet.borer.Decoder
import msocket.api.RequestClient
import org.mockito.ArgumentMatchers.{any, eq => argsEq}

import scala.concurrent.Future

class SequencerAdminClientTest extends BaseTestSuite with SequencerAdminHttpCodecs {

  private val postClient           = mock[RequestClient[SequencerAdminPostRequest]]
  private val sequencerAdminClient = new SequencerAdminClient(postClient)
  "SequencerAdminClient" must {

    "call postClient with GetSequence request | ESW-222" in {
      val command1 = Setup(Prefix("esw.test"), CommandName("command-1"), None)
      val sequence = Sequence(command1)
      val stepList = StepList(sequence).rightValue

      when(postClient.requestResponse[Option[StepList]](argsEq(GetSequence))(any[Decoder[Option[StepList]]]()))
        .thenReturn(Future.successful(Some(stepList)))
      sequencerAdminClient.getSequence.futureValue.get should ===(stepList)
    }

    "call postClient with IsAvailable request | ESW-222" in {
      when(postClient.requestResponse[Boolean](argsEq(IsAvailable))(any[Decoder[Boolean]]())).thenReturn(Future.successful(true))
      sequencerAdminClient.isAvailable.futureValue should ===(true)
    }

    "call postClient with IsOnline request | ESW-222" in {
      when(postClient.requestResponse[Boolean](argsEq(IsOnline))(any[Decoder[Boolean]]())).thenReturn(Future.successful(true))
      sequencerAdminClient.isOnline.futureValue should ===(true)
    }

    "call postClient with Add request | ESW-222" in {
      when(postClient.requestResponse[OkOrUnhandledResponse](argsEq(Add(List.empty)))(any[Decoder[OkOrUnhandledResponse]]()))
        .thenReturn(Future.successful(Ok))
      sequencerAdminClient.add(List.empty).futureValue should ===(Ok)
    }

    "call postClient with Prepend request | ESW-222" in {
      when(postClient.requestResponse[OkOrUnhandledResponse](argsEq(Prepend(List.empty)))(any[Decoder[OkOrUnhandledResponse]]()))
        .thenReturn(Future.successful(Ok))
      sequencerAdminClient.prepend(List.empty).futureValue should ===(Ok)
    }

    "call postClient with Replace request | ESW-222" in {
      val id = mock[Id]
      when(postClient.requestResponse[GenericResponse](argsEq(Replace(id, List.empty)))(any[Decoder[GenericResponse]]()))
        .thenReturn(Future.successful(Ok))
      sequencerAdminClient.replace(id, List.empty).futureValue should ===(Ok)
    }

    "call postClient with InsertAfter request | ESW-222" in {
      val id = mock[Id]
      when(postClient.requestResponse[GenericResponse](argsEq(InsertAfter(id, List.empty)))(any[Decoder[GenericResponse]]()))
        .thenReturn(Future.successful(Ok))
      sequencerAdminClient.insertAfter(id, List.empty).futureValue should ===(Ok)
    }

    "call postClient with Delete request | ESW-222" in {
      val id = mock[Id]
      when(postClient.requestResponse[GenericResponse](argsEq(Delete(id)))(any[Decoder[GenericResponse]]()))
        .thenReturn(Future.successful(Ok))
      sequencerAdminClient.delete(id).futureValue should ===(Ok)
    }

    "call postClient with Pause request | ESW-222" in {
      when(postClient.requestResponse[PauseResponse](argsEq(Pause))(any[Decoder[PauseResponse]]()))
        .thenReturn(Future.successful(Ok))
      sequencerAdminClient.pause.futureValue should ===(Ok)
    }

    "call postClient with Resume request | ESW-222" in {
      when(postClient.requestResponse[OkOrUnhandledResponse](argsEq(Resume))(any[Decoder[OkOrUnhandledResponse]]()))
        .thenReturn(Future.successful(Ok))
      sequencerAdminClient.resume.futureValue should ===(Ok)
    }

    "call postClient with AddBreakpoint request | ESW-222" in {
      val id = mock[Id]
      when(postClient.requestResponse[GenericResponse](argsEq(AddBreakpoint(id)))(any[Decoder[GenericResponse]]()))
        .thenReturn(Future.successful(Ok))
      sequencerAdminClient.addBreakpoint(id).futureValue should ===(Ok)
    }

    "call postClient with RemoveBreakpoint request | ESW-222" in {
      val id = mock[Id]
      when(
        postClient
          .requestResponse[RemoveBreakpointResponse](argsEq(RemoveBreakpoint(id)))(any[Decoder[RemoveBreakpointResponse]]())
      ).thenReturn(Future.successful(Ok))
      sequencerAdminClient.removeBreakpoint(id).futureValue should ===(Ok)
    }

    "call postClient with Reset request | ESW-222" in {
      when(postClient.requestResponse[OkOrUnhandledResponse](argsEq(Reset))(any[Decoder[OkOrUnhandledResponse]]()))
        .thenReturn(Future.successful(Ok))
      sequencerAdminClient.reset().futureValue should ===(Ok)
    }

    "call postClient with AbortSequence request | ESW-222" in {
      when(postClient.requestResponse[OkOrUnhandledResponse](argsEq(AbortSequence))(any[Decoder[OkOrUnhandledResponse]]()))
        .thenReturn(Future.successful(Ok))
      sequencerAdminClient.abortSequence().futureValue should ===(Ok)
    }

    "call postClient with GoOffline request | ESW-222" in {
      when(postClient.requestResponse[OkOrUnhandledResponse](argsEq(GoOffline))(any[Decoder[OkOrUnhandledResponse]]()))
        .thenReturn(Future.successful(Ok))
      sequencerAdminClient.goOffline().futureValue should ===(Ok)
    }

    "call postClient with GoOnline request | ESW-222" in {
      when(postClient.requestResponse[GoOnlineResponse](argsEq(GoOnline))(any[Decoder[GoOnlineResponse]]()))
        .thenReturn(Future.successful(Ok))
      sequencerAdminClient.goOnline().futureValue should ===(Ok)
    }
  }
}