package io.ahamdy.taskforce.worker


import java.util.concurrent.atomic.AtomicInteger

import monix.eval.Task
import io.ahamdy.taskforce.api.Worker
import io.ahamdy.taskforce.common.Logging
import io.ahamdy.taskforce.domain.{JobErrorDirective, JobErrorMessage, JobType}

class DummyJobHandler(val jobType: JobType, validateFunction: Map[String, String] => Task[Map[String, String]] = Task.pure)
  extends JobHandler with Logging {

  val FAIL_WITH_RETRY = "FAIL_WITH_RETRY"
  val FAIL_WITH_ABORT = "FAIL_WITH_ABORT"

  val totalRuns = new AtomicInteger()
  val successfulRuns = new AtomicInteger()
  val failedRuns = new AtomicInteger()

  override def validateJobInput(data: Map[String, String]): Task[Map[String, String]] = validateFunction(data)

  override def jobHandlerFunction(validData: Map[String, String], worker: Worker): Task[Unit] = Task{
    totalRuns.incrementAndGet()
    logger.info(s"running test job handler with data $validData")

    if(validData.keySet.contains(FAIL_WITH_RETRY))
      throw new Exception("fake error, retry")
    else if(validData.keySet.contains(FAIL_WITH_ABORT))
      throw new Exception("fake error, must abort")
    else
      successfulRuns.incrementAndGet()
  }

  override def errorHandler: PartialFunction[Throwable, (JobErrorDirective, JobErrorMessage)] = {
    case e: Exception if e.getMessage == "fake error, retry" =>
      failedRuns.incrementAndGet()
      (JobErrorDirective.Retry, JobErrorMessage(e.getMessage))
    case t: Throwable =>
      failedRuns.incrementAndGet()
      (JobErrorDirective.Abort, JobErrorMessage(t.getMessage))
  }

  def reset(): Unit = {
    totalRuns.set(0)
    successfulRuns.set(0)
    failedRuns.set(0)
  }
}
