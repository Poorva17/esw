package esw.ocs.api

import esw.ocs.api.models.responses.OkOrUnhandledResponse

import scala.concurrent.Future

trait SequencerSupervisorApi {
  def shutdown(): Future[OkOrUnhandledResponse]
  def abortSequence(): Future[OkOrUnhandledResponse]
}