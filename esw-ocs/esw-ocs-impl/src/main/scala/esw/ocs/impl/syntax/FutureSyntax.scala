package esw.ocs.impl.syntax

import esw.commons.Timeouts

import scala.concurrent.{Await, Future}

object FutureSyntax {
  implicit final class FutureOps[T](private val f: Future[T]) extends AnyVal {
    def block: T = Await.result(f, Timeouts.DefaultTimeout)
  }
}
