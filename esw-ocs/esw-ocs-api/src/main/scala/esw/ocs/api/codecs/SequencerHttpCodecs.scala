package esw.ocs.api.codecs

import com.github.ghik.silencer.silent
import esw.ocs.api.protocol.SequencerPostRequest._
import esw.ocs.api.protocol.SequencerWebsocketRequest.QueryFinal
import esw.ocs.api.protocol.{SequencerPostRequest, SequencerWebsocketRequest}
import io.bullet.borer.Codec
import io.bullet.borer.derivation.ArrayBasedCodecs.deriveUnaryCodec
import io.bullet.borer.derivation.MapBasedCodecs.deriveCodec
import msocket.api
import msocket.api.ErrorProtocol
import msocket.api.models.ServiceError

trait SequencerHttpCodecs extends OcsCodecs {
  import msocket.api.codecs.BasicCodecs._
  implicit def sequencerPostRequestCodec[T <: SequencerPostRequest]: Codec[T] =
    sequencerPostRequestValue.asInstanceOf[Codec[T]]

  lazy val sequencerPostRequestValue: Codec[SequencerPostRequest] = {
    // admin codecs
    @silent implicit lazy val getSequenceCodec: Codec[GetSequence.type]      = deriveCodec
    @silent implicit lazy val isAvailableCodec: Codec[IsAvailable.type]      = deriveCodec
    @silent implicit lazy val isOnlineCodec: Codec[IsOnline.type]            = deriveCodec
    @silent implicit lazy val pauseCodec: Codec[Pause.type]                  = deriveCodec
    @silent implicit lazy val resumeCodec: Codec[Resume.type]                = deriveCodec
    @silent implicit lazy val resetCodec: Codec[Reset.type]                  = deriveCodec
    @silent implicit lazy val abortSequenceCodec: Codec[AbortSequence.type]  = deriveCodec
    @silent implicit lazy val stopCodec: Codec[Stop.type]                    = deriveCodec
    @silent implicit lazy val addCodec: Codec[Add]                           = deriveCodec
    @silent implicit lazy val prependCodec: Codec[Prepend]                   = deriveCodec
    @silent implicit lazy val replaceCodec: Codec[Replace]                   = deriveCodec
    @silent implicit lazy val insertAfterCodec: Codec[InsertAfter]           = deriveCodec
    @silent implicit lazy val deleteCodec: Codec[Delete]                     = deriveCodec
    @silent implicit lazy val addBreakpointCodec: Codec[AddBreakpoint]       = deriveCodec
    @silent implicit lazy val removeBreakpointCodec: Codec[RemoveBreakpoint] = deriveCodec

    // command codecs
    @silent implicit lazy val submitSequenceCodec: Codec[Submit]              = deriveCodec
    @silent implicit lazy val loadSequenceCodec: Codec[LoadSequence]          = deriveCodec
    @silent implicit lazy val startSequenceCodec: Codec[StartSequence.type]   = deriveCodec
    @silent implicit lazy val queryCodec: Codec[Query]                        = deriveUnaryCodec
    @silent implicit lazy val goOnlineCodec: Codec[GoOnline.type]             = deriveCodec
    @silent implicit lazy val goOfflineCodec: Codec[GoOffline.type]           = deriveCodec
    @silent implicit lazy val operationsModeCodec: Codec[OperationsMode.type] = deriveCodec
    @silent implicit lazy val diagnosticModeCodec: Codec[DiagnosticMode]      = deriveCodec

    deriveCodec
  }

  implicit def sequencerWebsocketRequestCodec[T <: SequencerWebsocketRequest]: Codec[T] =
    sequencerWebsocketRequestValue.asInstanceOf[Codec[T]]

  lazy val sequencerWebsocketRequestValue: Codec[SequencerWebsocketRequest] = {
    @silent implicit lazy val queryFinalCodec: Codec[QueryFinal] = deriveCodec
    deriveCodec
  }

  implicit lazy val sequencerPostRequestErrorProtocol: ErrorProtocol[SequencerPostRequest] =
    ErrorProtocol.bind[SequencerPostRequest, ServiceError]

  implicit lazy val sequencerWebsocketErrorProtocol: api.ErrorProtocol[SequencerWebsocketRequest] =
    ErrorProtocol.bind[SequencerWebsocketRequest, ServiceError]
}
